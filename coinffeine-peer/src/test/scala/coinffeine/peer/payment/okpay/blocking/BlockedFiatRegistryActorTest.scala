package coinffeine.peer.payment.okpay.blocking

import akka.actor.{ActorRef, Props}
import akka.testkit.TestProbe

import coinffeine.common.akka.persistence.PeriodicSnapshot
import coinffeine.common.akka.test.AkkaSpec
import coinffeine.model.currency._
import coinffeine.model.exchange.ExchangeId
import coinffeine.peer.payment.PaymentProcessorActor
import coinffeine.peer.payment.okpay.blocking.BlockedFiatRegistryActor.TotalBlockedFunds

class BlockedFiatRegistryActorTest extends AkkaSpec {

  val eventProbe = TestProbe()
  system.eventStream.subscribe(
    eventProbe.ref, classOf[PaymentProcessorActor.FundsAvailabilityEvent])

  "The blocking funds actor" must "retrieve no blocked funds when no funds are blocked" in
    new WithBlockingFundsActor {
      currentTotalBlockedFunds(Euro) shouldBe Euro.zero
    }

  it must "retrieve blocked funds when blocked" in new WithBlockingFundsActor {
    givenAccountUpdate(100.EUR)
    givenAvailableFunds(100.EUR)
    currentTotalBlockedFunds(Euro) shouldBe 100.EUR
  }

  it must "retrieve no blocked funds after unblocked" in new WithBlockingFundsActor {
    givenAccountUpdate(100.EUR)
    val funds = givenAvailableFunds(100.EUR)
    actor ! PaymentProcessorActor.UnblockFunds(funds)
    currentTotalBlockedFunds(Euro) shouldBe Euro.zero
  }

  it must "retrieve blocked funds after using some" in new WithBlockingFundsActor {
    givenAccountUpdate(100.EUR)
    val funds = givenAvailableFunds(100.EUR)
    actor ! BlockedFiatRegistryActor.MarkUsed(funds, 60.EUR)
    expectMsgPF() {
      case BlockedFiatRegistryActor.FundsMarkedUsed(`funds`, _) =>
    }
    currentTotalBlockedFunds(Euro) shouldBe 40.EUR
  }

  it must "block funds up to existing balances" in new WithBlockingFundsActor {
    givenAccountUpdate(100.EUR)
    actor ! PaymentProcessorActor.BlockFunds(ExchangeId.random(), 50.EUR)
    actor ! PaymentProcessorActor.BlockFunds(ExchangeId.random(), 50.EUR)
    actor ! PaymentProcessorActor.BlockFunds(ExchangeId.random(), 50.EUR)
    expectMsgAllConformingOf(
      classOf[PaymentProcessorActor.BlockedFunds],
      classOf[PaymentProcessorActor.BlockedFunds],
      classOf[PaymentProcessorActor.BlockedFunds]
    )
    eventProbe.expectMsgAllConformingOf(
      classOf[PaymentProcessorActor.AvailableFunds],
      classOf[PaymentProcessorActor.AvailableFunds],
      classOf[PaymentProcessorActor.UnavailableFunds]
    )
  }

  it must "consider remaining usage limits when blocking funds" in new WithBlockingFundsActor {
    givenAccountUpdate(amount = 100.EUR, remainingLimit = 50.EUR)
    actor ! PaymentProcessorActor.BlockFunds(ExchangeId.random(), 50.EUR)
    actor ! PaymentProcessorActor.BlockFunds(ExchangeId.random(), 50.EUR)
    expectMsgAllConformingOf(
      classOf[PaymentProcessorActor.BlockedFunds],
      classOf[PaymentProcessorActor.BlockedFunds]
    )
    eventProbe.expectMsgAllConformingOf(
      classOf[PaymentProcessorActor.AvailableFunds],
      classOf[PaymentProcessorActor.UnavailableFunds]
    )
  }

  it must "reject blocking funds twice for the same exchange" in new WithBlockingFundsActor {
    givenAccountUpdate(100.EUR)
    val funds = givenAvailableFunds(50.EUR)
    actor ! PaymentProcessorActor.BlockFunds(funds, 20.EUR)
    expectMsg(PaymentProcessorActor.AlreadyBlockedFunds(funds))
  }

  it must "unblock blocked funds to make then available again" in new WithBlockingFundsActor {
    givenAccountUpdate(100.EUR)
    val funds1 = givenAvailableFunds(100.EUR)
    actor ! PaymentProcessorActor.UnblockFunds(funds1)
    val funds2 = givenBlockedFunds(100.EUR)
    expectBecomingAvailable(funds2)
  }

  it must "notify unavailable funds once block submission when insufficient funds" in
    new WithBlockingFundsActor {
      givenAccountUpdate(100.EUR)
      val funds = givenBlockedFunds(110.EUR)
      expectBecomingUnavailable(funds)
    }

  it must "notify unavailable funds to the last ones when blocks exceeds the funds" in
    new WithBlockingFundsActor {
      givenAccountUpdate(100.EUR)
      val funds1 = givenAvailableFunds(90.EUR)
      val funds2 = givenAvailableFunds(10.EUR)

      givenAccountUpdate(90.EUR)
      expectBecomingUnavailable(funds2)

      givenAccountUpdate(50.EUR)
      expectBecomingUnavailable(funds1)
    }

  it must "notify available funds when balance is enough again due to external increase" in
    new WithBlockingFundsActor {
      givenAccountUpdate(100.EUR)
      val funds = givenAvailableFunds(100.EUR)
      givenAccountUpdate(50.EUR)
      expectBecomingUnavailable(funds)
      givenAccountUpdate(100.EUR)
      expectBecomingAvailable(funds)
    }

  it must "notify available funds when balance is enough again due to unblocking" in
    new WithBlockingFundsActor {
      givenAccountUpdate(100.EUR)
      val funds1 = givenAvailableFunds(50.EUR)
      val funds2 = givenAvailableFunds(50.EUR)
      givenAccountUpdate(60.EUR)
      expectBecomingUnavailable(funds2)
      actor ! PaymentProcessorActor.UnblockFunds(funds1)
      expectBecomingAvailable(funds2)
    }

  it must "reject funds usage for unknown funds id" in new WithBlockingFundsActor {
    val unknownFunds = ExchangeId("unknown")
    actor ! BlockedFiatRegistryActor.MarkUsed(unknownFunds, 10.EUR)
    expectMsgPF() { case BlockedFiatRegistryActor.CannotMarkUsed(`unknownFunds`, _, _) => }
  }

  it must "reject funds usage when it exceeds blocked amount" in new WithBlockingFundsActor {
    givenAccountUpdate(100.EUR)
    val funds = givenAvailableFunds(50.EUR)
    actor ! BlockedFiatRegistryActor.MarkUsed(funds, 100.EUR)
    expectMsgPF() {
      case BlockedFiatRegistryActor.CannotMarkUsed(`funds`, _, _) =>
    }
  }

  it must "reject funds usage when block is unavailable" in new WithBlockingFundsActor {
    givenAccountUpdate(100.EUR)
    val funds = givenUnavailableFunds(150.EUR)
    actor ! BlockedFiatRegistryActor.MarkUsed(funds, 100.EUR)
    expectMsgPF() {
      case BlockedFiatRegistryActor.CannotMarkUsed(`funds`, _, _) =>
    }
  }

  it must "accept funds usage when amount is less than blocked funds" in
    new WithBlockingFundsActor {
      givenAccountUpdate(100.EUR)
      val funds = givenAvailableFunds(50.EUR)
      actor ! BlockedFiatRegistryActor.MarkUsed(funds, 10.EUR)
      expectMsg(BlockedFiatRegistryActor.FundsMarkedUsed(funds, 10.EUR))
    }

  it must "accept funds usage when amount is equals to blocked funds" in
    new WithBlockingFundsActor {
      givenAccountUpdate(100.EUR)
      val funds = givenAvailableFunds(50.EUR)
        actor ! BlockedFiatRegistryActor.MarkUsed(funds, 50.EUR)
        expectMsgPF() { case BlockedFiatRegistryActor.FundsMarkedUsed(`funds`, _) => }
    }

  it must "consider new balance after funds are used" in new WithBlockingFundsActor {
    givenAccountUpdate(100.EUR)
    val funds1 = givenAvailableFunds(50.EUR)
    actor ! BlockedFiatRegistryActor.MarkUsed(funds1, 10.EUR)
    expectMsg(BlockedFiatRegistryActor.FundsMarkedUsed(funds1, 10.EUR))
    val funds2 = givenBlockedFunds(60.EUR)
    expectBecomingUnavailable(funds2)
  }

  it must "un-use funds" in new WithBlockingFundsActor {
    givenAccountUpdate(100.EUR)
    val funds1 = givenAvailableFunds(50.EUR)

    // Mark and unmark funds
    actor ! BlockedFiatRegistryActor.MarkUsed(funds1, 50.EUR)
    expectMsgType[BlockedFiatRegistryActor.FundsMarkedUsed]
    actor ! BlockedFiatRegistryActor.UnmarkUsed(funds1, 50.EUR)

    // Mark again
    actor ! BlockedFiatRegistryActor.MarkUsed(funds1, 50.EUR)
    expectMsgType[BlockedFiatRegistryActor.FundsMarkedUsed]
  }

  val funds1, funds2 = ExchangeId.random()

  it must "persist its state" in new WithBlockingFundsActor {
    givenAccountUpdate(100.EUR)
    actor ! PaymentProcessorActor.BlockFunds(funds1, 60.EUR)
    actor ! PaymentProcessorActor.BlockFunds(funds2, 40.EUR)
    expectBecomingAvailable(funds1)
    expectBecomingAvailable(funds2)
    expectMsgAllOf(
      PaymentProcessorActor.BlockedFunds(funds1),
      PaymentProcessorActor.BlockedFunds(funds2)
    )
    actor ! PaymentProcessorActor.UnblockFunds(funds2)
    actor ! BlockedFiatRegistryActor.MarkUsed(funds1, 20.EUR)
    expectMsg(BlockedFiatRegistryActor.FundsMarkedUsed(funds1, 20.EUR))
    system.stop(actor)
  }

  it must "recover its previous state" in new WithBlockingFundsActor(persistentId = lastId) {
    expectBecomingUnavailable(funds1)
    givenAccountUpdate(100.EUR)
    expectBecomingAvailable(funds1)
    currentTotalBlockedFunds(Euro) shouldBe 40.EUR
    system.stop(actor)
  }

  it must "recover its previous state from snapshot" in
    new WithBlockingFundsActor(persistentId = lastId) {
      expectBecomingUnavailable(funds1)
      actor ! PeriodicSnapshot.CreateSnapshot
      currentTotalBlockedFunds(Euro)
      system.stop(actor)

      start()
      expectBecomingUnavailable(funds1)
      givenAccountUpdate(100.EUR)
      expectBecomingAvailable(funds1)
      currentTotalBlockedFunds(Euro) shouldBe 40.EUR
    }

  private abstract class WithBlockingFundsActor(persistentId: Int = freshId()) {
    var actor: ActorRef = _
    start()

    protected def start(): Unit = {
      actor = system.actorOf(Props(new BlockedFiatRegistryActor(persistentId.toString)))
    }

    protected def givenBlockedFunds(amount: FiatAmount): ExchangeId = {
      val fundsId = ExchangeId.random()
      actor ! PaymentProcessorActor.BlockFunds(fundsId, amount)
      expectMsg(PaymentProcessorActor.BlockedFunds(fundsId))
      fundsId
    }

    protected def givenAvailableFunds(amount: FiatAmount): ExchangeId = {
      val fundsId = givenBlockedFunds(amount)
      expectBecomingAvailable(fundsId)
      fundsId
    }

    protected def givenUnavailableFunds(amount: FiatAmount): ExchangeId = {
      val fundsId = givenBlockedFunds(amount)
      expectBecomingUnavailable(fundsId)
      fundsId
    }

    protected def givenAccountUpdate(amount: FiatAmount, remainingLimit: FiatAmount): Unit = {
      givenAccountUpdate(amount, Some(remainingLimit))
    }

    protected def givenAccountUpdate(
        amount: FiatAmount, remainingLimit: Option[FiatAmount] = None): Unit = {
      actor ! BlockedFiatRegistryActor.AccountUpdate(
        balances = FiatAmounts.fromAmounts(amount),
        remainingLimits = FiatAmounts(remainingLimit.toSeq)
      )
    }

    protected def expectBecomingAvailable(fundsId: ExchangeId): Unit = {
      eventProbe.expectMsg(PaymentProcessorActor.AvailableFunds(fundsId))
    }

    protected def expectBecomingUnavailable(fundsId: ExchangeId): Unit = {
      eventProbe.expectMsg(PaymentProcessorActor.UnavailableFunds(fundsId))
    }

    protected def currentTotalBlockedFunds(currency: FiatCurrency): FiatAmount = {
      actor ! BlockedFiatRegistryActor.RetrieveTotalBlockedFunds
      expectMsgType[TotalBlockedFunds].funds.get(currency).getOrElse(currency.zero)
    }
  }

  private var lastId = 0
  private def freshId(): Int = {
    lastId += 1
    lastId
  }
}