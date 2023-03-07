package haveno.apitest.method.trade;

import haveno.core.trade.Trade;

/**
 * A test fixture encapsulating expected trade protocol status.
 * Status flags should be cleared via init() before starting a new trade protocol.
 */
public class ExpectedProtocolStatus {
    Trade.State state;
    Trade.Phase phase;
    boolean isDepositPublished;
    boolean isDepositConfirmed;
    boolean isPaymentSentMessageSent;
    boolean isPaymentReceivedMessageSent;
    boolean isPayoutPublished;
    boolean isCompleted;

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

    public ExpectedProtocolStatus setDepositConfirmed(boolean depositConfirmed) {
        isDepositConfirmed = depositConfirmed;
        return this;
    }

    public ExpectedProtocolStatus setPaymentSentMessageSent(boolean paymentSentMessageSent) {
        isPaymentSentMessageSent = paymentSentMessageSent;
        return this;
    }

    public ExpectedProtocolStatus setPaymentReceivedMessageSent(boolean paymentReceivedMessageSent) {
        isPaymentReceivedMessageSent = paymentReceivedMessageSent;
        return this;
    }

    public ExpectedProtocolStatus setPayoutPublished(boolean payoutPublished) {
        isPayoutPublished = payoutPublished;
        return this;
    }

    public ExpectedProtocolStatus setCompleted(boolean completed) {
        isCompleted = completed;
        return this;
    }

    public void init() {
        state = null;
        phase = null;
        isDepositPublished = false;
        isDepositConfirmed = false;
        isPaymentSentMessageSent = false;
        isPaymentReceivedMessageSent = false;
        isPayoutPublished = false;
        isCompleted = false;
    }
}
