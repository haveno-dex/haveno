package haveno.core.locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BankUtilTest {

    @BeforeEach
    public void setup() {
        Locale.setDefault(new Locale("en", "US"));
        GlobalSettings.setLocale(new Locale("en", "US"));
        Res.setBaseCurrencyCode("XMR");
        Res.setBaseCurrencyName("Monero");
    }

    @Test
    public void testBankFieldsForArgentina() {
        final String argentina = "AR";

        assertTrue(BankUtil.isHolderIdRequired(argentina));
        assertEquals("CUIL/CUIT", BankUtil.getHolderIdLabel(argentina));
        assertEquals("CUIT", BankUtil.getHolderIdLabelShort(argentina));

        assertTrue(BankUtil.isNationalAccountIdRequired(argentina));
        assertEquals("CBU number", BankUtil.getNationalAccountIdLabel(argentina));

        assertTrue(BankUtil.isBankNameRequired(argentina));

        assertTrue(BankUtil.isBranchIdRequired(argentina));
        assertTrue(BankUtil.isAccountNrRequired(argentina));
        assertEquals("Número de cuenta", BankUtil.getAccountNrLabel(argentina));

        assertTrue(BankUtil.useValidation(argentina));

        assertFalse(BankUtil.isBankIdRequired(argentina));
        assertFalse(BankUtil.isStateRequired(argentina));
        assertFalse(BankUtil.isAccountTypeRequired(argentina));

    }

}
