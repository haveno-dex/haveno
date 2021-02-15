package bisq.core.trade.protocol.tasks.maker;

import bisq.common.taskrunner.TaskRunner;
import bisq.core.trade.Trade;
import bisq.core.trade.Trade.State;
import bisq.core.trade.protocol.tasks.SetupDepositTxsListener;

public class MakerSetupDepositTxsListener extends SetupDepositTxsListener {

  public MakerSetupDepositTxsListener(TaskRunner taskHandler, Trade trade) {
    super(taskHandler, trade);
  }

  @Override
  protected State getSeenState() {
    return Trade.State.MAKER_SAW_DEPOSIT_TX_IN_NETWORK;
  }
}
