package haveno.core.locale;

import java.util.Locale;

import org.junit.Before;
import org.junit.Test;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import haveno.core.locale.BankUtil;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;

public class BankUtilTest {

    @Before
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
