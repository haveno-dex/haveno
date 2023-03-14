package haveno.desktop.util.validation;

import haveno.core.locale.Res;
import haveno.core.payment.validation.NationalAccountIdValidator;
import org.junit.Before;
import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NationalAccountIdValidatorTest {
    @Before
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
