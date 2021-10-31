package haveno.core.trade.protocol;

import haveno.core.trade.ArbitratorTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.DepositRequest;
import haveno.core.trade.messages.InitMultisigRequest;
import haveno.core.trade.messages.InitTradeRequest;
import haveno.core.trade.messages.SignContractRequest;
import haveno.core.trade.protocol.tasks.ApplyFilter;
import haveno.core.trade.protocol.tasks.ArbitratorSendsInitTradeRequestToMakerIfFromTaker;
import haveno.core.trade.protocol.tasks.ProcessDepositRequest;
import haveno.core.trade.protocol.tasks.ProcessInitMultisigRequest;
import haveno.core.trade.protocol.tasks.ArbitratorProcessesReserveTx;
import haveno.core.trade.protocol.tasks.ArbitratorSendsInitMultisigRequestsIfFundsReserved;
import haveno.core.trade.protocol.tasks.ProcessInitTradeRequest;
import haveno.core.trade.protocol.tasks.ProcessSignContractRequest;
import haveno.core.util.Validator;
import haveno.network.p2p.NodeAddress;

import haveno.common.handlers.ErrorMessageHandler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ArbitratorProtocol extends DisputeProtocol {

  public ArbitratorProtocol(ArbitratorTrade trade) {
    super(trade);
  }

  ///////////////////////////////////////////////////////////////////////////////////////////
  // Incoming messages
  ///////////////////////////////////////////////////////////////////////////////////////////

  public void handleInitTradeRequest(InitTradeRequest message, NodeAddress peer, ErrorMessageHandler errorMessageHandler) { // TODO (woodser): update impl to use errorMessageHandler
      processModel.setTradeMessage(message); // TODO (woodser): confirm these are null without being set
      //processModel.setTempTradingPeerNodeAddress(peer);
      expect(phase(Trade.Phase.INIT)
              .with(message)
              .from(peer))
              .setup(tasks(
                  ApplyFilter.class,
                  ProcessInitTradeRequest.class,
                  ArbitratorProcessesReserveTx.class,
                  ArbitratorSendsInitTradeRequestToMakerIfFromTaker.class,
                  ArbitratorSendsInitMultisigRequestsIfFundsReserved.class))
              .executeTasks();
  }
  
  @Override
  public void handleInitMultisigRequest(InitMultisigRequest request, NodeAddress sender, ErrorMessageHandler errorMessageHandler) {
    System.out.println("ArbitratorProtocol.handleInitMultisigRequest()");
    Validator.checkTradeId(processModel.getOfferId(), request);
    processModel.setTradeMessage(request);
    expect(anyPhase(Trade.Phase.INIT)
        .with(request)
        .from(sender))
        .setup(tasks(
            ProcessInitMultisigRequest.class)
            .using(new TradeTaskRunner(trade,
                () -> {
                  handleTaskRunnerSuccess(sender, request);
                },
                errorMessage -> {
                    errorMessageHandler.handleErrorMessage(errorMessage);
                    handleTaskRunnerFault(sender, request, errorMessage);
                })))
        .executeTasks();
  }
  
  @Override
  public void handleSignContractRequest(SignContractRequest message, NodeAddress sender, ErrorMessageHandler errorMessageHandler) {
      System.out.println("ArbitratorProtocol.handleSignContractRequest()");
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
                    handleTaskRunnerSuccess(sender, message);
                  },
                  errorMessage -> {
                      errorMessageHandler.handleErrorMessage(errorMessage);
                      handleTaskRunnerFault(sender, message, errorMessage);
                  })))
          .executeTasks();
  }
  
  public void handleDepositRequest(DepositRequest request, NodeAddress sender, ErrorMessageHandler errorMessageHandler) {
    System.out.println("ArbitratorProtocol.handleDepositRequest()");
    Validator.checkTradeId(processModel.getOfferId(), request);
    processModel.setTradeMessage(request);
    expect(anyPhase(Trade.Phase.INIT)
        .with(request)
        .from(sender))
        .setup(tasks(
            ProcessDepositRequest.class)
            .using(new TradeTaskRunner(trade,
                () -> {
                  handleTaskRunnerSuccess(sender, request);
                },
                errorMessage -> {
                    errorMessageHandler.handleErrorMessage(errorMessage);
                    handleTaskRunnerFault(sender, request, errorMessage);
                })))
        .executeTasks();
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
