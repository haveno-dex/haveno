/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.payment;

import bisq.core.api.model.PaymentAccountForm;
import bisq.core.api.model.PaymentAccountFormField;
import bisq.core.locale.BankUtil;
import bisq.core.locale.Country;
import bisq.core.locale.CountryUtil;
import bisq.core.locale.CurrencyUtil;
import bisq.core.locale.Res;
import bisq.core.locale.TradeCurrency;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.payment.payload.PaymentMethod;
import bisq.core.payment.validation.AccountNrValidator;
import bisq.core.payment.validation.BICValidator;
import bisq.core.payment.validation.BranchIdValidator;
import bisq.core.payment.validation.EmailOrMobileNrValidator;
import bisq.core.payment.validation.EmailValidator;
import bisq.core.payment.validation.IBANValidator;
import bisq.core.payment.validation.LengthValidator;
import bisq.core.proto.CoreProtoResolver;
import bisq.core.util.validation.InputValidator;
import bisq.core.util.validation.InputValidator.ValidationResult;
import bisq.common.proto.ProtoUtil;
import bisq.common.proto.persistable.PersistablePayload;
import bisq.common.util.Utilities;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

import static bisq.core.payment.payload.PaymentMethod.TRANSFERWISE_ID;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Arrays.stream;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

@EqualsAndHashCode
@ToString
@Getter
@Slf4j
public abstract class PaymentAccount implements PersistablePayload {

    protected final PaymentMethod paymentMethod;
    @Setter
    protected String id;
    @Setter
    protected long creationDate;
    @Setter
    public PaymentAccountPayload paymentAccountPayload;
    @Setter
    protected String accountName;
    @Setter
    @EqualsAndHashCode.Exclude
    protected String persistedAccountName;

    protected final List<TradeCurrency> tradeCurrencies = new ArrayList<>();
    @Setter
    @Nullable
    protected TradeCurrency selectedTradeCurrency;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected PaymentAccount(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public void init() {
        id = UUID.randomUUID().toString();
        creationDate = new Date().getTime();
        paymentAccountPayload = createPayload();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.PaymentAccount toProtoMessage() {
        checkNotNull(accountName, "accountName must not be null");
        protobuf.PaymentAccount.Builder builder = protobuf.PaymentAccount.newBuilder()
                .setPaymentMethod(paymentMethod.toProtoMessage())
                .setId(id)
                .setCreationDate(creationDate)
                .setPaymentAccountPayload((protobuf.PaymentAccountPayload) paymentAccountPayload.toProtoMessage())
                .setAccountName(accountName)
                .addAllTradeCurrencies(ProtoUtil.collectionToProto(tradeCurrencies, protobuf.TradeCurrency.class));
        Optional.ofNullable(selectedTradeCurrency).ifPresent(selectedTradeCurrency -> builder.setSelectedTradeCurrency((protobuf.TradeCurrency) selectedTradeCurrency.toProtoMessage()));
        return builder.build();
    }

    public static PaymentAccount fromProto(protobuf.PaymentAccount proto, CoreProtoResolver coreProtoResolver) {
        String paymentMethodId = proto.getPaymentMethod().getId();
        List<TradeCurrency> tradeCurrencies = proto.getTradeCurrenciesList().stream()
                .map(TradeCurrency::fromProto)
                .collect(Collectors.toList());

        // We need to remove NGN for Transferwise
        Optional<TradeCurrency> ngnTwOptional = tradeCurrencies.stream()
                .filter(e -> paymentMethodId.equals(TRANSFERWISE_ID))
                .filter(e -> e.getCode().equals("NGN"))
                .findAny();
        // We cannot remove it in the stream as it would cause a concurrentModificationException
        ngnTwOptional.ifPresent(tradeCurrencies::remove);

        try {
            PaymentAccount account = PaymentAccountFactory.getPaymentAccount(PaymentMethod.getPaymentMethod(paymentMethodId));
            account.getTradeCurrencies().clear();
            account.setId(proto.getId());
            account.setCreationDate(proto.getCreationDate());
            account.setAccountName(proto.getAccountName());
            account.setPersistedAccountName(proto.getAccountName());
            account.getTradeCurrencies().addAll(tradeCurrencies);
            account.setPaymentAccountPayload(coreProtoResolver.fromProto(proto.getPaymentAccountPayload()));

            if (proto.hasSelectedTradeCurrency())
                account.setSelectedTradeCurrency(TradeCurrency.fromProto(proto.getSelectedTradeCurrency()));

            return account;
        } catch (RuntimeException e) {
            log.warn("Could not load account: {}, exception: {}", paymentMethodId, e.toString());
            return null;
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Date getCreationDate() {
        return new Date(creationDate);
    }

    public void addCurrency(TradeCurrency tradeCurrency) {
        if (!tradeCurrencies.contains(tradeCurrency))
            tradeCurrencies.add(tradeCurrency);
    }

    public void removeCurrency(TradeCurrency tradeCurrency) {
        tradeCurrencies.remove(tradeCurrency);
    }

    public boolean hasMultipleCurrencies() {
        return tradeCurrencies.size() > 1;
    }

    public void setSingleTradeCurrency(TradeCurrency tradeCurrency) {
        tradeCurrencies.clear();
        tradeCurrencies.add(tradeCurrency);
        setSelectedTradeCurrency(tradeCurrency);
    }

    @Nullable
    public TradeCurrency getSingleTradeCurrency() {
        if (tradeCurrencies.size() == 1)
            return tradeCurrencies.get(0);
        else
            return null;
    }

    public long getMaxTradePeriod() {
        return paymentMethod.getMaxTradePeriod();
    }

    protected abstract PaymentAccountPayload createPayload();

    public void setSalt(byte[] salt) {
        paymentAccountPayload.setSalt(salt);
    }

    public byte[] getSalt() {
        return paymentAccountPayload.getSalt();
    }

    public void setSaltAsHex(String saltAsHex) {
        setSalt(Utilities.decodeFromHex(saltAsHex));
    }

    public String getSaltAsHex() {
        return Utilities.bytesAsHexString(getSalt());
    }

    public String getOwnerId() {
        return paymentAccountPayload.getOwnerId();
    }

    public boolean isCountryBasedPaymentAccount() {
        return this instanceof CountryBasedPaymentAccount;
    }

    public boolean hasPaymentMethodWithId(String paymentMethodId) {
        return this.getPaymentMethod().getId().equals(paymentMethodId);
    }

    /**
     * Return an Optional of the trade currency for this payment account, or
     * Optional.empty() if none is found.  If this payment account has a selected
     * trade currency, that is returned, else its single trade currency is returned,
     * else the first trade currency in this payment account's tradeCurrencies
     * list is returned.
     *
     * @return Optional of the trade currency for the given payment account
     */
    public Optional<TradeCurrency> getTradeCurrency() {
        if (this.getSelectedTradeCurrency() != null)
            return Optional.of(this.getSelectedTradeCurrency());
        else if (this.getSingleTradeCurrency() != null)
            return Optional.of(this.getSingleTradeCurrency());
        else if (!this.getTradeCurrencies().isEmpty())
            return Optional.of(this.getTradeCurrencies().get(0));
        else
            return Optional.empty();
    }

    public void onAddToUser() {
        // We are in the process to get added to the user. This is called just before saving the account and the
        // last moment we could apply some special handling if needed (e.g. as it happens for Revolut)
    }

    public String getPreTradeMessage(boolean isBuyer) {
        if (isBuyer) {
            return getMessageForBuyer();
        } else {
            return getMessageForSeller();
        }
    }

    // will be overridden by specific account when necessary
    public String getMessageForBuyer() {
        return null;
    }

    // will be overridden by specific account when necessary
    public String getMessageForSeller() {
        return null;
    }

    // will be overridden by specific account when necessary
    public String getMessageForAccountCreation() {
        return null;
    }

    public void onPersistChanges() {
        setPersistedAccountName(getAccountName());
    }

    public void revertChanges() {
        setAccountName(getPersistedAccountName());
    }

    @NonNull
    public abstract List<TradeCurrency> getSupportedCurrencies();

    // ------------------------- PAYMENT ACCOUNT FORM -------------------------

    @NonNull
    public abstract List<PaymentAccountFormField.FieldId> getInputFieldIds();

    public PaymentAccountForm toForm() {
        PaymentAccountForm form = new PaymentAccountForm(PaymentAccountForm.FormId.valueOf(paymentMethod.getId()));
        for (PaymentAccountFormField.FieldId fieldId : getInputFieldIds()) {
            PaymentAccountFormField field = getEmptyFormField(fieldId);
            form.getFields().add(field);
        }
        return form;
    }

    public void validateFormField(PaymentAccountForm form, PaymentAccountFormField.FieldId fieldId, String value) {
        switch (fieldId) {
        case ACCEPTED_COUNTRY_CODES: {
            List<String> countryCodes = PaymentAccount.commaDelimitedCodesToList.apply(value);
            List<String> supportedCountryCodes = CountryUtil.getCountryCodes(((CountryBasedPaymentAccount) this).getSupportedCountries());
            for (String countryCode : countryCodes) {
                if (!supportedCountryCodes.contains(countryCode)) throw new IllegalArgumentException("Country is not supported by " + getPaymentMethod().getId() + ": " + value);
            }
            break;
        }
        case ACCOUNT_ID:
            processValidationResult(new InputValidator().validate(value));
            break;
        case ACCOUNT_NAME:
            processValidationResult(new LengthValidator(2, 100).validate(value));
            break;
        case ACCOUNT_NR:
            processValidationResult(new AccountNrValidator("GB").validate(value));
            break;
        case ACCOUNT_OWNER:
            processValidationResult(new LengthValidator(2, 100).validate(value));
            break;
        case ACCOUNT_TYPE:
            throw new IllegalArgumentException("Not implemented");
        case ANSWER:
            throw new IllegalArgumentException("Not implemented");
        case BANK_ACCOUNT_NAME:
            throw new IllegalArgumentException("Not implemented");
        case BANK_ACCOUNT_NUMBER:
            throw new IllegalArgumentException("Not implemented");
        case BANK_ACCOUNT_TYPE:
            throw new IllegalArgumentException("Not implemented");
        case BANK_ADDRESS:
        case INTERMEDIARY_ADDRESS:
            processValidationResult(new LengthValidator(1, 100).validate(value));
            break;
        case BANK_BRANCH:
        case INTERMEDIARY_BRANCH:
            processValidationResult(new LengthValidator(2, 34).validate(value));
            break;
        case BANK_BRANCH_CODE:
            throw new IllegalArgumentException("Not implemented");
        case BANK_BRANCH_NAME:
            throw new IllegalArgumentException("Not implemented");
        case BANK_CODE:
            throw new IllegalArgumentException("Not implemented");
        case BANK_COUNTRY_CODE:
            if (!CountryUtil.findCountryByCode(value).isPresent()) throw new IllegalArgumentException("Invalid country code: " + value);
            break;
        case BANK_ID:
            throw new IllegalArgumentException("Not implemented");
        case BANK_NAME:
        case INTERMEDIARY_NAME:
            processValidationResult(new LengthValidator(2, 34).validate(value));
            break;
        case BANK_SWIFT_CODE:
        case INTERMEDIARY_SWIFT_CODE:
            processValidationResult(new LengthValidator(11, 11).validate(value));
            break;
        case BENEFICIARY_ACCOUNT_NR:
            processValidationResult(new LengthValidator(2, 40).validate(value));
            break;
        case BENEFICIARY_ADDRESS:
            processValidationResult(new LengthValidator(1, 100).validate(value));
            break;
        case BENEFICIARY_CITY:
            processValidationResult(new LengthValidator(2, 34).validate(value));
            break;
        case BENEFICIARY_NAME:
            processValidationResult(new LengthValidator(2, 34).validate(value));
            break;
        case BENEFICIARY_PHONE:
            processValidationResult(new LengthValidator(2, 34).validate(value));
            break;
        case BIC:
            processValidationResult(new BICValidator().validate(value));
            break;
        case BRANCH_ID:
            throw new IllegalArgumentException("Not implemented");
        case CITY:
            processValidationResult(new LengthValidator(2, 34).validate(value));
            break;
        case CONTACT:
            processValidationResult(new InputValidator().validate(value));
            break;
        case COUNTRY:
            if (this instanceof CountryBasedPaymentAccount) {
                List<Country> supportedCountries = ((CountryBasedPaymentAccount) this).getSupportedCountries();
                if (supportedCountries != null && !supportedCountries.isEmpty()) {
                    List<String> supportedCountryCodes = CountryUtil.getCountryCodes(supportedCountries);
                    if (!supportedCountryCodes.contains(value)) throw new IllegalArgumentException("Country is not supported by " + getPaymentMethod().getId() + ": " + value);
                    return;
                }
            }
            if (!CountryUtil.findCountryByCode(value).isPresent()) throw new IllegalArgumentException("Invalid country code: " + value);
            break;
        case EMAIL:
            processValidationResult(new EmailValidator().validate(value));
            break;
        case EMAIL_OR_MOBILE_NR:
            processValidationResult(new EmailOrMobileNrValidator().validate(value));
            break;
        case EXTRA_INFO:
            break;
        case HOLDER_ADDRESS:
            throw new IllegalArgumentException("Not implemented");
        case HOLDER_EMAIL:
            throw new IllegalArgumentException("Not implemented");
        case HOLDER_NAME:
            processValidationResult(new LengthValidator(2, 100).validate(value));
            break;
        case HOLDER_TAX_ID:
            throw new IllegalArgumentException("Not implemented");
        case IBAN:
            processValidationResult(new IBANValidator().validate(value));
            break;
        case IFSC:
            throw new IllegalArgumentException("Not implemented");
        case INTERMEDIARY_COUNTRY_CODE:
            if (!CountryUtil.findCountryByCode(value).isPresent()) throw new IllegalArgumentException("Invalid country code: " + value);
            break;
        case MOBILE_NR:
            throw new IllegalArgumentException("Not implemented");
        case NATIONAL_ACCOUNT_ID:
            throw new IllegalArgumentException("Not implemented");
        case PAYID:
            throw new IllegalArgumentException("Not implemented");
        case PIX_KEY:
            throw new IllegalArgumentException("Not implemented");
        case POSTAL_ADDRESS:
            throw new IllegalArgumentException("Not implemented");
        case PROMPT_PAY_ID:
            throw new IllegalArgumentException("Not implemented");
        case QUESTION:
            throw new IllegalArgumentException("Not implemented");
        case REQUIREMENTS:
            throw new IllegalArgumentException("Not implemented");
        case SALT:
            if (!value.equals("")) throw new IllegalArgumentException("Salt must be empty");
            break;
        case SORT_CODE:
            processValidationResult(new BranchIdValidator("GB").validate(value));
            break;
        case SPECIAL_INSTRUCTIONS:
            break;
        case STATE:
            String countryCode = form.getValue(PaymentAccountFormField.FieldId.COUNTRY);
            boolean isStateRequired = BankUtil.isStateRequired(countryCode);
            if (value == null || value.isEmpty()) {
                if (isStateRequired) throw new IllegalArgumentException("Must provide state for country " + countryCode);
            } else {
                if (!isStateRequired) throw new IllegalArgumentException("Must not provide state for country " + countryCode);
            }
            break;
        case TRADE_CURRENCIES:
            processValidationResult(new InputValidator().validate(value));
            List<String> currencyCodes = commaDelimitedCodesToList.apply(value);
            Optional<List<TradeCurrency>> tradeCurrencies =  CurrencyUtil.getTradeCurrenciesInList(currencyCodes, getSupportedCurrencies());
            if (!tradeCurrencies.isPresent()) throw new IllegalArgumentException("No trade currencies were found in the " + getPaymentMethod().getDisplayString() + " account form");
            break;
        case USER_NAME:
            processValidationResult(new LengthValidator(3, 100).validate(value));
            break;
        case VIRTUAL_PAYMENT_ADDRESS:
            throw new IllegalArgumentException("Not implemented");
        default:
            throw new RuntimeException("Unhandled form field: " + fieldId);
        }
    }

    protected void processValidationResult(ValidationResult result) {
        if (!result.isValid) throw new IllegalArgumentException(result.errorMessage);
    }

    protected PaymentAccountFormField getEmptyFormField(PaymentAccountFormField.FieldId fieldId) {
        PaymentAccountFormField field = new PaymentAccountFormField(fieldId);
        switch (fieldId) {
        case ACCEPTED_COUNTRY_CODES:
            field.setComponent(PaymentAccountFormField.Component.SELECT_MULTIPLE);
            field.setLabel("Accepted country codes");
            field.setSupportedCountries(((CountryBasedPaymentAccount) this).getSupportedCountries());
            break;
        case ACCOUNT_ID:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("Username or email or phone no.");
            break;
        case ACCOUNT_NAME:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("Account name"); // TODO: pull all labels from language file
            field.setMinLength(3);
            field.setMaxLength(100);
            break;
        case ACCOUNT_NR:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("Account number");
            break;
        case ACCOUNT_OWNER:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("Account owner full name");
            break;
        case ACCOUNT_TYPE:
            throw new IllegalArgumentException("Not implemented");
        case ANSWER:
            throw new IllegalArgumentException("Not implemented");
        case BANK_ACCOUNT_NAME:
            throw new IllegalArgumentException("Not implemented");
        case BANK_ACCOUNT_NUMBER:
            throw new IllegalArgumentException("Not implemented");
        case BANK_ACCOUNT_TYPE:
            throw new IllegalArgumentException("Not implemented");
        case BANK_ADDRESS:
            field.setComponent(PaymentAccountFormField.Component.TEXTAREA);
            field.setLabel("Receiving Bank address");
            break;
        case BANK_BRANCH:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("Receiving Bank branch");
            break;
        case BANK_BRANCH_CODE:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("Receiving Bank SWIFT code"); // TODO: only used for swift?
            break;
        case BANK_BRANCH_NAME:
            throw new IllegalArgumentException("Not implemented");
        case BANK_CODE:
            throw new IllegalArgumentException("Not implemented");
        case BANK_COUNTRY_CODE:
            field.setComponent(PaymentAccountFormField.Component.SELECT_ONE);
            field.setLabel("Country of bank");
            break;
        case BANK_ID:
            throw new IllegalArgumentException("Not implemented");
        case BANK_NAME:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("Receiving Bank name");
            break;
        case BANK_SWIFT_CODE:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("Receiving Bank SWIFT Code");
            break;
        case BENEFICIARY_ACCOUNT_NR:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("Account No. (or IBAN)");
            break;
        case BENEFICIARY_ADDRESS:
            field.setComponent(PaymentAccountFormField.Component.TEXTAREA);
            field.setLabel("Beneficiary address");
            break;
        case BENEFICIARY_CITY:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("Beneficiary city");
            break;
        case BENEFICIARY_NAME:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("Account owner full name");
            break;
        case BENEFICIARY_PHONE:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("Beneficiary phone number");
            break;
        case BIC:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("BIC");
            break;
        case BRANCH_ID:
            throw new IllegalArgumentException("Not implemented");
        case CITY:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("Contact"));
        case CONTACT:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("City");
        case COUNTRY:
            field.setComponent(PaymentAccountFormField.Component.SELECT_ONE);
            field.setLabel("Country");
            if (this instanceof CountryBasedPaymentAccount) field.setSupportedCountries(((CountryBasedPaymentAccount) this).getSupportedCountries());
            break;
        case EMAIL:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setType("email");
            field.setLabel("Email");
            break;
        case EMAIL_OR_MOBILE_NR:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("Email or mobile number");
            break;
        case EXTRA_INFO:
            field.setComponent(PaymentAccountFormField.Component.TEXTAREA);
            field.setLabel("Optional additional information");
            break;
        case HOLDER_ADDRESS:
            throw new IllegalArgumentException("Not implemented");
        case HOLDER_EMAIL:
            throw new IllegalArgumentException("Not implemented");
        case HOLDER_NAME:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("Account owner full name");
            field.setMinLength(2);
            field.setMaxLength(100);
            break;
        case HOLDER_TAX_ID:
            throw new IllegalArgumentException("Not implemented");
        case IBAN:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("IBAN");
            break;
        case IFSC:
            throw new IllegalArgumentException("Not implemented");
        case INTERMEDIARY_ADDRESS:
            field.setComponent(PaymentAccountFormField.Component.TEXTAREA);
            field.setLabel("Intermediary Bank address");
            break;
        case INTERMEDIARY_BRANCH:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("Intermediary Bank branch");
            break;
        case INTERMEDIARY_COUNTRY_CODE:
            field.setComponent(PaymentAccountFormField.Component.SELECT_ONE);
            field.setLabel("Intermediary Bank country");
            break;
        case INTERMEDIARY_NAME:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("Intermediary Bank name");
            break;
        case INTERMEDIARY_SWIFT_CODE:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("Intermediary Bank SWIFT Code"); // TODO: swift only?
            break;
        case MOBILE_NR:
            throw new IllegalArgumentException("Not implemented");
        case NATIONAL_ACCOUNT_ID:
            throw new IllegalArgumentException("Not implemented");
        case PAYID:
            throw new IllegalArgumentException("Not implemented");
        case PIX_KEY:
            throw new IllegalArgumentException("Not implemented");
        case POSTAL_ADDRESS:
            throw new IllegalArgumentException("Not implemented");
        case PROMPT_PAY_ID:
            throw new IllegalArgumentException("Not implemented");
        case QUESTION:
            throw new IllegalArgumentException("Not implemented");
        case REQUIREMENTS:
            throw new IllegalArgumentException("Not implemented");
        case SALT:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("Salt");
            break;
        case SORT_CODE:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("UK sort code");
            break;
        case SPECIAL_INSTRUCTIONS:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("Special instructions");
            break;
        case STATE:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("State/Province/Region");
            field.setRequiredForCountries(CountryUtil.getCountryCodes(BankUtil.getAllStateRequiredCountries()));
            break;
        case TRADE_CURRENCIES:
            field.setComponent(PaymentAccountFormField.Component.SELECT_MULTIPLE);
            field.setLabel("Supported currencies");
            field.setSupportedCurrencies(getSupportedCurrencies());
            break;
        case USER_NAME:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("User name");
            field.setMinLength(3);
            field.setMaxLength(100);
            break;
        case VIRTUAL_PAYMENT_ADDRESS:
            throw new IllegalArgumentException("Not implemented");
        default:
            throw new RuntimeException("Unhandled form field: " + field);
        }
        if ("".equals(field.getValue())) field.setValue("");
        return field;
    }

    private static final Predicate<String> isCommaDelimitedCurrencyList = (s) -> s != null && s.contains(",");
    public static final Function<String, List<String>> commaDelimitedCodesToList = (s) -> {
        if (isCommaDelimitedCurrencyList.test(s))
            return stream(s.split(",")).map(a -> a.trim().toUpperCase()).collect(toList());
        else if (s != null && !s.isEmpty())
            return singletonList(s.trim().toUpperCase());
        else
            return new ArrayList<>();
    };
}
