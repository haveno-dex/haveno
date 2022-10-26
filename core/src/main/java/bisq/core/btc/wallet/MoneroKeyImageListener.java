package bisq.core.btc.wallet;

import java.util.Map;

import monero.daemon.model.MoneroKeyImageSpentStatus;

public interface MoneroKeyImageListener {
    
    /**
     * Called with changes to the spent status of key images.
     */
    public void onSpentStatusChanged(Map<String, MoneroKeyImageSpentStatus> spentStatuses);
}
