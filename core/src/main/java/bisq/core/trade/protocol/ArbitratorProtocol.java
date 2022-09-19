package bisq.core.trade.protocol;

import bisq.common.handlers.ErrorMessageHandler;
import bisq.core.trade.ArbitratorTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.DepositRequest;
import bisq.core.trade.messages.DepositResponse;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.messages.PaymentAccountKeyRequest;
import bisq.core.trade.messages.SignContractResponse;
import bisq.core.trade.messages.PayoutTxPublishedMessage;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.protocol.FluentProtocol.Condition;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.ArbitratorProcessesDepositRequest;
import bisq.core.trade.protocol.tasks.ArbitratorProcessesPaymentAccountKeyRequest;
import bisq.core.trade.protocol.tasks.ArbitratorProcessesReserveTx;
import bisq.core.trade.protocol.tasks.ArbitratorProcessPayoutTxPublishedMessage;
import bisq.core.trade.protocol.tasks.ArbitratorSendsInitTradeOrMultisigRequests;
import bisq.core.trade.protocol.tasks.ProcessInitTradeRequest;
import bisq.core.util.Validator;
import bisq.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ArbitratorProtocol extends DisputeProtocol {

  public ArbitratorProtocol(ArbitratorTrade trade) {
    super(trade);
  }

  @Override
  protected void onTradeMessage(TradeMessage message, NodeAddress peer) {
      super.onTradeMessage(message, peer);
      if (message instanceof PayoutTxPublishedMessage) {
          handle((PayoutTxPublishedMessage) message, peer);
      }
  }

  @Override
  public void onMailboxMessage(TradeMessage message, NodeAddress peer) {
      super.onMailboxMessage(message, peer);
      if (message instanceof PayoutTxPublishedMessage) {
          handle((PayoutTxPublishedMessage) message, peer);
      }
  }

  ///////////////////////////////////////////////////////////////////////////////////////////
  // Incoming messages
  ///////////////////////////////////////////////////////////////////////////////////////////

  public void handleInitTradeRequest(InitTradeRequest message, NodeAddress peer, ErrorMessageHandler errorMessageHandler) {
      System.out.println("ArbitratorProtocol.handleInitTradeRequest()");
      synchronized (trade) {
          latchTrade();
          this.errorMessageHandler = errorMessageHandler;
          processModel.setTradeMessage(message); // TODO (woodser): confirm these are null without being set
          expect(phase(Trade.Phase.INIT)
                  .with(message)
                  .from(peer))
                  .setup(tasks(
                          ApplyFilter.class,
                          ProcessInitTradeRequest.class,
                          ArbitratorProcessesReserveTx.class,
                          ArbitratorSendsInitTradeOrMultisigRequests.class)
                  .using(new TradeTaskRunner(trade,
                          () -> {
                              startTimeout(TRADE_TIMEOUT);
                              handleTaskRunnerSuccess(peer, message);
                          },
                          errorMessage -> {
                              handleTaskRunnerFault(peer, message, errorMessage);
                          }))
                  .withTimeout(TRADE_TIMEOUT))
                  .executeTasks(true);
          awaitTradeLatch();
      }
  }
  
  @Override
  public void handleSignContractResponse(SignContractResponse message, NodeAddress sender) {
      log.warn("Arbitrator ignoring SignContractResponse");
  }
  
  public void handleDepositRequest(DepositRequest request, NodeAddress sender) {
    System.out.println("ArbitratorProtocol.handleDepositRequest() " + trade.getId());
    synchronized (trade) {
        latchTrade();
        Validator.checkTradeId(processModel.getOfferId(), request);
        processModel.setTradeMessage(request);
        expect(phase(Trade.Phase.INIT)
            .with(request)
            .from(sender))
            .setup(tasks(
                    ArbitratorProcessesDepositRequest.class)
            .using(new TradeTaskRunner(trade,
                    () -> {
                        if (trade.getState() == Trade.State.ARBITRATOR_PUBLISHED_DEPOSIT_TXS) {
                            stopTimeout();
                            this.errorMessageHandler = null;
                        }
                        handleTaskRunnerSuccess(sender, request);
                    },
                    errorMessage -> {
                        handleTaskRunnerFault(sender, request, errorMessage);
                    }))
            .withTimeout(TRADE_TIMEOUT))
            .executeTasks(true);
        awaitTradeLatch();
    }
  }
  
  @Override
  public void handleDepositResponse(DepositResponse response, NodeAddress sender) {
      log.warn("Arbitrator ignoring DepositResponse for trade " + response.getTradeId());
  }
  
  public void handlePaymentAccountKeyRequest(PaymentAccountKeyRequest request, NodeAddress sender) {
      System.out.println("ArbitratorProtocol.handlePaymentAccountKeyRequest() " + trade.getId());
      synchronized (trade) {
          latchTrade();
          Validator.checkTradeId(processModel.getOfferId(), request);
          processModel.setTradeMessage(request);
          expect(new Condition(trade)
              .with(request)
              .from(sender))
              .setup(tasks(
                      ArbitratorProcessesPaymentAccountKeyRequest.class)
              .using(new TradeTaskRunner(trade,
                      () -> {
                          stopTimeout();
                          handleTaskRunnerSuccess(sender, request);
                      },
                      errorMessage -> {
                          handleTaskRunnerFault(sender, request, errorMessage);
                      }))
              .withTimeout(TRADE_TIMEOUT))
              .executeTasks(true);
          awaitTradeLatch();
      }
  }
  
  protected void handle(PayoutTxPublishedMessage request, NodeAddress peer) {
      System.out.println("ArbitratorProtocol.handle(PayoutTxPublishedMessage)");
      new Thread(() -> {
          synchronized (trade) {
              if (trade.isCompleted()) return; // ignore subsequent requests
              latchTrade();
              Validator.checkTradeId(processModel.getOfferId(), request);
              processModel.setTradeMessage(request);
              expect(phase(Trade.Phase.DEPOSITS_PUBLISHED)
                  .with(request)
                  .from(peer))
                  .setup(tasks(
                      ArbitratorProcessPayoutTxPublishedMessage.class)
                      .using(new TradeTaskRunner(trade,
                          () -> {
                              handleTaskRunnerSuccess(peer, request);
                          },
                          errorMessage -> {
                              handleTaskRunnerFault(peer, request, errorMessage);
                          })))
                  .executeTasks(true);
              awaitTradeLatch();
          }
      }).start();
  }
}
