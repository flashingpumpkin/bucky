package com.itv.bucky.taskz

import java.util.concurrent.ExecutorService

import com.itv.bucky.Monad.Id
import com.itv.bucky._
import com.itv.bucky.decl._
import com.itv.bucky.pattern.requeue._
import com.itv.bucky.suite._
import com.itv.lifecycle.Lifecycle
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging
import org.scalatest.Assertion

import scala.concurrent.duration._
import scalaz.{-\/, \/-}
import scalaz.concurrent.{Strategy, Task}
import scalaz.stream.Process
import org.scalatest.Matchers._

import scala.concurrent.ExecutionContextExecutor

trait TaskEffectVerification extends EffectVerification[Task] {

  def verifySuccess(f: Task[Unit]): Assertion = f.unsafePerformSyncAttempt should ===(\/-(()))

  def verifyFailure(f: Task[Unit]) =
    f.unsafePerformSyncAttempt shouldBe 'left

}

trait TaskExecutorService {
  implicit val pool: ExecutorService = ExecutionContextExecutorServiceBridge(new ExecutionContextExecutor {
    override def execute(runnable: Runnable): Unit = runnable.run()

    override def reportFailure(cause: Throwable): Unit = throw cause
  })
}

trait TaskMonadEffect extends EffectMonad[Task, Throwable] with TaskExecutorService {

  implicit def effectMonad: MonadError[Task, Throwable] = taskMonadError

}

trait TaskPublisherConsumerBaseTest extends PublisherConsumerBaseTest[Task] with TaskEffectVerification {

  override def withPublisherAndConsumer(queueName: QueueName, requeueStrategy: RequeueStrategy[Task])(
      f: (TestFixture[Task]) => Unit): Unit =
    IntegrationUtils.withPublisherAndConsumer(queueName, requeueStrategy)(f)
}

object IntegrationUtils extends StrictLogging {
  def defaultDeclaration(queueName: QueueName): List[Queue] =
    List(queueName).map(Queue(_).autoDelete.expires(2.minutes))

  def config: AmqpClientConfig = {
    val config = ConfigFactory.load("bucky")
    AmqpClientConfig(config.getString("rmq.host"),
                     config.getInt("rmq.port"),
                     config.getString("rmq.username"),
                     config.getString("rmq.password"))
  }

  protected def withPublihserAndAmqpClient(
      testQueueName: QueueName = Any.queue(),
      requeueStrategy: RequeueStrategy[Task] = NoneHandler,
      shouldDeclare: Boolean = true)(f: (AmqpClient[Id, Task, Throwable, Process[Task, Unit]], TestFixture[Task]) => Unit): Unit = {
    val routingKey                     = RoutingKey(testQueueName.value)
    val exchange                       = ExchangeName("")
    implicit val pool: ExecutorService = Strategy.DefaultExecutorService
    Lifecycle.using(DefaultTaskAmqpClientLifecycle(IntegrationUtils.config)) { client =>
      val declaration = requeueStrategy match {
        case NoneRequeue(_) => defaultDeclaration(testQueueName)
        case SimpleRequeue(_) =>
          basicRequeueDeclarations(testQueueName, retryAfter = 1.second) collect {
            case ex: Exchange => ex.autoDelete.expires(1.minute)
            case q: Queue     => q.autoDelete.expires(1.minute)
          }
        case _ =>
          logger.debug(s"Requeue declarations")
          requeueDeclarations(testQueueName,
                              RoutingKey(testQueueName.value),
                              Exchange(ExchangeName(s"${testQueueName.value}.dlx")),
                              retryAfter = 1.second) collect {
            case ex: Exchange => ex.autoDelete.expires(1.minute)
            case q: Queue     => q.autoDelete.expires(1.minute)
          }
      }
      if (shouldDeclare)
        DeclarationExecutor(declaration, client, 5.seconds)

      val publisher: Publisher[Task, PublishCommand] = client.publisher()
      f(client, TestFixture(publisher, routingKey, exchange, testQueueName, client))

      logger.debug(s"Closing the the publisher")
    }
  }

  def withPublisher(testQueueName: QueueName = Any.queue(),
                    requeueStrategy: RequeueStrategy[Task] = NoneHandler,
                    shouldDeclare: Boolean = true)(f: TestFixture[Task] => Unit): Unit =
    withPublihserAndAmqpClient(testQueueName, requeueStrategy, shouldDeclare) { (_, t) =>
      f(t)
    }

  def withPublisherAndConsumer(queueName: QueueName = Any.queue(), requeueStrategy: RequeueStrategy[Task])(
      f: TestFixture[Task] => Unit): Unit =
    withPublihserAndAmqpClient(queueName, requeueStrategy) {
      case (amqpClient, t) =>
        withPublisher(queueName, requeueStrategy = requeueStrategy) { app =>
          import TaskExt._

          val dlqHandler = requeueStrategy match {
            case NoneHandler    => None
            case NoneRequeue(_) => None
            case _ =>
              logger.debug(s"Create dlq handler")
              val dlqHandler   = new StubConsumeHandler[Task, Delivery]
              val dlqQueueName = QueueName(s"${queueName.value}.dlq")
              val consumer     = amqpClient.consumer(dlqQueueName, dlqHandler)
              consumer.run.unsafePerformAsync { result =>
                logger.info(s"Closing dead letter consumer $dlqQueueName}: $result")
              }

              Some(dlqHandler)
          }

          import scalaz.stream.Process
          val consumer: Process[Task, Unit] = requeueStrategy match {
            case NoneHandler => Process.empty[Task, Unit]
            case RawRequeue(requeueHandler, requeuePolicy) =>
              amqpClient.requeueOf(app.queueName, requeueHandler, requeuePolicy)
            case TypeRequeue(requeueHandler, requeuePolicy, unmarshaller) =>
              amqpClient.requeueHandlerOf(app.queueName, requeueHandler, requeuePolicy, unmarshaller)
            case SimpleRequeue(handler) => amqpClient.consumer(app.queueName, handler)
            case NoneRequeue(handler)   => amqpClient.consumer(app.queueName, handler)
          }

          consumer.run.unsafePerformAsync { result =>
            }

          f(app.copy(dlqHandler = dlqHandler))
        }
    }

}
