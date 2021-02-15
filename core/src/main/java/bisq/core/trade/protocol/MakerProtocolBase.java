package bisq.core.trade.protocol;

import bisq.common.handlers.ErrorMessageHandler;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.DepositTxMessage;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.messages.InputsForDepositTxRequest;
import bisq.core.trade.messages.MakerReadyToFundMultisigRequest;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.ProcessInitTradeRequest;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.core.trade.protocol.tasks.VerifyPeersAccountAgeWitness;
import bisq.core.trade.protocol.tasks.buyer_as_maker.BuyerAsMakerCreatesAndSignsDepositTx;
import bisq.core.trade.protocol.tasks.buyer_as_maker.BuyerAsMakerSendsInputsForDepositTxResponse;
import bisq.core.trade.protocol.tasks.maker.MakerCreateAndPublishDepositTx;
import bisq.core.trade.protocol.tasks.maker.MakerCreateAndSignContract;
import bisq.core.trade.protocol.tasks.maker.MakerProcessesInputsForDepositTxRequest;
import bisq.core.trade.protocol.tasks.maker.MakerRemovesOpenOffer;
import bisq.core.trade.protocol.tasks.maker.MakerSendsInitTradeRequest;
import bisq.core.trade.protocol.tasks.maker.MakerSendsReadyToFundMultisigResponse;
import bisq.core.trade.protocol.tasks.maker.MakerSetsLockTime;
import bisq.core.trade.protocol.tasks.maker.MakerSetupDepositTxsListener;
import bisq.core.trade.protocol.tasks.maker.MakerVerifyTakerFeePayment;
import bisq.core.util.Validator;
import bisq.network.p2p.NodeAddress;

/**
 * Abstract base class for maker protocol.
 */
public class MakerProtocolBase extends DisputeProtocol implements MakerProtocol {
  
  public MakerProtocolBase(Trade trade) {
    super(trade);
  }
  
  ///////////////////////////////////////////////////////////////////////////////////////////
  // Handle take offer request
  ///////////////////////////////////////////////////////////////////////////////////////////
  
  @Override
  public void handleInitTradeRequest(InitTradeRequest message,
                                     NodeAddress peer,
                                     ErrorMessageHandler errorMessageHandler) {
      expect(phase(Trade.Phase.INIT)
          .with(message)
          .from(peer))
          .setup(tasks(
                  ProcessInitTradeRequest.class,
                  ApplyFilter.class,
                  VerifyPeersAccountAgeWitness.class,
                  MakerVerifyTakerFeePayment.class,
                  MakerSendsInitTradeRequest.class, // TODO (woodser): contact arbitrator here?  probably later when ready to create multisig
                  MakerRemovesOpenOffer.class,      // TODO (woodser): remove offer after taker pays trade fee or it needs to be reserved until deposit tx
                  MakerSendsReadyToFundMultisigResponse.class).
                  using(new TradeTaskRunner(trade,
                          () -> {
                            stopTimeout();
                            handleTaskRunnerSuccess(message);
                          },
                          errorMessage -> {
                              errorMessageHandler.handleErrorMessage(errorMessage);
                              handleTaskRunnerFault(message, errorMessage);
                          }))
                  .withTimeout(30))
          .executeTasks();
  }

  // TODO (woodser): delete this and any others not used
  @Override
  public void handleTakeOfferRequest(InputsForDepositTxRequest message,
                                     NodeAddress peer,
                                     ErrorMessageHandler errorMessageHandler) {
      expect(phase(Trade.Phase.INIT)
              .with(message)
              .from(peer))
              .setup(tasks(
                      MakerProcessesInputsForDepositTxRequest.class,
                      ApplyFilter.class,
                      VerifyPeersAccountAgeWitness.class,
                      getVerifyPeersFeePaymentClass(),
                      MakerSetsLockTime.class,
                      MakerCreateAndSignContract.class,
                      BuyerAsMakerCreatesAndSignsDepositTx.class,
                      MakerSetupDepositTxsListener.class,
                      BuyerAsMakerSendsInputsForDepositTxResponse.class).
                      using(new TradeTaskRunner(trade,
                              () -> handleTaskRunnerSuccess(message),
                              errorMessage -> {
                                  errorMessageHandler.handleErrorMessage(errorMessage);
                                  handleTaskRunnerFault(message, errorMessage);
                              }))
                      .withTimeout(30))
              .executeTasks();
  }
  
  @Override
  public void handleMakerReadyToFundMultisigRequest(MakerReadyToFundMultisigRequest message,
                                     NodeAddress sender,
                                     ErrorMessageHandler errorMessageHandler) {
    Validator.checkTradeId(processModel.getOfferId(), message);
    processModel.setTradeMessage(message);
    processModel.setTempTradingPeerNodeAddress(sender);
    
    expect(anyPhase(Trade.Phase.INIT, Trade.Phase.TAKER_FEE_PUBLISHED)
          .with(message)
          .from(sender))
          .setup(tasks(
                  MakerSendsReadyToFundMultisigResponse.class).
                  using(new TradeTaskRunner(trade,
                          () -> {
                            stopTimeout();
                            handleTaskRunnerSuccess(message);
                          },
                          errorMessage -> {
                              errorMessageHandler.handleErrorMessage(errorMessage);
                              handleTaskRunnerFault(message, errorMessage);
                          }))
                  .withTimeout(30))
          .executeTasks();
  }
  
  @Override
  public void handleDepositTxMessage(DepositTxMessage message,
                                    NodeAddress sender,
                                    ErrorMessageHandler errorMessageHandler) {
    Validator.checkTradeId(processModel.getOfferId(), message);
    processModel.setTradeMessage(message);
    processModel.setTempTradingPeerNodeAddress(sender);
    
    // TODO (woodser): MakerProcessesTakerDepositTxMessage.java which verifies deposit amount = fee + security deposit (+ trade amount), or that deposit is exact amount
    expect(anyPhase(Trade.Phase.INIT, Trade.Phase.TAKER_FEE_PUBLISHED)
          .with(message)
          .from(sender))
          .setup(tasks(
                  MakerCreateAndSignContract.class,
                  MakerCreateAndPublishDepositTx.class,
                  MakerSetupDepositTxsListener.class).
                  using(new TradeTaskRunner(trade,
                          () -> handleTaskRunnerSuccess(message),
                          errorMessage -> {
                              errorMessageHandler.handleErrorMessage(errorMessage);
                              handleTaskRunnerFault(message, errorMessage);
                          })))
          .executeTasks();
  }
  
  protected Class<? extends TradeTask> getVerifyPeersFeePaymentClass() {
      return MakerVerifyTakerFeePayment.class;
  }
}