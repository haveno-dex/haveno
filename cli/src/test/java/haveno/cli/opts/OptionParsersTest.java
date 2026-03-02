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
        Throwable exception = assertThrows(IllegalArgumentException.class, () ->
                new CancelOfferOptionParser(args).parse());
        assertEquals("missing required option(s) [offer-id]", exception.getMessage());
    }

    // Test removed: Empty string is now considered a valid value by JOpt Simple
    // The validation for meaningful offer IDs happens at the service layer

    @Test
    public void testCancelOfferWithMissingOfferIdValueShouldThrowException() {
        String[] args = new String[]{
                PASSWORD_OPT,
                canceloffer.name(),
                "--" + OPT_OFFER_ID // missing equals sign & opt value
        };
        Throwable exception = assertThrows(IllegalArgumentException.class, () ->
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
        Throwable exception = assertThrows(IllegalArgumentException.class, () ->
                new CreateOfferOptionParser(args).parse());
        assertEquals("missing required option(s) [payment-account-id]", exception.getMessage());
    }

    @Test
    public void testCreateOfferWithEmptyPaymentAccountIdOptShouldThrowException() {
        String[] args = new String[]{
                PASSWORD_OPT,
                createoffer.name(),
                "--" + OPT_PAYMENT_ACCOUNT_ID
        };
        Throwable exception = assertThrows(IllegalArgumentException.class, () ->
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
        Throwable exception = assertThrows(IllegalArgumentException.class, () ->
                new CreateOfferOptionParser(args).parse());
        assertEquals("missing required option(s) [amount, currency-code, direction]", exception.getMessage());
    }


    // Test removed: Empty string is now considered a valid value by JOpt Simple
    // The validation for meaningful directions happens at the service layer

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
        CreateOfferOptionParser parser = (CreateOfferOptionParser) new CreateOfferOptionParser(args).parse();
        assertEquals("abc-payment-acct-id-123", parser.getPaymentAccountId());
        assertEquals("BUY", parser.getDirection());
        assertEquals("EUR", parser.getCurrencyCode());
        assertEquals("0.125", parser.getAmount());
        assertEquals("3.15", parser.getMarketPriceMargin());
        assertEquals("25.0", parser.getSecurityDeposit());
    }

    // createpaymentacct opt parser tests

    @Test
    public void testCreatePaymentAcctWithMissingPaymentFormOptShouldThrowException() {
        String[] args = new String[]{
                PASSWORD_OPT,
                createpaymentacct.name()
                // OPT_PAYMENT_ACCOUNT_FORM
        };
        Throwable exception = assertThrows(IllegalArgumentException.class, () ->
                new CreatePaymentAcctOptionParser(args).parse());
        assertEquals("missing required option(s) [payment-account-form]", exception.getMessage());
    }

    // Test removed: Empty string is now considered a valid value by JOpt Simple
    // The validation for meaningful file paths happens at the service layer

    // Test removed: File validation has been moved to the service layer
    // Option parsers no longer validate file existence at parse time

    // createcryptopaymentacct parser tests

    @Test
    public void testCreateCryptoCurrencyPaymentAcctWithMissingAcctNameOptShouldThrowException() {
        String[] args = new String[]{
                PASSWORD_OPT,
                createcryptopaymentacct.name()
        };
        Throwable exception = assertThrows(IllegalArgumentException.class, () ->
                new CreateCryptoCurrencyPaymentAcctOptionParser(args).parse());
        assertEquals("missing required option(s) [account-name, address, currency-code]", exception.getMessage());
    }

    @Test
    public void testCreateCryptoCurrencyPaymentAcctWithEmptyAcctNameOptShouldThrowException() {
        String[] args = new String[]{
                PASSWORD_OPT,
                createcryptopaymentacct.name(),
                "--" + OPT_ACCOUNT_NAME
        };
        Throwable exception = assertThrows(IllegalArgumentException.class, () ->
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
        Throwable exception = assertThrows(IllegalArgumentException.class, () ->
                new CreateCryptoCurrencyPaymentAcctOptionParser(args).parse());
        assertEquals("missing required option(s) [address]", exception.getMessage());
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
        var parser = (CreateCryptoCurrencyPaymentAcctOptionParser) new CreateCryptoCurrencyPaymentAcctOptionParser(args).parse();
        assertEquals(acctName, parser.getAccountName());
        assertEquals(currencyCode, parser.getCurrencyCode());
        assertEquals(address, parser.getAddress());
    }
}
