package haveno.cli.opts;

import org.junit.jupiter.api.Test;

import static haveno.cli.Method.canceloffer;
import static haveno.cli.Method.createcryptopaymentacct;
import static haveno.cli.Method.createoffer;
import static haveno.cli.Method.createpaymentacct;
import static haveno.cli.opts.OptLabel.OPT_ACCOUNT_NAME;
import static haveno.cli.opts.OptLabel.OPT_ADDRESS;
import static haveno.cli.opts.OptLabel.OPT_AMOUNT;
import static haveno.cli.opts.OptLabel.OPT_CURRENCY_CODE;
import static haveno.cli.opts.OptLabel.OPT_DIRECTION;
import static haveno.cli.opts.OptLabel.OPT_MKT_PRICE_MARGIN;
import static haveno.cli.opts.OptLabel.OPT_OFFER_ID;
import static haveno.cli.opts.OptLabel.OPT_PASSWORD;
import static haveno.cli.opts.OptLabel.OPT_PAYMENT_ACCOUNT_FORM;
import static haveno.cli.opts.OptLabel.OPT_PAYMENT_ACCOUNT_ID;
import static haveno.cli.opts.OptLabel.OPT_SECURITY_DEPOSIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


public class OptionParsersTest {

    private static final String PASSWORD_OPT = "--" + OPT_PASSWORD + "=" + "xyz";

    // canceloffer opt parser tests

    @Test
    public void testCancelOfferWithMissingOfferIdOptShouldThrowException() {
        String[] args = new String[]{
                PASSWORD_OPT,
                canceloffer.name()
        };
        Throwable exception = assertThrows(RuntimeException.class, () ->
                new CancelOfferOptionParser(args).parse());
        assertEquals("no offer id specified", exception.getMessage());
    }

    @Test
    public void testCancelOfferWithEmptyOfferIdOptShouldThrowException() {
        String[] args = new String[]{
                PASSWORD_OPT,
                canceloffer.name(),
                "--" + OPT_OFFER_ID + "=" // missing opt value
        };
        Throwable exception = assertThrows(RuntimeException.class, () ->
                new CancelOfferOptionParser(args).parse());
        assertEquals("no offer id specified", exception.getMessage());
    }

    @Test
    public void testCancelOfferWithMissingOfferIdValueShouldThrowException() {
        String[] args = new String[]{
                PASSWORD_OPT,
                canceloffer.name(),
                "--" + OPT_OFFER_ID // missing equals sign & opt value
        };
        Throwable exception = assertThrows(RuntimeException.class, () ->
                new CancelOfferOptionParser(args).parse());
        assertEquals("offer-id requires an argument", exception.getMessage());
    }

    @Test
    public void testValidCancelOfferOpts() {
        String[] args = new String[]{
                PASSWORD_OPT,
                canceloffer.name(),
                "--" + OPT_OFFER_ID + "=" + "ABC-OFFER-ID"
        };
        new CancelOfferOptionParser(args).parse();
    }

    // createoffer (v1) opt parser tests

    @Test
    public void testCreateOfferWithMissingPaymentAccountIdOptShouldThrowException() {
        String[] args = new String[]{
                PASSWORD_OPT,
                createoffer.name(),
                "--" + OPT_DIRECTION + "=" + "SELL",
                "--" + OPT_CURRENCY_CODE + "=" + "JPY",
                "--" + OPT_AMOUNT + "=" + "0.1"
        };
        Throwable exception = assertThrows(RuntimeException.class, () ->
                new CreateOfferOptionParser(args).parse());
        assertEquals("no payment account id specified", exception.getMessage());
    }

    @Test
    public void testCreateOfferWithEmptyPaymentAccountIdOptShouldThrowException() {
        String[] args = new String[]{
                PASSWORD_OPT,
                createoffer.name(),
                "--" + OPT_PAYMENT_ACCOUNT_ID
        };
        Throwable exception = assertThrows(RuntimeException.class, () ->
                new CreateOfferOptionParser(args).parse());
        assertEquals("payment-account-id requires an argument", exception.getMessage());
    }

    @Test
    public void testCreateOfferWithMissingDirectionOptShouldThrowException() {
        String[] args = new String[]{
                PASSWORD_OPT,
                createoffer.name(),
                "--" + OPT_PAYMENT_ACCOUNT_ID + "=" + "abc-payment-acct-id-123"
        };
        Throwable exception = assertThrows(RuntimeException.class, () ->
                new CreateOfferOptionParser(args).parse());
        assertEquals("no direction (buy|sell) specified", exception.getMessage());
    }


    @Test
    public void testCreateOfferWithMissingDirectionOptValueShouldThrowException() {
        String[] args = new String[]{
                PASSWORD_OPT,
                createoffer.name(),
                "--" + OPT_PAYMENT_ACCOUNT_ID + "=" + "abc-payment-acct-id-123",
                "--" + OPT_DIRECTION + "=" + ""
        };
        Throwable exception = assertThrows(RuntimeException.class, () ->
                new CreateOfferOptionParser(args).parse());
        assertEquals("no direction (buy|sell) specified", exception.getMessage());
    }

    @Test
    public void testValidCreateOfferOpts() {
        String[] args = new String[]{
                PASSWORD_OPT,
                createoffer.name(),
                "--" + OPT_PAYMENT_ACCOUNT_ID + "=" + "abc-payment-acct-id-123",
                "--" + OPT_DIRECTION + "=" + "BUY",
                "--" + OPT_CURRENCY_CODE + "=" + "EUR",
                "--" + OPT_AMOUNT + "=" + "0.125",
                "--" + OPT_MKT_PRICE_MARGIN + "=" + "3.15",
                "--" + OPT_SECURITY_DEPOSIT + "=" + "25.0"
        };
        CreateOfferOptionParser parser = new CreateOfferOptionParser(args).parse();
        assertEquals("abc-payment-acct-id-123", parser.getPaymentAccountId());
        assertEquals("BUY", parser.getDirection());
        assertEquals("EUR", parser.getCurrencyCode());
        assertEquals("0.125", parser.getAmount());
        assertEquals(3.15d, parser.getMktPriceMarginPct());
        assertEquals(25.0, parser.getSecurityDepositPct());
    }

    // createpaymentacct opt parser tests

    @Test
    public void testCreatePaymentAcctWithMissingPaymentFormOptShouldThrowException() {
        String[] args = new String[]{
                PASSWORD_OPT,
                createpaymentacct.name()
                // OPT_PAYMENT_ACCOUNT_FORM
        };
        Throwable exception = assertThrows(RuntimeException.class, () ->
                new CreatePaymentAcctOptionParser(args).parse());
        assertEquals("no path to json payment account form specified", exception.getMessage());
    }

    @Test
    public void testCreatePaymentAcctWithMissingPaymentFormOptValueShouldThrowException() {
        String[] args = new String[]{
                PASSWORD_OPT,
                createpaymentacct.name(),
                "--" + OPT_PAYMENT_ACCOUNT_FORM + "="
        };
        Throwable exception = assertThrows(RuntimeException.class, () ->
                new CreatePaymentAcctOptionParser(args).parse());
        assertEquals("no path to json payment account form specified", exception.getMessage());
    }

    @Test
    public void testCreatePaymentAcctWithInvalidPaymentFormOptValueShouldThrowException() {
        String[] args = new String[]{
                PASSWORD_OPT,
                createpaymentacct.name(),
                "--" + OPT_PAYMENT_ACCOUNT_FORM + "=" + "/tmp/milkyway/solarsystem/mars"
        };
        Throwable exception = assertThrows(RuntimeException.class, () ->
                new CreatePaymentAcctOptionParser(args).parse());
        if (System.getProperty("os.name").toLowerCase().indexOf("win") >= 0)
            assertEquals("json payment account form '\\tmp\\milkyway\\solarsystem\\mars' could not be found",
                    exception.getMessage());
        else
            assertEquals("json payment account form '/tmp/milkyway/solarsystem/mars' could not be found",
                    exception.getMessage());
    }

    // createcryptopaymentacct parser tests

    @Test
    public void testCreateCryptoCurrencyPaymentAcctWithMissingAcctNameOptShouldThrowException() {
        String[] args = new String[]{
                PASSWORD_OPT,
                createcryptopaymentacct.name()
        };
        Throwable exception = assertThrows(RuntimeException.class, () ->
                new CreateCryptoCurrencyPaymentAcctOptionParser(args).parse());
        assertEquals("no payment account name specified", exception.getMessage());
    }

    @Test
    public void testCreateCryptoCurrencyPaymentAcctWithEmptyAcctNameOptShouldThrowException() {
        String[] args = new String[]{
                PASSWORD_OPT,
                createcryptopaymentacct.name(),
                "--" + OPT_ACCOUNT_NAME
        };
        Throwable exception = assertThrows(RuntimeException.class, () ->
                new CreateCryptoCurrencyPaymentAcctOptionParser(args).parse());
        assertEquals("account-name requires an argument", exception.getMessage());
    }

    @Test
    public void testCreateCryptoCurrencyPaymentAcctWithInvalidCurrencyCodeOptShouldThrowException() {
        String[] args = new String[]{
                PASSWORD_OPT,
                createcryptopaymentacct.name(),
                "--" + OPT_ACCOUNT_NAME + "=" + "bsq payment account",
                "--" + OPT_CURRENCY_CODE + "=" + "bsq"
        };
        Throwable exception = assertThrows(RuntimeException.class, () ->
                new CreateCryptoCurrencyPaymentAcctOptionParser(args).parse());
        assertEquals("api does not support bsq payment accounts", exception.getMessage());
    }

    @Test
    public void testCreateBchPaymentAcct() {
        var acctName = "bch payment account";
        var currencyCode = "bch";
        var address = "B1nXyZ46XXX"; // address is validated on server
        String[] args = new String[]{
                PASSWORD_OPT,
                createcryptopaymentacct.name(),
                "--" + OPT_ACCOUNT_NAME + "=" + acctName,
                "--" + OPT_CURRENCY_CODE + "=" + currencyCode,
                "--" + OPT_ADDRESS + "=" + address
        };
        var parser = new CreateCryptoCurrencyPaymentAcctOptionParser(args).parse();
        assertEquals(acctName, parser.getAccountName());
        assertEquals(currencyCode, parser.getCurrencyCode());
        assertEquals(address, parser.getAddress());
    }
}
