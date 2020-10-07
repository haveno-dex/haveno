package bisq.core.trade.protocol;

import bisq.common.handlers.ErrorMessageHandler;
import bisq.core.trade.ArbitratorTrade;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.protocol.tasks.ProcessInitTradeRequest;
import bisq.core.util.Validator;
import bisq.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ArbitratorProtocol extends TradeProtocol {
  
  private final ArbitratorTrade arbitratorTrade;
  
  public ArbitratorProtocol(ArbitratorTrade trade) {
    super(trade);
    
    this.arbitratorTrade = trade;

//    Trade.Phase phase = trade.getState().getPhase();
//    if (phase == Trade.Phase.TAKER_FEE_PUBLISHED) {
//        TradeTaskRunner taskRunner = new TradeTaskRunner(trade,
//                () -> handleTaskRunnerSuccess("BuyerSetupDepositTxListener"),
//                this::handleTaskRunnerFault);
//
//        taskRunner.addTasks(BuyerSetupDepositTxListener.class);
//        taskRunner.run();
//    } else if (trade.isFiatSent() && !trade.isPayoutPublished()) {
//        TradeTaskRunner taskRunner = new TradeTaskRunner(trade,
//                () -> handleTaskRunnerSuccess("BuyerSetupPayoutTxListener"),
//                this::handleTaskRunnerFault);
//
//        taskRunner.addTasks(BuyerSetupPayoutTxListener.class);
//        taskRunner.run();
//    }
  }
  
  ///////////////////////////////////////////////////////////////////////////////////////////
  // Start trade
  ///////////////////////////////////////////////////////////////////////////////////////////
  
  public void handleInitTradeRequest(InitTradeRequest tradeMessage,
                                     NodeAddress peerNodeAddress,
                                     ErrorMessageHandler errorMessageHandler) {    
      Validator.checkTradeId(processModel.getOfferId(), tradeMessage);
      processModel.setTradeMessage(tradeMessage);
      
      TradeTaskRunner taskRunner = new TradeTaskRunner(arbitratorTrade,
              () -> handleTaskRunnerSuccess(tradeMessage, "handleInitTradeRequest"),
              errorMessage -> {
                  errorMessageHandler.handleErrorMessage(errorMessage);
                  handleTaskRunnerFault(errorMessage);
              });
      taskRunner.addTasks(
              //ApplyFilter.class,  // TODO (woodser): support context of arbitrator (NPE accessing trading peer)
              ProcessInitTradeRequest.class
      );
      
      // TODO (woodser): need timeout with xmr
      // We don't use a timeout here because if the DepositTxPublishedMessage does not arrive we
      // get the deposit tx set at MakerSetupDepositTxListener once it is seen in the bitcoin network
      taskRunner.run();
  }
}
