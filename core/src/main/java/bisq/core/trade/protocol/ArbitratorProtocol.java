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
import java.util.concurrent.CountDownLatch;
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

  public void handleInitTradeRequest(InitTradeRequest message,
          NodeAddress peer,
          ErrorMessageHandler errorMessageHandler) {
      synchronized (trade) {
          this.errorMessageHandler = errorMessageHandler;
          processModel.setTradeMessage(message); // TODO (woodser): confirm these are null without being set
          CountDownLatch latch = new CountDownLatch(1);
          //processModel.setTempTradingPeerNodeAddress(peer);
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
                              latch.countDown();
                              handleTaskRunnerSuccess(peer, message);
                          },
                          errorMessage -> {
                              latch.countDown();
                              errorMessageHandler.handleErrorMessage(errorMessage);
                              handleTaskRunnerFault(peer, message, errorMessage);
                          }))
                  .withTimeout(TRADE_TIMEOUT))
                  .executeTasks();
          wait(latch);
      }
  }
  
  @Override
  public void handleInitMultisigRequest(InitMultisigRequest request, NodeAddress sender) {
    System.out.println("ArbitratorProtocol.handleInitMultisigRequest()");
    synchronized (trade) {
        Validator.checkTradeId(processModel.getOfferId(), request);
        processModel.setTradeMessage(request);
        CountDownLatch latch = new CountDownLatch(1);
        expect(anyPhase(Trade.Phase.INIT)
            .with(request)
            .from(sender))
            .setup(tasks(
                    ProcessInitMultisigRequest.class)
            .using(new TradeTaskRunner(trade,
                    () -> {
                        latch.countDown();
                        handleTaskRunnerSuccess(sender, request);
                    },
                    errorMessage -> {
                        latch.countDown();
                        errorMessageHandler.handleErrorMessage(errorMessage);
                        handleTaskRunnerFault(sender, request, errorMessage);
                    }))
            .withTimeout(TRADE_TIMEOUT))
            .executeTasks();
        wait(latch);
    }
  }
  
  @Override
  public void handleSignContractRequest(SignContractRequest message, NodeAddress sender) {
      System.out.println("ArbitratorProtocol.handleSignContractRequest()");
      synchronized (trade) {
          Validator.checkTradeId(processModel.getOfferId(), message);
          processModel.setTradeMessage(message); // TODO (woodser): synchronize access since concurrent requests processed
          CountDownLatch latch = new CountDownLatch(1);
          expect(anyPhase(Trade.Phase.INIT)
              .with(message)
              .from(sender))
              .setup(tasks(
                      // TODO (woodser): validate request
                      ProcessSignContractRequest.class)
              .using(new TradeTaskRunner(trade,
                      () -> {
                          latch.countDown();
                          handleTaskRunnerSuccess(sender, message);
                      },
                      errorMessage -> {
                          latch.countDown();
                          errorMessageHandler.handleErrorMessage(errorMessage);
                          handleTaskRunnerFault(sender, message, errorMessage);
                      }))
              .withTimeout(TRADE_TIMEOUT))
              .executeTasks();
          wait(latch);
      }
  }
  
  public void handleDepositRequest(DepositRequest request, NodeAddress sender) {
    System.out.println("ArbitratorProtocol.handleDepositRequest()");
    synchronized (trade) {
        Validator.checkTradeId(processModel.getOfferId(), request);
        processModel.setTradeMessage(request);
        CountDownLatch latch = new CountDownLatch(1);
        expect(anyPhase(Trade.Phase.INIT)
            .with(request)
            .from(sender))
            .setup(tasks(
                    ArbitratorProcessesDepositRequest.class)
            .using(new TradeTaskRunner(trade,
                    () -> {
                        latch.countDown();
                        stopTimeout();
                        handleTaskRunnerSuccess(sender, request);
                    },
                    errorMessage -> {
                        latch.countDown();
                        errorMessageHandler.handleErrorMessage(errorMessage);
                        handleTaskRunnerFault(sender, request, errorMessage);
                    }))
            .withTimeout(TRADE_TIMEOUT))
            .executeTasks();
        wait(latch);
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
