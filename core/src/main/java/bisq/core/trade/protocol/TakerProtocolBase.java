package bisq.core.trade.protocol;

import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.DepositTxMessage;
import bisq.core.trade.messages.InitMultisigMessage;
import bisq.core.trade.messages.MakerReadyToFundMultisigResponse;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.ProcessInitMultisigMessage;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.core.trade.protocol.tasks.taker.FundMultisig;
import bisq.core.trade.protocol.tasks.taker.TakerCreateReserveTradeTx;
import bisq.core.trade.protocol.tasks.taker.TakerProcessesMakerDepositTxMessage;
import bisq.core.trade.protocol.tasks.taker.TakerPublishFeeTx;
import bisq.core.trade.protocol.tasks.taker.TakerSendInitMultisigMessages;
import bisq.core.trade.protocol.tasks.taker.TakerSendInitTradeRequests;
import bisq.core.trade.protocol.tasks.taker.TakerSendReadyToFundMultisigRequest;
import bisq.core.trade.protocol.tasks.taker.TakerSetupDepositTxsListener;
import bisq.core.trade.protocol.tasks.taker.TakerVerifyAndSignContract;
import bisq.core.trade.protocol.tasks.taker.TakerVerifyMakerFeePayment;
import bisq.core.util.Validator;
import bisq.network.p2p.NodeAddress;
import java.math.BigInteger;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroTxWallet;
import monero.wallet.model.MoneroWalletListener;

/**
 * Abstract base class for taker protocol.
 * 
 * TODO (woodser): rename to TakerProtocol, MakerProtocol?
 */
@Slf4j
public class TakerProtocolBase extends DisputeProtocol implements TakerProtocol {
  private ResultHandler takeOfferListener;
  private Timer initDepositTimer;
  
  public TakerProtocolBase(Trade trade) {
    super(trade);
  }
  
  ///////////////////////////////////////////////////////////////////////////////////////////
  // Take offer
  ///////////////////////////////////////////////////////////////////////////////////////////
  
  @Override
  public void onTakeOffer() {
    System.out.println("onTakeOffer()");
    
    expect(phase(Trade.Phase.INIT)
        .with(TakerEvent.TAKE_OFFER)
        .from(trade.getTradingPeerNodeAddress()))
        .setup(tasks(
            ApplyFilter.class,
            TakerVerifyMakerFeePayment.class,
            TakerSendInitTradeRequests.class)
        .withTimeout(30))
        .executeTasks();
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
      createAndFundMultisig(message, takeOfferListener);
    } else if (trade.getTakerFeeTxId() == null && !trade.getState().equals(Trade.State.TAKER_PUBLISHED_TAKER_FEE_TX)) { // TODO (woodser): use processModel.isTradeFeeTxInitiated() like check above to avoid timing issues with subsequent requests
      reserveTrade(message, takeOfferListener);
    }
  }
  
  private void reserveTrade(MakerReadyToFundMultisigResponse message, ResultHandler handler) {
    System.out.println("BuyerAsTakerProtocol.reserveTrade()");
    
    // define wallet listener which initiates multisig deposit when trade fee tx unlocked
    // TODO (woodser): this needs run for reserved trades when client is opened
    // TODO (woodser): test initiating multisig when maker offline
    MoneroWallet wallet = processModel.getProvider().getXmrWalletService().getWallet();
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
              if (!processModel.isMultisigDepositInitiated()) sendMakerReadyToFundMultisigRequest(message, handler);
              else initDepositTimer.stop();
            }
          };
          UserThread.execute(requestMultisigDeposit);
          initDepositTimer = UserThread.runPeriodically(requestMultisigDeposit, 60);
        }
      }
    };
    
    // run pipeline to publish trade fee tx
    expect(new FluentProtocol.Condition(trade))
      .setup(tasks(
          TakerCreateReserveTradeTx.class,
          TakerVerifyMakerFeePayment.class,
          //TakerVerifyAndSignContract.class, // TODO (woodser): no... create taker fee tx, send to maker which creates contract, returns, then taker verifies and signs contract, then publishes taker fee tx
          TakerPublishFeeTx.class)  // TODO (woodser): need to notify maker/network of trade fee tx id to reserve trade?
          .using(new TradeTaskRunner(trade, 
              () -> {
                stopTimeout();
                handleTaskRunnerSuccess(message);
                if (handler != null) handler.handleResult();  // TODO (woodser): use handler to timeout initializing entire trade or remove use of handler and let gui indicate failure later?
                wallet.addListener(fundMultisigListener);  // listen for trade fee tx to become available then initiate multisig deposit  // TODO: put in pipeline
              },
              errorMessage -> {
                  handleTaskRunnerFault(message, errorMessage);
              }))
          .withTimeout(30))
      .executeTasks();
  }
  
  private void sendMakerReadyToFundMultisigRequest(MakerReadyToFundMultisigResponse message, ResultHandler handler) {
    System.out.println("TakerProtocolBase.sendMakerReadyToFundMultisigRequest()");
    expect(new FluentProtocol.Condition(trade))
      .setup(tasks(
          TakerVerifyMakerFeePayment.class,
          TakerSendReadyToFundMultisigRequest.class)
          .using(new TradeTaskRunner(trade,
              () -> {
                stopTimeout();
                handleTaskRunnerSuccess(message);
              },
              errorMessage -> {
                handleTaskRunnerFault(message, errorMessage);
              }))
          .withTimeout(30))
      .executeTasks();
  }
  
  private void createAndFundMultisig(MakerReadyToFundMultisigResponse message, ResultHandler handler) {
    System.out.println("TakerProtocolBase.createAndFundMultisig()");
    expect(new FluentProtocol.Condition(trade))
        .setup(tasks(
                TakerVerifyMakerFeePayment.class,
                TakerVerifyAndSignContract.class,
                TakerSendInitMultisigMessages.class)  // will receive MultisigMessage in response
                .using(new TradeTaskRunner(trade,
                        () -> {
                          stopTimeout();
                          handleTaskRunnerSuccess(message);
                        },
                        errorMessage -> {
                            handleTaskRunnerFault(message, errorMessage);
                        }))
                .withTimeout(30))
        .executeTasks();
  }
  
  @Override
  public void handleMultisigMessage(InitMultisigMessage message, NodeAddress sender, ErrorMessageHandler errorMessageHandler) {
    System.out.println("TakerProtocolBase.handleMultisigMessage()");
    Validator.checkTradeId(processModel.getOfferId(), message);
    processModel.setTradeMessage(message);
    expect(anyPhase(Trade.Phase.INIT, Trade.Phase.TAKER_FEE_PUBLISHED)
        .with(message)
        .from(sender))
        .setup(tasks(
            ProcessInitMultisigMessage.class)
            .using(new TradeTaskRunner(trade,
                () -> {
                  System.out.println("handle multisig pipeline completed successfully!");
                  handleTaskRunnerSuccess(message);
                  if (processModel.isMultisigSetupComplete() && !processModel.isMultisigDepositInitiated()) {
                    processModel.setMultisigDepositInitiated(true); // ensure only funding multisig one time
                    fundMultisig(message, takeOfferListener);
                  }
                },
                errorMessage -> {
                    System.out.println("error in handle multisig pipeline!!!: " + errorMessage);
                    errorMessageHandler.handleErrorMessage(errorMessage);
                    handleTaskRunnerFault(message, errorMessage);
                    takeOfferListener.handleResult();
                })))
        .executeTasks();
  }
  
  private void fundMultisig(InitMultisigMessage message, ResultHandler handler) {
    System.out.println("TakerProtocolBase.fundMultisig()");
    expect(new FluentProtocol.Condition(trade))
        .setup(tasks(
                FundMultisig.class).  // will receive MultisigMessage in response
                using(new TradeTaskRunner(trade,
                        () -> {
                          System.out.println("MULTISIG WALLET FUNDED!!!!");
                          stopTimeout();
                          handleTaskRunnerSuccess(message);
                        },
                        errorMessage -> {
                            handleTaskRunnerFault(message, errorMessage);
                        }))
                .withTimeout(30))
        .executeTasks();
  }
  
  @Override
  public void handleDepositTxMessage(DepositTxMessage message, NodeAddress sender, ErrorMessageHandler errorMessageHandler) {
    System.out.println("TakerProtocolBase.handleDepositTxMessage()");
    processModel.setTradeMessage(message);
    expect(anyPhase(Trade.Phase.INIT, Trade.Phase.DEPOSIT_PUBLISHED)
        .with(message)
        .from(sender))
        .setup(tasks(
                TakerProcessesMakerDepositTxMessage.class,
                TakerSetupDepositTxsListener.class).
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
  
  ///////////////////////////////////////////////////////////////////////////////////////////
  // Message dispatcher
  ///////////////////////////////////////////////////////////////////////////////////////////
  
//  @Override
//  public void onMailboxMessage(TradeMessage message, NodeAddress peer) {
//    super.onMailboxMessage(message, peer);
//
//    if (message instanceof DepositTxAndDelayedPayoutTxMessage) {
//        handle((DepositTxAndDelayedPayoutTxMessage) message, peer);
//    } else if (message instanceof PayoutTxPublishedMessage) {
//        handle((PayoutTxPublishedMessage) message, peer);
//    }
//  }
//
//  @Override
//  protected void onTradeMessage(TradeMessage message, NodeAddress peer) { // TODO: what's the point of this?  TradeManager calls directly
//      super.onTradeMessage(message, peer);
//
//      if (message instanceof InputsForDepositTxResponse) {
//          handle((InputsForDepositTxResponse) message, peer);
//      }
//  }
  
  protected Class<? extends TradeTask> getVerifyPeersFeePaymentClass() {
      return TakerVerifyMakerFeePayment.class;
  }
}