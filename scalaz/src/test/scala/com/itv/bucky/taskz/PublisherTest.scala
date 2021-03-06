package com.itv.bucky.taskz

import java.io.IOException
import java.util.concurrent.TimeoutException

import com.itv.bucky.taskz.TaskExt._
import com.itv.bucky._
import com.rabbitmq.client.AMQP.Basic.Publish
import com.rabbitmq.client.AMQP.Confirm.Select
import com.rabbitmq.client._
import com.rabbitmq.client.impl.AMQImpl
import org.scalatest.FunSuite
import org.scalatest.Matchers._
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.language.postfixOps
import scala.concurrent.duration._

class PublisherTest extends FunSuite with Eventually {

  override implicit val patienceConfig =
    PatienceConfig(timeout = scaled(Span(10, Seconds)), interval = scaled(Span(5, Millis)))

  test("Publishing only returns success once publication is acknowledged with Id") {
    withPublisher(timeout = 100.hours) { publisher =>
      import publisher._

      channel.transmittedCommands should have size 1
      channel.transmittedCommands.last shouldBe an[AMQP.Confirm.Select]

      val task = resultFrom(publish(Any.publishCommand()))

      eventually {
        channel.transmittedCommands should have size 2
        channel.transmittedCommands.last shouldBe an[AMQP.Basic.Publish]
      }

      task shouldBe 'running

      eventually {
        channel.replyWith(new AMQImpl.Basic.Ack(1L, false))
        task shouldBe 'success
      }
    }
  }

  test("Publishing only returns success once publication is acknowledged") {
    withPublisher() { publisher =>
      import publisher._

      channel.transmittedCommands should have size 1
      channel.transmittedCommands.last shouldBe an[Select]

      val task = resultFrom(publish(Any.publishCommand()))

      eventually {
        channel.transmittedCommands should have size 2
        channel.transmittedCommands.last shouldBe an[Publish]
      }

      task shouldBe 'running

      eventually {
        channel.replyWith(new AMQImpl.Basic.Ack(1L, false))
        task shouldBe 'success
      }
    }
  }

  test("Negative acknowledgements result in failed future") {
    withPublisher(timeout = 100.hours) { publisher =>
      import publisher._

      val task = resultFrom(publish(Any.publishCommand()))

      task shouldBe 'running

      eventually {
        channel.replyWith(new AMQImpl.Basic.Nack(1L, false, false))
        task.failure.getMessage should include("Nack")
      }

    }
  }

  test("Cannot publish when there is a IOException") {
    val expectedMsg = "There is a network problem"
    val mockCannel = new StubChannel {
      override def basicPublish(exchange: String,
                                routingKey: String,
                                mandatory: Boolean,
                                immediate: Boolean,
                                props: AMQP.BasicProperties,
                                body: Array[Byte]): Unit =
        throw new IOException(expectedMsg)

    }
    withPublisher(timeout = 100.hours, channel = mockCannel) { publisher =>
      import publisher._

      channel.transmittedCommands should have size 1
      channel.transmittedCommands.last shouldBe an[AMQP.Confirm.Select]

      val task = resultFrom(publish(Any.publishCommand()))

      eventually {
        task.failure.getMessage should ===(expectedMsg)
      }
    }
  }

  test("Publisher times out if configured to do so") {

    withPublisher(timeout = 10.millis) { publisher =>
      import publisher._

      val result = resultFrom(publish(Any.publishCommand()))

      eventually {
        result.failure shouldBe a[TimeoutException]
      }
    }
  }

}
