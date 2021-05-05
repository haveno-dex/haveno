package bisq.core.trade.protocol.tasks.taker;

import bisq.core.trade.Trade;
import bisq.core.trade.Trade.State;
import bisq.core.trade.protocol.tasks.SetupDepositTxsListener;

import bisq.common.taskrunner.TaskRunner;

public class TakerSetupDepositTxsListener extends SetupDepositTxsListener {

  public TakerSetupDepositTxsListener(TaskRunner taskHandler, Trade trade) {
    super(taskHandler, trade);
  }

  @Override
  protected State getSeenState() {
    return Trade.State.TAKER_SAW_DEPOSIT_TX_IN_NETWORK;
  }
}
