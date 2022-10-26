package bisq.core.trade.protocol;

import bisq.common.handlers.ErrorMessageHandler;
import bisq.core.trade.ArbitratorTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.DepositRequest;
import bisq.core.trade.messages.DepositResponse;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.messages.SignContractResponse;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.ArbitratorProcessDepositRequest;
import bisq.core.trade.protocol.tasks.ArbitratorProcessReserveTx;
import bisq.core.trade.protocol.tasks.ArbitratorSendInitTradeOrMultisigRequests;
import bisq.core.trade.protocol.tasks.ProcessInitTradeRequest;
import bisq.core.trade.protocol.tasks.SendDepositsConfirmedMessageToBuyer;
import bisq.core.trade.protocol.tasks.SendDepositsConfirmedMessageToSeller;
import bisq.core.trade.protocol.tasks.TradeTask;
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
  }

  @Override
  public void onMailboxMessage(TradeMessage message, NodeAddress peer) {
      super.onMailboxMessage(message, peer);
  }

  ///////////////////////////////////////////////////////////////////////////////////////////
  // Incoming messages
  ///////////////////////////////////////////////////////////////////////////////////////////

  public void handleInitTradeRequest(InitTradeRequest message, NodeAddress peer, ErrorMessageHandler errorMessageHandler) {
      System.out.println("ArbitratorProtocol.handleInitTradeRequest()");
      new Thread(() -> {
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
                              ArbitratorProcessReserveTx.class,
                              ArbitratorSendInitTradeOrMultisigRequests.class)
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
      }).start();
  }
  
  @Override
  public void handleSignContractResponse(SignContractResponse message, NodeAddress sender) {
      log.warn("Arbitrator ignoring SignContractResponse");
  }
  
  public void handleDepositRequest(DepositRequest request, NodeAddress sender) {
    System.out.println("ArbitratorProtocol.handleDepositRequest() " + trade.getId());
    new Thread(() -> {
        synchronized (trade) {
            latchTrade();
            Validator.checkTradeId(processModel.getOfferId(), request);
            processModel.setTradeMessage(request);
            expect(phase(Trade.Phase.INIT)
                .with(request)
                .from(sender))
                .setup(tasks(
                        ArbitratorProcessDepositRequest.class)
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
    }).start();
  }
  
  @Override
  public void handleDepositResponse(DepositResponse response, NodeAddress sender) {
      log.warn("Arbitrator ignoring DepositResponse for trade " + response.getTradeId());
  }

  @SuppressWarnings("unchecked")
  @Override
  public Class<? extends TradeTask>[] getDepsitsConfirmedTasks() {
      return new Class[] { SendDepositsConfirmedMessageToBuyer.class, SendDepositsConfirmedMessageToSeller.class };
  }
}
