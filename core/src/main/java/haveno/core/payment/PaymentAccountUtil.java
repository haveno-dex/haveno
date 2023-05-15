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

import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.Country;
import haveno.core.locale.TradeCurrency;
import haveno.core.offer.Offer;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.user.User;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static haveno.core.payment.payload.PaymentMethod.ACH_TRANSFER_ID;
import static haveno.core.payment.payload.PaymentMethod.ADVANCED_CASH_ID;
import static haveno.core.payment.payload.PaymentMethod.ALI_PAY_ID;
import static haveno.core.payment.payload.PaymentMethod.AMAZON_GIFT_CARD_ID;
import static haveno.core.payment.payload.PaymentMethod.AUSTRALIA_PAYID_ID;
import static haveno.core.payment.payload.PaymentMethod.BIZUM_ID;
import static haveno.core.payment.payload.PaymentMethod.BLOCK_CHAINS;
import static haveno.core.payment.payload.PaymentMethod.BLOCK_CHAINS_INSTANT;
import static haveno.core.payment.payload.PaymentMethod.CAPITUAL_ID;
import static haveno.core.payment.payload.PaymentMethod.CASH_APP_ID;
import static haveno.core.payment.payload.PaymentMethod.CASH_BY_MAIL_ID;
import static haveno.core.payment.payload.PaymentMethod.CASH_DEPOSIT_ID;
import static haveno.core.payment.payload.PaymentMethod.CELPAY_ID;
import static haveno.core.payment.payload.PaymentMethod.CHASE_QUICK_PAY_ID;
import static haveno.core.payment.payload.PaymentMethod.ZELLE_ID;
import static haveno.core.payment.payload.PaymentMethod.DOMESTIC_WIRE_TRANSFER_ID;
import static haveno.core.payment.payload.PaymentMethod.F2F_ID;
import static haveno.core.payment.payload.PaymentMethod.FASTER_PAYMENTS_ID;
import static haveno.core.payment.payload.PaymentMethod.HAL_CASH_ID;
import static haveno.core.payment.payload.PaymentMethod.IMPS_ID;
import static haveno.core.payment.payload.PaymentMethod.INTERAC_E_TRANSFER_ID;
import static haveno.core.payment.payload.PaymentMethod.JAPAN_BANK_ID;
import static haveno.core.payment.payload.PaymentMethod.MONESE_ID;
import static haveno.core.payment.payload.PaymentMethod.MONEY_BEAM_ID;
import static haveno.core.payment.payload.PaymentMethod.MONEY_GRAM_ID;
import static haveno.core.payment.payload.PaymentMethod.NATIONAL_BANK_ID;
import static haveno.core.payment.payload.PaymentMethod.NEFT_ID;
import static haveno.core.payment.payload.PaymentMethod.NEQUI_ID;
import static haveno.core.payment.payload.PaymentMethod.PAXUM_ID;
import static haveno.core.payment.payload.PaymentMethod.PAYSERA_ID;
import static haveno.core.payment.payload.PaymentMethod.PAYTM_ID;
import static haveno.core.payment.payload.PaymentMethod.PERFECT_MONEY_ID;
import static haveno.core.payment.payload.PaymentMethod.PIX_ID;
import static haveno.core.payment.payload.PaymentMethod.POPMONEY_ID;
import static haveno.core.payment.payload.PaymentMethod.PROMPT_PAY_ID;
import static haveno.core.payment.payload.PaymentMethod.REVOLUT_ID;
import static haveno.core.payment.payload.PaymentMethod.RTGS_ID;
import static haveno.core.payment.payload.PaymentMethod.SAME_BANK_ID;
import static haveno.core.payment.payload.PaymentMethod.SATISPAY_ID;
import static haveno.core.payment.payload.PaymentMethod.SEPA_ID;
import static haveno.core.payment.payload.PaymentMethod.SEPA_INSTANT_ID;
import static haveno.core.payment.payload.PaymentMethod.SPECIFIC_BANKS_ID;
import static haveno.core.payment.payload.PaymentMethod.STRIKE_ID;
import static haveno.core.payment.payload.PaymentMethod.SWIFT_ID;
import static haveno.core.payment.payload.PaymentMethod.SWISH_ID;
import static haveno.core.payment.payload.PaymentMethod.TIKKIE_ID;
import static haveno.core.payment.payload.PaymentMethod.TRANSFERWISE_ID;
import static haveno.core.payment.payload.PaymentMethod.TRANSFERWISE_USD_ID;
import static haveno.core.payment.payload.PaymentMethod.UPHOLD_ID;
import static haveno.core.payment.payload.PaymentMethod.UPI_ID;
import static haveno.core.payment.payload.PaymentMethod.US_POSTAL_MONEY_ORDER_ID;
import static haveno.core.payment.payload.PaymentMethod.VENMO_ID;
import static haveno.core.payment.payload.PaymentMethod.VERSE_ID;
import static haveno.core.payment.payload.PaymentMethod.WECHAT_PAY_ID;
import static haveno.core.payment.payload.PaymentMethod.WESTERN_UNION_ID;
import static haveno.core.payment.payload.PaymentMethod.hasChargebackRisk;

@Slf4j
public class PaymentAccountUtil {

    public static boolean isAnyPaymentAccountValidForOffer(Offer offer,
                                                           Collection<PaymentAccount> paymentAccounts) {
        for (PaymentAccount paymentAccount : new ArrayList<PaymentAccount>(paymentAccounts)) {
            if (isPaymentAccountValidForOffer(offer, paymentAccount))
                return true;
        }
        return false;
    }

    public static ObservableList<PaymentAccount> getPossiblePaymentAccounts(Offer offer,
                                                                            Set<PaymentAccount> paymentAccounts,
                                                                            AccountAgeWitnessService accountAgeWitnessService) {
        ObservableList<PaymentAccount> result = FXCollections.observableArrayList();
        result.addAll(paymentAccounts.stream()
                .filter(paymentAccount -> isPaymentAccountValidForOffer(offer, paymentAccount))
                .filter(paymentAccount -> isAmountValidForOffer(offer, paymentAccount, accountAgeWitnessService))
                .collect(Collectors.toList()));
        return result;
    }

    // Return true if paymentAccount can take this offer
    public static boolean isAmountValidForOffer(Offer offer,
                                                PaymentAccount paymentAccount,
                                                AccountAgeWitnessService accountAgeWitnessService) {
        boolean hasChargebackRisk = hasChargebackRisk(offer.getPaymentMethod(), offer.getCurrencyCode());
        boolean hasValidAccountAgeWitness = accountAgeWitnessService.getMyTradeLimit(paymentAccount,
                offer.getCurrencyCode(), offer.getMirroredDirection()) >= offer.getMinAmount().longValueExact();
        return !hasChargebackRisk || hasValidAccountAgeWitness;
    }

    // TODO might be used to show more details if we get payment methods updates with diff. limits
    public static String getInfoForMismatchingPaymentMethodLimits(Offer offer, PaymentAccount paymentAccount) {
        // don't translate atm as it is not used so far in the UI just for logs
        return "Payment methods have different trade limits or trade periods.\n" +
                "Our local Payment method: " + paymentAccount.getPaymentMethod().toString() + "\n" +
                "Payment method from offer: " + offer.getPaymentMethod().toString();
    }

    public static boolean isPaymentAccountValidForOffer(Offer offer, PaymentAccount paymentAccount) {
        return new ReceiptValidator(offer, paymentAccount).isValid();
    }

    public static Optional<PaymentAccount> getMostMaturePaymentAccountForOffer(Offer offer,
                                                                               Set<PaymentAccount> paymentAccounts,
                                                                               AccountAgeWitnessService service) {
        PaymentAccounts accounts = new PaymentAccounts(paymentAccounts, service);
        return Optional.ofNullable(accounts.getOldestPaymentAccountForOffer(offer));
    }

    @Nullable
    public static ArrayList<String> getAcceptedCountryCodes(PaymentAccount paymentAccount) {
        ArrayList<String> acceptedCountryCodes = null;
        if (paymentAccount instanceof SepaAccount) {
            acceptedCountryCodes = new ArrayList<>(((SepaAccount) paymentAccount).getAcceptedCountryCodes());
        } else if (paymentAccount instanceof SepaInstantAccount) {
            acceptedCountryCodes = new ArrayList<>(((SepaInstantAccount) paymentAccount).getAcceptedCountryCodes());
        } else if (paymentAccount instanceof CountryBasedPaymentAccount) {
            acceptedCountryCodes = new ArrayList<>();
            Country country = ((CountryBasedPaymentAccount) paymentAccount).getCountry();
            if (country != null)
                acceptedCountryCodes.add(country.code);
        }
        return acceptedCountryCodes;
    }

    public static List<TradeCurrency> getTradeCurrencies(PaymentMethod paymentMethod) {
        switch (paymentMethod.getId()) {
            case ADVANCED_CASH_ID:
                return AdvancedCashAccount.SUPPORTED_CURRENCIES;
            case AMAZON_GIFT_CARD_ID:
                return AmazonGiftCardAccount.SUPPORTED_CURRENCIES;
            case CAPITUAL_ID:
                return CapitualAccount.SUPPORTED_CURRENCIES;
            case MONEY_GRAM_ID:
                return MoneyGramAccount.SUPPORTED_CURRENCIES;
            case PAXUM_ID:
                return PaxumAccount.SUPPORTED_CURRENCIES;
            case PAYSERA_ID:
                return PayseraAccount.SUPPORTED_CURRENCIES;
            case REVOLUT_ID:
                return RevolutAccount.SUPPORTED_CURRENCIES;
            case SWIFT_ID:
                return SwiftAccount.SUPPORTED_CURRENCIES;
            case TRANSFERWISE_ID:
                return TransferwiseAccount.SUPPORTED_CURRENCIES;
            case UPHOLD_ID:
                return UpholdAccount.SUPPORTED_CURRENCIES;
            case INTERAC_E_TRANSFER_ID:
                return InteracETransferAccount.SUPPORTED_CURRENCIES;
            case STRIKE_ID:
                return StrikeAccount.SUPPORTED_CURRENCIES;
            case TIKKIE_ID:
                return TikkieAccount.SUPPORTED_CURRENCIES;
            case ALI_PAY_ID:
                return AliPayAccount.SUPPORTED_CURRENCIES;
            case NEQUI_ID:
                return NequiAccount.SUPPORTED_CURRENCIES;
            case IMPS_ID:
            case NEFT_ID:
            case PAYTM_ID:
            case RTGS_ID:
            case UPI_ID:
                return IfscBasedAccount.SUPPORTED_CURRENCIES;
            case BIZUM_ID:
                return BizumAccount.SUPPORTED_CURRENCIES;
            case MONEY_BEAM_ID:
                return MoneyBeamAccount.SUPPORTED_CURRENCIES;
            case PIX_ID:
                return PixAccount.SUPPORTED_CURRENCIES;
            case SATISPAY_ID:
                return SatispayAccount.SUPPORTED_CURRENCIES;
            case CHASE_QUICK_PAY_ID:
                return ChaseQuickPayAccount.SUPPORTED_CURRENCIES;
            case US_POSTAL_MONEY_ORDER_ID:
                return USPostalMoneyOrderAccount.SUPPORTED_CURRENCIES;
            case VENMO_ID:
                return VenmoAccount.SUPPORTED_CURRENCIES;
            case JAPAN_BANK_ID:
                return JapanBankAccount.SUPPORTED_CURRENCIES;
            case WECHAT_PAY_ID:
                return WeChatPayAccount.SUPPORTED_CURRENCIES;
            case ZELLE_ID:
                return ZelleAccount.SUPPORTED_CURRENCIES;
            case AUSTRALIA_PAYID_ID:
                return AustraliaPayidAccount.SUPPORTED_CURRENCIES;
            case PERFECT_MONEY_ID:
                return PerfectMoneyAccount.SUPPORTED_CURRENCIES;
            case HAL_CASH_ID:
                return HalCashAccount.SUPPORTED_CURRENCIES;
            case SWISH_ID:
                return SwishAccount.SUPPORTED_CURRENCIES;
            case CASH_APP_ID:
                return CashAppAccount.SUPPORTED_CURRENCIES;
            case POPMONEY_ID:
                return PopmoneyAccount.SUPPORTED_CURRENCIES;
            case PROMPT_PAY_ID:
                return PromptPayAccount.SUPPORTED_CURRENCIES;
            case SEPA_ID:
                return SepaAccount.SUPPORTED_CURRENCIES;
            case SEPA_INSTANT_ID:
                return SepaInstantAccount.SUPPORTED_CURRENCIES;
            case CASH_BY_MAIL_ID:
                return CashByMailAccount.SUPPORTED_CURRENCIES;
            case F2F_ID:
                return F2FAccount.SUPPORTED_CURRENCIES;
            case NATIONAL_BANK_ID:
                return NationalBankAccount.SUPPORTED_CURRENCIES;
            case SAME_BANK_ID:
                return SameBankAccount.SUPPORTED_CURRENCIES;
            case SPECIFIC_BANKS_ID:
                return SpecificBanksAccount.SUPPORTED_CURRENCIES;
            case CASH_DEPOSIT_ID:
                return CashDepositAccount.SUPPORTED_CURRENCIES;
            case WESTERN_UNION_ID:
                return WesternUnionAccount.SUPPORTED_CURRENCIES;
            case FASTER_PAYMENTS_ID:
                return FasterPaymentsAccount.SUPPORTED_CURRENCIES;
            case DOMESTIC_WIRE_TRANSFER_ID:
                return DomesticWireTransferAccount.SUPPORTED_CURRENCIES;
            case ACH_TRANSFER_ID:
                return AchTransferAccount.SUPPORTED_CURRENCIES;
            case CELPAY_ID:
                return CelPayAccount.SUPPORTED_CURRENCIES;
            case MONESE_ID:
                return MoneseAccount.SUPPORTED_CURRENCIES;
            case TRANSFERWISE_USD_ID:
                return TransferwiseUsdAccount.SUPPORTED_CURRENCIES;
            case VERSE_ID:
                return VerseAccount.SUPPORTED_CURRENCIES;
            default:
                return Collections.emptyList();
        }
    }

    public static boolean supportsCurrency(PaymentMethod paymentMethod, TradeCurrency selectedTradeCurrency) {
        return getTradeCurrencies(paymentMethod).stream()
                .anyMatch(tradeCurrency -> tradeCurrency.equals(selectedTradeCurrency));
    }

    @Nullable
    public static List<String> getAcceptedBanks(PaymentAccount paymentAccount) {
        List<String> acceptedBanks = null;
        if (paymentAccount instanceof SpecificBanksAccount) {
            acceptedBanks = new ArrayList<>(((SpecificBanksAccount) paymentAccount).getAcceptedBanks());
        } else if (paymentAccount instanceof SameBankAccount) {
            acceptedBanks = new ArrayList<>();
            acceptedBanks.add(((SameBankAccount) paymentAccount).getBankId());
        }
        return acceptedBanks;
    }

    @Nullable
    public static String getBankId(PaymentAccount paymentAccount) {
        return paymentAccount instanceof BankAccount ? ((BankAccount) paymentAccount).getBankId() : null;
    }

    @Nullable
    public static String getCountryCode(PaymentAccount paymentAccount) {
        // That is optional and set to null if not supported (Cryptos,...)
        if (paymentAccount instanceof CountryBasedPaymentAccount) {
            Country country = (((CountryBasedPaymentAccount) paymentAccount)).getCountry();
            return country != null ? country.code : null;
        }
        return null;
    }

    public static boolean isCryptoCurrencyAccount(PaymentAccount paymentAccount) {
        return (paymentAccount != null && paymentAccount.getPaymentMethod().equals(BLOCK_CHAINS) ||
                paymentAccount != null && paymentAccount.getPaymentMethod().equals(BLOCK_CHAINS_INSTANT));
    }

    public static Optional<PaymentAccount> findPaymentAccount(PaymentAccountPayload paymentAccountPayload,
                                                              User user) {
        return user.getPaymentAccountsAsObservable().stream().
                filter(e -> e.getPaymentAccountPayload().equals(paymentAccountPayload))
                .findAny();
    }
}
