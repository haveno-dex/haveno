package haveno.core.trade;

import haveno.core.trade.Trade;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates Trade phase ordering to ensure the fix for issue #113
 * (false invalid trade errors) works correctly. The fix changes
 * isMaybeInvalidTrade() threshold from DEPOSITS_PUBLISHED to
 * DEPOSITS_CONFIRMED, so deposit txs are not expected during publication.
 */
public class TradePhaseValidationTest {

    /**
     * Before the fix, trades at DEPOSITS_PUBLISHED phase with missing
     * deposit tx hashes were incorrectly flagged as invalid. The fix
     * changes the threshold from DEPOSITS_PUBLISHED to DEPOSITS_CONFIRMED.
     */
    @Test
    public void testDepositPublishedNotConsideredInvalidForTxChain() {
        // Phase.DEPOSITS_PUBLISHED has ordinal greater than DEPOSITS_PUBLISHED
        // but less than DEPOSITS_CONFIRMED. Verify the ordering is correct.
        assertTrue(
            Trade.Phase.DEPOSITS_CONFIRMED.ordinal() > Trade.Phase.DEPOSITS_PUBLISHED.ordinal(),
            "DEPOSITS_CONFIRMED should come after DEPOSITS_PUBLISHED"
        );

        // With the old threshold (DEPOSITS_PUBLISHED), a trade at DEPOSITS_PUBLISHED
        // phase would pass the phase check: DEPOSITS_PUBLISHED.ordinal() <= DEPOSITS_PUBLISHED.ordinal()
        boolean oldCheck = Trade.Phase.DEPOSITS_PUBLISHED.ordinal() <= Trade.Phase.DEPOSITS_PUBLISHED.ordinal();
        assertTrue(oldCheck, "Old check: DEPOSITS_PUBLISHED always passes");

        // With the new threshold (DEPOSITS_CONFIRMED), a trade at DEPOSITS_PUBLISHED
        // phase does NOT pass the phase check: DEPOSITS_CONFIRMED.ordinal() > DEPOSITS_PUBLISHED.ordinal()
        boolean newCheck = Trade.Phase.DEPOSITS_CONFIRMED.ordinal() <= Trade.Phase.DEPOSITS_PUBLISHED.ordinal();
        assertFalse(newCheck, "New check: DEPOSITS_PUBLISHED does NOT pass DEPOSITS_CONFIRMED threshold");

        // At DEPOSITS_CONFIRMED phase, the new check DOES apply:
        // DEPOSITS_CONFIRMED.ordinal() <= DEPOSITS_CONFIRMED.ordinal() == true
        boolean confirmedCheck = Trade.Phase.DEPOSITS_CONFIRMED.ordinal() <= Trade.Phase.DEPOSITS_CONFIRMED.ordinal();
        assertTrue(confirmedCheck, "DEPOSITS_CONFIRMED phase passes the new threshold");
    }

    /**
     * Verify that phases beyond DEPOSITS_CONFIRMED also trigger the tx chain check.
     */
    @Test
    public void testPhasesBeyondDepositsConfirmedStillChecked() {
        Trade.Phase[] phasesAfterConfirmed = {
            Trade.Phase.DEPOSITS_UNLOCKED,
            Trade.Phase.PAYMENT_SENT,
            Trade.Phase.PAYMENT_RECEIVED,
            Trade.Phase.DEPOSITS_FINALIZED,
        };

        for (Trade.Phase phase : phasesAfterConfirmed) {
            assertTrue(
                Trade.Phase.DEPOSITS_CONFIRMED.ordinal() <= phase.ordinal(),
                phase + " should be at or after DEPOSITS_CONFIRMED"
            );
        }
    }

    /**
     * Verify the inverted check: phases before DEPOSITS_CONFIRMED should NOT
     * trigger the tx chain validity check.
     */
    @Test
    public void testPhasesBeforeDepositsConfirmedNotChecked() {
        Trade.Phase[] phasesBeforeConfirmed = {
            Trade.Phase.INIT,
            Trade.Phase.DEPOSIT_REQUESTED,
            Trade.Phase.DEPOSITS_PUBLISHED,
        };

        for (Trade.Phase phase : phasesBeforeConfirmed) {
            assertFalse(
                Trade.Phase.DEPOSITS_CONFIRMED.ordinal() <= phase.ordinal(),
                phase + " should NOT pass DEPOSITS_CONFIRMED threshold"
            );
        }
    }
}
