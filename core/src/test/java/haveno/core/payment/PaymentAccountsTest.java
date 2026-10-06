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
import haveno.core.api.CorePaymentAccountsService;
import haveno.core.api.model.PaymentAccountForm;
import haveno.core.api.model.PaymentAccountFormField;
import haveno.core.api.model.PaymentAccountFormField.FieldId;
import haveno.core.locale.BankUtil;
import haveno.core.locale.Country;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.offer.Offer;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.payload.PopmoneyAccountPayload;
import haveno.core.payment.payload.SwishAccountPayload;
import haveno.core.payment.payload.TwintAccountPayload;
import haveno.core.payment.validation.EmailValidator;
import haveno.core.payment.validation.InteracETransferAnswerValidator;
import haveno.core.payment.validation.InteracETransferQuestionValidator;
import haveno.core.payment.validation.InteracETransferValidator;
import haveno.core.payment.validation.LengthValidator;
import haveno.core.proto.CoreProtoResolver;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.Preferences;
import haveno.core.user.UserPayload;
import haveno.core.util.validation.RegexValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PaymentAccountsTest {
    @Test
    public void testFormFieldRequiredPresenceRoundTrip() {
        protobuf.PaymentAccountFormField legacy = protobuf.PaymentAccountFormField.newBuilder()
                .setId(protobuf.PaymentAccountFormField.FieldId.EXTRA_INFO)
                .setComponent(protobuf.PaymentAccountFormField.Component.TEXTAREA)
                .build();
        assertNull(PaymentAccountFormField.fromProto(legacy).getRequired());
        assertEquals(legacy, PaymentAccountFormField.fromProto(legacy).toProtoMessage());
        for (boolean required : List.of(false, true)) {
            protobuf.PaymentAccountFormField proto = legacy.toBuilder().setRequired(required).build();
            PaymentAccountFormField restored = PaymentAccountFormField.fromProto(proto);
            assertEquals(required, restored.getRequired());
            assertTrue(restored.toProtoMessage().hasRequired());
            assertEquals(proto, restored.toProtoMessage());
        }
        // preserve acceptance of client forms with incomplete currency metadata
        protobuf.PaymentAccountFormField withCurrencyMetadata = legacy.toBuilder()
                .setRequired(false)
                .addSupportedCurrencies(protobuf.TradeCurrency.newBuilder().setCode("USD"))
                .build();
        assertFalse(PaymentAccountFormField.fromProto(withCurrencyMetadata).getRequired());
        protobuf.PaymentAccountFormField conditional = legacy.toBuilder().setRequired(false)
                .addRequiredIfAnyFieldHasValue(protobuf.PaymentAccountFormField.FieldId.INTERMEDIARY_SWIFT_CODE)
                .addRequiredIfAnyFieldHasValue(protobuf.PaymentAccountFormField.FieldId.INTERMEDIARY_NAME).build();
        assertEquals(conditional, PaymentAccountFormField.fromProto(conditional).toProtoMessage());
    }

    @Test
    public void testAllPaymentFormRequirementsMatchValidation() {
        Res.setup();
        CorePaymentAccountsService previousService = HavenoUtils.corePaymentAccountService;
        Preferences previousPreferences = HavenoUtils.preferences;
        HavenoUtils.preferences = mock(Preferences.class);
        when(HavenoUtils.preferences.getUserLanguage()).thenReturn("en");
        new CorePaymentAccountsService(null, null, null, new InteracETransferValidator(new EmailValidator(),
                new InteracETransferQuestionValidator(new LengthValidator(), new RegexValidator()),
                new InteracETransferAnswerValidator(new LengthValidator(), new RegexValidator())));
        try {
            for (PaymentMethod method : PaymentMethod.getPaymentMethods()) {
                PaymentAccountForm blank = PaymentAccountForm.getForm(method.getId());
                protobuf.PaymentAccountForm proto = blank.toProtoMessage();
                protobuf.PaymentAccountForm restored = PaymentAccountForm.fromProto(proto).toProtoMessage();
                for (int i = 0; i < proto.getFieldsCount(); i++) {
                    protobuf.PaymentAccountFormField field = proto.getFields(i);
                    protobuf.PaymentAccountFormField restoredField = restored.getFields(i);
                    assertTrue(field.hasRequired(), method.getId() + ": " + field.getId());
                    assertTrue(restoredField.hasRequired(), method.getId() + ": " + field.getId());
                    assertEquals(field.getRequired(), restoredField.getRequired());
                    assertEquals(field.getRequiredForCountriesList(), restoredField.getRequiredForCountriesList());
                    assertEquals(field.getRequiredIfAnyFieldHasValueList(), restoredField.getRequiredIfAnyFieldHasValueList());
                }

                PaymentAccount account = PaymentAccountFactory.getPaymentAccount(method);
                account.init();
                List<Country> countries = account.getSupportedCountries();
                Country country = countries == null || countries.isEmpty()
                        ? CountryUtil.findCountryByCode("US").orElseThrow() : countries.getFirst();
                if (account instanceof CountryBasedPaymentAccount) ((CountryBasedPaymentAccount) account).setCountry(country);
                PaymentAccountForm form = account.toForm();
                for (int i = 0; i < form.getFields().size(); i++) {
                    PaymentAccountFormField field = form.getFields().get(i);
                    PaymentAccountFormField blankField = blank.getFields().get(i);
                    assertEquals(blankField.getRequired(), field.getRequired());
                    assertEquals(blankField.getRequiredForCountries(), field.getRequiredForCountries());
                    assertEquals(blankField.getRequiredIfAnyFieldHasValue(), field.getRequiredIfAnyFieldHasValue());
                    if (field.getId() == PaymentAccountFormField.FieldId.COUNTRY) field.setValue(country.code);
                }
                for (PaymentAccountFormField field : form.getFields()) {
                    assertFormFieldRequiredMatchesValidation(account, form, field, country.code);
                }

                // requirement metadata must not affect account JSON
                String json = form.toPaymentAccountJsonString();
                for (PaymentAccountFormField field : form.getFields()) {
                    boolean required = field.getRequired();
                    List<String> requiredForCountries = field.getRequiredForCountries();
                    field.setRequired(!required);
                    field.setRequiredForCountries(List.of());
                    List<FieldId> triggers = field.getRequiredIfAnyFieldHasValue();
                    field.setRequiredIfAnyFieldHasValue(List.of(FieldId.ACCOUNT_NAME));
                    assertEquals(json, form.toPaymentAccountJsonString());
                    field.setRequired(required);
                    field.setRequiredForCountries(requiredForCountries);
                    field.setRequiredIfAnyFieldHasValue(triggers);
                }
            }
        } finally {
            HavenoUtils.corePaymentAccountService = previousService;
            HavenoUtils.preferences = previousPreferences;
        }
    }

    @Test
    public void testPaymentFormSupportedCountries() {
        Res.setup();
        List<Country> allCountries = CountryUtil.getAllCountries();
        Map<PaymentMethod, List<Country>> expectedCountries = Map.of(
                PaymentMethod.SWIFT, allCountries,
                PaymentMethod.NATIONAL_BANK, allCountries,
                PaymentMethod.MONEY_GRAM, allCountries,
                PaymentMethod.SEPA, CountryUtil.getAllSepaCountries(),
                PaymentMethod.SEPA_INSTANT, CountryUtil.getAllSepaCountries(),
                PaymentMethod.ACH_TRANSFER, List.of(CountryUtil.findCountryByCode("US").orElseThrow()),
                PaymentMethod.AMAZON_GIFT_CARD, CountryUtil.getAllAmazonGiftCardCountries());
        for (Map.Entry<PaymentMethod, List<Country>> entry : expectedCountries.entrySet()) {
            PaymentAccount account = PaymentAccountFactory.getPaymentAccount(entry.getKey());
            PaymentAccountForm form = PaymentAccountForm.getForm(entry.getKey().getId());
            PaymentAccountForm restored = PaymentAccountForm.fromProto(form.toProtoMessage());
            List<FieldId> fieldIds = entry.getKey().equals(PaymentMethod.SWIFT)
                    ? List.of(FieldId.BANK_COUNTRY_CODE, FieldId.INTERMEDIARY_COUNTRY_CODE) : List.of(FieldId.COUNTRY);
            for (FieldId fieldId : fieldIds) {
                String context = entry.getKey().getId() + ": " + fieldId;
                for (PaymentAccountForm candidate : List.of(form, restored)) {
                    PaymentAccountFormField field = candidate.getFields().stream()
                            .filter(f -> f.getId() == fieldId).findFirst().orElseThrow();
                    assertEquals(PaymentAccountFormField.Component.SELECT_ONE, field.getComponent(), context);
                    assertEquals(entry.getValue(), field.getSupportedCountries(), context);
                }
                for (Country country : entry.getValue()) {
                    assertDoesNotThrow(() -> account.validateFormField(form, fieldId, country.code), context + ": " + country.code);
                }
                assertThrows(IllegalArgumentException.class, () -> account.validateFormField(form, fieldId, "ZZ"), context);
                allCountries.stream().filter(country -> !entry.getValue().contains(country)).findFirst().ifPresent(country ->
                        assertThrows(IllegalArgumentException.class, () -> account.validateFormField(form, fieldId, country.code), context));
            }
        }
    }

    @Test
    public void testCountryDependentFormRequirements() {
        Res.setup();
        for (PaymentAccount account : List.of(new NationalBankAccount(), new SameBankAccount(),
                new SpecificBanksAccount(), new CashDepositAccount(), new MoneyGramAccount(), new WesternUnionAccount())) {
            account.init();
            PaymentAccountForm form = account.toForm();
            PaymentAccountFormField countryField = form.getFields().stream()
                    .filter(field -> field.getId() == PaymentAccountFormField.FieldId.COUNTRY).findFirst().orElseThrow();
            for (Country country : CountryUtil.getAllCountries()) {
                countryField.setValue(country.code);
                if (account instanceof CountryBasedPaymentAccount) ((CountryBasedPaymentAccount) account).setCountry(country);
                for (PaymentAccountFormField field : form.getFields()) {
                    if (!field.getRequired()) assertFormFieldRequiredMatchesValidation(account, form, field, country.code);
                }
            }
            for (PaymentAccountFormField field : form.getFields()) {
                if (field.getId() == PaymentAccountFormField.FieldId.STATE) {
                    assertTrue(field.getRequiredForCountries().isEmpty());
                } else if (field.getId() == PaymentAccountFormField.FieldId.BANK_NAME) {
                    assertEquals(Set.of("GB", "US", "BR", "AU", "CA", "NZ", "MX", "HK", "SE", "NO", "AR"),
                            Set.copyOf(field.getRequiredForCountries()));
                }
            }
        }
    }

    @Test
    public void testMoneyTransferStatesAreOptional() {
        Res.setup();
        for (PaymentMethod method : List.of(PaymentMethod.MONEY_GRAM, PaymentMethod.WESTERN_UNION)) {
            PaymentAccountForm form = PaymentAccountForm.getForm(method.getId());
            PaymentAccountFormField stateField = form.getFields().stream()
                    .filter(field -> field.getId() == FieldId.STATE).findFirst().orElseThrow();
            assertFalse(stateField.getRequired());
            assertTrue(stateField.getRequiredForCountries().isEmpty());
            setFormValue(form, FieldId.ACCOUNT_NAME, "money transfer account");
            setFormValue(form, FieldId.HOLDER_NAME, "Test Holder");
            setFormValue(form, FieldId.EMAIL, "holder@example.com");
            setFormValue(form, FieldId.TRADE_CURRENCIES, "EUR");
            setFormValue(form, FieldId.SALT, "");
            if (method.equals(PaymentMethod.WESTERN_UNION)) setFormValue(form, FieldId.CITY, "Test City");
            setFormValue(form, FieldId.STATE, "Test State");
            for (String country : List.of("US", "CA", "AU", "MY", "MX", "CN", "FR", "GB")) {
                setFormValue(form, FieldId.COUNTRY, country);
                PaymentAccount account = form.toPaymentAccount();
                assertDoesNotThrow(() -> account.validateForm(form));
                PaymentAccount restored = PaymentAccount.fromProto(account.toProtoMessage(), new CoreProtoResolver());
                assertEquals("Test State", restored.toForm().getValue(FieldId.STATE));
                assertTrue(restored.getPaymentAccountPayload().getPaymentDetailsForTradePopup().contains("Test State"));
                assertTrue(restored.getPaymentAccountPayload().getPaymentDetails().contains("Test State"));

                PaymentAccountForm missing = PaymentAccountForm.fromProto(form.toProtoMessage());
                missing.getFields().removeIf(field -> field.getId() == FieldId.STATE);
                PaymentAccount withoutState = missing.toPaymentAccount();
                assertDoesNotThrow(() -> withoutState.validateForm(missing));
                assertEquals("", withoutState.toForm().getValue(FieldId.STATE));
                assertEquals(BankUtil.isStateRequired(country), withoutState.getPaymentAccountPayload().getPaymentDetailsForTradePopup().contains(Res.get("payment.account.state")));
                assertDoesNotThrow(() -> account.validateFormField(form, FieldId.STATE, null));
                for (String blank : List.of("", "   ")) {
                    assertDoesNotThrow(() -> account.validateFormField(form, FieldId.STATE, blank));
                    PaymentAccountForm blankForm = PaymentAccountForm.fromProto(form.toProtoMessage());
                    setFormValue(blankForm, FieldId.STATE, blank);
                    assertDoesNotThrow(() -> blankForm.toPaymentAccount().validateForm(blankForm));
                }
            }
        }
    }

    private static void assertFormFieldRequiredMatchesValidation(PaymentAccount account, PaymentAccountForm form,
                                                               PaymentAccountFormField field, String countryCode) {
        boolean required = field.getRequired() || field.getRequiredForCountries() != null
                && field.getRequiredForCountries().contains(countryCode)
                || field.getRequiredIfAnyFieldHasValue() != null && form.getFields().stream().anyMatch(other ->
                        other.getId() != field.getId() && field.getRequiredIfAnyFieldHasValue().contains(other.getId())
                                && other.getValue() != null && !other.getValue().isEmpty());
        String context = form.getId() + ": " + field.getId() + " in " + countryCode;
        boolean unconditional = field.getRequired();
        List<String> requiredForCountries = field.getRequiredForCountries();
        List<FieldId> triggers = field.getRequiredIfAnyFieldHasValue();
        try {
            // metadata from the client must not control server validation
            field.setRequired(!required);
            field.setRequiredForCountries(List.of());
            field.setRequiredIfAnyFieldHasValue(List.of(FieldId.ACCOUNT_NAME));
            if (required) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> account.validateFormField(form, field.getId(), ""), context);
                assertNotEquals("Not implemented", error.getMessage(), context);
            } else assertDoesNotThrow(() -> account.validateFormField(form, field.getId(), ""), context);
        } finally {
            field.setRequired(unconditional);
            field.setRequiredForCountries(requiredForCountries);
            field.setRequiredIfAnyFieldHasValue(triggers);
        }
    }

    @Test
    public void testDesktopRequiredFieldRules() {
        Res.setup();
        // expectations follow the desktop forms' save validation, including the optional Wise USD address
        Map<PaymentMethod, FieldId> requiredFields = Map.of(
                PaymentMethod.ACH_TRANSFER, FieldId.HOLDER_ADDRESS,
                PaymentMethod.DOMESTIC_WIRE_TRANSFER, FieldId.HOLDER_ADDRESS,
                PaymentMethod.SEPA, FieldId.ACCEPTED_COUNTRY_CODES,
                PaymentMethod.SEPA_INSTANT, FieldId.ACCEPTED_COUNTRY_CODES);
        Map<PaymentMethod, FieldId> optionalFields = Map.of(
                PaymentMethod.TRANSFERWISE_USD, FieldId.HOLDER_ADDRESS,
                PaymentMethod.UPHOLD, FieldId.ACCOUNT_OWNER);
        for (Map<PaymentMethod, FieldId> expectations : List.of(requiredFields, optionalFields)) {
            for (Map.Entry<PaymentMethod, FieldId> entry : expectations.entrySet()) {
                PaymentAccount account = PaymentAccountFactory.getPaymentAccount(entry.getKey());
                account.init();
                PaymentAccountForm form = account.toForm();
                form.getFields().stream().filter(field -> field.getId() == FieldId.COUNTRY).forEach(field -> field.setValue("US"));
                PaymentAccountFormField field = form.getFields().stream().filter(f -> f.getId() == entry.getValue()).findFirst().orElseThrow();
                assertEquals(expectations == requiredFields, field.getRequired(), entry.getKey().getId());
                assertFormFieldRequiredMatchesValidation(account, form, field, "US");
            }
        }
        for (PaymentAccount account : List.of(new AchTransferAccount(), new DomesticWireTransferAccount())) {
            assertThrows(IllegalArgumentException.class, () -> account.validateFormField(null, FieldId.HOLDER_ADDRESS, "   "));
        }
    }

    @Test
    public void testSwiftIntermediaryRequirements() {
        Res.setup();
        SwiftAccount account = new SwiftAccount();
        account.init();
        PaymentAccountForm form = account.toForm();
        for (PaymentAccountFormField field : form.getFields()) field.setValue("valid value");
        setFormValue(form, FieldId.SALT, "");
        setFormValue(form, FieldId.BANK_SWIFT_CODE, "ABCDEFGH123");
        setFormValue(form, FieldId.BANK_COUNTRY_CODE, "US");
        List<FieldId> details = List.of(FieldId.INTERMEDIARY_SWIFT_CODE, FieldId.INTERMEDIARY_NAME,
                FieldId.INTERMEDIARY_BRANCH, FieldId.INTERMEDIARY_ADDRESS);
        List<PaymentAccountFormField> intermediary = form.getFields().stream()
                .filter(field -> details.contains(field.getId()) || field.getId() == FieldId.INTERMEDIARY_COUNTRY_CODE).toList();
        for (PaymentAccountFormField field : intermediary) {
            field.setValue("");
            assertFalse(field.getRequired());
            assertEquals(details.stream().filter(id -> id != field.getId()).toList(), field.getRequiredIfAnyFieldHasValue());
        }
        assertDoesNotThrow(() -> form.toPaymentAccount().validateForm(form));

        // disabling the intermediary bank can leave its country selected
        setFormValue(form, FieldId.INTERMEDIARY_COUNTRY_CODE, "US");
        assertDoesNotThrow(() -> form.toPaymentAccount().validateForm(form));
        for (FieldId trigger : details) {
            setFormValue(form, trigger, "ABCDEFGH123");
            assertThrows(IllegalArgumentException.class, () -> form.toPaymentAccount().validateForm(form));
            for (PaymentAccountFormField field : intermediary) assertFormFieldRequiredMatchesValidation(account, form, field, "");
            setFormValue(form, trigger, "");
        }

        for (FieldId fieldId : details) setFormValue(form, fieldId, "ABCDEFGH123");
        assertDoesNotThrow(() -> form.toPaymentAccount().validateForm(form));
        for (PaymentAccountFormField field : intermediary) {
            PaymentAccountForm missing = PaymentAccountForm.fromProto(form.toProtoMessage());
            missing.getFields().removeIf(f -> f.getId() == field.getId());
            assertThrows(IllegalArgumentException.class, () -> missing.toPaymentAccount().validateForm(missing));
        }
        form.getFields().removeAll(intermediary);
        assertDoesNotThrow(() -> form.toPaymentAccount().validateForm(form));
    }

    @Test
    public void testRequiredFieldsCannotBeOmitted() {
        Res.setup();
        PaymentAccountForm form = PaymentAccountForm.getForm(PaymentMethod.REVOLUT_ID);
        setFormValue(form, FieldId.ACCOUNT_NAME, "revolut account");
        setFormValue(form, FieldId.USERNAME, "username");
        setFormValue(form, FieldId.TRADE_CURRENCIES, "USD");
        form.getFields().removeIf(field -> field.getId() == FieldId.SALT);
        assertDoesNotThrow(() -> form.toPaymentAccount().validateForm(form));
        for (PaymentAccountFormField field : form.getFields()) {
            field.setRequired(false);
            PaymentAccountForm missing = PaymentAccountForm.fromProto(form.toProtoMessage());
            missing.getFields().removeIf(f -> f.getId() == field.getId());
            assertThrows(IllegalArgumentException.class, () -> missing.toPaymentAccount().validateForm(missing));
        }
        PaymentAccountForm empty = PaymentAccountForm.fromProto(protobuf.PaymentAccountForm.newBuilder()
                .setId(protobuf.PaymentAccountForm.FormId.REVOLUT).build());
        assertTrue(empty.getFields().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> empty.toPaymentAccount().validateForm(empty));
        PaymentAccountForm duplicate = PaymentAccountForm.fromProto(form.toProtoMessage());
        duplicate.addField(duplicate.getFields().getFirst());
        assertThrows(IllegalArgumentException.class, () -> duplicate.toPaymentAccount().validateForm(duplicate));
        PaymentAccountFormField unexpected = new PaymentAccountFormField(FieldId.BANK_NAME);
        form.addField(unexpected);
        assertThrows(IllegalArgumentException.class, () -> new RevolutAccount().validateForm(form));
    }

    private static void setFormValue(PaymentAccountForm form, FieldId fieldId, String value) {
        form.getFields().stream().filter(field -> field.getId() == fieldId).findFirst().orElseThrow().setValue(value);
    }

    @Test
    public void testBlankJapanBankForms() throws IOException {
        Res.setup();
        PaymentAccountForm form = PaymentAccountForm.getForm(PaymentMethod.JAPAN_BANK_ID);
        assertEquals(PaymentAccountForm.FormId.JAPAN_BANK, form.getId());
        assertNull(form.getValue(PaymentAccountFormField.FieldId.BANK_NAME));
        PaymentAccountFormField bankField = form.getFields().stream()
                .filter(field -> field.getId() == PaymentAccountFormField.FieldId.BANK_NAME).findFirst().orElseThrow();
        assertEquals(JapanBankData.prettyPrintBankList(), bankField.getSupportedValues());

        Path jsonForm = PaymentAccountForm.getPaymentAccountForm(PaymentMethod.JAPAN_BANK_ID).toPath();
        try {
            assertEquals(form.toPaymentAccountJsonString(), Files.readString(jsonForm));
        } finally {
            Files.deleteIfExists(jsonForm);
        }
    }

    @Test
    public void testJapanBankFormRoundTripPreservesBankAndWitnessIdentity() {
        Res.setup();
        List<String> banks = JapanBankData.prettyPrintBankList();
        for (String bank : List.of(banks.getFirst(), banks.getLast())) {
            JapanBankAccount account = new JapanBankAccount();
            account.init();
            account.setAccountName("japan bank account");
            account.setBankName(bank);
            account.setBankBranchCode("123");
            account.setBankBranchName("東京");
            account.setBankAccountNumber("1234567");
            account.setBankAccountName("タナカ");
            account.setBankAccountType(JapanBankData.accountTypes().getFirst());
            PaymentAccountForm form = account.toForm();
            assertEquals(bank, form.getValue(PaymentAccountFormField.FieldId.BANK_NAME));
            assertDoesNotThrow(() -> account.validateFormField(form, PaymentAccountFormField.FieldId.BANK_NAME, bank));
            JapanBankAccount restored = (JapanBankAccount) form.toPaymentAccount();
            assertEquals(account.getBankCode(), restored.getBankCode());
            assertEquals(account.getBankName(), restored.getBankName());
            assertArrayEquals(account.getPaymentAccountPayload().getAgeWitnessInputData(),
                    restored.getPaymentAccountPayload().getAgeWitnessInputData());
        }
    }

    @Test
    public void testFormExportPreservesPaymentIdentifiersInsteadOfLocalAccountIds() {
        Res.setup();
        for (PaymentAccount account : List.of(new MoneyBeamAccount(), new UpholdAccount())) {
            account.init();
            account.setAccountName("payment account");
            account.setSingleTradeCurrency(account.getSupportedCurrencies().get(0));
            PaymentAccountForm form = account.toForm();
            form.getFields().stream().filter(field -> field.getId() == PaymentAccountFormField.FieldId.ACCOUNT_ID)
                    .forEach(field -> field.setValue("payment-identifier@example.com"));
            PaymentAccount restored = form.toPaymentAccount();
            byte[] witnessInput = restored.getPaymentAccountPayload().getAgeWitnessInputData();
            assertEquals("payment-identifier@example.com", restored.toForm().getValue(PaymentAccountFormField.FieldId.ACCOUNT_ID));
            assertArrayEquals(witnessInput, restored.toForm().toPaymentAccount().getPaymentAccountPayload().getAgeWitnessInputData());
        }
    }

    @Test
    public void testRetiredPopmoneyIsUnavailableAndSkippedOnLoad() {
        Res.setup();
        assertFalse(PaymentMethod.getPaymentMethods().stream().anyMatch(method -> method.getId().equals(PaymentMethod.POPMONEY_ID)));
        assertThrows(IllegalArgumentException.class, () -> PaymentAccountForm.getForm(PaymentMethod.POPMONEY_ID));
        protobuf.PaymentAccount proto = protobuf.PaymentAccount.newBuilder()
                .setId("popmoney")
                .setPaymentMethod(protobuf.PaymentMethod.newBuilder().setId(PaymentMethod.POPMONEY_ID))
                .setAccountName("historical account")
                .addTradeCurrencies((protobuf.TradeCurrency) new TraditionalCurrency("USD").toProtoMessage())
                .setPaymentAccountPayload((protobuf.PaymentAccountPayload) new PopmoneyAccountPayload(PaymentMethod.POPMONEY_ID, "popmoney").toProtoMessage())
                .build();
        assertNull(PaymentAccount.fromProto(proto, new CoreProtoResolver()));
        protobuf.UserPayload userProto = protobuf.UserPayload.newBuilder()
                .addPaymentAccounts(proto)
                .addMarketAlertFilters(protobuf.MarketAlertFilter.newBuilder().setPaymentAccount(proto).setTriggerValue(100))
                .build();
        UserPayload user = UserPayload.fromProto(userProto, new CoreProtoResolver());
        assertTrue(user.getPaymentAccounts().isEmpty());
        assertTrue(user.getMarketAlertFilters().isEmpty());
        assertDoesNotThrow(user::toProtoMessage);
    }

    @Test
    public void testDuitNowKeepsNricDistinctFromInternationalMobileNumbers() {
        Res.setup();
        DuitNowAccount account = new DuitNowAccount();
        account.init();
        account.setAccountName("duitnow account");
        account.setAccountNr("601112345678");
        assertEquals("601112345678", account.getAccountNr());
        assertDoesNotThrow(() -> account.validateFormField(null, PaymentAccountFormField.FieldId.ACCOUNT_NR, account.getAccountNr()));
        account.setAccountNr("+60 11-1234-5678");
        assertEquals("01112345678", account.getAccountNr());
        account.setAccountNr(account.getAccountNr());
        assertEquals("01112345678", account.getAccountNr());
        assertDoesNotThrow(() -> account.validateFormField(null, PaymentAccountFormField.FieldId.ACCOUNT_NR, account.getAccountNr()));
    }

    @Test
    public void testMobileAccountFormsUseTheDesktopWitnessIdentity() {
        GlobalSettings.setLocale(Locale.US);
        Res.setBaseCurrencyCode("XMR");
        Res.setBaseCurrencyName("Monero");
        List<PaymentAccount> accounts = List.of(new SwishAccount(), new MbWayAccount(), new TwintAccount(), new PagoMovilAccount());
        List<String> inputs = List.of("070 123 45 67", "912 345 678", "+41 79 123 45 67", "0412 123 4567");
        List<String> normalized = List.of("+46701234567", "+351912345678", "+41791234567", "+584121234567");
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
    public void testSwishAccountPreservesStoredMobileNumbers() {
        for (String input : List.of("070 123 45 67", "+460701234567", "+46701234567")) {
            SwishAccount account = new SwishAccount();
            account.init();
            account.setAccountName("swish account");
            account.setHolderName("Alice");
            ((SwishAccountPayload) account.getPaymentAccountPayload()).setMobileNr(input);
            protobuf.PaymentAccount proto = account.toProtoMessage();
            SwishAccount restored = (SwishAccount) PaymentAccount.fromProto(proto, new CoreProtoResolver());
            assertEquals(input, restored.getMobileNr());
            assertEquals(proto, restored.toProtoMessage());
            assertArrayEquals(account.getPaymentAccountPayload().getAgeWitnessInputData(),
                    restored.getPaymentAccountPayload().getAgeWitnessInputData());
        }
    }

    @Test
    public void testTwintAccountFormsNormalizeNationalNumbers() {
        GlobalSettings.setLocale(Locale.US);
        Res.setBaseCurrencyCode("XMR");
        Res.setBaseCurrencyName("Monero");
        TwintAccount account = new TwintAccount();
        account.init();
        account.setAccountName("twint account");
        account.setHolderName("Alice");
        account.setMobileNr("+41791234567");
        PaymentAccountForm form = account.toForm();
        PaymentAccountFormField mobileField = form.getFields().stream()
                .filter(field -> field.getId() == PaymentAccountFormField.FieldId.MOBILE_NR).findFirst().orElseThrow();
        for (String input : List.of("079 123 45 67", "079-123-45-67", "+41 (0)79 123 45 67", "+41 79 123 45 67", "+41791234567")) {
            mobileField.setValue(input);
            TwintAccount fromInput = (TwintAccount) form.toPaymentAccount();
            assertEquals("+41791234567", fromInput.getMobileNr());
            assertArrayEquals(account.getPaymentAccountPayload().getAgeWitnessInputData(),
                    fromInput.getPaymentAccountPayload().getAgeWitnessInputData());
            fromInput.setMobileNr(fromInput.getMobileNr());
            assertEquals("+41791234567", fromInput.getMobileNr());
        }
    }

    @Test
    public void testTwintAccountPreservesStoredMobileNumbers() {
        for (String input : List.of("079 123 45 67", "+410791234567", "+41791234567")) {
            TwintAccount account = new TwintAccount();
            account.init();
            account.setAccountName("twint account");
            account.setHolderName("Alice");
            ((TwintAccountPayload) account.getPaymentAccountPayload()).setMobileNr(input);
            protobuf.PaymentAccount proto = account.toProtoMessage();
            TwintAccount restored = (TwintAccount) PaymentAccount.fromProto(proto, new CoreProtoResolver());
            assertEquals(input, restored.getMobileNr());
            assertEquals(proto, restored.toProtoMessage());
            assertArrayEquals(account.getPaymentAccountPayload().getAgeWitnessInputData(),
                    restored.getPaymentAccountPayload().getAgeWitnessInputData());
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
