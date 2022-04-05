package bisq.apitest.method.trade;

import bisq.core.trade.Trade;

/**
 * A test fixture encapsulating expected trade protocol status.
 * Status flags should be cleared via init() before starting a new trade protocol.
 */
public class ExpectedProtocolStatus {
    Trade.State state;
    Trade.Phase phase;
    boolean isDepositPublished;
    boolean isDepositUnlocked;
    boolean isPaymentSent;
    boolean isPaymentReceived;
    boolean isPayoutPublished;
    boolean isWithdrawn;

    public ExpectedProtocolStatus setState(Trade.State state) {
        this.state = state;
        return this;
    }

    public ExpectedProtocolStatus setPhase(Trade.Phase phase) {
        this.phase = phase;
        return this;
    }

    public ExpectedProtocolStatus setDepositPublished(boolean depositPublished) {
        isDepositPublished = depositPublished;
        return this;
    }

    public ExpectedProtocolStatus setDepositUnlocked(boolean depositUnlocked) {
        isDepositUnlocked = depositUnlocked;
        return this;
    }

    public ExpectedProtocolStatus setFiatSent(boolean paymentSent) {
        isPaymentSent = paymentSent;
        return this;
    }

    public ExpectedProtocolStatus setFiatReceived(boolean paymentReceived) {
        isPaymentReceived = paymentReceived;
        return this;
    }

    public ExpectedProtocolStatus setPayoutPublished(boolean payoutPublished) {
        isPayoutPublished = payoutPublished;
        return this;
    }

    public ExpectedProtocolStatus setWithdrawn(boolean withdrawn) {
        isWithdrawn = withdrawn;
        return this;
    }

    public void init() {
        state = null;
        phase = null;
        isDepositPublished = false;
        isDepositUnlocked = false;
        isPaymentSent = false;
        isPaymentReceived = false;
        isPayoutPublished = false;
        isWithdrawn = false;
    }
}
