/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import language.implicitConversions
import akka.actor._
import akka.actor.Status._
import akka.ConfigurationException
import akka.dispatch.MonitorableThreadFactory
import akka.event.Logging
import akka.pattern._
import akka.remote._
import akka.routing._
import akka.util._
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.annotation.tailrec
import scala.collection.immutable
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ ExecutionContext, Await }
import com.typesafe.config.ConfigFactory
import akka.remote.DefaultFailureDetectorRegistry
import akka.remote.FailureDetector
import com.typesafe.config.Config
import akka.event.LoggingAdapter
import java.util.concurrent.ThreadFactory
import scala.util.control.NonFatal

/**
 * Cluster Extension Id and factory for creating Cluster extension.
 */
object Cluster extends ExtensionId[Cluster] with ExtensionIdProvider {
  override def get(system: ActorSystem): Cluster = super.get(system)

  override def lookup = Cluster

  override def createExtension(system: ExtendedActorSystem): Cluster = new Cluster(system)

  /**
   * INTERNAL API
   */
  private[cluster] final val isAssertInvariantsEnabled: Boolean =
    System.getProperty("akka.cluster.assert", "off").toLowerCase match {
      case "on" | "true" ⇒ true
      case _             ⇒ false
    }
}

/**
 * This module is responsible cluster membership information. Changes to the cluster
 * information is retrieved through [[#subscribe]]. Commands to operate the cluster is
 * available through methods in this class, such as [[#join]], [[#down]] and [[#leave]].
 *
 * Each cluster [[Member]] is identified by its [[akka.actor.Address]], and
 * the cluster address of this actor system is [[#selfAddress]]. A member also has a status;
 * initially [[MemberStatus.Joining]] followed by [[MemberStatus.Up]].
 */
class Cluster(val system: ExtendedActorSystem) extends Extension {

  import ClusterEvent._

  val settings = new ClusterSettings(system.settings.config, system.name)
  import settings._
  import InfoLogger._

  /**
   * INTERNAL API
   */
  private[cluster] val selfUniqueAddress: UniqueAddress = system.provider match {
    case c: ClusterActorRefProvider ⇒
      UniqueAddress(c.transport.defaultAddress, AddressUidExtension(system).addressUid)
    case other ⇒ throw new ConfigurationException(
      s"ActorSystem [${system}] needs to have a 'ClusterActorRefProvider' enabled in the configuration, currently uses [${other.getClass.getName}]")
  }

  /**
   * The address of this cluster member.
   */
  def selfAddress: Address = selfUniqueAddress.address

  /**
   * roles that this member has
   */
  def selfRoles: Set[String] = settings.Roles

  /**
   * Java API: roles that this member has
   */
  def getSelfRoles: java.util.Set[String] =
    scala.collection.JavaConverters.setAsJavaSetConverter(selfRoles).asJava

  private val _isTerminated = new AtomicBoolean(false)
  private val log = Logging(system, "Cluster")
  // ClusterJmx is initialized as the last thing in the constructor
  private var clusterJmx: Option[ClusterJmx] = None

  logInfo("Starting up...")

  val failureDetector: FailureDetectorRegistry[Address] = {
    def createFailureDetector(): FailureDetector =
      FailureDetectorLoader.load(settings.FailureDetectorImplementationClass, settings.FailureDetectorConfig, system)

    new DefaultFailureDetectorRegistry(() ⇒ createFailureDetector())
  }

  // ========================================================
  // ===================== WORK DAEMONS =====================
  // ========================================================

  /**
   * INTERNAL API
   */
  private[cluster] val scheduler: Scheduler = {
    if (system.scheduler.maxFrequency < 1.second / SchedulerTickDuration) {
      import scala.collection.JavaConverters._
      logInfo("Using a dedicated scheduler for cluster. Default scheduler can be used if configured " +
        "with 'akka.scheduler.tick-duration' [{} ms] <=  'akka.cluster.scheduler.tick-duration' [{} ms].",
        (1000 / system.scheduler.maxFrequency).toInt, SchedulerTickDuration.toMillis)

      val cfg = ConfigFactory.parseString(
        s"akka.scheduler.tick-duration=${SchedulerTickDuration.toMillis}ms").withFallback(
          system.settings.config)
      val threadFactory = system.threadFactory match {
        case tf: MonitorableThreadFactory ⇒ tf.withName(tf.name + "-cluster-scheduler")
        case tf                           ⇒ tf
      }
      system.dynamicAccess.createInstanceFor[Scheduler](system.settings.SchedulerClass, immutable.Seq(
        classOf[Config] -> cfg,
        classOf[LoggingAdapter] -> log,
        classOf[ThreadFactory] -> threadFactory)).get
    } else {
      // delegate to system.scheduler, but don't close over system
      val systemScheduler = system.scheduler
      new Scheduler with Closeable {
        override def close(): Unit = () // we are using system.scheduler, which we are not responsible for closing

        override def maxFrequency: Double = systemScheduler.maxFrequency

        override def schedule(initialDelay: FiniteDuration, interval: FiniteDuration,
                              runnable: Runnable)(implicit executor: ExecutionContext): Cancellable =
          systemScheduler.schedule(initialDelay, interval, runnable)

        override def scheduleOnce(delay: FiniteDuration,
                                  runnable: Runnable)(implicit executor: ExecutionContext): Cancellable =
          systemScheduler.scheduleOnce(delay, runnable)
      }
    }
  }

  // create supervisor for daemons under path "/system/cluster"
  private val clusterDaemons: ActorRef = {
    system.asInstanceOf[ActorSystemImpl].systemActorOf(Props(classOf[ClusterDaemon], settings).
      withDispatcher(UseDispatcher).withDeploy(Deploy.local), name = "cluster")
  }

  /**
   * INTERNAL API
   */
  private[cluster] val clusterCore: ActorRef = {
    implicit val timeout = system.settings.CreationTimeout
    try {
      Await.result((clusterDaemons ? InternalClusterAction.GetClusterCoreRef).mapTo[ActorRef], timeout.duration)
    } catch {
      case NonFatal(e) ⇒
        log.error(e, "Failed to startup Cluster")
        shutdown()
        throw e
    }
  }

  @volatile
  private var readViewStarted = false
  private[cluster] lazy val readView: ClusterReadView = {
    val readView = new ClusterReadView(this)
    readViewStarted = true
    readView
  }

  system.registerOnTermination(shutdown())

  if (JmxEnabled)
    clusterJmx = {
      val jmx = new ClusterJmx(this, log)
      jmx.createMBean()
      Some(jmx)
    }

  logInfo("Started up successfully")

  // ======================================================
  // ===================== PUBLIC API =====================
  // ======================================================

  /**
   * Returns true if this cluster instance has be shutdown.
   */
  def isTerminated: Boolean = _isTerminated.get

  /**
   * Subscribe to cluster domain events.
   * The `to` Class can be [[akka.cluster.ClusterEvent.ClusterDomainEvent]]
   * or subclass.
   *
   * A snapshot of [[akka.cluster.ClusterEvent.CurrentClusterState]]
   * will be sent to the subscriber as the first event.
   */
  def subscribe(subscriber: ActorRef, to: Class[_]): Unit =
    clusterCore ! InternalClusterAction.Subscribe(subscriber, to)

  /**
   * Unsubscribe to all cluster domain events.
   */
  def unsubscribe(subscriber: ActorRef): Unit =
    clusterCore ! InternalClusterAction.Unsubscribe(subscriber, None)

  /**
   * Unsubscribe to a specific type of cluster domain events,
   * matching previous `subscribe` registration.
   */
  def unsubscribe(subscriber: ActorRef, to: Class[_]): Unit =
    clusterCore ! InternalClusterAction.Unsubscribe(subscriber, Some(to))

  /**
   * Publish current (full) state of the cluster to subscribers,
   * that are subscribing to [[akka.cluster.ClusterEvent.ClusterDomainEvent]]
   * or [[akka.cluster.ClusterEvent.CurrentClusterState]].
   * If you want this to happen periodically you need to schedule a call to
   * this method yourself.
   */
  def publishCurrentClusterState(): Unit =
    clusterCore ! InternalClusterAction.PublishCurrentClusterState(None)

  /**
   * Publish current (full) state of the cluster to the specified
   * receiver. If you want this to happen periodically you need to schedule
   * a call to this method yourself.
   */
  def sendCurrentClusterState(receiver: ActorRef): Unit =
    clusterCore ! InternalClusterAction.PublishCurrentClusterState(Some(receiver))

  /**
   * Try to join this cluster node with the node specified by 'address'.
   * A 'Join(selfAddress)' command is sent to the node to join.
   *
   * An actor system can only join a cluster once. Additional attempts will be ignored.
   * When it has successfully joined it must be restarted to be able to join another
   * cluster or to join the same cluster again.
   */
  def join(address: Address): Unit =
    clusterCore ! ClusterUserAction.JoinTo(address)

  /**
   * Join the specified seed nodes without defining them in config.
   * Especially useful from tests when Addresses are unknown before startup time.
   *
   * An actor system can only join a cluster once. Additional attempts will be ignored.
   * When it has successfully joined it must be restarted to be able to join another
   * cluster or to join the same cluster again.
   *
   * JAVA API: Use akka.japi.Util.immutableSeq to convert a java.lang.Iterable
   * to the type needed for the seedNodes parameter.
   */
  def joinSeedNodes(seedNodes: immutable.Seq[Address]): Unit =
    clusterCore ! InternalClusterAction.JoinSeedNodes(seedNodes.toVector)

  /**
   * Send command to issue state transition to LEAVING for the node specified by 'address'.
   * The member will go through the status changes [[MemberStatus.Leaving]] (not published to
   * subscribers) followed by [[MemberStatus.Exiting]] and finally [[MemberStatus.Removed]].
   *
   * Note that this command can be issued to any member in the cluster, not necessarily the
   * one that is leaving. The cluster extension, but not the actor system or JVM, of the
   * leaving member will be shutdown after the leader has changed status of the member to
   * Exiting. Thereafter the member will be removed from the cluster. Normally this is
   * handled automatically, but in case of network failures during this process it might
   * still be necessary to set the node’s status to Down in order to complete the removal.
   */
  def leave(address: Address): Unit =
    clusterCore ! ClusterUserAction.Leave(address)

  /**
   * Send command to DOWN the node specified by 'address'.
   *
   * When a member is considered by the failure detector to be unreachable the leader is not
   * allowed to perform its duties, such as changing status of new joining members to 'Up'.
   * The status of the unreachable member must be changed to 'Down', which can be done with
   * this method.
   */
  def down(address: Address): Unit =
    clusterCore ! ClusterUserAction.Down(address)

  /**
   * The supplied thunk will be run, once, when current cluster member is `Up`.
   * Typically used together with configuration option `akka.cluster.min-nr-of-members'
   * to defer some action, such as starting actors, until the cluster has reached
   * a certain size.
   */
  def registerOnMemberUp[T](code: ⇒ T): Unit =
    registerOnMemberUp(new Runnable { def run = code })

  /**
   * Java API: The supplied callback will be run, once, when current cluster member is `Up`.
   * Typically used together with configuration option `akka.cluster.min-nr-of-members'
   * to defer some action, such as starting actors, until the cluster has reached
   * a certain size.
   */
  def registerOnMemberUp(callback: Runnable): Unit = clusterDaemons ! InternalClusterAction.AddOnMemberUpListener(callback)

  // ========================================================
  // ===================== INTERNAL API =====================
  // ========================================================

  /**
   * INTERNAL API.
   *
   * Shuts down all connections to other members, the cluster daemon and the periodic gossip and cleanup tasks.
   *
   * Should not called by the user. The user can issue a LEAVE command which will tell the node
   * to go through graceful handoff process `LEAVE -> EXITING -> REMOVED -> SHUTDOWN`.
   */
  private[cluster] def shutdown(): Unit = {
    if (_isTerminated.compareAndSet(false, true)) {
      logInfo("Shutting down...")

      system.stop(clusterDaemons)
      if (readViewStarted) readView.close()

      closeScheduler()

      clusterJmx foreach { _.unregisterMBean() }

      logInfo("Successfully shut down")
    }
  }

  private def closeScheduler(): Unit = scheduler match {
    case x: Closeable ⇒ x.close()
    case _            ⇒
  }

  /**
   * INTERNAL API
   */
  private[cluster] object InfoLogger {

    def logInfo(message: String): Unit =
      if (LogInfo) log.info("Cluster Node [{}] - {}", selfAddress, message)

    def logInfo(template: String, arg1: Any): Unit =
      if (LogInfo) log.info("Cluster Node [{}] - " + template, selfAddress, arg1)

    def logInfo(template: String, arg1: Any, arg2: Any): Unit =
      if (LogInfo) log.info("Cluster Node [{}] - " + template, selfAddress, arg1, arg2)
  }

}
