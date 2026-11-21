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

package haveno.desktop.components.paymentmethods;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import haveno.asset.Asset;
import haveno.asset.CryptoAccountDisclaimer;
import haveno.asset.coins.Monero;
import haveno.common.config.Config;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.filter.FilterManager;
import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.offer.OfferRestrictions;
import haveno.core.payment.AmazonGiftCardAccount;
import haveno.core.payment.AustraliaPayidAccount;
import haveno.core.payment.CashAppAccount;
import haveno.core.payment.CashAtAtmAccount;
import haveno.core.payment.CashDepositAccount;
import haveno.core.payment.F2FAccount;
import haveno.core.payment.HalCashAccount;
import haveno.core.payment.MoneyGramAccount;
import haveno.core.payment.PayByMailAccount;
import haveno.core.payment.PayPalAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PaysafeAccount;
import haveno.core.payment.RevolutAccount;
import haveno.core.payment.USPostalMoneyOrderAccount;
import haveno.core.payment.VenmoAccount;
import haveno.core.payment.WesternUnionAccount;
import haveno.core.payment.ZelleAccount;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.validation.AdvancedCashValidator;
import haveno.core.payment.validation.AliPayValidator;
import haveno.core.payment.validation.AustraliaPayidValidator;
import haveno.core.payment.validation.BICValidator;
import haveno.core.payment.validation.CapitualValidator;
import haveno.core.payment.validation.ChaseQuickPayValidator;
import haveno.core.payment.validation.CryptoAddressValidator;
import haveno.core.payment.validation.EmailOrMobileNrOrCashtagValidator;
import haveno.core.payment.validation.EmailOrMobileNrOrUsernameValidator;
import haveno.core.payment.validation.EmailOrMobileNrValidator;
import haveno.core.payment.validation.F2FValidator;
import haveno.core.payment.validation.HalCashValidator;
import haveno.core.payment.validation.InteracETransferValidator;
import haveno.core.payment.validation.JapanBankTransferValidator;
import haveno.core.payment.validation.LengthValidator;
import haveno.core.payment.validation.MbWayValidator;
import haveno.core.payment.validation.MoneyBeamValidator;
import haveno.core.payment.validation.PagoMovilValidator;
import haveno.core.payment.validation.PerfectMoneyValidator;
import haveno.core.payment.validation.PopmoneyValidator;
import haveno.core.payment.validation.PromptPayValidator;
import haveno.core.payment.validation.RevolutValidator;
import haveno.core.payment.validation.SwishValidator;
import haveno.core.payment.validation.TransferwiseValidator;
import haveno.core.payment.validation.TwintValidator;
import haveno.core.payment.validation.USPostalMoneyOrderValidator;
import haveno.core.payment.validation.UpholdValidator;
import haveno.core.payment.validation.WeChatPayValidator;
import haveno.core.payment.validation.WeroValidator;
import haveno.core.trade.HavenoUtils;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.util.GUIUtil;
import java.math.BigInteger;
import java.util.Optional;
import javafx.scene.layout.GridPane;
import lombok.extern.slf4j.Slf4j;

// Builds the form of a payment account and shows the info a new account has to confirm before it is saved.
@Slf4j
public class PaymentAccountForms {

    private final BICValidator bicValidator;
    private final CapitualValidator capitualValidator;
    private final LengthValidator inputValidator;
    private final UpholdValidator upholdValidator;
    private final MoneyBeamValidator moneyBeamValidator;
    private final PopmoneyValidator popmoneyValidator;
    private final RevolutValidator revolutValidator;
    private final AliPayValidator aliPayValidator;
    private final PerfectMoneyValidator perfectMoneyValidator;
    private final SwishValidator swishValidator;
    private final TwintValidator twintValidator;
    private final WeroValidator weroValidator;
    private final MbWayValidator mbWayValidator;
    private final PagoMovilValidator pagoMovilValidator;
    private final EmailOrMobileNrValidator zelleValidator;
    private final EmailOrMobileNrOrUsernameValidator paypalValidator;
    private final EmailOrMobileNrOrUsernameValidator venmoValidator;
    private final EmailOrMobileNrOrCashtagValidator cashAppValidator;
    private final ChaseQuickPayValidator chaseQuickPayValidator;
    private final InteracETransferValidator interacETransferValidator;
    private final JapanBankTransferValidator japanBankTransferValidator;
    private final AustraliaPayidValidator australiapayidValidator;
    private final USPostalMoneyOrderValidator usPostalMoneyOrderValidator;
    private final WeChatPayValidator weChatPayValidator;
    private final HalCashValidator halCashValidator;
    private final F2FValidator f2FValidator;
    private final PromptPayValidator promptPayValidator;
    private final AdvancedCashValidator advancedCashValidator;
    private final TransferwiseValidator transferwiseValidator;
    private final InputValidator assetInputValidator;
    private final CryptoAddressValidator cryptoAddressValidator;
    private final FilterManager filterManager;
    private final AccountAgeWitnessService accountAgeWitnessService;
    private final CoinFormatter formatter;

    @Inject
    public PaymentAccountForms(BICValidator bicValidator,
                               CapitualValidator capitualValidator,
                               LengthValidator inputValidator,
                               UpholdValidator upholdValidator,
                               MoneyBeamValidator moneyBeamValidator,
                               PopmoneyValidator popmoneyValidator,
                               RevolutValidator revolutValidator,
                               AliPayValidator aliPayValidator,
                               PerfectMoneyValidator perfectMoneyValidator,
                               SwishValidator swishValidator,
                               TwintValidator twintValidator,
                               WeroValidator weroValidator,
                               MbWayValidator mbWayValidator,
                               PagoMovilValidator pagoMovilValidator,
                               EmailOrMobileNrValidator zelleValidator,
                               EmailOrMobileNrOrCashtagValidator cashAppValidator,
                               EmailOrMobileNrOrUsernameValidator emailMobileUsernameValidator,
                               ChaseQuickPayValidator chaseQuickPayValidator,
                               InteracETransferValidator interacETransferValidator,
                               JapanBankTransferValidator japanBankTransferValidator,
                               AustraliaPayidValidator australiaPayIDValidator,
                               USPostalMoneyOrderValidator usPostalMoneyOrderValidator,
                               WeChatPayValidator weChatPayValidator,
                               HalCashValidator halCashValidator,
                               F2FValidator f2FValidator,
                               PromptPayValidator promptPayValidator,
                               AdvancedCashValidator advancedCashValidator,
                               TransferwiseValidator transferwiseValidator,
                               InputValidator assetInputValidator,
                               CryptoAddressValidator cryptoAddressValidator,
                               FilterManager filterManager,
                               AccountAgeWitnessService accountAgeWitnessService,
                               @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter) {
        this.bicValidator = bicValidator;
        this.capitualValidator = capitualValidator;
        this.inputValidator = inputValidator;
        this.inputValidator.setMaxLength(100); // restrict general field entry length
        this.inputValidator.setMinLength(2);
        this.upholdValidator = upholdValidator;
        this.moneyBeamValidator = moneyBeamValidator;
        this.popmoneyValidator = popmoneyValidator;
        this.revolutValidator = revolutValidator;
        this.aliPayValidator = aliPayValidator;
        this.perfectMoneyValidator = perfectMoneyValidator;
        this.swishValidator = swishValidator;
        this.twintValidator = twintValidator;
        this.weroValidator = weroValidator;
        this.mbWayValidator = mbWayValidator;
        this.pagoMovilValidator = pagoMovilValidator;
        this.zelleValidator = zelleValidator;
        this.paypalValidator = emailMobileUsernameValidator;
        this.venmoValidator = emailMobileUsernameValidator;
        this.cashAppValidator = cashAppValidator;
        this.chaseQuickPayValidator = chaseQuickPayValidator;
        this.interacETransferValidator = interacETransferValidator;
        this.japanBankTransferValidator = japanBankTransferValidator;
        this.australiapayidValidator = australiaPayIDValidator;
        this.usPostalMoneyOrderValidator = usPostalMoneyOrderValidator;
        this.weChatPayValidator = weChatPayValidator;
        this.halCashValidator = halCashValidator;
        this.f2FValidator = f2FValidator;
        this.promptPayValidator = promptPayValidator;
        this.advancedCashValidator = advancedCashValidator;
        this.transferwiseValidator = transferwiseValidator;
        this.assetInputValidator = assetInputValidator;
        this.cryptoAddressValidator = cryptoAddressValidator;
        this.filterManager = filterManager;
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.formatter = formatter;
    }

    // The form for the account, which adds its rows to the grid below the given row.
    public PaymentMethodForm getForm(PaymentAccount paymentAccount, GridPane gridPane, int gridRow) {
        switch (paymentAccount.getPaymentMethod().getId()) {
            case PaymentMethod.UPHOLD_ID:
                return new UpholdForm(paymentAccount, accountAgeWitnessService, upholdValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.MONEY_BEAM_ID:
                return new MoneyBeamForm(paymentAccount, accountAgeWitnessService, moneyBeamValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.POPMONEY_ID:
                return new PopmoneyForm(paymentAccount, accountAgeWitnessService, popmoneyValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.REVOLUT_ID:
                return new RevolutForm(paymentAccount, accountAgeWitnessService, revolutValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.PERFECT_MONEY_ID:
                return new PerfectMoneyForm(paymentAccount, accountAgeWitnessService, perfectMoneyValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.SEPA_ID:
                return new SepaForm(paymentAccount, accountAgeWitnessService, bicValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.SEPA_INSTANT_ID:
                return new SepaInstantForm(paymentAccount, accountAgeWitnessService, bicValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.FASTER_PAYMENTS_ID:
                return new FasterPaymentsForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.NATIONAL_BANK_ID:
                return new NationalBankForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.SAME_BANK_ID:
                return new SameBankForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.SPECIFIC_BANKS_ID:
                return new SpecificBankForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.JAPAN_BANK_ID:
                return new JapanBankTransferForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.AUSTRALIA_PAYID_ID:
                return new AustraliaPayidForm(paymentAccount, accountAgeWitnessService, australiapayidValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.ALI_PAY_ID:
                return new AliPayForm(paymentAccount, accountAgeWitnessService, aliPayValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.WECHAT_PAY_ID:
                return new WeChatPayForm(paymentAccount, accountAgeWitnessService, weChatPayValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.SWISH_ID:
                return new SwishForm(paymentAccount, accountAgeWitnessService, swishValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.ZELLE_ID:
                return new ZelleForm(paymentAccount, accountAgeWitnessService, zelleValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.CHASE_QUICK_PAY_ID:
                return new ChaseQuickPayForm(paymentAccount, accountAgeWitnessService, chaseQuickPayValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.INTERAC_E_TRANSFER_ID:
                return new InteracETransferForm(paymentAccount, accountAgeWitnessService, interacETransferValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.US_POSTAL_MONEY_ORDER_ID:
                return new USPostalMoneyOrderForm(paymentAccount, accountAgeWitnessService, usPostalMoneyOrderValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.MONEY_GRAM_ID:
                return new MoneyGramForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.WESTERN_UNION_ID:
                return new WesternUnionForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.CASH_DEPOSIT_ID:
                return new CashDepositForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.PAY_BY_MAIL_ID:
                return new PayByMailForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.CASH_AT_ATM_ID:
                return new CashAtAtmForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.HAL_CASH_ID:
                return new HalCashForm(paymentAccount, accountAgeWitnessService, halCashValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.F2F_ID:
                return new F2FForm(paymentAccount, accountAgeWitnessService, f2FValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.PROMPT_PAY_ID:
                return new PromptPayForm(paymentAccount, accountAgeWitnessService, promptPayValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.ADVANCED_CASH_ID:
                return new AdvancedCashForm(paymentAccount, accountAgeWitnessService, advancedCashValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.TRANSFERWISE_ID:
                return new TransferwiseForm(paymentAccount, accountAgeWitnessService, transferwiseValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.TRANSFERWISE_USD_ID:
                return new TransferwiseUsdForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.PAYSERA_ID:
                return new PayseraForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.PAXUM_ID:
                return new PaxumForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.NEFT_ID:
                return new NeftForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.RTGS_ID:
                return new RtgsForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.IMPS_ID:
                return new ImpsForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.UPI_ID:
                return new UpiForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.PAYTM_ID:
                return new PaytmForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.NEQUI_ID:
                return new NequiForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.MPESA_ID:
                return new MpesaForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.MERCADO_PAGO_ID:
                return new MercadoPagoForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.GCASH_ID:
                return new GcashForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.MOMO_ID:
                return new MomoForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.SPEI_ID:
                return new SpeiForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.BIZUM_ID:
                return new BizumForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.PIX_ID:
                return new PixForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.AMAZON_GIFT_CARD_ID:
                return new AmazonGiftCardForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.CAPITUAL_ID:
                return new CapitualForm(paymentAccount, accountAgeWitnessService, capitualValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.CELPAY_ID:
                return new CelPayForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.MONESE_ID:
                return new MoneseForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.SATISPAY_ID:
                return new SatispayForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.TIKKIE_ID:
                return new TikkieForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.VERSE_ID:
                return new VerseForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.STRIKE_ID:
                return new StrikeForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.SWIFT_ID:
                return new SwiftForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.ACH_TRANSFER_ID:
                return new AchTransferForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.DOMESTIC_WIRE_TRANSFER_ID:
                return new DomesticWireTransferForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.PAYPAL_ID:
                return new PayPalForm(paymentAccount, accountAgeWitnessService, paypalValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.VENMO_ID:
                return new VenmoForm(paymentAccount, accountAgeWitnessService, venmoValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.CASH_APP_ID:
                return new CashAppForm(paymentAccount, accountAgeWitnessService, cashAppValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.PAYSAFE_ID:
                return new PaysafeForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.BLIK_ID:
                return new BlikForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.VIPPS_MOBILEPAY_ID:
                return new VippsMobilePayForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.TWINT_ID:
                return new TwintForm(paymentAccount, accountAgeWitnessService, twintValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.NIP_ID:
                return new NipForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.PAGO_MOVIL_ID:
                return new PagoMovilForm(paymentAccount, accountAgeWitnessService, pagoMovilValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.QRIS_ID:
                return new QrisForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.PAPARA_ID:
                return new PaparaForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.RAAST_ID:
                return new RaastForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.PAYNOW_ID:
                return new PayNowForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.WERO_ID:
                return new WeroForm(paymentAccount, accountAgeWitnessService, weroValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.FPS_ID:
                return new FpsForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.DUITNOW_ID:
                return new DuitNowForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.MB_WAY_ID:
                return new MbWayForm(paymentAccount, accountAgeWitnessService, mbWayValidator, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.PAYPAY_ID:
                return new PayPayForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.SBP_ID:
                return new SbpForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.MIR_ID:
                return new MirForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.YOOMONEY_ID:
                return new YooMoneyForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.UKRAINE_CARD_ID:
                return new UkraineCardForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.KASPI_ID:
                return new KaspiForm(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
            case PaymentMethod.BLOCK_CHAINS_ID:
            case PaymentMethod.BLOCK_CHAINS_INSTANT_ID:
                return new AssetsForm(paymentAccount, accountAgeWitnessService, cryptoAddressValidator, assetInputValidator, gridPane, gridRow, formatter, filterManager);
            default:
                log.error("Not supported PaymentMethod: " + paymentAccount.getPaymentMethod());
                return null;
        }
    }

    // Shows the info for a new account of this kind, then runs the handler once the user confirmed it.
    public void confirmNewAccount(PaymentAccount paymentAccount, Runnable resultHandler) {
        if (paymentAccount.getPaymentMethod().isBlockchain()) {
            if (paymentAccount.getSelectedTradeCurrency() == null) return;
            showAssetAccountInfo(paymentAccount);
            resultHandler.run();
            return;
        }

        BigInteger maxTradeLimit = paymentAccount.getPaymentMethod().getMaxTradeLimit("USD");
        BigInteger maxTradeLimitSecondMonth = maxTradeLimit.divide(BigInteger.valueOf(2L));
        BigInteger maxTradeLimitFirstMonth = maxTradeLimit.divide(BigInteger.valueOf(4L));
        if (paymentAccount instanceof F2FAccount) {
            new Popup().information(Res.get("payment.f2f.info"))
                    .width(700)
                    .closeButtonText(Res.get("payment.f2f.info.openURL"))
                    .onClose(() -> GUIUtil.openWebPage("https://docs.haveno.exchange/overview/payment-methods/F2F"))
                    .actionButtonText(Res.get("shared.iUnderstand"))
                    .onAction(resultHandler)
                    .show();
        } else if (paymentAccount instanceof PayByMailAccount) {
            // PayByMail has no chargeback risk so we don't show the text from payment.limits.info.
            new Popup().information(Res.get("payment.payByMail.info"))
                    .width(850)
                    .closeButtonText(Res.get("shared.cancel"))
                    .actionButtonText(Res.get("shared.iUnderstand"))
                    .onAction(resultHandler)
                    .showScrollPane()
                    .show();
        } else if (paymentAccount instanceof CashAtAtmAccount) {
            // CashAtAtm has no chargeback risk so we don't show the text from payment.limits.info.
            new Popup().information(Res.get("payment.cashAtAtm.info"))
                    .width(850)
                    .closeButtonText(Res.get("shared.cancel"))
                    .actionButtonText(Res.get("shared.iUnderstand"))
                    .onAction(resultHandler)
                    .show();
        } else if (paymentAccount instanceof HalCashAccount) {
            // HalCash has no chargeback risk so we don't show the text from payment.limits.info.
            new Popup().information(Res.get("payment.halCash.info"))
                    .width(700)
                    .closeButtonText(Res.get("shared.cancel"))
                    .actionButtonText(Res.get("shared.iUnderstand"))
                    .onAction(resultHandler)
                    .show();
        } else {

            String limitsInfoKey = "payment.limits.info";
            String initialLimit = HavenoUtils.formatXmr(maxTradeLimitFirstMonth, true);

            if (PaymentMethod.hasChargebackRisk(paymentAccount.getPaymentMethod(), paymentAccount.getTradeCurrencies())) {
                limitsInfoKey = "payment.limits.info.withSigning";
                initialLimit = HavenoUtils.formatXmr(OfferRestrictions.TOLERATED_SMALL_TRADE_AMOUNT, true);
            }

            new Popup().information(Res.get(limitsInfoKey,
                    initialLimit,
                    HavenoUtils.formatXmr(maxTradeLimitSecondMonth, true),
                    HavenoUtils.formatXmr(maxTradeLimit, true)))
                    .width(700)
                    .closeButtonText(Res.get("shared.cancel"))
                    .actionButtonText(Res.get("shared.iUnderstand"))
                    .onAction(() -> {
                        final String currencyName = Config.baseCurrencyNetwork().getCurrencyName();
                        if (paymentAccount instanceof ZelleAccount) {
                            new Popup().information(Res.get("payment.zelle.info", currencyName, currencyName))
                                    .width(900)
                                    .closeButtonText(Res.get("shared.cancel"))
                                    .actionButtonText(Res.get("shared.iConfirm"))
                                    .onAction(resultHandler)
                                    .show();
                        } else if (paymentAccount instanceof WesternUnionAccount) {
                            new Popup().information(Res.get("payment.westernUnion.info"))
                                    .width(700)
                                    .closeButtonText(Res.get("shared.cancel"))
                                    .actionButtonText(Res.get("shared.iUnderstand"))
                                    .onAction(resultHandler)
                                    .show();
                        } else if (paymentAccount instanceof MoneyGramAccount) {
                            new Popup().information(Res.get("payment.moneyGram.info"))
                                    .width(700)
                                    .closeButtonText(Res.get("shared.cancel"))
                                    .actionButtonText(Res.get("shared.iUnderstand"))
                                    .onAction(resultHandler)
                                    .show();
                        } else if (paymentAccount instanceof CashDepositAccount) {
                            new Popup().information(Res.get("payment.cashDeposit.info"))
                                    .width(700)
                                    .closeButtonText(Res.get("shared.cancel"))
                                    .actionButtonText(Res.get("shared.iConfirm"))
                                    .onAction(resultHandler)
                                    .show();
                        } else if (paymentAccount instanceof RevolutAccount) {
                            new Popup().information(Res.get("payment.revolut.info"))
                                    .width(700)
                                    .closeButtonText(Res.get("shared.cancel"))
                                    .actionButtonText(Res.get("shared.iConfirm"))
                                    .onAction(resultHandler)
                                    .show();
                        } else if (paymentAccount instanceof USPostalMoneyOrderAccount) {
                            new Popup().information(Res.get("payment.usPostalMoneyOrder.info"))
                                    .width(700)
                                    .closeButtonText(Res.get("shared.cancel"))
                                    .actionButtonText(Res.get("shared.iUnderstand"))
                                    .onAction(resultHandler)
                                    .show();
                        } else if (paymentAccount instanceof AustraliaPayidAccount) {
                            new Popup().information(Res.get("payment.payid.info", currencyName, currencyName))
                                    .width(900)
                                    .closeButtonText(Res.get("shared.cancel"))
                                    .actionButtonText(Res.get("shared.iConfirm"))
                                    .onAction(resultHandler)
                                    .show();
                        } else if (paymentAccount instanceof AmazonGiftCardAccount) {
                            new Popup().information(Res.get("payment.amazonGiftCard.info", currencyName, currencyName))
                                    .width(900)
                                    .closeButtonText(Res.get("shared.cancel"))
                                    .actionButtonText(Res.get("shared.iUnderstand"))
                                    .onAction(resultHandler)
                                    .show();
                        } else if (paymentAccount instanceof CashAppAccount) {
                            new Popup().warning(Res.get("payment.cashapp.info"))
                                    .width(700)
                                    .closeButtonText(Res.get("shared.cancel"))
                                    .actionButtonText(Res.get("shared.iUnderstand"))
                                    .onAction(resultHandler)
                                    .show();
                        } else if (paymentAccount instanceof VenmoAccount) {
                            new Popup().warning(Res.get("payment.venmo.info"))
                                    .width(700)
                                    .closeButtonText(Res.get("shared.cancel"))
                                    .actionButtonText(Res.get("shared.iUnderstand"))
                                    .onAction(resultHandler)
                                    .show();
                        } else if (paymentAccount instanceof PayPalAccount) {
                            new Popup().warning(Res.get("payment.paypal.info"))
                                    .width(700)
                                    .closeButtonText(Res.get("shared.cancel"))
                                    .actionButtonText(Res.get("shared.iUnderstand"))
                                    .onAction(resultHandler)
                                    .show();
                        } else if (paymentAccount instanceof PaysafeAccount) {
                            new Popup().warning(Res.get("payment.paysafe.info"))
                                    .width(700)
                                    .closeButtonText(Res.get("shared.cancel"))
                                    .actionButtonText(Res.get("shared.iUnderstand"))
                                    .onAction(resultHandler)
                                    .show();
                        } else {
                            resultHandler.run();
                        }
                    })
                    .show();
        }
    }

    private void showAssetAccountInfo(PaymentAccount paymentAccount) {
        TradeCurrency selectedTradeCurrency = paymentAccount.getSelectedTradeCurrency();
        if (selectedTradeCurrency instanceof CryptoCurrency && ((CryptoCurrency) selectedTradeCurrency).isAsset()) {
            String name = selectedTradeCurrency.getName();
            new Popup().information(Res.get("account.crypto.popup.wallet.msg",
                    selectedTradeCurrency.getCodeAndName(),
                    name,
                    name))
                    .closeButtonText(Res.get("account.crypto.popup.wallet.confirm"))
                    .show();
        }

        final Optional<Asset> asset = CurrencyUtil.findAsset(selectedTradeCurrency.getCode());
        if (asset.isPresent()) {
            final CryptoAccountDisclaimer disclaimerAnnotation = asset.get().getClass().getAnnotation(CryptoAccountDisclaimer.class);
            if (disclaimerAnnotation != null) {
                new Popup()
                        .width(asset.get() instanceof Monero ? 1000 : 669)
                        .maxMessageLength(2500)
                        .information(Res.get(disclaimerAnnotation.value()))
                        .useIUnderstandButton()
                        .show();
            }
        }
    }
}
