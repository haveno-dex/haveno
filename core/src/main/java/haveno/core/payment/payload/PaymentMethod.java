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

package haveno.core.payment.payload;

import haveno.common.config.BaseCurrencyNetwork;
import haveno.common.config.Config;
import haveno.common.proto.persistable.PersistablePayload;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.offer.OfferRestrictions;
import haveno.core.payment.AchTransferAccount;
import haveno.core.payment.AdvancedCashAccount;
import haveno.core.payment.AliPayAccount;
import haveno.core.payment.AmazonGiftCardAccount;
import haveno.core.payment.AustraliaPayidAccount;
import haveno.core.payment.BizumAccount;
import haveno.core.payment.CapitualAccount;
import haveno.core.payment.PayByMailAccount;
import haveno.core.payment.CashDepositAccount;
import haveno.core.payment.CelPayAccount;
import haveno.core.payment.ZelleAccount;
import haveno.core.payment.DomesticWireTransferAccount;
import haveno.core.payment.F2FAccount;
import haveno.core.payment.FasterPaymentsAccount;
import haveno.core.payment.HalCashAccount;
import haveno.core.payment.ImpsAccount;
import haveno.core.payment.InteracETransferAccount;
import haveno.core.payment.JapanBankAccount;
import haveno.core.payment.MoneseAccount;
import haveno.core.payment.MoneyBeamAccount;
import haveno.core.payment.MoneyGramAccount;
import haveno.core.payment.NationalBankAccount;
import haveno.core.payment.NeftAccount;
import haveno.core.payment.NequiAccount;
import haveno.core.payment.PaxumAccount;
import haveno.core.payment.PayseraAccount;
import haveno.core.payment.PaytmAccount;
import haveno.core.payment.PerfectMoneyAccount;
import haveno.core.payment.PixAccount;
import haveno.core.payment.PopmoneyAccount;
import haveno.core.payment.PromptPayAccount;
import haveno.core.payment.RevolutAccount;
import haveno.core.payment.RtgsAccount;
import haveno.core.payment.SameBankAccount;
import haveno.core.payment.SatispayAccount;
import haveno.core.payment.SepaAccount;
import haveno.core.payment.SepaInstantAccount;
import haveno.core.payment.SpecificBanksAccount;
import haveno.core.payment.StrikeAccount;
import haveno.core.payment.SwiftAccount;
import haveno.core.payment.SwishAccount;
import haveno.core.payment.TikkieAccount;
import haveno.core.payment.TradeLimits;
import haveno.core.payment.TransferwiseAccount;
import haveno.core.payment.TransferwiseUsdAccount;
import haveno.core.payment.USPostalMoneyOrderAccount;
import haveno.core.payment.UpholdAccount;
import haveno.core.payment.UpiAccount;
import haveno.core.payment.VerseAccount;
import haveno.core.payment.WeChatPayAccount;
import haveno.core.payment.WesternUnionAccount;
import haveno.core.trade.HavenoUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@EqualsAndHashCode(exclude = {"maxTradePeriod", "maxTradeLimit"})
@ToString
@Slf4j
public final class PaymentMethod implements PersistablePayload, Comparable<PaymentMethod> {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Static
    ///////////////////////////////////////////////////////////////////////////////////////////

    // time in ms for 1 "day" (mainnet), 30m (stagenet) or 1 minute (local)
    private static final long DAY = Config.baseCurrencyNetwork() == BaseCurrencyNetwork.XMR_LOCAL ? TimeUnit.MINUTES.toMillis(1) :
                                    Config.baseCurrencyNetwork() == BaseCurrencyNetwork.XMR_STAGENET ? TimeUnit.MINUTES.toMillis(30) :
                                    TimeUnit.DAYS.toMillis(1);

    // Default trade limits.
    // We initialize very early before reading persisted data. We will apply later the limit from
    // the DAO param (Param.MAX_TRADE_LIMIT) but that can be only done after the dao is initialized.
    // The default values will be used for deriving the
    // risk factor so the relation between the risk categories stays the same as with the default values.
    // We must not change those values as it could lead to invalid offers if amount becomes lower then new trade limit.
    // Increasing might be ok, but needs more thought as well...
    private static final BigInteger DEFAULT_TRADE_LIMIT_VERY_LOW_RISK = HavenoUtils.xmrToAtomicUnits(100);
    private static final BigInteger DEFAULT_TRADE_LIMIT_LOW_RISK = HavenoUtils.xmrToAtomicUnits(50);
    private static final BigInteger DEFAULT_TRADE_LIMIT_MID_RISK = HavenoUtils.xmrToAtomicUnits(25);
    private static final BigInteger DEFAULT_TRADE_LIMIT_HIGH_RISK = HavenoUtils.xmrToAtomicUnits(12.5);

    public static final String UPHOLD_ID = "UPHOLD";
    public static final String MONEY_BEAM_ID = "MONEY_BEAM";
    public static final String POPMONEY_ID = "POPMONEY";
    public static final String REVOLUT_ID = "REVOLUT";
    public static final String PERFECT_MONEY_ID = "PERFECT_MONEY";
    public static final String SEPA_ID = "SEPA";
    public static final String SEPA_INSTANT_ID = "SEPA_INSTANT";
    public static final String FASTER_PAYMENTS_ID = "FASTER_PAYMENTS";
    public static final String NATIONAL_BANK_ID = "NATIONAL_BANK";
    public static final String JAPAN_BANK_ID = "JAPAN_BANK";
    public static final String AUSTRALIA_PAYID_ID = "AUSTRALIA_PAYID";
    public static final String SAME_BANK_ID = "SAME_BANK";
    public static final String SPECIFIC_BANKS_ID = "SPECIFIC_BANKS";
    public static final String SWISH_ID = "SWISH";
    public static final String ALI_PAY_ID = "ALI_PAY";
    public static final String WECHAT_PAY_ID = "WECHAT_PAY";
    public static final String ZELLE_ID = "ZELLE";

    @Deprecated
    public static final String CHASE_QUICK_PAY_ID = "CHASE_QUICK_PAY"; // Removed due to QuickPay becoming Zelle

    public static final String INTERAC_E_TRANSFER_ID = "INTERAC_E_TRANSFER";
    public static final String US_POSTAL_MONEY_ORDER_ID = "US_POSTAL_MONEY_ORDER";
    public static final String CASH_DEPOSIT_ID = "CASH_DEPOSIT";
    public static final String MONEY_GRAM_ID = "MONEY_GRAM";
    public static final String WESTERN_UNION_ID = "WESTERN_UNION";
    public static final String HAL_CASH_ID = "HAL_CASH";
    public static final String F2F_ID = "F2F";
    public static final String BLOCK_CHAINS_ID = "BLOCK_CHAINS";
    public static final String PROMPT_PAY_ID = "PROMPT_PAY";
    public static final String ADVANCED_CASH_ID = "ADVANCED_CASH";
    public static final String TRANSFERWISE_ID = "TRANSFERWISE";
    public static final String TRANSFERWISE_USD_ID = "TRANSFERWISE_USD";
    public static final String PAYSERA_ID = "PAYSERA";
    public static final String PAXUM_ID = "PAXUM";
    public static final String NEFT_ID = "NEFT";
    public static final String RTGS_ID = "RTGS";
    public static final String IMPS_ID = "IMPS";
    public static final String UPI_ID = "UPI";
    public static final String PAYTM_ID = "PAYTM";
    public static final String NEQUI_ID = "NEQUI";
    public static final String BIZUM_ID = "BIZUM";
    public static final String PIX_ID = "PIX";
    public static final String AMAZON_GIFT_CARD_ID = "AMAZON_GIFT_CARD";
    public static final String BLOCK_CHAINS_INSTANT_ID = "BLOCK_CHAINS_INSTANT";
    public static final String PAY_BY_MAIL_ID = "PAY_BY_MAIL";
    public static final String CAPITUAL_ID = "CAPITUAL";
    public static final String CELPAY_ID = "CELPAY";
    public static final String MONESE_ID = "MONESE";
    public static final String SATISPAY_ID = "SATISPAY";
    public static final String TIKKIE_ID = "TIKKIE";
    public static final String VERSE_ID = "VERSE";
    public static final String STRIKE_ID = "STRIKE";
    public static final String SWIFT_ID = "SWIFT";
    public static final String ACH_TRANSFER_ID = "ACH_TRANSFER";
    public static final String DOMESTIC_WIRE_TRANSFER_ID = "DOMESTIC_WIRE_TRANSFER";

    // Cannot be deleted as it would break old trade history entries
    @Deprecated
    public static final String OK_PAY_ID = "OK_PAY";
    @Deprecated
    public static final String CASH_APP_ID = "CASH_APP"; // Removed due too high chargeback risk
    @Deprecated
    public static final String VENMO_ID = "VENMO";  // Removed due too high chargeback risk

    public static PaymentMethod UPHOLD;
    public static PaymentMethod MONEY_BEAM;
    public static PaymentMethod POPMONEY;
    public static PaymentMethod REVOLUT;
    public static PaymentMethod PERFECT_MONEY;
    public static PaymentMethod SEPA;
    public static PaymentMethod SEPA_INSTANT;
    public static PaymentMethod FASTER_PAYMENTS;
    public static PaymentMethod NATIONAL_BANK;
    public static PaymentMethod JAPAN_BANK;
    public static PaymentMethod AUSTRALIA_PAYID;
    public static PaymentMethod SAME_BANK;
    public static PaymentMethod SPECIFIC_BANKS;
    public static PaymentMethod SWISH;
    public static PaymentMethod ALI_PAY;
    public static PaymentMethod WECHAT_PAY;
    public static PaymentMethod ZELLE;
    public static PaymentMethod CHASE_QUICK_PAY;
    public static PaymentMethod INTERAC_E_TRANSFER;
    public static PaymentMethod US_POSTAL_MONEY_ORDER;
    public static PaymentMethod CASH_DEPOSIT;
    public static PaymentMethod MONEY_GRAM;
    public static PaymentMethod WESTERN_UNION;
    public static PaymentMethod F2F;
    public static PaymentMethod HAL_CASH;
    public static PaymentMethod BLOCK_CHAINS;
    public static PaymentMethod PROMPT_PAY;
    public static PaymentMethod ADVANCED_CASH;
    public static PaymentMethod TRANSFERWISE;
    public static PaymentMethod TRANSFERWISE_USD;
    public static PaymentMethod PAYSERA;
    public static PaymentMethod PAXUM;
    public static PaymentMethod NEFT;
    public static PaymentMethod RTGS;
    public static PaymentMethod IMPS;
    public static PaymentMethod UPI;
    public static PaymentMethod PAYTM;
    public static PaymentMethod NEQUI;
    public static PaymentMethod BIZUM;
    public static PaymentMethod PIX;
    public static PaymentMethod AMAZON_GIFT_CARD;
    public static PaymentMethod BLOCK_CHAINS_INSTANT;
    public static PaymentMethod PAY_BY_MAIL;
    public static PaymentMethod CAPITUAL;
    public static PaymentMethod CELPAY;
    public static PaymentMethod MONESE;
    public static PaymentMethod SATISPAY;
    public static PaymentMethod TIKKIE;
    public static PaymentMethod VERSE;
    public static PaymentMethod STRIKE;
    public static PaymentMethod SWIFT;
    public static PaymentMethod ACH_TRANSFER;
    public static PaymentMethod DOMESTIC_WIRE_TRANSFER;
    public static PaymentMethod BSQ_SWAP;

    // Cannot be deleted as it would break old trade history entries
    @Deprecated
    public static PaymentMethod OK_PAY = getDummyPaymentMethod(OK_PAY_ID);
    @Deprecated
    public static PaymentMethod CASH_APP = getDummyPaymentMethod(CASH_APP_ID); // Removed due too high chargeback risk
    @Deprecated
    public static PaymentMethod VENMO = getDummyPaymentMethod(VENMO_ID); // Removed due too high chargeback risk

    // The limit and duration assignment must not be changed as that could break old offers (if amount would be higher
    // than new trade limit) and violate the maker expectation when he created the offer (duration).
    public final static List<PaymentMethod> paymentMethods = Arrays.asList(
            // EUR
            HAL_CASH = new PaymentMethod(HAL_CASH_ID, DAY, DEFAULT_TRADE_LIMIT_LOW_RISK, getAssetCodes(HalCashAccount.SUPPORTED_CURRENCIES)),
            SEPA = new PaymentMethod(SEPA_ID, 6 * DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(SepaAccount.SUPPORTED_CURRENCIES)),
            SEPA_INSTANT = new PaymentMethod(SEPA_INSTANT_ID, DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(SepaInstantAccount.SUPPORTED_CURRENCIES)),
            MONEY_BEAM = new PaymentMethod(MONEY_BEAM_ID, DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(MoneyBeamAccount.SUPPORTED_CURRENCIES)),

            // UK
            FASTER_PAYMENTS = new PaymentMethod(FASTER_PAYMENTS_ID, DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(FasterPaymentsAccount.SUPPORTED_CURRENCIES)),

            // Sweden
            SWISH = new PaymentMethod(SWISH_ID, DAY, DEFAULT_TRADE_LIMIT_LOW_RISK, getAssetCodes(SwishAccount.SUPPORTED_CURRENCIES)),

            // US
            ZELLE = new PaymentMethod(ZELLE_ID, 4 * DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(ZelleAccount.SUPPORTED_CURRENCIES)),

            POPMONEY = new PaymentMethod(POPMONEY_ID, DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(PopmoneyAccount.SUPPORTED_CURRENCIES)),
            US_POSTAL_MONEY_ORDER = new PaymentMethod(US_POSTAL_MONEY_ORDER_ID, 8 * DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(USPostalMoneyOrderAccount.SUPPORTED_CURRENCIES)),

            // Canada
            INTERAC_E_TRANSFER = new PaymentMethod(INTERAC_E_TRANSFER_ID, DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(InteracETransferAccount.SUPPORTED_CURRENCIES)),

            // Global
            CASH_DEPOSIT = new PaymentMethod(CASH_DEPOSIT_ID, 4 * DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(CashDepositAccount.SUPPORTED_CURRENCIES)),
            PAY_BY_MAIL = new PaymentMethod(PAY_BY_MAIL_ID, 8 * DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(PayByMailAccount.SUPPORTED_CURRENCIES)),
            MONEY_GRAM = new PaymentMethod(MONEY_GRAM_ID, 4 * DAY, DEFAULT_TRADE_LIMIT_MID_RISK, getAssetCodes(MoneyGramAccount.SUPPORTED_CURRENCIES)),
            WESTERN_UNION = new PaymentMethod(WESTERN_UNION_ID, 4 * DAY, DEFAULT_TRADE_LIMIT_MID_RISK, getAssetCodes(WesternUnionAccount.SUPPORTED_CURRENCIES)),
            NATIONAL_BANK = new PaymentMethod(NATIONAL_BANK_ID, 4 * DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(NationalBankAccount.SUPPORTED_CURRENCIES)),
            SAME_BANK = new PaymentMethod(SAME_BANK_ID, 2 * DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(SameBankAccount.SUPPORTED_CURRENCIES)),
            SPECIFIC_BANKS = new PaymentMethod(SPECIFIC_BANKS_ID, 4 * DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(SpecificBanksAccount.SUPPORTED_CURRENCIES)),
            F2F = new PaymentMethod(F2F_ID, 4 * DAY, DEFAULT_TRADE_LIMIT_LOW_RISK, getAssetCodes(F2FAccount.SUPPORTED_CURRENCIES)),
            AMAZON_GIFT_CARD = new PaymentMethod(AMAZON_GIFT_CARD_ID, DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(AmazonGiftCardAccount.SUPPORTED_CURRENCIES)),

            // Trans national
            UPHOLD = new PaymentMethod(UPHOLD_ID, DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(UpholdAccount.SUPPORTED_CURRENCIES)),
            REVOLUT = new PaymentMethod(REVOLUT_ID, DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(RevolutAccount.SUPPORTED_CURRENCIES)),
            PERFECT_MONEY = new PaymentMethod(PERFECT_MONEY_ID, DAY, DEFAULT_TRADE_LIMIT_LOW_RISK, getAssetCodes(PerfectMoneyAccount.SUPPORTED_CURRENCIES)),
            ADVANCED_CASH = new PaymentMethod(ADVANCED_CASH_ID, DAY, DEFAULT_TRADE_LIMIT_VERY_LOW_RISK, getAssetCodes(AdvancedCashAccount.SUPPORTED_CURRENCIES)),
            TRANSFERWISE = new PaymentMethod(TRANSFERWISE_ID, 4 * DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(TransferwiseAccount.SUPPORTED_CURRENCIES)),
            TRANSFERWISE_USD = new PaymentMethod(TRANSFERWISE_USD_ID, 4 * DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(TransferwiseUsdAccount.SUPPORTED_CURRENCIES)),
            PAYSERA = new PaymentMethod(PAYSERA_ID, DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(PayseraAccount.SUPPORTED_CURRENCIES)),
            PAXUM = new PaymentMethod(PAXUM_ID, DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(PaxumAccount.SUPPORTED_CURRENCIES)),
            NEFT = new PaymentMethod(NEFT_ID, DAY, HavenoUtils.xmrToAtomicUnits(0.02), getAssetCodes(NeftAccount.SUPPORTED_CURRENCIES)),
            RTGS = new PaymentMethod(RTGS_ID, DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(RtgsAccount.SUPPORTED_CURRENCIES)),
            IMPS = new PaymentMethod(IMPS_ID, DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(ImpsAccount.SUPPORTED_CURRENCIES)),
            UPI = new PaymentMethod(UPI_ID, DAY, HavenoUtils.xmrToAtomicUnits(0.05), getAssetCodes(UpiAccount.SUPPORTED_CURRENCIES)),
            PAYTM = new PaymentMethod(PAYTM_ID, DAY, HavenoUtils.xmrToAtomicUnits(0.05), getAssetCodes(PaytmAccount.SUPPORTED_CURRENCIES)),
            NEQUI = new PaymentMethod(NEQUI_ID, DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(NequiAccount.SUPPORTED_CURRENCIES)),
            BIZUM = new PaymentMethod(BIZUM_ID, DAY, HavenoUtils.xmrToAtomicUnits(0.04), getAssetCodes(BizumAccount.SUPPORTED_CURRENCIES)),
            PIX = new PaymentMethod(PIX_ID, DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(PixAccount.SUPPORTED_CURRENCIES)),
            CAPITUAL = new PaymentMethod(CAPITUAL_ID, DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(CapitualAccount.SUPPORTED_CURRENCIES)),
            CELPAY = new PaymentMethod(CELPAY_ID, DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(CelPayAccount.SUPPORTED_CURRENCIES)),
            MONESE = new PaymentMethod(MONESE_ID, DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(MoneseAccount.SUPPORTED_CURRENCIES)),
            SATISPAY = new PaymentMethod(SATISPAY_ID, DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(SatispayAccount.SUPPORTED_CURRENCIES)),
            TIKKIE = new PaymentMethod(TIKKIE_ID, DAY, HavenoUtils.xmrToAtomicUnits(0.05), getAssetCodes(TikkieAccount.SUPPORTED_CURRENCIES)),
            VERSE = new PaymentMethod(VERSE_ID, DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(VerseAccount.SUPPORTED_CURRENCIES)),
            STRIKE = new PaymentMethod(STRIKE_ID, DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(StrikeAccount.SUPPORTED_CURRENCIES)),
            SWIFT = new PaymentMethod(SWIFT_ID, 7 * DAY, DEFAULT_TRADE_LIMIT_MID_RISK, getAssetCodes(SwiftAccount.SUPPORTED_CURRENCIES)),
            ACH_TRANSFER = new PaymentMethod(ACH_TRANSFER_ID, 5 * DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(AchTransferAccount.SUPPORTED_CURRENCIES)),
            DOMESTIC_WIRE_TRANSFER = new PaymentMethod(DOMESTIC_WIRE_TRANSFER_ID, 3 * DAY, DEFAULT_TRADE_LIMIT_HIGH_RISK, getAssetCodes(DomesticWireTransferAccount.SUPPORTED_CURRENCIES)),

            // Japan
            JAPAN_BANK = new PaymentMethod(JAPAN_BANK_ID, DAY, DEFAULT_TRADE_LIMIT_LOW_RISK, getAssetCodes(JapanBankAccount.SUPPORTED_CURRENCIES)),

            // Australia
            AUSTRALIA_PAYID = new PaymentMethod(AUSTRALIA_PAYID_ID, DAY, DEFAULT_TRADE_LIMIT_LOW_RISK, getAssetCodes(AustraliaPayidAccount.SUPPORTED_CURRENCIES)),

            // China
            ALI_PAY = new PaymentMethod(ALI_PAY_ID, DAY, DEFAULT_TRADE_LIMIT_LOW_RISK, getAssetCodes(AliPayAccount.SUPPORTED_CURRENCIES)),
            WECHAT_PAY = new PaymentMethod(WECHAT_PAY_ID, DAY, DEFAULT_TRADE_LIMIT_LOW_RISK, getAssetCodes(WeChatPayAccount.SUPPORTED_CURRENCIES)),

            // Thailand
            PROMPT_PAY = new PaymentMethod(PROMPT_PAY_ID, DAY, DEFAULT_TRADE_LIMIT_LOW_RISK, getAssetCodes(PromptPayAccount.SUPPORTED_CURRENCIES)),

            // Cryptos
            BLOCK_CHAINS = new PaymentMethod(BLOCK_CHAINS_ID, DAY, DEFAULT_TRADE_LIMIT_VERY_LOW_RISK, Arrays.asList()),
            // Cryptos with 1 hour trade period
            BLOCK_CHAINS_INSTANT = new PaymentMethod(BLOCK_CHAINS_INSTANT_ID, TimeUnit.HOURS.toMillis(1), DEFAULT_TRADE_LIMIT_VERY_LOW_RISK, Arrays.asList())
    );

    // TODO: delete this override method, which overrides the paymentMethods variable, when all payment methods supported using structured form api, and make paymentMethods private
    public static List<PaymentMethod> getPaymentMethods() {
        List<String> paymentMethodIds = List.of(
                BLOCK_CHAINS_ID,
                REVOLUT_ID,
                SEPA_ID,
                SEPA_INSTANT_ID,
                TRANSFERWISE_ID,
                ZELLE_ID,
                SWIFT_ID,
                F2F_ID,
                STRIKE_ID,
                MONEY_GRAM_ID,
                FASTER_PAYMENTS_ID,
                UPHOLD_ID,
                PAXUM_ID);
        return paymentMethods.stream().filter(paymentMethod -> paymentMethodIds.contains(paymentMethod.getId())).collect(Collectors.toList());
    }

    private static List<String> getAssetCodes(List<TradeCurrency> tradeCurrencies) {
        return tradeCurrencies.stream().map(TradeCurrency::getCode).collect(Collectors.toList());
    }

    static {
        paymentMethods.sort((o1, o2) -> {
            String id1 = o1.getId();
            if (id1.equals(ZELLE_ID))
                id1 = "ZELLE";
            String id2 = o2.getId();
            if (id2.equals(ZELLE_ID))
                id2 = "ZELLE";
            return id1.compareTo(id2);
        });
    }

    public static PaymentMethod getDummyPaymentMethod(String id) {
        return new PaymentMethod(id, 0, BigInteger.valueOf(0), Arrays.asList());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Instance fields
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Getter
    private final String id;

    // Must not change as old offers would get a new period then and that would violate the makers "contract" or
    // expectation when he created the offer.
    @Getter
    private final long maxTradePeriod;

    // With v0.9.4 we changed context of that field. Before it was the hard coded trade limit. Now it is the default
    // limit based on the risk factor.
    // The risk factor is derived from the maxTradeLimit.
    // As that field is used in protobuffer definitions we cannot change it to reflect better the new context. We prefer
    // to keep the convention that PB fields has the same name as the Java class field (as we could rename it in
    // Java without breaking PB).
    private final long maxTradeLimit;

    // list of asset codes the payment method supports
    private List<String> supportedAssetCodes;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    /**
     * @param id             against charge back risk. If Bank do the charge back quickly the Arbitrator and the seller can push another
     *                       double spend tx to invalidate the time locked payout tx. For the moment we set all to 0 but will have it in
     *                       place when needed.
     * @param maxTradePeriod The min. period a trader need to wait until he gets displayed the contact form for opening a dispute.
     * @param maxTradeLimit  The max. allowed trade amount in Bitcoin for that payment method (depending on charge back risk)
     * @param supportedAssetCodes Supported asset codes.
     */
    private PaymentMethod(String id, long maxTradePeriod, BigInteger maxTradeLimit, List<String> supportedAssetCodes) {
        this.id = id;
        this.maxTradePeriod = maxTradePeriod;
        this.maxTradeLimit = maxTradeLimit.longValueExact();
        this.supportedAssetCodes = supportedAssetCodes;
    }

    // Used for dummy entries in payment methods list (SHOW_ALL)
    private PaymentMethod(String id) {
        this(id, 0, BigInteger.valueOf(0), new ArrayList<String>());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.PaymentMethod toProtoMessage() {
        return protobuf.PaymentMethod.newBuilder()
                .setId(id)
                .setMaxTradePeriod(maxTradePeriod)
                .setMaxTradeLimit(maxTradeLimit)
                .addAllSupportedAssetCodes(supportedAssetCodes)
                .build();
    }

    public static PaymentMethod fromProto(protobuf.PaymentMethod proto) {
        return new PaymentMethod(proto.getId(),
                proto.getMaxTradePeriod(),
                BigInteger.valueOf(proto.getMaxTradeLimit()),
                proto.getSupportedAssetCodesList());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public static PaymentMethod getPaymentMethod(String id) {
        return getActivePaymentMethod(id)
                .orElseGet(() -> new PaymentMethod(Res.get("shared.na")));
    }

    // We look up only our active payment methods not retired ones.
    public static Optional<PaymentMethod> getActivePaymentMethod(String id) {
        return paymentMethods.stream()
                .filter(e -> e.getId().equals(id))
                .findFirst();
    }

    public BigInteger getMaxTradeLimit(String currencyCode) {
        // Hack for SF as the smallest unit is 1 SF ;-( and price is about 3 BTC!
        if (currencyCode.equals("SF"))
            return HavenoUtils.xmrToAtomicUnits(4);
        // payment methods which define their own trade limits
        if (id.equals(NEFT_ID) || id.equals(UPI_ID) || id.equals(PAYTM_ID) || id.equals(BIZUM_ID) || id.equals(TIKKIE_ID)) {
            return BigInteger.valueOf(maxTradeLimit);
        }

        // We use the class field maxTradeLimit only for mapping the risk factor.
        long riskFactor;
        if (maxTradeLimit == DEFAULT_TRADE_LIMIT_VERY_LOW_RISK.longValueExact())
            riskFactor = 1;
        else if (maxTradeLimit == DEFAULT_TRADE_LIMIT_LOW_RISK.longValueExact())
            riskFactor = 2;
        else if (maxTradeLimit == DEFAULT_TRADE_LIMIT_MID_RISK.longValueExact())
            riskFactor = 4;
        else if (maxTradeLimit == DEFAULT_TRADE_LIMIT_HIGH_RISK.longValueExact())
            riskFactor = 8;
        else {
            riskFactor = 8;
            log.warn("maxTradeLimit is not matching one of our default values. We use highest risk factor. " +
                            "maxTradeLimit={}. PaymentMethod={}", maxTradeLimit, this);
        }

        // get risk based trade limit
        TradeLimits tradeLimits = new TradeLimits();
        long maxTradeLimit = tradeLimits.getMaxTradeLimit().longValueExact();
        long riskBasedTradeLimit = tradeLimits.getRoundedRiskBasedTradeLimit(maxTradeLimit, riskFactor);

        // if traditional and stagenet, cap offer amounts to avoid offers which cannot be taken
        boolean isTraditional = CurrencyUtil.isTraditionalCurrency(currencyCode);
        boolean isStagenet = Config.baseCurrencyNetwork() == BaseCurrencyNetwork.XMR_STAGENET;
        if (isTraditional && isStagenet && riskBasedTradeLimit > OfferRestrictions.TOLERATED_SMALL_TRADE_AMOUNT.longValueExact()) {
            riskBasedTradeLimit = OfferRestrictions.TOLERATED_SMALL_TRADE_AMOUNT.longValueExact();
        }
        return BigInteger.valueOf(riskBasedTradeLimit);
    }

    public String getShortName() {
        // in cases where translation is not found, Res.get() simply returns the key string
        // so no need for special error-handling code.
        return Res.get(this.id + "_SHORT");
    }

    @Override
    public int compareTo(@NotNull PaymentMethod other) {
        return Res.get(id).compareTo(Res.get(other.id));
    }

    public String getDisplayString() {
        return Res.get(id);
    }

    public boolean isTraditional() {
        return !isCrypto();
    }

    public boolean isBlockchain() {
        return this.equals(BLOCK_CHAINS_INSTANT) || this.equals(BLOCK_CHAINS);
    }

    // Includes any non btc asset, not limited to blockchain payment methods
    public boolean isCrypto() {
        return isBlockchain() || isBsqSwap();
    }

    public boolean isBsqSwap() {
        return this.equals(BSQ_SWAP);
    }

    public static boolean hasChargebackRisk(PaymentMethod paymentMethod, List<TradeCurrency> tradeCurrencies) {
        return tradeCurrencies.stream()
                .anyMatch(tradeCurrency -> hasChargebackRisk(paymentMethod, tradeCurrency.getCode()));
    }

    public static boolean hasChargebackRisk(PaymentMethod paymentMethod) {
        return hasChargebackRisk(paymentMethod, CurrencyUtil.getMatureMarketCurrencies());
    }

    public static boolean hasChargebackRisk(PaymentMethod paymentMethod, String currencyCode) {
        if (paymentMethod == null)
            return false;

        String id = paymentMethod.getId();
        return hasChargebackRisk(id, currencyCode);
    }

    public static boolean hasChargebackRisk(String id, String currencyCode) {
        if (CurrencyUtil.getMatureMarketCurrencies().stream()
                .noneMatch(c -> c.getCode().equals(currencyCode)))
            return false;

        return id.equals(PaymentMethod.SEPA_ID) ||
                id.equals(PaymentMethod.SEPA_INSTANT_ID) ||
                id.equals(PaymentMethod.INTERAC_E_TRANSFER_ID) ||
                id.equals(PaymentMethod.ZELLE_ID) ||
                id.equals(PaymentMethod.REVOLUT_ID) ||
                id.equals(PaymentMethod.NATIONAL_BANK_ID) ||
                id.equals(PaymentMethod.SAME_BANK_ID) ||
                id.equals(PaymentMethod.SPECIFIC_BANKS_ID) ||
                id.equals(PaymentMethod.CHASE_QUICK_PAY_ID) ||
                id.equals(PaymentMethod.POPMONEY_ID) ||
                id.equals(PaymentMethod.MONEY_BEAM_ID) ||
                id.equals(PaymentMethod.UPHOLD_ID);
    }
}
