/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.core.payment;

import haveno.core.account.witness.AccountAgeWitness;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.api.model.PaymentAccountForm;
import haveno.core.api.model.PaymentAccountFormField;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.offer.Offer;
import haveno.core.payment.payload.PaymentAccountPayload;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PaymentAccountsTest {
    @Test
    public void testMobileAccountFormsUseTheDesktopWitnessIdentity() {
        GlobalSettings.setLocale(Locale.US);
        Res.setBaseCurrencyCode("XMR");
        Res.setBaseCurrencyName("Monero");
        List<PaymentAccount> accounts = List.of(new MbWayAccount(), new TwintAccount(), new PagoMovilAccount());
        List<String> inputs = List.of("912 345 678", "+41 79 123 45 67", "0412 123 4567");
        List<String> normalized = List.of("+351912345678", "+41791234567", "+584121234567");
        for (int i = 0; i < accounts.size(); i++) {
            PaymentAccount account = accounts.get(i);
            account.init();
            account.setAccountName("mobile account");
            PaymentAccountForm form = account.toForm();
            PaymentAccountFormField mobileField = form.getFields().stream()
                    .filter(field -> field.getId() == PaymentAccountFormField.FieldId.MOBILE_NR).findFirst().orElseThrow();
            mobileField.setValue(inputs.get(i));
            PaymentAccount fromInput = form.toPaymentAccount();
            assertEquals(normalized.get(i), fromInput.toForm().getValue(PaymentAccountFormField.FieldId.MOBILE_NR));
            mobileField.setValue(normalized.get(i));
            PaymentAccount fromNormalized = form.toPaymentAccount();
            assertArrayEquals(fromNormalized.getPaymentAccountPayload().getAgeWitnessInputData(),
                    fromInput.getPaymentAccountPayload().getAgeWitnessInputData());
            mobileField.setValue("not a phone");
            PaymentAccount invalid = form.toPaymentAccount();
            assertEquals("not a phone", invalid.toForm().getValue(PaymentAccountFormField.FieldId.MOBILE_NR));
            assertThrows(IllegalArgumentException.class,
                    () -> invalid.validateFormField(form, PaymentAccountFormField.FieldId.MOBILE_NR, "not a phone"));
        }
    }

    @Test
    public void testAccountNumberValidationRequiresCountry() {
        NeftAccount account = new NeftAccount();
        account.init();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> account.validateFormField(null, PaymentAccountFormField.FieldId.ACCOUNT_NR, "12345678"));
        assertEquals("Country must be set before validating account number", error.getMessage());
    }

    @Test
    public void testFpsAccountValidatesTheStoredIdentifier() {
        GlobalSettings.setLocale(Locale.US);
        Res.setBaseCurrencyCode("XMR");
        Res.setBaseCurrencyName("Monero");
        FpsAccount account = new FpsAccount();
        account.init();
        account.setAccountNr("alice-test@example.com");
        assertDoesNotThrow(() -> account.validateFormField(null, PaymentAccountFormField.FieldId.ACCOUNT_NR, account.getAccountNr()));
        assertEquals("alice-test@example.com", account.getAccountNr());
        account.setAccountNr("+852 9123-4567");
        assertDoesNotThrow(() -> account.validateFormField(null, PaymentAccountFormField.FieldId.ACCOUNT_NR, account.getAccountNr()));
        assertEquals("91234567", account.getAccountNr());
        for (String value : List.of("ali ce@example.com", "alice@exam ple.com")) {
            account.setAccountNr(value);
            assertThrows(IllegalArgumentException.class, () -> account.validateFormField(null, PaymentAccountFormField.FieldId.ACCOUNT_NR, account.getAccountNr()));
        }
    }

    @Test
    public void testPayPayAccountValidatesTheStoredIdentifier() {
        GlobalSettings.setLocale(Locale.US);
        Res.setBaseCurrencyCode("XMR");
        Res.setBaseCurrencyName("Monero");
        PayPayAccount account = new PayPayAccount();
        account.init();
        account.setAccountNr("paypay_id");
        assertDoesNotThrow(() -> account.validateFormField(null, PaymentAccountFormField.FieldId.ACCOUNT_NR, account.getAccountNr()));
        assertEquals("paypay_id", account.getAccountNr());
        account.setAccountNr("+81 90-1234-5678");
        assertDoesNotThrow(() -> account.validateFormField(null, PaymentAccountFormField.FieldId.ACCOUNT_NR, account.getAccountNr()));
        assertEquals("09012345678", account.getAccountNr());
        for (String value : List.of("paypay-id", "pay pay_id")) {
            account.setAccountNr(value);
            assertThrows(IllegalArgumentException.class, () -> account.validateFormField(null, PaymentAccountFormField.FieldId.ACCOUNT_NR, account.getAccountNr()));
        }
    }

    @Test
    public void testBankAccountNumbersDoNotUseMobilePaymentRules() {
        GlobalSettings.setLocale(Locale.US);
        Res.setBaseCurrencyCode("XMR");
        Res.setBaseCurrencyName("Monero");
        for (String countryCode : List.of("SG", "MY", "TR", "PK")) {
            PaymentAccountForm form = new PaymentAccountForm(PaymentAccountForm.FormId.NATIONAL_BANK);
            PaymentAccountFormField country = new PaymentAccountFormField(PaymentAccountFormField.FieldId.COUNTRY);
            country.setValue(countryCode);
            form.addField(country);
            for (GeneralBankAccount account : List.of(new NationalBankAccount(), new SameBankAccount(), new SpecificBanksAccount())) {
                account.init();
                account.setCountry(CountryUtil.findCountryByCode(countryCode).orElseThrow());
                assertDoesNotThrow(() -> account.validateFormField(form, PaymentAccountFormField.FieldId.ACCOUNT_NR, "12345678901234"));
                assertThrows(IllegalArgumentException.class, () -> account.validateFormField(form, PaymentAccountFormField.FieldId.ACCOUNT_NR, ""));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new PayNowAccount().validateFormField(null, PaymentAccountFormField.FieldId.ACCOUNT_NR, "12345678901234"));
        assertThrows(IllegalArgumentException.class, () -> new DuitNowAccount().validateFormField(null, PaymentAccountFormField.FieldId.ACCOUNT_NR, "12345678901234"));
    }

    @Test
    public void testGetOldestPaymentAccountForOfferWhenNoValidAccounts() {
        PaymentAccounts accounts = new PaymentAccounts(Collections.emptySet(), mock(AccountAgeWitnessService.class));
        PaymentAccount actual = accounts.getOldestPaymentAccountForOffer(mock(Offer.class));

        assertNull(actual);
    }

//    @Test
//    public void testGetOldestPaymentAccountForOffer() {
//        AccountAgeWitnessService service = mock(AccountAgeWitnessService.class);
//
//        PaymentAccount oldest = createAccountWithAge(service, 3);
//        Set<PaymentAccount> accounts = Sets.newHashSet(
//                oldest,
//                createAccountWithAge(service, 2),
//                createAccountWithAge(service, 1));
//
//        BiFunction<Offer, PaymentAccount, Boolean> dummyValidator = (offer, account) -> true;
//        PaymentAccounts testedEntity = new PaymentAccounts(accounts, service, dummyValidator);
//
//        PaymentAccount actual = testedEntity.getOldestPaymentAccountForOffer(mock(Offer.class));
//        assertEquals(oldest, actual);
//    }

    private static PaymentAccount createAccountWithAge(AccountAgeWitnessService service, long age) {
        PaymentAccountPayload payload = mock(PaymentAccountPayload.class);

        PaymentAccount account = mock(PaymentAccount.class);
        when(account.getPaymentAccountPayload()).thenReturn(payload);

        AccountAgeWitness witness = mock(AccountAgeWitness.class);
        when(service.getAccountAge(eq(witness), any())).thenReturn(age);

        when(service.getMyWitness(payload)).thenReturn(witness);

        return account;
    }
}
