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

package haveno.desktop.main.offer.takeoffer;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.jfoenix.controls.JFXTextField;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.common.util.Tuple2;
import haveno.common.util.Tuple3;
import haveno.common.util.Tuple4;
import haveno.common.util.Utilities;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.offer.Offer;
import haveno.core.payment.FasterPaymentsAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.DontShowAgainLookup;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.desktop.Navigation;
import haveno.desktop.common.view.ActivatableViewAndModel;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.AddressTextField;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.BalanceTextField;
import haveno.desktop.components.BusyAnimation;
import haveno.desktop.components.FundsTextField;
import haveno.desktop.components.InfoInputTextField;
import haveno.desktop.components.InputTextField;
import haveno.desktop.components.TitledGroupBg;
import haveno.desktop.main.MainView;
import haveno.desktop.main.funds.FundsView;
import haveno.desktop.main.funds.withdrawal.WithdrawalView;
import haveno.desktop.main.offer.ClosableView;
import haveno.desktop.main.offer.InitializableViewWithTakeOfferData;
import haveno.desktop.main.offer.OfferView;
import haveno.desktop.main.offer.OfferViewUtil;
import static haveno.desktop.main.offer.OfferViewUtil.addPayInfoEntry;
import haveno.desktop.main.offer.SelectableView;
import haveno.desktop.main.overlays.notifications.Notification;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.GenericMessageWindow;
import haveno.desktop.main.overlays.windows.OfferDetailsWindow;
import haveno.desktop.main.overlays.windows.QRCodeWindow;
import haveno.desktop.main.portfolio.PortfolioView;
import haveno.desktop.main.portfolio.pendingtrades.PendingTradesView;
import static haveno.desktop.util.FormBuilder.add2ButtonsWithBox;
import static haveno.desktop.util.FormBuilder.addAddressTextField;
import static haveno.desktop.util.FormBuilder.addBalanceTextField;
import static haveno.desktop.util.FormBuilder.addComboBoxTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextArea;
import static haveno.desktop.util.FormBuilder.addFundsTextfield;
import static haveno.desktop.util.FormBuilder.addTitledGroupBg;
import static haveno.desktop.util.FormBuilder.getEditableValueBox;
import static haveno.desktop.util.FormBuilder.getIconForLabel;
import static haveno.desktop.util.FormBuilder.getNonEditableValueBox;
import static haveno.desktop.util.FormBuilder.getNonEditableValueBoxWithInfo;
import static haveno.desktop.util.FormBuilder.getSmallIconForLabel;
import static haveno.desktop.util.FormBuilder.getTopLabelWithVBox;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.Layout;
import haveno.desktop.util.Transitions;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.net.URI;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import static javafx.beans.binding.Bindings.createStringBinding;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import net.glxn.qrgen.QRCode;
import net.glxn.qrgen.image.ImageType;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;
import org.jetbrains.annotations.NotNull;

@FxmlView
public class TakeOfferView extends ActivatableViewAndModel<AnchorPane, TakeOfferViewModel> implements ClosableView, InitializableViewWithTakeOfferData, SelectableView {
    private final Navigation navigation;
    private final CoinFormatter formatter;
    private final OfferDetailsWindow offerDetailsWindow;
    private final Transitions transitions;

    private ScrollPane scrollPane;
    private GridPane gridPane;
    private TitledGroupBg noFundingRequiredTitledGroupBg;
    private Label noFundingRequiredLabel;
    private int gridRowNoFundingRequired;
    private TitledGroupBg payFundsTitledGroupBg;
    private TitledGroupBg advancedOptionsGroup;
    private VBox priceAsPercentageInputBox, amountRangeBox;
    private HBox fundingHBox, amountValueCurrencyBox, priceValueCurrencyBox, volumeValueCurrencyBox,
            priceAsPercentageValueCurrencyBox, minAmountValueCurrencyBox, advancedOptionsBox,
            takeOfferBox, nextButtonBox, firstRowHBox;
    private ComboBox<PaymentAccount> paymentAccountsComboBox;
    private TextArea extraInfoTextArea;
    private Label amountDescriptionLabel,
            paymentMethodLabel,
            priceCurrencyLabel, priceAsPercentageLabel,
            volumeCurrencyLabel, priceDescriptionLabel, volumeDescriptionLabel,
            waitingForFundsLabel, offerAvailabilityLabel, priceAsPercentageDescription,
            tradeFeeDescriptionLabel, resultLabel, tradeFeeInXmrLabel, xLabel,
            fakeXLabel, extraInfoLabel;
    private InputTextField amountTextField;
    private TextField paymentMethodTextField, currencyTextField, priceTextField, priceAsPercentageTextField,
            volumeTextField, amountRangeTextField;
    private FundsTextField totalToPayTextField;
    private AddressTextField addressTextField;
    private BalanceTextField balanceTextField;
    private Text xIcon, fakeXIcon;
    private Button nextButton, cancelButton1, cancelButton2;
    private AutoTooltipButton takeOfferButton, fundFromSavingsWalletButton;
    private ImageView qrCodeImageView;
    private BusyAnimation waitingForFundsBusyAnimation, offerAvailabilityBusyAnimation;
    private Notification walletFundedNotification;
    private OfferView.CloseHandler closeHandler;
    private Subscription balanceSubscription,
            showTransactionPublishedScreenSubscription, showWarningInvalidBtcDecimalPlacesSubscription,
            isWaitingForFundsSubscription, offerWarningSubscription, errorMessageSubscription,
            isOfferAvailableSubscription;
    private ChangeListener<BigInteger> missingCoinListener;

    private int gridRow = 0;
    private final HashMap<String, Boolean> paymentAccountWarningDisplayed = new HashMap<>();
    private boolean offerDetailsWindowDisplayed, extraInfoPopupDisplayed, zelleWarningDisplayed, fasterPaymentsWarningDisplayed,
            takeOfferFromUnsignedAccountWarningDisplayed, payByMailWarningDisplayed, cashAtAtmWarningDisplayed,
            australiaPayidWarningDisplayed, paypalWarningDisplayed, cashAppWarningDisplayed, F2FWarningDisplayed;
    private SimpleBooleanProperty errorPopupDisplayed;
    private ChangeListener<Boolean> amountFocusedListener, getShowWalletFundedNotificationListener;

    private InfoInputTextField volumeInfoTextField;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    private TakeOfferView(TakeOfferViewModel model,
                          Navigation navigation,
                          @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter,
                          OfferDetailsWindow offerDetailsWindow,
                          Transitions transitions) {
        super(model);

        this.navigation = navigation;
        this.formatter = formatter;
        this.offerDetailsWindow = offerDetailsWindow;
        this.transitions = transitions;
    }

    @Override
    protected void initialize() {
        addScrollPane();
        addGridPane();
        addPaymentGroup();
        addAmountPriceGroup();
        addOptionsGroup();

        createListeners();

        addNextButtons();
        addOfferAvailabilityLabel();
        addFundingGroup();

        balanceTextField.setFormatter(model.getXmrFormatter());

        amountFocusedListener = (o, oldValue, newValue) -> {
            model.onFocusOutAmountTextField(oldValue, newValue, amountTextField.getText());
            amountTextField.setText(model.amount.get());
        };

        getShowWalletFundedNotificationListener = (observable, oldValue, newValue) -> {
            if (newValue) {
                Notification walletFundedNotification = new Notification()
                        .headLine(Res.get("notification.walletUpdate.headline"))
                        .notification(Res.get("notification.walletUpdate.msg", HavenoUtils.formatXmr(model.dataModel.getTotalToPay().get(), true)))
                        .autoClose();

                walletFundedNotification.show();
            }
        };

        GUIUtil.focusWhenAddedToScene(amountTextField);
    }

    @Override
    protected void activate() {
        addBindings();
        addSubscriptions();
        addListeners();

        if (offerAvailabilityBusyAnimation != null && !model.showPayFundsScreenDisplayed.get()) {
            // temporarily disabled due to high CPU usage (per issue #4649)
            //    offerAvailabilityBusyAnimation.play();
            offerAvailabilityLabel.setVisible(true);
            offerAvailabilityLabel.setManaged(true);
        } else {
            offerAvailabilityLabel.setVisible(false);
            offerAvailabilityLabel.setManaged(false);
        }

        if (waitingForFundsBusyAnimation != null && model.isWaitingForFunds.get()) {
            // temporarily disabled due to high CPU usage (per issue #4649)
            //    waitingForFundsBusyAnimation.play();
            waitingForFundsLabel.setVisible(true);
            waitingForFundsLabel.setManaged(true);
        } else {
            waitingForFundsLabel.setVisible(false);
            waitingForFundsLabel.setManaged(false);
        }

        String currencyCode = model.dataModel.getCurrencyCode();
        volumeCurrencyLabel.setText(currencyCode);
        priceDescriptionLabel.setText(CurrencyUtil.getPriceWithCurrencyCode(currencyCode));
        volumeDescriptionLabel.setText(model.volumeDescriptionLabel.get());

        PaymentAccount lastPaymentAccount = model.getLastSelectedPaymentAccount();

        if (model.getPossiblePaymentAccounts().size() > 1) {
            new Popup().headLine(Res.get("popup.info.multiplePaymentAccounts.headline"))
                    .information(Res.get("popup.info.multiplePaymentAccounts.msg"))
                    .dontShowAgainId("MultiplePaymentAccountsAvailableWarning")
                    .show();

            paymentAccountsComboBox.setItems(model.getPossiblePaymentAccounts());
            paymentAccountsComboBox.getSelectionModel().select(lastPaymentAccount);
            model.onPaymentAccountSelected(lastPaymentAccount);
        }

        balanceTextField.setTargetAmount(model.dataModel.getTotalToPay().get());

        maybeShowExtraInfoPopup(model.dataModel.getOffer());
        maybeShowTakeOfferFromUnsignedAccountWarning(model.dataModel.getOffer());
        maybeShowZelleWarning(lastPaymentAccount);
        maybeShowFasterPaymentsWarning(lastPaymentAccount);
        maybeShowAccountWarning(lastPaymentAccount, model.dataModel.isBuyOffer());

        if (!model.isRange()) {
            nextButton.setVisible(false);
            cancelButton1.setVisible(false);
            if (model.isOfferAvailable.get())
                showNextStepAfterAmountIsSet();
        }
    }

    @Override
    protected void deactivate() {
        removeBindings();
        removeSubscriptions();
        removeListeners();

        if (offerAvailabilityBusyAnimation != null)
            offerAvailabilityBusyAnimation.stop();

        if (waitingForFundsBusyAnimation != null)
            waitingForFundsBusyAnimation.stop();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void initWithData(Offer offer) {
        model.initWithData(offer);
        priceAsPercentageInputBox.setVisible(offer.isUseMarketBasedPrice());

        if (OfferViewUtil.isShownAsSellOffer(model.getOffer())) {
            takeOfferButton.setId("buy-button-big");
            nextButton.setId("buy-button");
            fundFromSavingsWalletButton.setId("buy-button");
            takeOfferButton.updateText(getTakeOfferLabel(offer, false));
        } else {
            takeOfferButton.setId("sell-button-big");
            nextButton.setId("sell-button");
            fundFromSavingsWalletButton.setId("sell-button");
            takeOfferButton.updateText(getTakeOfferLabel(offer, true));
        }
        priceAsPercentageDescription.setText(model.getPercentagePriceDescription());

        boolean showComboBox = model.getPossiblePaymentAccounts().size() > 1;
        paymentAccountsComboBox.setVisible(showComboBox);
        paymentAccountsComboBox.setManaged(showComboBox);
        paymentAccountsComboBox.setMouseTransparent(!showComboBox);
        paymentMethodTextField.setVisible(!showComboBox);
        paymentMethodTextField.setManaged(!showComboBox);
        paymentMethodLabel.setVisible(!showComboBox);
        paymentMethodLabel.setManaged(!showComboBox);

        if (!showComboBox) {
            paymentMethodTextField.setText(model.getPossiblePaymentAccounts().get(0).getAccountName());
        }

        currencyTextField.setText(model.dataModel.getCurrencyNameAndCode());
        amountDescriptionLabel.setText(model.getAmountDescription());

        if (model.isRange()) {
            amountRangeTextField.setText(model.getAmountRange());
            amountRangeBox.setVisible(true);
        } else {
            amountTextField.setDisable(true);
        }

        priceTextField.setText(model.getPrice());
        priceAsPercentageTextField.setText(model.marketPriceMargin);
        addressTextField.setPaymentLabel(model.getPaymentLabel());
        addressTextField.setAddress(model.dataModel.getAddressEntry().getAddressString());

        if (offer.isFiatOffer()) {
            Label popOverLabel = OfferViewUtil.createPopOverLabel(Res.get("offerbook.info.roundedFiatVolume"));
            volumeInfoTextField.setContentForPrivacyPopOver(popOverLabel);
        }

        if (offer.getPrice() == null)
            new Popup().warning(Res.get("takeOffer.noPriceFeedAvailable"))
                    .onClose(() -> close(false))
                    .show();

        if (offer.hasBuyerAsTakerWithoutDeposit() && offer.getCombinedExtraInfo() != null && !offer.getCombinedExtraInfo().isEmpty()) {

            // attach extra info text area
            //updateOfferElementsStyle();
            Tuple2<Label, TextArea> extraInfoTuple = addCompactTopLabelTextArea(gridPane, ++gridRowNoFundingRequired, Res.get("payment.shared.extraInfo.noDeposit"), "");
            extraInfoLabel = extraInfoTuple.first;
            extraInfoLabel.setVisible(false);
            extraInfoLabel.setManaged(false);
            extraInfoTextArea = extraInfoTuple.second;
            extraInfoTextArea.setVisible(false);
            extraInfoTextArea.setManaged(false);
            extraInfoTextArea.setText(offer.getCombinedExtraInfo().trim());
            extraInfoTextArea.getStyleClass().add("text-area");
            extraInfoTextArea.setWrapText(true);
            extraInfoTextArea.setMaxHeight(300);
            extraInfoTextArea.setEditable(false);
            GUIUtil.adjustHeightAutomatically(extraInfoTextArea);
            GridPane.setRowIndex(extraInfoTextArea, gridRowNoFundingRequired);
            GridPane.setColumnSpan(extraInfoTextArea, GridPane.REMAINING);
            GridPane.setColumnIndex(extraInfoTextArea, 0);

            // move up take offer buttons
            GridPane.setRowIndex(takeOfferBox, gridRowNoFundingRequired + 1);
            GridPane.setMargin(takeOfferBox, new Insets(15, 0, 0, 0));
        }
    }

    @Override
    public void setCloseHandler(OfferView.CloseHandler closeHandler) {
        this.closeHandler = closeHandler;
    }

    // Called from parent as the view does not get notified when the tab is closed
    public void onClose() {
        BigInteger availableBalance = model.dataModel.getAvailableBalance().get();
        if (availableBalance != null && availableBalance.compareTo(BigInteger.ZERO) > 0 && !model.takeOfferCompleted.get() && !DevEnv.isDevMode()) {
            model.dataModel.swapTradeToSavings();
        }
    }

    @Override
    public void onTabSelected(boolean isSelected) {
        model.dataModel.onTabSelected(isSelected);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI actions
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onTakeOffer() {
        if (!model.dataModel.canTakeOffer()) {
            return;
        }

        if (DevEnv.isDevMode()) {
            balanceSubscription.unsubscribe();
            model.onTakeOffer(() -> {
            });
            return;
        }

        offerDetailsWindow.onTakeOffer(() ->
                model.onTakeOffer(() -> {
                    offerDetailsWindow.hide();
                    offerDetailsWindowDisplayed = false;
                })
        ).show(model.getOffer(),
                model.dataModel.getAmount().get(),
                model.dataModel.tradePrice);

        offerDetailsWindowDisplayed = true;
    }

    private void onShowPayFundsScreen() {
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        nextButton.setVisible(false);
        nextButton.setManaged(false);
        nextButton.setOnAction(null);
        cancelButton1.setVisible(false);
        cancelButton1.setManaged(false);
        cancelButton1.setOnAction(null);
        offerAvailabilityBusyAnimation.stop();
        offerAvailabilityBusyAnimation.setVisible(false);
        offerAvailabilityBusyAnimation.setManaged(false);
        offerAvailabilityLabel.setVisible(false);
        offerAvailabilityLabel.setManaged(false);

        int delay = 500;
        int diff = 100;

        transitions.fadeOutAndRemove(advancedOptionsGroup, delay, (event) -> {
        });
        delay -= diff;
        transitions.fadeOutAndRemove(advancedOptionsBox, delay);

        model.onShowPayFundsScreen();

        amountTextField.setMouseTransparent(true);
        amountTextField.setDisable(false);
        amountTextField.setFocusTraversable(false);

        amountRangeTextField.setMouseTransparent(true);
        amountRangeTextField.setDisable(false);
        amountRangeTextField.setFocusTraversable(false);

        priceTextField.setMouseTransparent(true);
        priceTextField.setDisable(false);
        priceTextField.setFocusTraversable(false);

        priceAsPercentageTextField.setMouseTransparent(true);
        priceAsPercentageTextField.setDisable(false);
        priceAsPercentageTextField.setFocusTraversable(false);

        volumeTextField.setMouseTransparent(true);
        volumeTextField.setDisable(false);
        volumeTextField.setFocusTraversable(false);

        updateOfferElementsStyle();

        balanceTextField.setTargetAmount(model.dataModel.getTotalToPay().get());

        if (!DevEnv.isDevMode() && model.dataModel.hasTotalToPay()) {
            String tradeAmountText = model.isSeller() ? Res.get("takeOffer.takeOfferFundWalletInfo.tradeAmount", model.getTradeAmount()) : "";
            String message = Res.get("takeOffer.takeOfferFundWalletInfo.msg",
                    model.getTotalToPayInfo(),
                    tradeAmountText,
                    model.getSecurityDepositInfo(),
                    model.getTradeFee()
            );
            String key = "takeOfferFundWalletInfo";
            new Popup().headLine(Res.get("takeOffer.takeOfferFundWalletInfo.headline"))
                    .instruction(message)
                    .dontShowAgainId(key)
                    .show();
        }

        cancelButton2.setVisible(true);

        // temporarily disabled due to high CPU usage (per issue #4649)
        //waitingForFundsBusyAnimation.play();

        if (model.getOffer().hasBuyerAsTakerWithoutDeposit()) {
            noFundingRequiredTitledGroupBg.setVisible(true);
            noFundingRequiredLabel.setVisible(true);
            extraInfoLabel.setVisible(true);
            extraInfoLabel.setManaged(true);
            extraInfoTextArea.setVisible(true);
            extraInfoTextArea.setManaged(true);
        } else {
            payFundsTitledGroupBg.setVisible(true);
            totalToPayTextField.setVisible(true);
            addressTextField.setVisible(true);
            qrCodeImageView.setVisible(true);
            balanceTextField.setVisible(true);
        }

        totalToPayTextField.setFundsStructure(Res.get("takeOffer.fundsBox.fundsStructure",
                model.getSecurityDepositWithCode(), model.getTakerFeePercentage()));
        totalToPayTextField.setContentForInfoPopOver(createInfoPopover());

        if (model.dataModel.getIsXmrWalletFunded().get() && model.dataModel.hasTotalToPay()) {
            if (walletFundedNotification == null) {
                walletFundedNotification = new Notification()
                        .headLine(Res.get("notification.walletUpdate.headline"))
                        .notification(Res.get("notification.takeOffer.walletUpdate.msg", HavenoUtils.formatXmr(model.dataModel.getTotalToPay().get(), true)))
                        .autoClose();
                walletFundedNotification.show();
            }
        }

        updateQrCode();
    }

    private void updateQrCode() {
        final byte[] imageBytes = QRCode
                .from(getMoneroURI())
                .withSize(300, 300)
                .to(ImageType.PNG)
                .stream()
                .toByteArray();
        Image qrImage = new Image(new ByteArrayInputStream(imageBytes));
        qrCodeImageView.setImage(qrImage);
    }

    private void updateOfferElementsStyle() {
        GridPane.setColumnSpan(firstRowHBox, 1);

        final String activeInputStyle = "offer-input";
        final String readOnlyInputStyle = "offer-input-readonly";
        amountValueCurrencyBox.getStyleClass().remove(activeInputStyle);
        amountValueCurrencyBox.getStyleClass().add(readOnlyInputStyle);
        priceAsPercentageValueCurrencyBox.getStyleClass().remove(activeInputStyle);
        priceAsPercentageValueCurrencyBox.getStyleClass().add(readOnlyInputStyle);
        volumeValueCurrencyBox.getStyleClass().remove(activeInputStyle);
        volumeValueCurrencyBox.getStyleClass().add(readOnlyInputStyle);
        priceValueCurrencyBox.getStyleClass().remove(activeInputStyle);
        priceValueCurrencyBox.getStyleClass().add(readOnlyInputStyle);
        minAmountValueCurrencyBox.getStyleClass().remove(activeInputStyle);
        minAmountValueCurrencyBox.getStyleClass().add(readOnlyInputStyle);

        resultLabel.getStyleClass().add("small");
        xLabel.getStyleClass().add("small");
        xIcon.setStyle(String.format("-fx-font-family: %s; -fx-font-size: %s;", MaterialDesignIcon.CLOSE.fontFamily(), "1em"));
        fakeXIcon.setStyle(String.format("-fx-font-family: %s; -fx-font-size: %s;", MaterialDesignIcon.CLOSE.fontFamily(), "1em"));
        fakeXLabel.getStyleClass().add("small");
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Navigation
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void close() {
        close(true);
    }

    private void close(boolean removeOffer) {
        model.dataModel.onClose(removeOffer);
        if (closeHandler != null)
            closeHandler.close();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Bindings, Listeners
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void addBindings() {
        amountTextField.textProperty().bindBidirectional(model.amount);
        volumeTextField.textProperty().bindBidirectional(model.volume);
        totalToPayTextField.textProperty().bind(model.totalToPay);
        addressTextField.amountAsProperty().bind(model.dataModel.getMissingCoin());
        amountTextField.validationResultProperty().bind(model.amountValidationResult);
        priceCurrencyLabel.textProperty().bind(createStringBinding(() -> CurrencyUtil.getCounterCurrency(model.dataModel.getCurrencyCode())));
        priceAsPercentageLabel.prefWidthProperty().bind(priceCurrencyLabel.widthProperty());
        nextButton.disableProperty().bind(model.isNextButtonDisabled);
        tradeFeeInXmrLabel.textProperty().bind(model.tradeFeeInXmrWithFiat);
        tradeFeeDescriptionLabel.textProperty().bind(model.tradeFeeDescription);
        tradeFeeInXmrLabel.visibleProperty().bind(model.isTradeFeeVisible);
        tradeFeeDescriptionLabel.visibleProperty().bind(model.isTradeFeeVisible);
        tradeFeeDescriptionLabel.managedProperty().bind(tradeFeeDescriptionLabel.visibleProperty());

        // funding
        fundingHBox.visibleProperty().bind(model.dataModel.getIsXmrWalletFunded().not().and(model.showPayFundsScreenDisplayed));
        fundingHBox.managedProperty().bind(model.dataModel.getIsXmrWalletFunded().not().and(model.showPayFundsScreenDisplayed));
        waitingForFundsLabel.textProperty().bind(model.spinnerInfoText);
        takeOfferBox.visibleProperty().bind(model.dataModel.getIsXmrWalletFunded().and(model.showPayFundsScreenDisplayed));
        takeOfferBox.managedProperty().bind(model.dataModel.getIsXmrWalletFunded().and(model.showPayFundsScreenDisplayed));
        takeOfferButton.disableProperty().bind(model.isTakeOfferButtonDisabled);
    }

    private void removeBindings() {
        amountTextField.textProperty().unbindBidirectional(model.amount);
        volumeTextField.textProperty().unbindBidirectional(model.volume);
        totalToPayTextField.textProperty().unbind();
        addressTextField.amountAsProperty().unbind();
        amountTextField.validationResultProperty().unbind();
        priceCurrencyLabel.textProperty().unbind();
        priceAsPercentageLabel.prefWidthProperty().unbind();
        nextButton.disableProperty().unbind();
        tradeFeeInXmrLabel.textProperty().unbind();
        tradeFeeDescriptionLabel.textProperty().unbind();
        tradeFeeInXmrLabel.visibleProperty().unbind();
        tradeFeeDescriptionLabel.visibleProperty().unbind();
        tradeFeeDescriptionLabel.managedProperty().unbind();

        // funding
        fundingHBox.visibleProperty().unbind();
        fundingHBox.managedProperty().unbind();
        waitingForFundsLabel.textProperty().unbind();
        takeOfferBox.visibleProperty().unbind();
        takeOfferBox.managedProperty().unbind();
        takeOfferButton.disableProperty().unbind();
    }

    private void addSubscriptions() {
        errorPopupDisplayed = new SimpleBooleanProperty();
        offerWarningSubscription = EasyBind.subscribe(model.offerWarning, newValue -> {
            if (newValue != null) {
                if (offerDetailsWindowDisplayed)
                    offerDetailsWindow.hide();

                UserThread.runAfter(() -> new Popup().warning(newValue + "\n\n" +
                                Res.get("takeOffer.alreadyPaidInFunds"))
                        .actionButtonTextWithGoTo("funds.tab.withdrawal")
                        .onAction(() -> {
                            errorPopupDisplayed.set(true);
                            model.resetOfferWarning();
                            close();
                            navigation.navigateTo(MainView.class, FundsView.class, WithdrawalView.class);
                        })
                        .onClose(() -> {
                            errorPopupDisplayed.set(true);
                            model.resetOfferWarning();
                            close();
                        })
                        .show(), 100, TimeUnit.MILLISECONDS);
            }
        });

        errorMessageSubscription = EasyBind.subscribe(model.errorMessage, newValue -> {
            if (newValue != null) {
                new Popup().error(Res.get("takeOffer.error.message", model.errorMessage.get()))
                        .onClose(() -> {
                            errorPopupDisplayed.set(true);
                            model.resetErrorMessage();
                            close();
                        })
                        .show();
            }
        });

        isOfferAvailableSubscription = EasyBind.subscribe(model.isOfferAvailable, isOfferAvailable -> {
            if (isOfferAvailable) {
                offerAvailabilityBusyAnimation.stop();
                offerAvailabilityBusyAnimation.setVisible(false);
                if (!model.isRange() && !model.showPayFundsScreenDisplayed.get())
                    showNextStepAfterAmountIsSet();
            }

            offerAvailabilityLabel.setVisible(!isOfferAvailable);
            offerAvailabilityLabel.setManaged(!isOfferAvailable);
        });

        isWaitingForFundsSubscription = EasyBind.subscribe(model.isWaitingForFunds, isWaitingForFunds -> {
            // temporarily disabled due to high CPU usage (per issue #4649)
            //  waitingForFundsBusyAnimation.play();
            waitingForFundsLabel.setVisible(isWaitingForFunds);
            waitingForFundsLabel.setManaged(isWaitingForFunds);
        });

        showWarningInvalidBtcDecimalPlacesSubscription = EasyBind.subscribe(model.showWarningInvalidBtcDecimalPlaces, newValue -> {
            if (newValue) {
                new Popup().warning(Res.get("takeOffer.amountPriceBox.warning.invalidXmrDecimalPlaces")).show();
                model.showWarningInvalidBtcDecimalPlaces.set(false);
            }
        });

        showTransactionPublishedScreenSubscription = EasyBind.subscribe(model.showTransactionPublishedScreen, newValue -> {
            if (newValue && DevEnv.isDevMode()) {
                close();
            } else if (newValue && model.getTrade() != null && !model.getTrade().hasFailed()) {
                String key = "takeOfferSuccessInfo";
                if (DontShowAgainLookup.showAgain(key)) {
                    UserThread.runAfter(() -> new Popup().headLine(Res.get("takeOffer.success.headline"))
                            .feedback(Res.get("takeOffer.success.info"))
                            .actionButtonTextWithGoTo("portfolio.tab.pendingTrades")
                            .dontShowAgainId(key)
                            .onAction(() -> {
                                UserThread.runAfter(
                                        () -> navigation.navigateTo(MainView.class, PortfolioView.class, PendingTradesView.class)
                                        , 100, TimeUnit.MILLISECONDS);
                                close();
                            })
                            .onClose(this::close)
                            .show(), 1);
                } else {
                    close();
                }
            }
        });

        balanceSubscription = EasyBind.subscribe(model.dataModel.getAvailableBalance(), balanceTextField::setBalance);
    }

    private void removeSubscriptions() {
        offerWarningSubscription.unsubscribe();
        errorMessageSubscription.unsubscribe();
        isOfferAvailableSubscription.unsubscribe();
        isWaitingForFundsSubscription.unsubscribe();
        showWarningInvalidBtcDecimalPlacesSubscription.unsubscribe();
        showTransactionPublishedScreenSubscription.unsubscribe();
        // noSufficientFeeSubscription.unsubscribe();
        balanceSubscription.unsubscribe();
    }

    private void createListeners() {
        missingCoinListener = (observable, oldValue, newValue) -> {
            if (!newValue.toString().equals("")) {
                updateQrCode();
            }
        };
    }

    private void addListeners() {
        amountTextField.focusedProperty().addListener(amountFocusedListener);
        model.dataModel.getShowWalletFundedNotification().addListener(getShowWalletFundedNotificationListener);
        model.dataModel.getMissingCoin().addListener(missingCoinListener);
    }

    private void removeListeners() {
        amountTextField.focusedProperty().removeListener(amountFocusedListener);
        model.dataModel.getShowWalletFundedNotification().removeListener(getShowWalletFundedNotificationListener);
        model.dataModel.getMissingCoin().removeListener(missingCoinListener);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Build UI elements
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void addScrollPane() {
        scrollPane = GUIUtil.createScrollPane();
        root.getChildren().add(scrollPane);
    }

    private void addGridPane() {
        gridPane = new GridPane();
        gridPane.getStyleClass().add("content-pane");
        gridPane.setPadding(new Insets(15, 15, -1, 15));
        gridPane.setHgap(5);
        gridPane.setVgap(5);
        GUIUtil.setDefaultTwoColumnConstraintsForGridPane(gridPane);
        scrollPane.setContent(gridPane);
    }

    private void addPaymentGroup() {
        TitledGroupBg paymentAccountTitledGroupBg = addTitledGroupBg(gridPane, gridRow, 1, Res.get("offerbook.takeOffer"));
        GridPane.setColumnSpan(paymentAccountTitledGroupBg, 2);

        final Tuple4<ComboBox<PaymentAccount>, Label, TextField, HBox> paymentAccountTuple = addComboBoxTopLabelTextField(gridPane,
                gridRow, Res.get("shared.chooseTradingAccount"),
                Res.get("shared.chooseTradingAccount"), Layout.FIRST_ROW_DISTANCE);

        paymentAccountsComboBox = paymentAccountTuple.first;
        HBox.setMargin(paymentAccountsComboBox, new Insets(Layout.FLOATING_LABEL_DISTANCE, 0, 0, 0));
        paymentAccountsComboBox.setConverter(GUIUtil.getPaymentAccountsComboBoxStringConverter());
        paymentAccountsComboBox.setCellFactory(model.getPaymentAccountListCellFactory(paymentAccountsComboBox));
        paymentAccountsComboBox.setVisible(false);
        paymentAccountsComboBox.setManaged(false);
        paymentAccountsComboBox.setOnAction(e -> {
            PaymentAccount paymentAccount = paymentAccountsComboBox.getSelectionModel().getSelectedItem();
            if (paymentAccount != null) {
                maybeShowZelleWarning(paymentAccount);
                maybeShowFasterPaymentsWarning(paymentAccount);
                maybeShowAccountWarning(paymentAccount, model.dataModel.isBuyOffer());
            }
            model.onPaymentAccountSelected(paymentAccount);
        });

        paymentMethodLabel = paymentAccountTuple.second;
        paymentMethodTextField = paymentAccountTuple.third;
        paymentMethodTextField.setMinWidth(250);
        paymentMethodTextField.setEditable(false);
        paymentMethodTextField.setMouseTransparent(true);
        paymentMethodTextField.setFocusTraversable(false);

        currencyTextField = new JFXTextField();
        currencyTextField.setMinWidth(250);
        currencyTextField.setEditable(false);
        currencyTextField.setMouseTransparent(true);
        currencyTextField.setFocusTraversable(false);

        final Tuple2<Label, VBox> tradeCurrencyTuple = getTopLabelWithVBox(Res.get("shared.tradeCurrency"), currencyTextField);
        HBox.setMargin(tradeCurrencyTuple.second, new Insets(5, 0, 0, 0));

        final HBox hBox = paymentAccountTuple.fourth;
        hBox.setSpacing(30);
        hBox.setAlignment(Pos.CENTER_LEFT);
        hBox.setPadding(new Insets(10, 0, 18, 0));

        hBox.getChildren().add(tradeCurrencyTuple.second);
    }

    private void addAmountPriceGroup() {
        TitledGroupBg titledGroupBg = addTitledGroupBg(gridPane, ++gridRow, 2,
                Res.get("takeOffer.setAmountPrice"), Layout.COMPACT_GROUP_DISTANCE);
        GridPane.setColumnSpan(titledGroupBg, 2);

        addAmountPriceFields();
        addSecondRow();
    }

    private void addOptionsGroup() {
        advancedOptionsGroup = addTitledGroupBg(gridPane, ++gridRow, 1, Res.get("shared.advancedOptions"), Layout.COMPACT_GROUP_DISTANCE);

        advancedOptionsBox = new HBox();
        advancedOptionsBox.setSpacing(40);

        GridPane.setRowIndex(advancedOptionsBox, gridRow);
        GridPane.setColumnSpan(advancedOptionsBox, GridPane.REMAINING);
        GridPane.setColumnIndex(advancedOptionsBox, 0);
        GridPane.setHalignment(advancedOptionsBox, HPos.LEFT);
        GridPane.setMargin(advancedOptionsBox, new Insets(Layout.COMPACT_FIRST_ROW_AND_GROUP_DISTANCE, 0, 0, 0));
        gridPane.getChildren().add(advancedOptionsBox);

        VBox tradeFeeFieldsBox = getTradeFeeFieldsBox();
        tradeFeeFieldsBox.setMinWidth(240);
        advancedOptionsBox.getChildren().addAll(tradeFeeFieldsBox);
    }

    private void addNextButtons() {
        Tuple3<Button, Button, HBox> tuple = add2ButtonsWithBox(gridPane, ++gridRow,
                Res.get("shared.nextStep"), Res.get("shared.cancel"), 15, true);

        nextButtonBox = tuple.third;

        nextButton = tuple.first;
        nextButton.setMaxWidth(200);
        nextButton.setDefaultButton(true);
        nextButton.setOnAction(e -> nextStepCheckMakerTx());

        cancelButton1 = tuple.second;
        cancelButton1.setMaxWidth(200);
        cancelButton1.setDefaultButton(false);
        cancelButton1.setOnAction(e -> {
            model.dataModel.swapTradeToSavings();
            close(false);
        });
    }

    private void nextStepCheckMakerTx() {
        // TODO: pre-check if open offer's reserve tx is failed or double spend seen?
        showNextStepAfterAmountIsSet();
    }

    private void showNextStepAfterAmountIsSet() {
            onShowPayFundsScreen();
    }

    private void addOfferAvailabilityLabel() {
        offerAvailabilityBusyAnimation = new BusyAnimation(false);
        offerAvailabilityLabel = new AutoTooltipLabel(Res.get("takeOffer.fundsBox.isOfferAvailable"));
        HBox.setMargin(offerAvailabilityLabel, new Insets(6, 0, 0, 0));
        nextButtonBox.getChildren().addAll(offerAvailabilityBusyAnimation, offerAvailabilityLabel);
    }

    private void addFundingGroup() {

        // no funding required title
        noFundingRequiredTitledGroupBg = addTitledGroupBg(gridPane, gridRow, 3,
                Res.get("takeOffer.fundsBox.noFundingRequiredTitle"), Layout.COMPACT_GROUP_DISTANCE);
        noFundingRequiredTitledGroupBg.getStyleClass().add("last");
        GridPane.setColumnSpan(noFundingRequiredTitledGroupBg, 2);
        noFundingRequiredTitledGroupBg.setVisible(false);

        // no funding required description
        noFundingRequiredLabel = new AutoTooltipLabel(Res.get("takeOffer.fundsBox.noFundingRequiredDescription"));
        noFundingRequiredLabel.setVisible(false);
        //GridPane.setRowSpan(noFundingRequiredLabel, 1);
        GridPane.setRowIndex(noFundingRequiredLabel, gridRow);
        noFundingRequiredLabel.setPadding(new Insets(Layout.COMPACT_FIRST_ROW_AND_GROUP_DISTANCE, 0, 0, 0));
        GridPane.setHalignment(noFundingRequiredLabel, HPos.LEFT);
        gridPane.getChildren().add(noFundingRequiredLabel);
        gridRowNoFundingRequired = gridRow;

        // funding title
        payFundsTitledGroupBg = addTitledGroupBg(gridPane, gridRow, 3,
                Res.get("takeOffer.fundsBox.title"), Layout.COMPACT_GROUP_DISTANCE);
        payFundsTitledGroupBg.getStyleClass().add("last");
        GridPane.setColumnSpan(payFundsTitledGroupBg, 2);
        payFundsTitledGroupBg.setVisible(false);

        totalToPayTextField = addFundsTextfield(gridPane, gridRow,
                Res.get("shared.totalsNeeded"), Layout.COMPACT_FIRST_ROW_AND_GROUP_DISTANCE);
        totalToPayTextField.setVisible(false);

        qrCodeImageView = new ImageView();
        qrCodeImageView.setVisible(false);
        qrCodeImageView.setFitHeight(150);
        qrCodeImageView.setFitWidth(150);
        qrCodeImageView.getStyleClass().add("qr-code");
        Tooltip.install(qrCodeImageView, new Tooltip(Res.get("shared.openLargeQRWindow")));
        qrCodeImageView.setOnMouseClicked(e -> UserThread.runAfter(
                        () -> new QRCodeWindow(getMoneroURI()).show(),
                        200, TimeUnit.MILLISECONDS));
        GridPane.setRowIndex(qrCodeImageView, gridRow);
        GridPane.setColumnIndex(qrCodeImageView, 1);
        GridPane.setRowSpan(qrCodeImageView, 3);
        GridPane.setValignment(qrCodeImageView, VPos.BOTTOM);
        GridPane.setMargin(qrCodeImageView, new Insets(Layout.FIRST_ROW_DISTANCE - 9, 0, 0, 10));
        gridPane.getChildren().add(qrCodeImageView);

        addressTextField = addAddressTextField(gridPane, ++gridRow, Res.get("shared.tradeWalletAddress"));
        addressTextField.setVisible(false);

        balanceTextField = addBalanceTextField(gridPane, ++gridRow, Res.get("shared.tradeWalletBalance"));
        balanceTextField.setVisible(false);

        fundingHBox = new HBox();
        fundingHBox.setVisible(false);
        fundingHBox.setManaged(false);
        fundingHBox.setSpacing(10);
        fundFromSavingsWalletButton = new AutoTooltipButton(Res.get("shared.fundFromSavingsWalletButton"));
        fundFromSavingsWalletButton.setDefaultButton(true);
        fundFromSavingsWalletButton.getStyleClass().add("action-button");
        fundFromSavingsWalletButton.setOnAction(e -> model.fundFromSavingsWallet());
        Label label = new AutoTooltipLabel(Res.get("shared.OR"));
        label.setPadding(new Insets(5, 0, 0, 0));
        Button fundFromExternalWalletButton = new AutoTooltipButton(Res.get("shared.fundFromExternalWalletButton"));
        fundFromExternalWalletButton.setDefaultButton(false);
        fundFromExternalWalletButton.setOnAction(e -> openWallet());
        waitingForFundsBusyAnimation = new BusyAnimation(false);
        waitingForFundsLabel = new AutoTooltipLabel();
        waitingForFundsLabel.setPadding(new Insets(5, 0, 0, 0));
        fundingHBox.getChildren().addAll(fundFromSavingsWalletButton,
                label,
                fundFromExternalWalletButton,
                waitingForFundsBusyAnimation,
                waitingForFundsLabel);

        GridPane.setRowIndex(fundingHBox, ++gridRow);
        GridPane.setMargin(fundingHBox, new Insets(5, 0, 0, 0));
        gridPane.getChildren().add(fundingHBox);

        takeOfferBox = new HBox();
        takeOfferBox.setSpacing(10);
        GridPane.setRowIndex(takeOfferBox, gridRow);
        GridPane.setColumnSpan(takeOfferBox, 2);
        GridPane.setMargin(takeOfferBox, new Insets(5, 20, 0, 0));
        gridPane.getChildren().add(takeOfferBox);

        takeOfferButton = new AutoTooltipButton();
        takeOfferButton.setOnAction(e -> onTakeOffer());
        takeOfferButton.setMinHeight(40);
        takeOfferButton.setPadding(new Insets(0, 20, 0, 20));

        takeOfferBox.getChildren().add(takeOfferButton);
        takeOfferBox.visibleProperty().addListener((observable, oldValue, newValue) -> {
            UserThread.execute(() -> {
                if (newValue) {
                    fundingHBox.getChildren().remove(cancelButton2);
                    takeOfferBox.getChildren().add(cancelButton2);
                } else if (!fundingHBox.getChildren().contains(cancelButton2)) {
                    takeOfferBox.getChildren().remove(cancelButton2);
                    fundingHBox.getChildren().add(cancelButton2);
                }
            });
        });

        cancelButton2 = new AutoTooltipButton(Res.get("shared.cancel"));

        fundingHBox.getChildren().add(cancelButton2);

        cancelButton2.setOnAction(e -> {
            String key = "CreateOfferCancelAndFunded";
            if (model.dataModel.getIsXmrWalletFunded().get() && model.dataModel.hasTotalToPay() && 
                    model.dataModel.preferences.showAgain(key)) {
                new Popup().backgroundInfo(Res.get("takeOffer.alreadyFunded.askCancel"))
                        .closeButtonText(Res.get("shared.no"))
                        .actionButtonText(Res.get("shared.yesCancel"))
                        .onAction(() -> {
                            model.dataModel.swapTradeToSavings();
                            close(false);
                        })
                        .dontShowAgainId(key)
                        .show();
            } else {
                close(false);
                model.dataModel.swapTradeToSavings();
            }
        });
        cancelButton2.setDefaultButton(false);
        cancelButton2.setVisible(false);
    }

    private void openWallet() {
        try {
            Utilities.openURI(URI.create(getMoneroURI()));
        } catch (Exception ex) {
            log.warn(ex.getMessage());
            new Popup().warning(Res.get("shared.openDefaultWalletFailed")).show();
        }
    }

    @NotNull
    private String getMoneroURI() {
        return GUIUtil.getMoneroURI(
                model.dataModel.getAddressEntry().getAddressString(),
                model.dataModel.getMissingCoin().get(),
                model.getPaymentLabel());
    }

    private void addAmountPriceFields() {
        // amountBox
        Tuple3<HBox, InputTextField, Label> amountValueCurrencyBoxTuple = getEditableValueBox(Res.get("takeOffer.amount.prompt"));
        amountValueCurrencyBox = amountValueCurrencyBoxTuple.first;
        amountTextField = amountValueCurrencyBoxTuple.second;
        Tuple2<Label, VBox> amountInputBoxTuple = getTradeInputBox(amountValueCurrencyBox, model.getAmountDescription());
        amountDescriptionLabel = amountInputBoxTuple.first;
        VBox amountBox = amountInputBoxTuple.second;

        // x
        xLabel = new Label();
        xIcon = getIconForLabel(MaterialDesignIcon.CLOSE, "2em", xLabel);
        xIcon.getStyleClass().add("opaque-icon");
        xLabel.getStyleClass().addAll("opaque-icon-character");

        // price
        Tuple3<HBox, TextField, Label> priceValueCurrencyBoxTuple = getNonEditableValueBox();
        priceValueCurrencyBox = priceValueCurrencyBoxTuple.first;
        priceTextField = priceValueCurrencyBoxTuple.second;
        priceCurrencyLabel = priceValueCurrencyBoxTuple.third;
        Tuple2<Label, VBox> priceInputBoxTuple = getTradeInputBox(priceValueCurrencyBox,
                Res.get("takeOffer.amountPriceBox.priceDescription"));
        priceDescriptionLabel = priceInputBoxTuple.first;

        getSmallIconForLabel(MaterialDesignIcon.LOCK, priceDescriptionLabel, "small-icon-label");

        VBox priceBox = priceInputBoxTuple.second;

        // =
        resultLabel = new AutoTooltipLabel("=");
        resultLabel.getStyleClass().addAll("opaque-icon-character");

        // volume
        Tuple3<HBox, InfoInputTextField, Label> volumeValueCurrencyBoxTuple = getNonEditableValueBoxWithInfo();
        volumeValueCurrencyBox = volumeValueCurrencyBoxTuple.first;

        volumeInfoTextField = volumeValueCurrencyBoxTuple.second;
        volumeTextField = volumeInfoTextField.getInputTextField();
        volumeCurrencyLabel = volumeValueCurrencyBoxTuple.third;
        Tuple2<Label, VBox> volumeInputBoxTuple = getTradeInputBox(volumeValueCurrencyBox, model.volumeDescriptionLabel.get());
        volumeDescriptionLabel = volumeInputBoxTuple.first;
        VBox volumeBox = volumeInputBoxTuple.second;

        firstRowHBox = new HBox();
        firstRowHBox.setSpacing(5);
        firstRowHBox.setAlignment(Pos.CENTER_LEFT);
        firstRowHBox.getChildren().addAll(amountBox, xLabel, priceBox, resultLabel, volumeBox);
        GridPane.setColumnSpan(firstRowHBox, 2);
        GridPane.setRowIndex(firstRowHBox, gridRow);
        GridPane.setMargin(firstRowHBox, new Insets(Layout.COMPACT_FIRST_ROW_AND_GROUP_DISTANCE, 10, 0, 0));
        gridPane.getChildren().add(firstRowHBox);
    }

    private void addSecondRow() {
        Tuple3<HBox, TextField, Label> priceAsPercentageTuple = getNonEditableValueBox();
        priceAsPercentageValueCurrencyBox = priceAsPercentageTuple.first;
        priceAsPercentageTextField = priceAsPercentageTuple.second;
        priceAsPercentageLabel = priceAsPercentageTuple.third;

        Tuple2<Label, VBox> priceAsPercentageInputBoxTuple = getTradeInputBox(priceAsPercentageValueCurrencyBox, "");
        priceAsPercentageDescription = priceAsPercentageInputBoxTuple.first;

        getSmallIconForLabel(MaterialDesignIcon.CHART_LINE, priceAsPercentageDescription, "small-icon-label");

        priceAsPercentageInputBox = priceAsPercentageInputBoxTuple.second;

        priceAsPercentageLabel.setText("%");

        Tuple3<HBox, TextField, Label> amountValueCurrencyBoxTuple = getNonEditableValueBox();
        amountRangeTextField = amountValueCurrencyBoxTuple.second;

        minAmountValueCurrencyBox = amountValueCurrencyBoxTuple.first;
        Tuple2<Label, VBox> amountInputBoxTuple = getTradeInputBox(minAmountValueCurrencyBox,
                Res.get("takeOffer.amountPriceBox.amountRangeDescription"));

        amountRangeBox = amountInputBoxTuple.second;
        amountRangeBox.setVisible(false);

        fakeXLabel = new Label();
        fakeXIcon = getIconForLabel(MaterialDesignIcon.CLOSE, "2em", fakeXLabel);
        fakeXLabel.setVisible(false); // we just use it to get the same layout as the upper row
        fakeXLabel.getStyleClass().add("opaque-icon-character");

        HBox hBox = new HBox();
        hBox.setSpacing(5);
        hBox.setAlignment(Pos.CENTER_LEFT);
        hBox.getChildren().addAll(amountRangeBox, fakeXLabel, priceAsPercentageInputBox);

        GridPane.setRowIndex(hBox, ++gridRow);
        GridPane.setMargin(hBox, new Insets(0, 10, 10, 0));
        gridPane.getChildren().add(hBox);
    }

    private VBox getTradeFeeFieldsBox() {
        tradeFeeInXmrLabel = new Label();
        tradeFeeInXmrLabel.setMouseTransparent(true);
        tradeFeeInXmrLabel.setId("trade-fee-textfield");

        VBox vBox = new VBox();
        vBox.setSpacing(6);
        vBox.setMaxWidth(300);
        vBox.setAlignment(Pos.CENTER_LEFT);
        vBox.getChildren().addAll(tradeFeeInXmrLabel);

        HBox hBox = new HBox();
        hBox.getChildren().addAll(vBox);
        hBox.setMinHeight(47);
        hBox.setMaxHeight(hBox.getMinHeight());
        HBox.setHgrow(vBox, Priority.ALWAYS);

        final Tuple2<Label, VBox> tradeInputBox = getTradeInputBox(hBox, Res.get("createOffer.tradeFee.description"));

        tradeFeeDescriptionLabel = tradeInputBox.first;

        return tradeInputBox.second;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void maybeShowExtraInfoPopup(Offer offer) {
        if (offer.getCombinedExtraInfo() != null && !offer.getCombinedExtraInfo().isEmpty() && !extraInfoPopupDisplayed) {
            extraInfoPopupDisplayed = true;
            UserThread.runAfter(() -> {
                new GenericMessageWindow()
                        .preamble(Res.get("payment.tradingRestrictions"))
                        .instruction(offer.getCombinedExtraInfo().trim())
                        .actionButtonText(Res.get("shared.iConfirm"))
                        .closeButtonText(Res.get("shared.close"))
                        .width(Layout.INITIAL_WINDOW_WIDTH)
                        .onClose(() -> close(false))
                        .show();
            }, 500, TimeUnit.MILLISECONDS);
        }
    }

    private void maybeShowTakeOfferFromUnsignedAccountWarning(Offer offer) {
        // warn if you are selling BTC to unsigned account (#5343)
        if (model.isSellingToAnUnsignedAccount(offer) && !takeOfferFromUnsignedAccountWarningDisplayed) {
            takeOfferFromUnsignedAccountWarningDisplayed = true;
        }
    }

    private void maybeShowZelleWarning(PaymentAccount paymentAccount) {
        if (paymentAccount.getPaymentMethod().getId().equals(PaymentMethod.ZELLE_ID) &&
                !zelleWarningDisplayed) {
            zelleWarningDisplayed = true;
            UserThread.runAfter(GUIUtil::showZelleWarning, 500, TimeUnit.MILLISECONDS);
        }
    }

    private void maybeShowFasterPaymentsWarning(PaymentAccount paymentAccount) {
        if (paymentAccount.getPaymentMethod().getId().equals(PaymentMethod.FASTER_PAYMENTS_ID) &&
                ((FasterPaymentsAccount) paymentAccount).getHolderName().isEmpty() &&
                !fasterPaymentsWarningDisplayed) {
            fasterPaymentsWarningDisplayed = true;
            UserThread.runAfter(() -> GUIUtil.showFasterPaymentsWarning(navigation), 500, TimeUnit.MILLISECONDS);
        }
    }

    private void maybeShowAccountWarning(PaymentAccount paymentAccount, boolean isBuyer) {
        String msgKey = paymentAccount.getPreTradeMessage(!isBuyer);
        OfferViewUtil.showPaymentAccountWarning(msgKey, paymentAccountWarningDisplayed);
    }

    private Tuple2<Label, VBox> getTradeInputBox(HBox amountValueBox, String promptText) {
        Label descriptionLabel = new AutoTooltipLabel(promptText);
        descriptionLabel.setId("input-description-label");
        descriptionLabel.setPrefWidth(170);

        VBox box = new VBox();
        box.setPadding(new Insets(10, 0, 0, 0));
        box.setSpacing(2);
        box.getChildren().addAll(descriptionLabel, amountValueBox);
        return new Tuple2<>(descriptionLabel, box);
    }

    // As we don't use binding here we need to recreate it on mouse over to reflect the current state
    private GridPane createInfoPopover() {
        GridPane infoGridPane = new GridPane();
        infoGridPane.setHgap(5);
        infoGridPane.setVgap(5);
        infoGridPane.setPadding(new Insets(10, 10, 10, 10));

        int i = 0;
        if (model.isSeller()) {
            addPayInfoEntry(infoGridPane, i++, Res.get("takeOffer.fundsBox.tradeAmount"), model.getTradeAmount());
        }

        addPayInfoEntry(infoGridPane, i++, Res.getWithCol("shared.yourSecurityDeposit"), model.getSecurityDepositInfo());
        addPayInfoEntry(infoGridPane, i++, Res.get("takeOffer.fundsBox.offerFee"), model.getTradeFee());
        Separator separator = new Separator();
        separator.setOrientation(Orientation.HORIZONTAL);
        separator.getStyleClass().add("offer-separator");
        GridPane.setConstraints(separator, 1, i++);
        infoGridPane.getChildren().add(separator);
        addPayInfoEntry(infoGridPane, i, Res.getWithCol("shared.total"),
                model.getTotalToPayInfo());

        return infoGridPane;
    }

    @NotNull
    private String getTakeOfferLabel(Offer offer, boolean isBuyOffer) {
        return offer.isTraditionalOffer() ?
                Res.get("takeOffer.takeOfferButton", isBuyOffer ? Res.get("shared.sell") : Res.get("shared.buy")) :
                Res.get("takeOffer.takeOfferButtonCrypto",
                        isBuyOffer ? Res.get("shared.buy") : Res.get("shared.sell"),
                        offer.getCurrencyCode());
    }

}

