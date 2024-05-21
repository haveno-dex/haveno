package haveno.common.config;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class NetworkJSONFile {

    private List<String> alertKeys = new ArrayList<>();
    private List<String> arbitratorKeys = new ArrayList<>();
    private List<String> filterKeys = new ArrayList<>();
    private List<String> privateNotificationKeys = new ArrayList<>();

    private Boolean arbitratorAssignsTradeFeeAddress = true;
    private Double maker_ratio = 0.0015;
    private Double taker_ratio = 0.0075;
    private Double penalty_ratio = 0.02;
/*
    private static final boolean ARBITRATOR_ASSIGNS_TRADE_FEE_ADDRESS_DEFAULT = true;
    private static final double TAKER_RATIO_DEFAULT = 0.0015;
    private static final double MAKER_RATIO_DEFAULT = 0.0075;
    private static final double PENALTY_RATIO_DEFAULT = 0.02;*/

    private List<NetworkJSONFileSeedNode> seedNodes = new ArrayList<>();

    public boolean getArbitratorAssignsTradeFeeAddress() {
        return arbitratorAssignsTradeFeeAddress;
    }

    public void setArbitratorAssignsTradeFeeAddress(Boolean arbitratorAssignsTradeFeeAddress) {
        this.arbitratorAssignsTradeFeeAddress = arbitratorAssignsTradeFeeAddress;
    }

    public double getTaker_ratio() {
        return taker_ratio;
    }

    public void setTaker_ratio(Double taker_ratio) {
        this.taker_ratio = taker_ratio;
    }

    public double getMaker_ratio() {
        return maker_ratio;
    }

    public void setMaker_ratio(Double maker_ratio) {
        this.maker_ratio = maker_ratio;
    }

    public double getPenalty_ratio() {
        return penalty_ratio;
    }

    public void setPenalty_ratio(Double penalty_ratio) {
        this.penalty_ratio = penalty_ratio;
    }

    public List<String> getAlertKeys() {
        if(privateNotificationKeys == null || privateNotificationKeys.size() < 1) throw new NetworkJSONParseException("alertKeys needs to be a populated list of public keys!");
        return alertKeys;
    }

    public void setAlertKeys(List<String> alertKeys) {
        this.alertKeys = alertKeys;
    }

    public List<String> getArbitratorKeys() {
        if(privateNotificationKeys == null || privateNotificationKeys.size() < 1) throw new NetworkJSONParseException("arbitratorKeys needs to be a populated list of public keys!");
        return arbitratorKeys;
    }

    public void setArbitratorKeys(List<String> arbitratorKeys) {
        this.arbitratorKeys = arbitratorKeys;
    }

    public List<String> getFilterKeys() {
        if(privateNotificationKeys == null || privateNotificationKeys.size() < 1) throw new NetworkJSONParseException("filterKeys needs to be a populated list of public keys!");
        return filterKeys;
    }

    public void setFilterKeys(List<String> filterKeys) {
        this.filterKeys = filterKeys;
    }

    public List<String> getPrivateNotificationKeys() {
        if(privateNotificationKeys == null || privateNotificationKeys.size() < 1) throw new NetworkJSONParseException("privateNotificationKeys needs to be a populated list of public keys!");
        return privateNotificationKeys;
    }

    public void setPrivateNotificationKeys(List<String> privateNotificationKeys) {
        this.privateNotificationKeys = privateNotificationKeys;
    }

    public List<NetworkJSONFileSeedNode> getSeedNodes() {
        if(seedNodes == null || seedNodes.size() < 1) throw new NetworkJSONParseException("seedNodes needs to be a populated list of seed nodes!");
        return seedNodes;
    }

    public void setSeedNodes(List<NetworkJSONFileSeedNode> seedNodes) {
        this.seedNodes = seedNodes;
    }

    class NetworkJSONFileSeedNode {
        String onionAddress;
        String info;
    }

    class NetworkJSONParseException extends ConfigException {
        public NetworkJSONParseException(String format, Object... args) {
            super(format, args);
        }
    }
}
