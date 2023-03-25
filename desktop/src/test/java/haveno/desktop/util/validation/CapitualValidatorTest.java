package haveno.desktop.util.validation;

import haveno.common.config.BaseCurrencyNetwork;
import haveno.common.config.Config;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.payment.validation.CapitualValidator;
import haveno.core.util.validation.RegexValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CapitualValidatorTest {
    @BeforeEach
    public void setup() {
        final BaseCurrencyNetwork baseCurrencyNetwork = Config.baseCurrencyNetwork();
        final String currencyCode = baseCurrencyNetwork.getCurrencyCode();
        Res.setBaseCurrencyCode(currencyCode);
        Res.setBaseCurrencyName(baseCurrencyNetwork.getCurrencyName());
        CurrencyUtil.setBaseCurrencyCode(currencyCode);
    }

    @Test
    public void validate() {
        CapitualValidator validator = new CapitualValidator(
                new RegexValidator()
        );

        assertTrue(validator.validate("CAP-123456").isValid);
        assertTrue(validator.validate("CAP-XXXXXX").isValid);
        assertTrue(validator.validate("CAP-123XXX").isValid);

        assertFalse(validator.validate("").isValid);
        assertFalse(validator.validate(null).isValid);
        assertFalse(validator.validate("123456").isValid);
        assertFalse(validator.validate("XXXXXX").isValid);
        assertFalse(validator.validate("123XXX").isValid);
        assertFalse(validator.validate("12XXX").isValid);
        assertFalse(validator.validate("CAP-12XXX").isValid);
        assertFalse(validator.validate("CA-12XXXx").isValid);
    }
}
