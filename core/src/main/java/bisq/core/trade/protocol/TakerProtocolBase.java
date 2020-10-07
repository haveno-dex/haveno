package bisq.core.trade.protocol;

import java.math.BigInteger;

import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.DepositTxMessage;
import bisq.core.trade.messages.InitMultisigMessage;
import bisq.core.trade.messages.MakerReadyToFundMultisigResponse;
import bisq.core.trade.protocol.tasks.ProcessInitMultisigMessage;
import bisq.core.trade.protocol.tasks.taker.FundMultisig;
import bisq.core.trade.protocol.tasks.taker.TakerCreateReserveTradeTx;
import bisq.core.trade.protocol.tasks.taker.TakerProcessesMakerDepositTxMessage;
import bisq.core.trade.protocol.tasks.taker.TakerPublishReserveTradeTx;
import bisq.core.trade.protocol.tasks.taker.TakerSendInitTradeRequests;
import bisq.core.trade.protocol.tasks.taker.TakerSendInitMultisigMessages;
import bisq.core.trade.protocol.tasks.taker.TakerSendReadyToFundMultisigRequest;
import bisq.core.trade.protocol.tasks.taker.TakerSetupDepositTxsListener;
import bisq.core.trade.protocol.tasks.taker.TakerVerifyAndSignContract;
import bisq.core.trade.protocol.tasks.taker.TakerVerifyMakerAccount;
import bisq.core.trade.protocol.tasks.taker.TakerVerifyMakerFeePayment;
import bisq.core.util.Validator;
import bisq.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.MoneroWalletJni;
import monero.wallet.model.MoneroTxWallet;
import monero.wallet.model.MoneroWalletListener;

/**
 * Abstract base class for taker protocol.
 */
@Slf4j
public abstract class TakerProtocolBase extends TradeProtocol implements TakerProtocol {
  private ResultHandler takeOfferListener;
  private Timer initDepositTimer;
  
  public TakerProtocolBase(Trade trade) {
    super(trade);
  }
  
  ///////////////////////////////////////////////////////////////////////////////////////////
  // Start trade
  ///////////////////////////////////////////////////////////////////////////////////////////

  @Override
  public void takeAvailableOffer(ResultHandler handler) {
      this.takeOfferListener = handler;
      TradeTaskRunner taskRunner = new TradeTaskRunner(trade,
              () -> handleTaskRunnerSuccess("takeAvailableOffer"),
              this::handleTaskRunnerFault);

      taskRunner.addTasks(
          TakerVerifyMakerAccount.class,
          TakerVerifyMakerFeePayment.class,
          TakerSendInitTradeRequests.class  // will receive MakerReadyToFundMultisigResponse in response
      );

      //TODO if peer does get an error he does not respond and all we get is the timeout now knowing why it failed.
      // We should add an error message the peer sends us in such cases.
      startTimeout();
      taskRunner.run();
  }
  
  ///////////////////////////////////////////////////////////////////////////////////////////
  // Incoming message handling
  ///////////////////////////////////////////////////////////////////////////////////////////
  
  @Override
  public void handleMakerReadyToFundMultisigResponse(MakerReadyToFundMultisigResponse message, NodeAddress peer, ErrorMessageHandler errorMessageHandler) {
    System.out.println("BuyerAsTakerProtocol.handleMakerReadyToFundMultisigResponse()");
    System.out.println("Maker is ready to fund multisig: " + message.isMakerReadyToFundMultisig());
    processModel.setTempTradingPeerNodeAddress(peer); // TODO: verify this
    if (processModel.isMultisigDepositInitiated()) throw new RuntimeException("Taker has already initiated multisig deposit.  This should not happen"); // TODO (woodser): proper error handling
    processModel.setTradeMessage(message);
    if (message.isMakerReadyToFundMultisig()) {
      createAndFundMultisig(takeOfferListener);
    } else if (trade.getTakerFeeTxId() == null && !trade.getState().equals(Trade.State.TAKER_PUBLISHED_TAKER_FEE_TX)) { // TODO (woodser): use processModel.isTradeFeeTxInitiated() like check above to avoid timing issues with subsequent requests
      reserveTrade(takeOfferListener);
    }
  }
  
  @Override
  public void handleDepositTxMessage(DepositTxMessage message, NodeAddress sender, ErrorMessageHandler errorMessageHandler) {
    System.out.println("SellerAsTakerProtocol.handleDepositTxMessage()");
    processModel.setTradeMessage(message);
    
    TradeTaskRunner taskRunner = new TradeTaskRunner(trade,
            () -> { handleTaskRunnerSuccess("handleDepositTxMessage"); },
            this::handleTaskRunnerFault);
    
    taskRunner.addTasks(
            TakerProcessesMakerDepositTxMessage.class,
            TakerSetupDepositTxsListener.class);
    
    taskRunner.run();
  }
  
  private void createAndFundMultisig(ResultHandler handler) {
    System.out.println("BuyerAsTakerProtocol.createAndFundMultisig()");
    TradeTaskRunner taskRunner = new TradeTaskRunner(trade,
            () -> {
              handleTaskRunnerSuccess("createAndFundMultisig");
            },
            this::handleTaskRunnerFault);
    
    taskRunner.addTasks(
            TakerVerifyMakerAccount.class,
            TakerVerifyMakerFeePayment.class,
            TakerVerifyAndSignContract.class,
            TakerSendInitMultisigMessages.class);  // will receive MultisigMessage in response

    taskRunner.run();
  }
  
  @Override
  public void handleMultisigMessage(InitMultisigMessage message, NodeAddress peer, ErrorMessageHandler errorMessageHandler) {
    System.out.println("SellerAsTakerProtocol.handleMultisigMessage()");
    Validator.checkTradeId(processModel.getOfferId(), message);
    processModel.setTradeMessage(message);

    TradeTaskRunner taskRunner = new TradeTaskRunner(trade,
            () -> {
              System.out.println("handle multisig pipeline completed successfully!");
              handleTaskRunnerSuccess(message, "handleMultisigMessage");
              if (processModel.isMultisigSetupComplete() && !processModel.isMultisigDepositInitiated()) {
                processModel.setMultisigDepositInitiated(true); // ensure only funding multisig one time
                fundMultisig(takeOfferListener);
              }
            },
            errorMessage -> {
              System.out.println("error in handle multisig pipeline!!!: " + errorMessage);
              errorMessageHandler.handleErrorMessage(errorMessage);
              handleTaskRunnerFault(errorMessage);
              takeOfferListener.handleResult();
            });
    taskRunner.addTasks(
            ProcessInitMultisigMessage.class
    );
    taskRunner.run();
  }
  
  private void fundMultisig(ResultHandler handler) {
    System.out.println("BuyerAsTakerProtocol.fundMultisig()");
    TradeTaskRunner taskRunner = new TradeTaskRunner(trade,
            () -> {
              System.out.println("MULTISIG WALLET FUNDED!!!!");
              stopTimeout();
              handleTaskRunnerSuccess("fundMultisig");
              handler.handleResult();
            },
            this::handleTaskRunnerFault);
    
    taskRunner.addTasks(FundMultisig.class);
    taskRunner.run();
  }
  
  private void reserveTrade(ResultHandler handler) {
    System.out.println("BuyerAsTakerProtocol.reserveTrade()");
    
    // define wallet listener which initiates multisig deposit when trade fee tx unlocked
    // TODO (woodser): this needs run for reserved trades when client is opened
    // TODO (woodser): test initiating multisig when maker offline
    MoneroWalletJni wallet = processModel.getXmrWalletService().getWallet();
    MoneroWalletListener fundMultisigListener = new MoneroWalletListener() {
      public void onBalancesChanged(BigInteger newBalance, BigInteger newUnlockedBalance) {
        
        // get updated offer fee tx
        MoneroTxWallet feeTx = wallet.getTx(processModel.getTakeOfferFeeTxId());
        
        // check if tx is unlocked
        if (Boolean.FALSE.equals(feeTx.isLocked())) {
          System.out.println("TRADE FEE TX IS UNLOCKED!!!");
          
          // stop listening to wallet
          wallet.removeListener(this);
          
          // periodically request multisig deposit until successful
          Runnable requestMultisigDeposit = new Runnable() {
            @Override
            public void run() {
              if (!processModel.isMultisigDepositInitiated()) sendMakerReadyToFundMultisigRequest(handler);
              else initDepositTimer.stop();
            }
          };
          UserThread.execute(requestMultisigDeposit);
          initDepositTimer = UserThread.runPeriodically(requestMultisigDeposit, 60);
        }
      }
    };
    
    // run pipeline to publish trade fee tx
    TradeTaskRunner taskRunner = new TradeTaskRunner(trade,
            () -> {
              stopTimeout();
              handleTaskRunnerSuccess("reserveTrade");
              handler.handleResult();
              wallet.addListener(fundMultisigListener);  // listen for trade fee tx to become available then initiate multisig deposit  // TODO: put in pipeline
            },
            this::handleTaskRunnerFault);
    
    taskRunner.addTasks(
            TakerCreateReserveTradeTx.class,
            TakerVerifyMakerAccount.class,
            TakerVerifyMakerFeePayment.class,
            //TakerVerifyAndSignContract.class, // TODO (woodser): no... create taker fee tx, send to maker which creates contract, returns, then taker verifies and signs contract, then publishes taker fee tx
            TakerPublishReserveTradeTx.class);  // TODO (woodser): need to notify maker/network of trade fee tx id to reserve trade?

    taskRunner.run();
  }
  
  private void sendMakerReadyToFundMultisigRequest(ResultHandler handler) {
    System.out.println("BuyerAsTakerProtocol.sendMakerReadyToFundMultisigRequest()");
    this.takeOfferListener = handler;
    TradeTaskRunner taskRunner = new TradeTaskRunner(trade,
            () -> {
              handleTaskRunnerSuccess("readyToFundMultisigRequest");
            },
            this::handleTaskRunnerFault);

    taskRunner.addTasks(
            TakerVerifyMakerAccount.class,
            TakerVerifyMakerFeePayment.class,
            TakerSendReadyToFundMultisigRequest.class
    );
    
    taskRunner.run();
  }
}