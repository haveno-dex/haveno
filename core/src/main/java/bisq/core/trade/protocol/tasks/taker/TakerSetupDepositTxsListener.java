package bisq.core.trade.protocol.tasks.taker;

import bisq.common.taskrunner.TaskRunner;
import bisq.core.trade.Trade;
import bisq.core.trade.Trade.State;
import bisq.core.trade.protocol.tasks.SetupDepositTxsListener;

public class TakerSetupDepositTxsListener extends SetupDepositTxsListener {

    public TakerSetupDepositTxsListener(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected State getSeenState() {
        return Trade.State.TAKER_SAW_DEPOSIT_TX_IN_NETWORK;
    }
}
