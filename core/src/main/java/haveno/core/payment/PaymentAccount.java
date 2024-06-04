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

package haveno.core.payment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import haveno.common.proto.ProtoUtil;
import haveno.common.proto.persistable.PersistablePayload;
import haveno.common.util.Utilities;
import haveno.core.api.model.PaymentAccountForm;
import haveno.core.api.model.PaymentAccountFormField;
import haveno.core.locale.BankUtil;
import haveno.core.locale.Country;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.validation.AccountNrValidator;
import haveno.core.payment.validation.BICValidator;
import haveno.core.payment.validation.BranchIdValidator;
import haveno.core.payment.validation.EmailOrMobileNrValidator;
import haveno.core.payment.validation.EmailValidator;
import haveno.core.payment.validation.IBANValidator;
import haveno.core.payment.validation.LengthValidator;
import haveno.core.proto.CoreProtoResolver;
import haveno.core.trade.HavenoUtils;
import haveno.core.util.validation.InputValidator;
import haveno.core.util.validation.InputValidator.ValidationResult;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static haveno.core.payment.payload.PaymentMethod.TRANSFERWISE_ID;
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

    private static final GsonBuilder gsonBuilder = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls();


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

    public void init(PaymentAccountPayload payload) {
        id = payload.getId();
        creationDate = new Date().getTime();
        paymentAccountPayload = payload;
    }

    public boolean isFiat() {
        return getSingleTradeCurrency() == null || CurrencyUtil.isFiatCurrency(getSingleTradeCurrency().getCode()); // TODO: check if trade currencies contain fiat
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

    public protobuf.PaymentAccount toProtoMessage(protobuf.PaymentAccountPayload paymentAccountPayload) {
        checkNotNull(accountName, "accountName must not be null");
        protobuf.PaymentAccount.Builder builder = protobuf.PaymentAccount.newBuilder()
                .setPaymentMethod(paymentMethod.toProtoMessage())
                .setId(id)
                .setCreationDate(creationDate)
                .setPaymentAccountPayload(paymentAccountPayload)
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
            PaymentAccount account = PaymentAccountFactory.getPaymentAccount(PaymentMethod.getPaymentMethodOrNA(paymentMethodId));
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

    // ---------------------------- SERIALIZATION -----------------------------

    public String toJson() {
        Map<String, Object> jsonMap = new HashMap<String, Object>();
        if (paymentAccountPayload != null) jsonMap.putAll(gsonBuilder.create().fromJson(paymentAccountPayload.toJson(), (Type) Object.class));
        jsonMap.put("accountName", getAccountName());
        jsonMap.put("accountId", getId());
        if (paymentAccountPayload != null) jsonMap.put("salt", getSaltAsHex());
        return gsonBuilder.create().toJson(jsonMap);
    }

    /**
     * Deserialize a PaymentAccount json string into a new PaymentAccount instance.
     *
     * @param paymentAccountJsonString The json data representing a new payment account form.
     * @return A populated PaymentAccount subclass instance.
     */
    public static synchronized PaymentAccount fromJson(String paymentAccountJsonString) {
        Class<? extends PaymentAccount> clazz = getPaymentAccountClassFromJson(paymentAccountJsonString);
        Gson gson = gsonBuilder.registerTypeAdapter(clazz, new PaymentAccountTypeAdapter(clazz)).create();
        return gson.fromJson(paymentAccountJsonString, clazz);
    }

    private static Class<? extends PaymentAccount> getPaymentAccountClassFromJson(String json) {
        Map<String, Object> jsonMap = gsonBuilder.create().fromJson(json, (Type) Object.class);
        String paymentMethodId = checkNotNull((String) jsonMap.get("paymentMethodId"),
                String.format("cannot not find a paymentMethodId in json string: %s", json));
        return getPaymentAccountClass(paymentMethodId);
    }

    private static Class<? extends PaymentAccount> getPaymentAccountClass(String paymentMethodId) {
        PaymentMethod paymentMethod = PaymentMethod.getPaymentMethodOrNA(paymentMethodId);
        return PaymentAccountFactory.getPaymentAccount(paymentMethod).getClass();
    }

    // ------------------------- PAYMENT ACCOUNT FORM -------------------------

    @NonNull
    public abstract List<PaymentAccountFormField.FieldId> getInputFieldIds();

    public PaymentAccountForm toForm() {

        // convert to json map
        Map<String, Object> jsonMap = gsonBuilder.create().fromJson(toJson(), (Type) Object.class);

        // build form
        PaymentAccountForm form = new PaymentAccountForm(PaymentAccountForm.FormId.valueOf(paymentMethod.getId()));
        for (PaymentAccountFormField.FieldId fieldId : getInputFieldIds()) {
            PaymentAccountFormField field = getEmptyFormField(fieldId);
            field.setValue((String) jsonMap.get(HavenoUtils.toCamelCase(field.getId().toString())));
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
            processValidationResult(new LengthValidator(2, 100).validate(value));
            break;
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
            processValidationResult(new LengthValidator(2, 100).validate(value));
            break;
        case PIX_KEY:
            throw new IllegalArgumentException("Not implemented");
        case POSTAL_ADDRESS:
            processValidationResult(new InputValidator().validate(value));
            break;
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
        case ADDRESS:
            processValidationResult(new LengthValidator(10, 150).validate(value)); // TODO: validate crypto address
            break;
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
            field.setLabel(Res.get("payment.accepted.countries"));
            field.setSupportedCountries(((CountryBasedPaymentAccount) this).getSupportedCountries());
            break;
        case ACCOUNT_ID:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.uphold.accountId"));
            break;
        case ACCOUNT_NAME:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.account.name"));
            field.setMinLength(3);
            field.setMaxLength(100);
            break;
        case ACCOUNT_NR:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("payment.accountNr");
            break;
        case ACCOUNT_OWNER:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.account.owner"));
            break;
        case ACCOUNT_TYPE:
            throw new IllegalArgumentException("Not implemented");
        case ANSWER:
            throw new IllegalArgumentException("Not implemented");
        case BANK_ACCOUNT_NAME:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.account.owner"));
            field.setMinLength(2);
            field.setMaxLength(100);
            break;
        case BANK_ACCOUNT_NUMBER:
            throw new IllegalArgumentException("Not implemented");
        case BANK_ACCOUNT_TYPE:
            throw new IllegalArgumentException("Not implemented");
        case BANK_ADDRESS:
            field.setComponent(PaymentAccountFormField.Component.TEXTAREA);
            field.setLabel(Res.get("payment.swift.address.bank"));
            break;
        case BANK_BRANCH:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.swift.branch.bank"));
            break;
        case BANK_BRANCH_CODE:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.swift.swiftCode.bank"));
            break;
        case BANK_BRANCH_NAME:
            throw new IllegalArgumentException("Not implemented");
        case BANK_CODE:
            throw new IllegalArgumentException("Not implemented");
        case BANK_COUNTRY_CODE:
            field.setComponent(PaymentAccountFormField.Component.SELECT_ONE);
            field.setLabel(Res.get("payment.bank.country"));
            break;
        case BANK_ID:
            throw new IllegalArgumentException("Not implemented");
        case BANK_NAME:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.swift.name.bank"));
            break;
        case BANK_SWIFT_CODE:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.swift.swiftCode.bank"));
            break;
        case BENEFICIARY_ACCOUNT_NR:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.swift.account"));
            break;
        case BENEFICIARY_ADDRESS:
            field.setComponent(PaymentAccountFormField.Component.TEXTAREA);
            field.setLabel(Res.get("payment.swift.address.beneficiary"));
            break;
        case BENEFICIARY_CITY:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.account.city"));
            break;
        case BENEFICIARY_NAME:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.account.owner"));
            break;
        case BENEFICIARY_PHONE:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.swift.phone.beneficiary"));
            break;
        case BIC:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel("BIC");
            break;
        case BRANCH_ID:
            throw new IllegalArgumentException("Not implemented");
        case CITY:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.account.city"));
        case CONTACT:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.payByMail.contact"));
        case COUNTRY:
            field.setComponent(PaymentAccountFormField.Component.SELECT_ONE);
            field.setLabel(Res.get("shared.country"));
            if (this instanceof CountryBasedPaymentAccount) field.setSupportedCountries(((CountryBasedPaymentAccount) this).getSupportedCountries());
            break;
        case EMAIL:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setType("email");
            field.setLabel(Res.get("payment.email"));
            break;
        case EMAIL_OR_MOBILE_NR:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.email.mobile"));
            break;
        case EXTRA_INFO:
            field.setComponent(PaymentAccountFormField.Component.TEXTAREA);
            field.setLabel(Res.get("payment.shared.optionalExtra"));
            break;
        case HOLDER_ADDRESS:
            throw new IllegalArgumentException("Not implemented");
        case HOLDER_EMAIL:
            throw new IllegalArgumentException("Not implemented");
        case HOLDER_NAME:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.account.owner"));
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
            field.setLabel(Res.get("payment.swift.address.intermediary"));
            break;
        case INTERMEDIARY_BRANCH:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.swift.branch.intermediary"));
            break;
        case INTERMEDIARY_COUNTRY_CODE:
            field.setComponent(PaymentAccountFormField.Component.SELECT_ONE);
            field.setLabel(Res.get("payment.swift.country.intermediary"));
            break;
        case INTERMEDIARY_NAME:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.swift.name.intermediary"));
            break;
        case INTERMEDIARY_SWIFT_CODE:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.swift.swiftCode.intermediary"));
            break;
        case MOBILE_NR:
            throw new IllegalArgumentException("Not implemented");
        case NATIONAL_ACCOUNT_ID:
            throw new IllegalArgumentException("Not implemented");
        case PAYID:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.email.mobile"));
            break;
        case PIX_KEY:
            throw new IllegalArgumentException("Not implemented");
        case POSTAL_ADDRESS:
            field.setComponent(PaymentAccountFormField.Component.TEXTAREA);
            field.setLabel(Res.get("payment.postal.address"));
            break;
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
            field.setLabel(Res.get("payment.fasterPayments.ukSortCode"));
            break;
        case SPECIAL_INSTRUCTIONS:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.shared.extraInfo"));
            break;
        case STATE:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.account.state"));
            field.setRequiredForCountries(CountryUtil.getCountryCodes(BankUtil.getAllStateRequiredCountries()));
            break;
        case TRADE_CURRENCIES:
            field.setComponent(PaymentAccountFormField.Component.SELECT_MULTIPLE);
            field.setLabel(Res.get("payment.supportedCurrencies"));
            field.setSupportedCurrencies(getSupportedCurrencies());
            field.setValue(String.join(",", getSupportedCurrencies().stream().map(TradeCurrency::getCode).collect(Collectors.toList())));
            break;
        case USER_NAME:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.account.userName"));
            field.setMinLength(3);
            field.setMaxLength(100);
            break;
        case ADDRESS:
            field.setComponent(PaymentAccountFormField.Component.TEXT);
            field.setLabel(Res.get("payment.account.address"));
            field.setMinLength(10);
            field.setMaxLength(150);
            break;
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
