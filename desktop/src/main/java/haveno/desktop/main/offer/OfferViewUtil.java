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

package haveno.desktop.main.offer;

import com.google.common.base.Joiner;
import haveno.common.UserThread;
import haveno.core.locale.BankUtil;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.main.offer.offerbook.FiatOfferBookView;
import haveno.desktop.main.offer.offerbook.OfferBookView;
import haveno.desktop.main.offer.offerbook.CryptoOfferBookView;
import haveno.desktop.main.offer.offerbook.OtherOfferBookView;
import haveno.desktop.main.overlays.popups.Popup;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import lombok.extern.slf4j.Slf4j;
import monero.daemon.model.MoneroSubmitTxResult;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

// Shared utils for Views
@Slf4j
public class OfferViewUtil {

    public static Label createPopOverLabel(String text) {
        final Label label = new Label(text);
        label.setPrefWidth(300);
        label.setWrapText(true);
        label.setLineSpacing(1);
        label.setPadding(new Insets(10));
        return label;
    }

    public static void showPaymentAccountWarning(String msgKey,
                                                 HashMap<String, Boolean> paymentAccountWarningDisplayed) {
        if (msgKey == null || paymentAccountWarningDisplayed.getOrDefault(msgKey, false)) {
            return;
        }
        paymentAccountWarningDisplayed.put(msgKey, true);
        UserThread.runAfter(() -> {
            new Popup().information(Res.get(msgKey))
                    .width(900)
                    .closeButtonText(Res.get("shared.iConfirm"))
                    .dontShowAgainId(msgKey)
                    .show();
        }, 500, TimeUnit.MILLISECONDS);
    }

    public static void addPayInfoEntry(GridPane infoGridPane, int row, String labelText, String value) {
        Label label = new AutoTooltipLabel(labelText);
        TextField textField = new TextField(value);
        textField.setMinWidth(500);
        textField.setEditable(false);
        textField.setFocusTraversable(false);
        textField.setId("payment-info");
        GridPane.setConstraints(label, 0, row, 1, 1, HPos.RIGHT, VPos.CENTER);
        GridPane.setConstraints(textField, 1, row);
        infoGridPane.getChildren().addAll(label, textField);
    }

    public static Class<? extends OfferBookView<?, ?>> getOfferBookViewClass(String currencyCode) {
        Class<? extends OfferBookView<?, ?>> offerBookViewClazz;
        if (CurrencyUtil.isFiatCurrency(currencyCode)) {
            offerBookViewClazz = FiatOfferBookView.class;
        } else if (CurrencyUtil.isCryptoCurrency(currencyCode)) {
            offerBookViewClazz = CryptoOfferBookView.class;
        } else {
            offerBookViewClazz = OtherOfferBookView.class;
        }
        return offerBookViewClazz;
    }

    public static String getPaymentMethod(Offer offer) {
        if (isF2F(offer)) {
            return offer.getPaymentMethod().getShortName() + " (" + offer.getCountryCode() + ", " + offer.getF2FCity() + ")";
        } else {
            return offer.getPaymentMethodNameWithCountryCode();
        }
    }

    public static String getPaymentMethodToolTip(Offer offer) {
        String result = Res.getWithCol("shared.paymentMethod") + " " + Res.get(offer.getPaymentMethod().getId());
        result += "\n" + Res.getWithCol("shared.currency") + " " + CurrencyUtil.getNameAndCode(offer.getCounterCurrencyCode());

        String countryCode = offer.getCountryCode();
        if (isF2F(offer)) {
            if (countryCode != null) {
                result += "\n" + Res.get("payment.f2f.offerbook.tooltip.countryAndCity",
                        CountryUtil.getNameByCode(countryCode), offer.getF2FCity());
            }
        } else {
            if (countryCode != null) {
                String bankId = offer.getBankId();
                if (bankId != null && !bankId.equals("null")) {
                    if (BankUtil.isBankIdRequired(countryCode))
                        result += "\n" + Res.get("offerbook.offerersBankId", bankId);
                    else if (BankUtil.isBankNameRequired(countryCode))
                        result += "\n" + Res.get("offerbook.offerersBankName", bankId);
                }
            }

            if (countryCode != null)
                result += "\n" + Res.get("offerbook.offerersBankSeat", CountryUtil.getNameByCode(countryCode));

            List<String> acceptedCountryCodes = offer.getAcceptedCountryCodes();
            List<String> acceptedBanks = offer.getAcceptedBankIds();
            if (acceptedCountryCodes != null && !acceptedCountryCodes.isEmpty()) {
                if (CountryUtil.containsAllSepaEuroCountries(acceptedCountryCodes))
                    result += "\n" + Res.get("offerbook.offerersAcceptedBankSeatsEuro");
                else
                    result += "\n" + Res.get("offerbook.offerersAcceptedBankSeats", CountryUtil.getNamesByCodesString(acceptedCountryCodes));
            } else if (acceptedBanks != null && !acceptedBanks.isEmpty()) {
                if (offer.getPaymentMethod().equals(PaymentMethod.SAME_BANK))
                    result += "\n" + Res.getWithCol("shared.bankName") + " " + acceptedBanks.get(0);
                else if (offer.getPaymentMethod().equals(PaymentMethod.SPECIFIC_BANKS))
                    result += "\n" + Res.getWithCol("shared.acceptedBanks") + " " + Joiner.on(", ").join(acceptedBanks);
            }
        }
        if (offer.getCombinedExtraInfo() != null && !offer.getCombinedExtraInfo().isEmpty())
            result += "\n" + Res.get("payment.shared.extraInfo.tooltip", offer.getCombinedExtraInfo());
        return result;
    }

    private static boolean isF2F(Offer offer) {
        return offer.getPaymentMethod().equals(PaymentMethod.F2F);
    }

    public static boolean isShownAsSellOffer(Offer offer) {
        return isShownAsSellOffer(offer.getCounterCurrencyCode(), offer.getDirection());
    }

    public static boolean isShownAsSellOffer(TradeCurrency tradeCurrency, OfferDirection direction) {
        return isShownAsSellOffer(tradeCurrency.getCode(), direction);
    }

    public static boolean isShownAsSellOffer(String currencyCode, OfferDirection direction) {
        return direction == OfferDirection.SELL;
    }

    public static boolean isShownAsBuyOffer(Offer offer) {
        return !isShownAsSellOffer(offer);
    }

    public static boolean isShownAsBuyOffer(OfferDirection direction, TradeCurrency tradeCurrency) {
        return !isShownAsSellOffer(tradeCurrency.getCode(), direction);
    }

    public static TradeCurrency getAnyOfMainCryptoCurrencies() {
        return getMainCryptoCurrencies().findAny().get();
    }

    public static TradeCurrency getAnyOfOtherCurrencies() {
        return getOtherCurrencies().findAny().get();
    }

    @NotNull
    public static Stream<CryptoCurrency> getMainCryptoCurrencies() {
        return CurrencyUtil.getMainCryptoCurrencies().stream();
    }

    @NotNull
    public static Stream<TraditionalCurrency> getOtherCurrencies() {
        return CurrencyUtil.getTraditionalNonFiatCurrencies().stream();
    }

    public static void submitTransactionHex(XmrWalletService xmrWalletService,
                                             TableView tableView,
                                             String reserveTxHex) {
        MoneroSubmitTxResult result = xmrWalletService.getMonerod().submitTxHex(reserveTxHex);
        log.info("submitTransactionHex: reserveTxHex={} result={}", result);
        tableView.refresh();

        if(result.isGood()) {
            new Popup().information(Res.get("support.result.success")).show();
        } else {
            new Popup().attention(result.toString()).show();
        }
    }
}
