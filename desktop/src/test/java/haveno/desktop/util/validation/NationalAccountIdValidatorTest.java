package haveno.desktop.util.validation;

import haveno.core.locale.Res;
import haveno.core.payment.validation.NationalAccountIdValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NationalAccountIdValidatorTest {
    @BeforeEach
    public void setup() {
        Locale.setDefault(new Locale("en", "US"));
        Res.setBaseCurrencyCode("XMR");
        Res.setBaseCurrencyName("Monero");
    }

    @Test
    public void testValidationForArgentina(){
        NationalAccountIdValidator validator = new NationalAccountIdValidator("AR");
        assertTrue(validator.validate("2850590940090418135201").isValid);
        final String wrongNationalAccountId = "285059094009041813520";
        assertFalse(validator.validate(wrongNationalAccountId).isValid);
        assertEquals("CBU number must consist of 22 numbers.", validator.validate(wrongNationalAccountId).errorMessage);
    }
}
