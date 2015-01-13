package coinffeine.protocol.gateway.overlay

import scala.concurrent.duration._

import akka.actor._
import akka.testkit._
import org.scalatest.concurrent.Eventually

import coinffeine.common.akka.ServiceActor
import coinffeine.common.akka.test.AkkaSpec
import coinffeine.model.currency._
import coinffeine.model.exchange.{Both, ExchangeId}
import coinffeine.model.market.OrderId
import coinffeine.model.network.{BrokerId, MutableCoinffeineNetworkProperties, NetworkEndpoint, PeerId}
import coinffeine.overlay.OverlayId
import coinffeine.protocol.MessageGatewaySettings
import coinffeine.protocol.gateway.MessageGateway
import coinffeine.protocol.gateway.MessageGateway.Subscribe
import coinffeine.protocol.messages.PublicMessage
import coinffeine.protocol.messages.brokerage.OrderMatch
import coinffeine.protocol.serialization.TestProtocolSerializationComponent

class OverlayMessageGatewayTest
  extends AkkaSpec(AkkaSpec.systemWithLoggingInterception("overlayGateway")) with Eventually
  with TestProtocolSerializationComponent with IdConversions {

  val settings = MessageGatewaySettings(
    peerId = PeerId("0" * 19 + "1"),
    peerPort = 1111,
    brokerEndpoint = NetworkEndpoint("server", 2222),
    ignoredNetworkInterfaces = Seq.empty,
    connectionRetryInterval = 1.second.dilated,
    externalForwardedPort = None
  )
  val overlayId = OverlayId(1)
  val sampleOrderMatch = OrderMatch(OrderId.random(), ExchangeId.random(),
    Both.fill(1.BTC), Both.fill(300.EUR), lockTime = 42, counterpart = PeerId.random())

  "An overlay message gateway" should "try to join on start" in new FreshGateway {
    expectSuccessfulStart()
    overlay.expectJoinAs(overlayId)
  }

  it should "retry connecting when join attempt fails" in new FreshGateway {
    expectSuccessfulStart()
    overlay.expectJoinAs(overlayId)
    overlay.rejectJoin()
    expectSuccessfulJoin()
  }

  it should "retry connecting after a random disconnection" in new JoinedGateway {
    overlay.givenRandomDisconnection()
    overlay.expectJoinAs(overlayId)
  }

  it should "deliver incoming messages to subscribers" in new FreshGateway {
    gateway ! Subscribe {
      case MessageGateway.ReceiveMessage(_: OrderMatch[_], _) =>
    }
    expectSuccessfulStart()
    val sender = PeerId.random()
    expectSuccessfulJoin()
    overlay.receiveFrom(sender, sampleOrderMatch)
    expectMsg(MessageGateway.ReceiveMessage(sampleOrderMatch, sender))
  }

  it should "forward outgoing messages to the overlay network" in new JoinedGateway {
    val dest = PeerId.random()
    gateway ! MessageGateway.ForwardMessage(sampleOrderMatch, dest)
    overlay.expectSendTo(dest, sampleOrderMatch)
  }

  it should "log messages whose serialization fail" in new JoinedGateway {
    object InvalidMessage extends PublicMessage
    EventFilter[Exception](start = "Cannot serialize message", occurrences = 1) intercept {
      gateway ! MessageGateway.ForwardMessage(InvalidMessage, PeerId.random())
    }
  }

  it should "log invalid messages received" in new JoinedGateway {
    EventFilter[Exception](start = "Dropping invalid incoming message", occurrences = 1) intercept {
      overlay.receiveInvalidMessageFrom(BrokerId)
    }
  }

  trait FreshGateway {
    val overlay = new MockOverlayNetwork(protocolSerialization)
    val properties = new MutableCoinffeineNetworkProperties
    val gateway = system.actorOf(Props(
      new OverlayMessageGateway(overlay.adapter, protocolSerialization, properties)))

    def expectSuccessfulStart(): Unit = {
      gateway ! ServiceActor.Start(MessageGateway.Join(MessageGateway.PeerNode, settings))
      expectMsg(ServiceActor.Started)
      overlay.expectClientSpawn()
    }

    def expectSuccessfulJoin(): Unit = {
      overlay.expectJoinAs(overlayId)
      overlay.acceptJoin(networkSize = 3)
      eventually {
        properties.activePeers.get shouldBe 2
      }
    }
  }

  trait JoinedGateway extends FreshGateway {
    expectSuccessfulStart()
    expectSuccessfulJoin()
  }
}
