package coinffeine.peer.exchange.handshake

import akka.testkit.TestProbe
import org.scalatest.mock.MockitoSugar

import coinffeine.model.bitcoin.ImmutableTransaction
import coinffeine.model.bitcoin.Implicits._
import coinffeine.model.bitcoin.test.BitcoinjTest
import coinffeine.peer.ProtocolConstants
import coinffeine.peer.bitcoin.WalletActor
import coinffeine.peer.exchange.handshake.HandshakeActor.StartHandshake
import coinffeine.peer.exchange.protocol.{MockExchangeProtocol, MockHandshake}
import coinffeine.peer.exchange.test.CoinffeineClientTest
import coinffeine.peer.exchange.test.CoinffeineClientTest.SellerPerspective
import coinffeine.protocol.messages.handshake.{PeerHandshake, RefundSignatureRequest, RefundSignatureResponse}

/** Test fixture for testing the handshake actor interaction, one derived class per scenario. */
abstract class HandshakeActorTest(systemName: String)
  extends CoinffeineClientTest(systemName) with SellerPerspective with BitcoinjTest with MockitoSugar {

  def protocolConstants: ProtocolConstants

  lazy val handshake = new MockHandshake(handshakingExchange)
  val listener, blockchain, wallet = TestProbe()
  val actor = system.actorOf(
    HandshakeActor.props(
      HandshakeActor.ExchangeToHandshake(exchange, user),
      HandshakeActor.Collaborators(gateway.ref, blockchain.ref, wallet.ref, listener.ref),
      HandshakeActor.ProtocolDetails(new MockExchangeProtocol, protocolConstants)
    ),
    "handshake-actor"
  )
  listener.watch(actor)

  def givenActorIsInitialized(): Unit = {
    actor ! StartHandshake()
  }

  def givenCounterpartPeerHandshake(): Unit = {
    val peerHandshake =
      PeerHandshake(exchange.id, counterpart.bitcoinKey.publicKey, counterpart.paymentProcessorAccount)
    gateway.relayMessage(peerHandshake, counterpartId)
  }

  def givenValidRefundSignatureResponse() = {
    val validSignature = RefundSignatureResponse(exchange.id, MockExchangeProtocol.RefundSignature)
    gateway.relayMessage(validSignature, counterpartId)
  }

  def shouldCreateDeposits(): Unit = {
    val request = wallet.expectMsgClass(classOf[WalletActor.CreateDeposit])
    request.amount should be (exchange.amounts.depositTransactionAmounts.seller.output)
    request.transactionFee should be (exchange.amounts.transactionFee)
    wallet.reply(WalletActor.DepositCreated(request, MockExchangeProtocol.DummyDeposit))
  }

  def shouldForwardPeerHandshake(): Unit = {
    val peerHandshake =
      PeerHandshake(exchange.id, user.bitcoinKey.publicKey, user.paymentProcessorAccount)
    gateway.expectForwarding(peerHandshake, counterpartId)
  }

  def shouldForwardRefundSignatureRequest(): Unit = {
    val refundSignatureRequest = RefundSignatureRequest(exchange.id, handshake.myUnsignedRefund)
    gateway.expectForwarding(refundSignatureRequest, counterpartId)
  }

  def shouldSignCounterpartRefund(): Unit = {
    val request = RefundSignatureRequest(exchange.id, ImmutableTransaction(handshake.counterpartRefund))
    gateway.relayMessage(request, counterpartId)
    val refundSignature =
      RefundSignatureResponse(exchange.id, MockExchangeProtocol.CounterpartRefundSignature)
    gateway.expectForwarding(refundSignature, counterpartId)
  }

  override protected def resetBlockchainBetweenTests = false
}
