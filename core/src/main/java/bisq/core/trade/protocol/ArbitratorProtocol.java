package bisq.core.trade.protocol;

import bisq.common.handlers.ErrorMessageHandler;
import bisq.core.trade.ArbitratorTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.DepositTxMessage;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.protocol.tasks.ProcessInitTradeRequest;
import bisq.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ArbitratorProtocol extends DisputeProtocol {
  
  private final ArbitratorTrade arbitratorTrade;
  
  public ArbitratorProtocol(ArbitratorTrade trade) {
    super(trade);
    this.arbitratorTrade = trade;
  }
  
  ///////////////////////////////////////////////////////////////////////////////////////////
  // Incoming messages
  ///////////////////////////////////////////////////////////////////////////////////////////
  
  // TODO: new implementation for MakerProtocol
//  private void handle(InitTradeRequest message, NodeAddress peer) {
//      expect(phase(Trade.Phase.INIT)
//              .with(message)
//              .from(peer))
//              .setup(tasks(ProcessInitTradeRequest.class,
//                  ApplyFilter.class,
//                  VerifyPeersAccountAgeWitness.class,
//                  MakerVerifyTakerFeePayment.class,
//                  MakerSendsInitTradeRequest.class, // TODO (woodser): contact arbitrator here?  probably later when ready to create multisig
//                  MakerSendsReadyToFundMultisigResponse.class)
//                  .withTimeout(30))
//              .executeTasks();
//  }
  
  public void handleInitTradeRequest(InitTradeRequest message, NodeAddress peer, ErrorMessageHandler errorMessageHandler) { // TODO (woodser): update impl to use errorMessageHandler
    expect(phase(Trade.Phase.INIT)
            .with(message)
            .from(peer))
            .setup(tasks(
                //ApplyFilter.class,
                ProcessInitTradeRequest.class))
            .executeTasks();
  }
  
  @Override
  public void handleDepositTxMessage(DepositTxMessage message, NodeAddress taker, ErrorMessageHandler errorMessageHandler) {
    throw new RuntimeException("Not implemented");
  }
  
//  @Override
//  public void handleTakeOfferRequest(InputsForDepositTxRequest message,
//                                     NodeAddress peer,
//                                     ErrorMessageHandler errorMessageHandler) {
//      expect(phase(Trade.Phase.INIT)
//              .with(message)
//              .from(peer))
//              .setup(tasks(
//                      MakerProcessesInputsForDepositTxRequest.class,
//                      ApplyFilter.class,
//                      VerifyPeersAccountAgeWitness.class,
//                      getVerifyPeersFeePaymentClass(),
//                      MakerSetsLockTime.class,
//                      MakerCreateAndSignContract.class,
//                      BuyerAsMakerCreatesAndSignsDepositTx.class,
//                      BuyerSetupDepositTxListener.class,
//                      BuyerAsMakerSendsInputsForDepositTxResponse.class).
//                      using(new TradeTaskRunner(trade,
//                              () -> handleTaskRunnerSuccess(message),
//                              errorMessage -> {
//                                  errorMessageHandler.handleErrorMessage(errorMessage);
//                                  handleTaskRunnerFault(message, errorMessage);
//                              }))
//                      .withTimeout(30))
//              .executeTasks();
//  }
  
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
