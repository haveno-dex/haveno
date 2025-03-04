package haveno.core.trade.protocol;

import haveno.common.ThreadUtils;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.core.trade.ArbitratorTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.DepositRequest;
import haveno.core.trade.messages.DepositResponse;
import haveno.core.trade.messages.InitTradeRequest;
import haveno.core.trade.messages.SignContractResponse;
import haveno.core.trade.messages.TradeMessage;
import haveno.core.trade.protocol.tasks.ApplyFilter;
import haveno.core.trade.protocol.tasks.ArbitratorProcessDepositRequest;
import haveno.core.trade.protocol.tasks.ArbitratorProcessReserveTx;
import haveno.core.trade.protocol.tasks.ArbitratorSendInitTradeOrMultisigRequests;
import haveno.core.trade.protocol.tasks.ProcessInitTradeRequest;
import haveno.core.trade.protocol.tasks.SendDepositsConfirmedMessageToBuyer;
import haveno.core.trade.protocol.tasks.SendDepositsConfirmedMessageToSeller;
import haveno.core.trade.protocol.tasks.TradeTask;
import haveno.core.util.Validator;
import haveno.network.p2p.NodeAddress;
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
      log.info(TradeProtocol.LOG_HIGHLIGHT + "handleInitTradeRequest() for {} {}", trade.getClass().getSimpleName(), trade.getShortId());
      ThreadUtils.execute(() -> {
          synchronized (trade.getLock()) {
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
                                  startTimeout();
                                  handleTaskRunnerSuccess(peer, message);
                              },
                              errorMessage -> {
                                  handleTaskRunnerFault(peer, message, errorMessage);
                              }))
                      .withTimeout(TRADE_STEP_TIMEOUT_SECONDS))
                      .executeTasks(true);
              awaitTradeLatch();
          }
      }, trade.getId());
  }
  
  @Override
  public void handleSignContractResponse(SignContractResponse message, NodeAddress sender) {
      log.warn("Arbitrator ignoring SignContractResponse");
  }
  
  public void handleDepositRequest(DepositRequest request, NodeAddress sender) {
    log.info(TradeProtocol.LOG_HIGHLIGHT + "handleDepositRequest() for {} {}", trade.getClass().getSimpleName(), trade.getShortId());
    ThreadUtils.execute(() -> {
        synchronized (trade.getLock()) {
            latchTrade();
            Validator.checkTradeId(processModel.getOfferId(), request);
            processModel.setTradeMessage(request);
            expect(anyPhase(Trade.Phase.INIT, Trade.Phase.DEPOSIT_REQUESTED)
                .with(request)
                .from(sender))
                .setup(tasks(
                        ArbitratorProcessDepositRequest.class)
                .using(new TradeTaskRunner(trade,
                        () -> {
                            if (trade.getState().ordinal() >= Trade.State.ARBITRATOR_PUBLISHED_DEPOSIT_TXS.ordinal()) {
                                stopTimeout();
                                this.errorMessageHandler = null;
                            }
                            handleTaskRunnerSuccess(sender, request);
                        },
                        errorMessage -> {
                            handleTaskRunnerFault(sender, request, errorMessage);
                        })))
                .executeTasks(true);
            awaitTradeLatch();
        }
    }, trade.getId());
  }
  
  @Override
  public void handleDepositResponse(DepositResponse response, NodeAddress sender) {
      log.warn("Arbitrator ignoring DepositResponse for trade " + response.getOfferId());
  }

  @SuppressWarnings("unchecked")
  @Override
  public Class<? extends TradeTask>[] getDepositsConfirmedTasks() {
      return new Class[] { SendDepositsConfirmedMessageToBuyer.class, SendDepositsConfirmedMessageToSeller.class };
  }

  @Override
  public void handleError(String errorMessage) {
    // set trade state to send deposit responses with nack
    if (trade instanceof ArbitratorTrade && trade.getState() == Trade.State.SAW_ARRIVED_PUBLISH_DEPOSIT_TX_REQUEST) {
        trade.setStateIfValidTransitionTo(Trade.State.PUBLISH_DEPOSIT_TX_REQUEST_FAILED);
    }
    super.handleError(errorMessage);
  }
}
