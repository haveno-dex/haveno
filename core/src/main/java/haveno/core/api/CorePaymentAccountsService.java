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

package haveno.core.api;

import haveno.asset.Asset;
import haveno.asset.AssetRegistry;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.api.model.PaymentAccountForm;
import haveno.core.api.model.PaymentAccountFormField;
import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.AssetAccount;
import haveno.core.payment.CryptoCurrencyAccount;
import haveno.core.payment.InstantCryptoCurrencyAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PaymentAccountFactory;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.user.User;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static haveno.common.config.Config.baseCurrencyNetwork;
import static haveno.core.locale.CurrencyUtil.findAsset;
import static haveno.core.locale.CurrencyUtil.getCryptoCurrency;
import static java.lang.String.format;

@Singleton
@Slf4j
class CorePaymentAccountsService {

    private final CoreAccountService accountService;
    private final AccountAgeWitnessService accountAgeWitnessService;
    private final User user;

    @Inject
    public CorePaymentAccountsService(CoreAccountService accountService,
                                      AccountAgeWitnessService accountAgeWitnessService,
                                      User user) {
        this.accountService = accountService;
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.user = user;
    }

    PaymentAccount createPaymentAccount(PaymentAccountForm form) {
        PaymentAccount paymentAccount = form.toPaymentAccount();
        setSelectedTradeCurrency(paymentAccount); // TODO: selected trade currency is function of offer, not payment account payload
        verifyPaymentAccountHasRequiredFields(paymentAccount);
        user.addPaymentAccountIfNotExists(paymentAccount);
        accountAgeWitnessService.publishMyAccountAgeWitness(paymentAccount.getPaymentAccountPayload());
        log.info("Saved payment account with id {} and payment method {}.",
                paymentAccount.getId(),
                paymentAccount.getPaymentAccountPayload().getPaymentMethodId());
        return paymentAccount;
    }

    private static void setSelectedTradeCurrency(PaymentAccount paymentAccount) {
        TradeCurrency singleTradeCurrency = paymentAccount.getSingleTradeCurrency();
        List<TradeCurrency> tradeCurrencies = paymentAccount.getTradeCurrencies();
        if (singleTradeCurrency != null) {
            paymentAccount.setSelectedTradeCurrency(singleTradeCurrency);
        } else if (tradeCurrencies != null && !tradeCurrencies.isEmpty()) {
            if (tradeCurrencies.contains(CurrencyUtil.getDefaultTradeCurrency())) {
                paymentAccount.setSelectedTradeCurrency(CurrencyUtil.getDefaultTradeCurrency());
            } else {
                paymentAccount.setSelectedTradeCurrency(tradeCurrencies.get(0));
            }
        }
    }

    PaymentAccount getPaymentAccount(String paymentAccountId) {
        return user.getPaymentAccount(paymentAccountId);
    }

    Set<PaymentAccount> getPaymentAccounts() {
        return user.getPaymentAccounts();
    }

    List<PaymentMethod> getPaymentMethods() {
        return PaymentMethod.getPaymentMethods().stream()
                .sorted(Comparator.comparing(PaymentMethod::getId))
                .collect(Collectors.toList());
    }

    PaymentAccountForm getPaymentAccountForm(String paymentMethodId) {
        return PaymentAccountForm.getForm(paymentMethodId);
    }

    PaymentAccountForm getPaymentAccountForm(PaymentAccount paymentAccount) {
        return paymentAccount.toForm();
    }

    String getPaymentAccountFormAsString(String paymentMethodId) {
        File jsonForm = getPaymentAccountFormFile(paymentMethodId);
        jsonForm.deleteOnExit(); // If just asking for a string, delete the form file.
        return PaymentAccountForm.toJsonString(jsonForm);
    }

    File getPaymentAccountFormFile(String paymentMethodId) {
        return PaymentAccountForm.getPaymentAccountForm(paymentMethodId);
    }

    // Crypto Currency Accounts

    synchronized PaymentAccount createCryptoCurrencyPaymentAccount(String accountName,
                                                      String currencyCode,
                                                      String address,
                                                      boolean tradeInstant) {
        accountService.checkAccountOpen();
        verifyAccountNameUnique(accountName);
        verifyCryptoCurrencyAddress(currencyCode.toUpperCase(), address);
        AssetAccount cryptoCurrencyAccount = tradeInstant
                ? (InstantCryptoCurrencyAccount) PaymentAccountFactory.getPaymentAccount(PaymentMethod.BLOCK_CHAINS_INSTANT)
                : (CryptoCurrencyAccount) PaymentAccountFactory.getPaymentAccount(PaymentMethod.BLOCK_CHAINS);
        cryptoCurrencyAccount.init();
        cryptoCurrencyAccount.setAccountName(accountName);
        cryptoCurrencyAccount.setAddress(address);
        Optional<CryptoCurrency> cryptoCurrency = getCryptoCurrency(currencyCode.toUpperCase());
        cryptoCurrency.ifPresent(cryptoCurrencyAccount::setSingleTradeCurrency);
        user.addPaymentAccount(cryptoCurrencyAccount);
        log.info("Saved crypto payment account with id {} and payment method {}.",
                cryptoCurrencyAccount.getId(),
                cryptoCurrencyAccount.getPaymentAccountPayload().getPaymentMethodId());
        return cryptoCurrencyAccount;
    }

    // TODO Support all alt coin payment methods supported by UI.
    //  The getCryptoCurrencyPaymentMethods method below will be
    //  callable from the CLI when more are supported.

    List<PaymentMethod> getCryptoCurrencyPaymentMethods() {
        return PaymentMethod.getPaymentMethods().stream()
                .filter(PaymentMethod::isCrypto)
                .sorted(Comparator.comparing(PaymentMethod::getId))
                .collect(Collectors.toList());
    }

    void validateFormField(PaymentAccountForm form, PaymentAccountFormField.FieldId fieldId, String value) {

        // get payment method id
        PaymentAccountForm.FormId formId = form.getId();

        // validate field with empty payment account
        PaymentAccount paymentAccount = PaymentAccountFactory.getPaymentAccount(PaymentMethod.getPaymentMethod(formId.toString()));
        paymentAccount.validateFormField(form, fieldId, value);
    }

    private void verifyAccountNameUnique(String accountName) {
        if (getPaymentAccounts().stream().anyMatch(e -> e.getAccountName() != null &&
                e.getAccountName().equals(accountName)))
            throw new IllegalArgumentException(format("Account '%s' is already taken", accountName));
    }

    private void verifyCryptoCurrencyAddress(String cryptoCurrencyCode, String address) {
        Asset asset = getAsset(cryptoCurrencyCode);

        if (!asset.validateAddress(address).isValid())
            throw new IllegalArgumentException(
                    format("%s is not a valid %s address",
                            address,
                            cryptoCurrencyCode.toLowerCase()));
    }

    private Asset getAsset(String cryptoCurrencyCode) {
        return findAsset(new AssetRegistry(),
                cryptoCurrencyCode,
                baseCurrencyNetwork())
                .orElseThrow(() -> new IllegalStateException(
                        format("crypto currency with code '%s' not found",
                                cryptoCurrencyCode.toLowerCase())));
    }

    private void verifyPaymentAccountHasRequiredFields(PaymentAccount paymentAccount) {
        if (!paymentAccount.hasMultipleCurrencies() && paymentAccount.getSingleTradeCurrency() == null)
            throw new IllegalArgumentException(format("no trade currency defined for %s payment account",
                    paymentAccount.getPaymentMethod().getDisplayString().toLowerCase()));
    }
}
