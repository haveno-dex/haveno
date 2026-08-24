package haveno.core.payment.payload;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PaymentMethodTest {

    @Test
    public void testIsValidMaxTradePeriod() {
        assertTrue(PaymentMethod.isValidMaxTradePeriod(PaymentMethod.ZELLE_ID, PaymentMethod.ZELLE.getMaxTradePeriod()));
        assertTrue(PaymentMethod.isValidMaxTradePeriod(PaymentMethod.ZELLE_ID, PaymentMethod.ZELLE.getMaxTradePeriod() * 4)); // grandfathered
        assertTrue(PaymentMethod.isValidMaxTradePeriod(PaymentMethod.SEPA_ID, PaymentMethod.SEPA.getMaxTradePeriod()));

        assertFalse(PaymentMethod.isValidMaxTradePeriod(PaymentMethod.SEPA_ID, PaymentMethod.SEPA.getMaxTradePeriod() * 4));
        assertFalse(PaymentMethod.isValidMaxTradePeriod(PaymentMethod.ZELLE_ID, PaymentMethod.ZELLE.getMaxTradePeriod() * 2));
        assertFalse(PaymentMethod.isValidMaxTradePeriod(PaymentMethod.ZELLE_ID, Long.MAX_VALUE));
        assertFalse(PaymentMethod.isValidMaxTradePeriod(PaymentMethod.ZELLE_ID, 0));
        assertFalse(PaymentMethod.isValidMaxTradePeriod("UNKNOWN", PaymentMethod.ZELLE.getMaxTradePeriod()));
    }
}
