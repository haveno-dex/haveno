package bisq.core.trade.protocol;

import bisq.common.handlers.ErrorMessageHandler;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.DepositTxMessage;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.messages.MakerReadyToFundMultisigRequest;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.ProcessInitTradeRequest;
import bisq.core.trade.protocol.tasks.VerifyPeersAccountAgeWitness;
import bisq.core.trade.protocol.tasks.maker.MakerCreateAndPublishDepositTx;
import bisq.core.trade.protocol.tasks.maker.MakerCreateAndSignContract;
import bisq.core.trade.protocol.tasks.maker.MakerSendsInitTradeRequest;
import bisq.core.trade.protocol.tasks.maker.MakerSendsReadyToFundMultisigResponse;
import bisq.core.trade.protocol.tasks.maker.MakerSetupDepositTxsListener;
import bisq.core.trade.protocol.tasks.maker.MakerVerifyTakerAccount;
import bisq.core.trade.protocol.tasks.maker.MakerVerifyTakerFeePayment;
import bisq.core.util.Validator;
import bisq.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base class for maker protocol.
 */
@Slf4j
public abstract class MakerProtocolBase extends TradeProtocol implements MakerProtocol {
  
  public MakerProtocolBase(Trade trade) {
    super(trade);
  }
  
  ///////////////////////////////////////////////////////////////////////////////////////////
  // Start trade
  ///////////////////////////////////////////////////////////////////////////////////////////
  
  @Override
  public void handleInitTradeRequest(InitTradeRequest tradeMessage,
                                     NodeAddress peerNodeAddress,
                                     ErrorMessageHandler errorMessageHandler) {
      Validator.checkTradeId(processModel.getOfferId(), tradeMessage);
      processModel.setTradeMessage(tradeMessage);
      processModel.setTempTradingPeerNodeAddress(peerNodeAddress);

      TradeTaskRunner taskRunner = new TradeTaskRunner(trade,
              () -> handleTaskRunnerSuccess(tradeMessage, "handleInitTradeRequest"),
              errorMessage -> {
                  errorMessageHandler.handleErrorMessage(errorMessage);
                  handleTaskRunnerFault(errorMessage);
              });
      taskRunner.addTasks(
              ProcessInitTradeRequest.class,
              ApplyFilter.class,
              MakerVerifyTakerAccount.class,
              VerifyPeersAccountAgeWitness.class,
              MakerVerifyTakerFeePayment.class,
              MakerSendsInitTradeRequest.class, // TODO (woodser): contact arbitrator here?  probably later when ready to create multisig
              MakerSendsReadyToFundMultisigResponse.class
      );
      // We don't use a timeout here because if the DepositTxPublishedMessage does not arrive we
      // get the deposit tx set at MakerSetupDepositTxListener once it is seen in the bitcoin network
      taskRunner.run();
  }
  
  @Override
  public void handleMakerReadyToFundMultisigRequest(MakerReadyToFundMultisigRequest tradeMessage,
                                     NodeAddress peerNodeAddress,
                                     ErrorMessageHandler errorMessageHandler) {
      Validator.checkTradeId(processModel.getOfferId(), tradeMessage);
      processModel.setTradeMessage(tradeMessage);
      processModel.setTempTradingPeerNodeAddress(peerNodeAddress);

      TradeTaskRunner taskRunner = new TradeTaskRunner(trade,
              () -> handleTaskRunnerSuccess(tradeMessage, "handleReadyToFundMultisigRequest"),
              errorMessage -> {
                  errorMessageHandler.handleErrorMessage(errorMessage);
                  handleTaskRunnerFault(errorMessage);
              });
      taskRunner.addTasks(
              MakerSendsReadyToFundMultisigResponse.class

      );
      taskRunner.run();
  }
  
  @Override
  public void handleDepositTxMessage(DepositTxMessage message, NodeAddress taker, ErrorMessageHandler errorMessageHandler) {
    Validator.checkTradeId(processModel.getOfferId(), message);
    processModel.setTradeMessage(message);
    processModel.setTempTradingPeerNodeAddress(taker);

    TradeTaskRunner taskRunner = new TradeTaskRunner(trade,
            () -> handleTaskRunnerSuccess(message, "handleDepositTxMessage"),
            errorMessage -> {
                errorMessageHandler.handleErrorMessage(errorMessage);
                handleTaskRunnerFault(errorMessage);
            });
    taskRunner.addTasks(
        // TODO (woodser): MakerProcessesTakerDepositTxMessage.java which verifies deposit amount = fee + security deposit (+ trade amount), or that deposit is exact amount
            MakerCreateAndSignContract.class,
            MakerCreateAndPublishDepositTx.class,
            MakerSetupDepositTxsListener.class

    );
    taskRunner.run();
  }
}