package bisq.core.trade.protocol;

import bisq.core.trade.ArbitratorTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.DepositRequest;
import bisq.core.trade.messages.InitMultisigRequest;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.messages.SignContractRequest;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.ArbitratorSendsInitTradeAndMultisigRequests;
import bisq.core.trade.protocol.tasks.ArbitratorProcessesDepositRequest;
import bisq.core.trade.protocol.tasks.ProcessInitMultisigRequest;
import bisq.core.trade.protocol.tasks.ArbitratorProcessesReserveTx;
import bisq.core.trade.protocol.tasks.ProcessInitTradeRequest;
import bisq.core.trade.protocol.tasks.ProcessSignContractRequest;
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
                  .executeTasks();
          awaitTradeLatch();
      }
  }
  
  @Override
  public void handleInitMultisigRequest(InitMultisigRequest request, NodeAddress sender) {
    System.out.println("ArbitratorProtocol.handleInitMultisigRequest()");
    synchronized (trade) {
        latchTrade();
        Validator.checkTradeId(processModel.getOfferId(), request);
        processModel.setTradeMessage(request);
        expect(anyPhase(Trade.Phase.INIT)
            .with(request)
            .from(sender))
            .setup(tasks(
                    ProcessInitMultisigRequest.class)
            .using(new TradeTaskRunner(trade,
                    () -> {
                        startTimeout(TRADE_TIMEOUT);
                        handleTaskRunnerSuccess(sender, request);
                    },
                    errorMessage -> {
                        handleTaskRunnerFault(sender, request, errorMessage);
                    }))
            .withTimeout(TRADE_TIMEOUT))
            .executeTasks();
        awaitTradeLatch();
    }
  }
  
  @Override
  public void handleSignContractRequest(SignContractRequest message, NodeAddress sender) {
      System.out.println("ArbitratorProtocol.handleSignContractRequest()");
      synchronized (trade) {
          latchTrade();
          Validator.checkTradeId(processModel.getOfferId(), message);
          processModel.setTradeMessage(message); // TODO (woodser): synchronize access since concurrent requests processed
          expect(anyPhase(Trade.Phase.INIT)
              .with(message)
              .from(sender))
              .setup(tasks(
                      // TODO (woodser): validate request
                      ProcessSignContractRequest.class)
              .using(new TradeTaskRunner(trade,
                      () -> {
                          startTimeout(TRADE_TIMEOUT);
                          handleTaskRunnerSuccess(sender, message);
                      },
                      errorMessage -> {
                          handleTaskRunnerFault(sender, message, errorMessage);
                      }))
              .withTimeout(TRADE_TIMEOUT))
              .executeTasks();
          awaitTradeLatch();
      }
  }
  
  public void handleDepositRequest(DepositRequest request, NodeAddress sender) {
    System.out.println("ArbitratorProtocol.handleDepositRequest()");
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
                        if (trade.getState() == Trade.State.ARBITRATOR_PUBLISHED_DEPOSIT_TX) {
                            stopTimeout();
                            this.errorMessageHandler = null;
                        }
                        handleTaskRunnerSuccess(sender, request);
                    },
                    errorMessage -> {
                        handleTaskRunnerFault(sender, request, errorMessage);
                    }))
            .withTimeout(TRADE_TIMEOUT))
            .executeTasks();
        awaitTradeLatch();
    }
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
