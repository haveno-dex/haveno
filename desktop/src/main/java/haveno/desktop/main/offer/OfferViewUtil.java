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

package haveno.desktop.main.offer;

import haveno.common.UserThread;
import haveno.common.util.Tuple2;
import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.Navigation;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.HyperlinkWithIcon;
import haveno.desktop.main.offer.offerbook.BtcOfferBookView;
import haveno.desktop.main.offer.offerbook.OfferBookView;
import haveno.desktop.main.offer.offerbook.OtherOfferBookView;
import haveno.desktop.main.offer.offerbook.TopCryptoOfferBookView;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.util.GUIUtil;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import lombok.extern.slf4j.Slf4j;
import monero.daemon.model.MoneroSubmitTxResult;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Objects;
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

    public static Tuple2<AutoTooltipButton, HBox> createBuyBsqButtonBox(Navigation navigation) {
        String buyBsqText = Res.get("shared.buyCurrency", "BSQ");
        var buyBsqButton = new AutoTooltipButton(buyBsqText);
        buyBsqButton.getStyleClass().add("action-button");
        buyBsqButton.getStyleClass().add("tiny-button");
        buyBsqButton.setMinWidth(60);
        buyBsqButton.setOnAction(e -> openBuyBsqOfferBook(navigation)
        );

        var info = new AutoTooltipLabel("BSQ is colored BTC that helps fund Haveno developers.");
        var learnMore = new HyperlinkWithIcon("Learn More");
        learnMore.setOnAction(e -> new Popup().headLine(buyBsqText)
                .information(Res.get("createOffer.buyBsq.popupMessage"))
                .actionButtonText(buyBsqText)
                .buttonAlignment(HPos.CENTER)
                .onAction(() -> openBuyBsqOfferBook(navigation)).show());
        learnMore.setMinWidth(100);

        HBox buyBsqBox = new HBox(buyBsqButton, info, learnMore);
        buyBsqBox.setAlignment(Pos.BOTTOM_LEFT);
        buyBsqBox.setSpacing(10);
        buyBsqBox.setPadding(new Insets(0, 0, 4, -20));

        return new Tuple2<>(buyBsqButton, buyBsqBox);
    }

    private static void openBuyBsqOfferBook(Navigation navigation) {
        throw new Error("BSQ not supported");
    }

    public static Class<? extends OfferBookView<?, ?>> getOfferBookViewClass(String currencyCode) {
        Class<? extends OfferBookView<?, ?>> offerBookViewClazz;
        if (CurrencyUtil.isTraditionalCurrency(currencyCode)) {
            offerBookViewClazz = BtcOfferBookView.class;
        } else if (currencyCode.equals(GUIUtil.TOP_CRYPTO.getCode())) {
            offerBookViewClazz = TopCryptoOfferBookView.class;
        } else {
            offerBookViewClazz = OtherOfferBookView.class;
        }
        return offerBookViewClazz;
    }

    public static boolean isShownAsSellOffer(Offer offer) {
        return isShownAsSellOffer(offer.getCurrencyCode(), offer.getDirection());
    }

    public static boolean isShownAsSellOffer(TradeCurrency tradeCurrency, OfferDirection direction) {
        return isShownAsSellOffer(tradeCurrency.getCode(), direction);
    }

    public static boolean isShownAsSellOffer(String currencyCode, OfferDirection direction) {
        return CurrencyUtil.isTraditionalCurrency(currencyCode) == (direction == OfferDirection.SELL);
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

    @NotNull
    public static Stream<CryptoCurrency> getMainCryptoCurrencies() {
        return CurrencyUtil.getMainCryptoCurrencies().stream().filter(cryptoCurrency ->
                !Objects.equals(cryptoCurrency.getCode(), GUIUtil.TOP_CRYPTO.getCode()));
    }

    public static void submitTransactionHex(XmrWalletService xmrWalletService,
                                             TableView tableView,
                                             String reserveTxHex) {
        MoneroSubmitTxResult result = xmrWalletService.getDaemon().submitTxHex(reserveTxHex);
        log.info("submitTransactionHex: reserveTxHex={} result={}", result);
        tableView.refresh();

        if(result.isGood()) {
            new Popup().information(Res.get("support.result.success")).show();
        } else {
            new Popup().attention(result.toString()).show();
        }
    }
}
