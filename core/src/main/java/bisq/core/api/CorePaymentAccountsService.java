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

package bisq.core.api;

import bisq.core.account.witness.AccountAgeWitnessService;
import bisq.core.api.model.PaymentAccountForm;
import bisq.core.api.model.PaymentAccountFormField;
import bisq.core.locale.CryptoCurrency;
import bisq.core.locale.CurrencyUtil;
import bisq.core.locale.TradeCurrency;
import bisq.core.payment.AssetAccount;
import bisq.core.payment.CryptoCurrencyAccount;
import bisq.core.payment.InstantCryptoCurrencyAccount;
import bisq.core.payment.PaymentAccount;
import bisq.core.payment.PaymentAccountFactory;
import bisq.core.payment.payload.PaymentMethod;
import bisq.core.user.User;

import bisq.asset.Asset;
import bisq.asset.AssetRegistry;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.io.File;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import static bisq.common.config.Config.baseCurrencyNetwork;
import static bisq.core.locale.CurrencyUtil.findAsset;
import static bisq.core.locale.CurrencyUtil.getCryptoCurrency;
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
            return;
        } else if (tradeCurrencies != null && !tradeCurrencies.isEmpty()) {
            if (tradeCurrencies.contains(CurrencyUtil.getDefaultTradeCurrency())) {
                paymentAccount.setSelectedTradeCurrency(CurrencyUtil.getDefaultTradeCurrency());
            } else {
                paymentAccount.setSelectedTradeCurrency(tradeCurrencies.get(0));
            }
        }
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
        if (!(cryptoCurrencyAccount instanceof AssetAccount)) accountAgeWitnessService.publishMyAccountAgeWitness(cryptoCurrencyAccount.getPaymentAccountPayload()); // TODO (woodser): applies to Haveno?
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
                .filter(PaymentMethod::isAltcoin)
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
