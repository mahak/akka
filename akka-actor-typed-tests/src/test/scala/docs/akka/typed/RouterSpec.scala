/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed

import akka.actor.typed.DispatcherSelector
// #pool
import akka.actor.testkit.typed.scaladsl.{ LogCapturing, ScalaTestWithActorTestKit }
import akka.actor.typed.{ Behavior, SupervisorStrategy }
import akka.actor.typed.receptionist.{ Receptionist, ServiceKey }
import akka.actor.typed.scaladsl.{ Behaviors, Routers }

// #pool
import org.scalatest.wordspec.AnyWordSpecLike
object RouterSpec {

  // #routee
  object Worker {
    sealed trait Command
    case class DoLog(text: String) extends Command

    def apply(): Behavior[Command] = Behaviors.setup { context =>
      context.log.info("Starting worker")

      Behaviors.receiveMessage {
        case DoLog(text) =>
          context.log.info("Got message {}", text)
          Behaviors.same
      }
    }
  }

  // #routee

  //intentionally out of the routee section
  class DoBroadcastLog(text: String) extends Worker.DoLog(text)
  object DoBroadcastLog {
    def apply(text: String) = new DoBroadcastLog(text)
  }

  // This code is extra indented for visualization purposes
  // format: OFF
  // #group
        val serviceKey = ServiceKey[Worker.Command]("log-worker")

  // #group
  // format: ON
}

class RouterSpec extends ScalaTestWithActorTestKit("akka.loglevel=warning") with AnyWordSpecLike with LogCapturing {
  import RouterSpec._

  "The routing sample" must {

    "show pool routing" in {
      // trixery to monitor worker but make the sample look like we use it directly
      val probe = createTestProbe[RouterSpec.Worker.Command]()
      object Worker {
        def apply(): Behavior[RouterSpec.Worker.Command] =
          Behaviors.monitor(probe.ref, RouterSpec.Worker())

        def DoLog(text: String) = RouterSpec.Worker.DoLog(text)
      }

      spawn(
        // #pool
        // This would be defined within your actor object
        Behaviors.setup[Unit] { ctx =>
          val pool = Routers.pool(poolSize = 4) {
            // make sure the workers are restarted if they fail
            Behaviors.supervise(Worker()).onFailure[Exception](SupervisorStrategy.restart)
          }
          val router = ctx.spawn(pool, "worker-pool")

          (0 to 10).foreach { n =>
            router ! Worker.DoLog(s"msg $n")
          }
          // #pool

          // #pool-dispatcher
          // make sure workers use the default blocking IO dispatcher
          val blockingPool = pool.withRouteeProps(routeeProps = DispatcherSelector.blocking())
          // spawn head router using the same executor as the parent
          val blockingRouter = ctx.spawn(blockingPool, "blocking-pool", DispatcherSelector.sameAsParent())
          // #pool-dispatcher

          blockingRouter ! Worker.DoLog("msg")

          // #strategy
          val alternativePool = pool.withPoolSize(2).withRoundRobinRouting()
          // #strategy

          val alternativeRouter = ctx.spawn(alternativePool, "alternative-pool")
          alternativeRouter ! Worker.DoLog("msg")
          //#pool

          // #broadcast
          val poolWithBroadcast = pool.withBroadcastPredicate(_.isInstanceOf[DoBroadcastLog])
          val routerWithBroadcast = ctx.spawn(poolWithBroadcast, "pool-with-broadcast")
          //this will be sent to all 4 routees
          routerWithBroadcast ! DoBroadcastLog("msg")
          Behaviors.empty
        // #broadcast
        }
        //#pool
      )

      probe.receiveMessages(15)
    }

    "show group routing" in {
      // trixery to monitor worker but make the sample look like we use it directly
      val probe = createTestProbe[RouterSpec.Worker.Command]()
      object Worker {
        def apply(): Behavior[RouterSpec.Worker.Command] =
          Behaviors.monitor(probe.ref, RouterSpec.Worker())

        def DoLog(text: String) = RouterSpec.Worker.DoLog(text)
      }

      spawn(
        // #group
        Behaviors.setup[Unit] { ctx =>
          // this would likely happen elsewhere - if we create it locally we
          // can just as well use a pool
          val worker = ctx.spawn(Worker(), "worker")
          ctx.system.receptionist ! Receptionist.Register(serviceKey, worker)

          val group = Routers.group(serviceKey)
          val router = ctx.spawn(group, "worker-group")

          // the group router will stash messages until it sees the first listing of registered
          // services from the receptionist, so it is safe to send messages right away
          (0 to 10).foreach { n =>
            router ! Worker.DoLog(s"msg $n")
          }

          Behaviors.empty
        }
        // #group
      )

      probe.receiveMessages(10)
    }
  }
}
