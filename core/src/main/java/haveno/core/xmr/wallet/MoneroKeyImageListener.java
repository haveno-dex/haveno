package haveno.core.xmr.wallet;

import monero.daemon.model.MoneroKeyImageSpentStatus;

import java.util.Map;

public interface MoneroKeyImageListener {

    /**
     * Called with changes to the spent status of key images.
     */
    public void onSpentStatusChanged(Map<String, MoneroKeyImageSpentStatus> spentStatuses);
}
