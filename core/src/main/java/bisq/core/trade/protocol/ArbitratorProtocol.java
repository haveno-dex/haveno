package bisq.core.trade.protocol;

import bisq.core.trade.ArbitratorTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.DepositRequest;
import bisq.core.trade.messages.DepositResponse;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.messages.PaymentAccountPayloadRequest;
import bisq.core.trade.messages.SignContractResponse;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.ArbitratorSendsInitTradeAndMultisigRequests;
import bisq.core.trade.protocol.tasks.ArbitratorProcessesDepositRequest;
import bisq.core.trade.protocol.tasks.ArbitratorProcessesReserveTx;
import bisq.core.trade.protocol.tasks.ProcessInitTradeRequest;
import bisq.core.util.Validator;
import bisq.network.p2p.NodeAddress;
import bisq.common.handlers.ErrorMessageHandler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ArbitratorProtocol extends DisputeProtocol {

  public ArbitratorProtocol(ArbitratorTrade trade) {
    super(trade);
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
                          ArbitratorSendsInitTradeAndMultisigRequests.class)
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
      log.warn("Arbitrator ignoring DepositResponse");
  }

  @Override
  public void handlePaymentAccountPayloadRequest(PaymentAccountPayloadRequest request, NodeAddress sender) {
      log.warn("Arbitrator ignoring PaymentAccountPayloadRequest");
  }

  ///////////////////////////////////////////////////////////////////////////////////////////
  // Message dispatcher
  ///////////////////////////////////////////////////////////////////////////////////////////

//  @Override
//  protected void onTradeMessage(TradeMessage message, NodeAddress peer) {
//    if (message instanceof InitTradeRequest) {
//      handleInitTradeRequest((InitTradeRequest) message, peer);
//    }
//  }
}
