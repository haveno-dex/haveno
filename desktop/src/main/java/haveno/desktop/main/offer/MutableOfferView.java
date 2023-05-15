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

import de.jensd.fx.fontawesome.AwesomeIcon;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.common.util.Tuple2;
import haveno.common.util.Tuple3;
import haveno.common.util.Utilities;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.payment.FasterPaymentsAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.DontShowAgainLookup;
import haveno.core.user.Preferences;
import haveno.core.util.coin.CoinFormatter;
import haveno.desktop.Navigation;
import haveno.desktop.common.view.ActivatableViewAndModel;
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
import haveno.desktop.main.account.AccountView;
import haveno.desktop.main.account.content.traditionalaccounts.TraditionalAccountsView;
import haveno.desktop.main.overlays.notifications.Notification;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.OfferDetailsWindow;
import haveno.desktop.main.overlays.windows.QRCodeWindow;
import haveno.desktop.main.portfolio.PortfolioView;
import haveno.desktop.main.portfolio.openoffer.OpenOffersView;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.Layout;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
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
import javafx.util.StringConverter;
import lombok.Setter;
import net.glxn.qrgen.QRCode;
import net.glxn.qrgen.image.ImageType;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static haveno.core.payment.payload.PaymentMethod.HAL_CASH_ID;
import static haveno.desktop.main.offer.OfferViewUtil.addPayInfoEntry;
import static haveno.desktop.util.FormBuilder.add2ButtonsAfterGroup;
import static haveno.desktop.util.FormBuilder.addAddressTextField;
import static haveno.desktop.util.FormBuilder.addBalanceTextField;
import static haveno.desktop.util.FormBuilder.addFundsTextfield;
import static haveno.desktop.util.FormBuilder.addTitledGroupBg;
import static haveno.desktop.util.FormBuilder.addTopLabelComboBox;
import static haveno.desktop.util.FormBuilder.addTopLabelTextField;
import static haveno.desktop.util.FormBuilder.getEditableValueBox;
import static haveno.desktop.util.FormBuilder.getEditableValueBoxWithInfo;
import static haveno.desktop.util.FormBuilder.getIconButton;
import static haveno.desktop.util.FormBuilder.getIconForLabel;
import static haveno.desktop.util.FormBuilder.getSmallIconForLabel;
import static haveno.desktop.util.FormBuilder.getTradeInputBox;
import static javafx.beans.binding.Bindings.createStringBinding;

public abstract class MutableOfferView<M extends MutableOfferViewModel<?>> extends ActivatableViewAndModel<AnchorPane, M> implements ClosableView, SelectableView {
    protected final Navigation navigation;
    private final Preferences preferences;
    private final OfferDetailsWindow offerDetailsWindow;
    private final CoinFormatter xmrFormatter;

    private ScrollPane scrollPane;
    protected GridPane gridPane;
    private TitledGroupBg payFundsTitledGroupBg, setDepositTitledGroupBg, paymentTitledGroupBg;
    protected TitledGroupBg amountTitledGroupBg;
    private BusyAnimation waitingForFundsSpinner;
    private AutoTooltipButton nextButton, cancelButton1, cancelButton2, placeOfferButton, fundFromSavingsWalletButton;
    private Button priceTypeToggleButton;
    private InputTextField fixedPriceTextField, marketBasedPriceTextField, triggerPriceInputTextField;
    protected InputTextField amountTextField, minAmountTextField, volumeTextField, buyerSecurityDepositInputTextField;
    private TextField currencyTextField;
    private AddressTextField addressTextField;
    private BalanceTextField balanceTextField;
    private FundsTextField totalToPayTextField;
    private Label amountDescriptionLabel, priceCurrencyLabel, priceDescriptionLabel, volumeDescriptionLabel,
            waitingForFundsLabel, marketBasedPriceLabel, percentagePriceDescriptionLabel, tradeFeeDescriptionLabel,
            resultLabel, tradeFeeInXmrLabel, xLabel, fakeXLabel, buyerSecurityDepositLabel,
            buyerSecurityDepositPercentageLabel, triggerPriceCurrencyLabel, triggerPriceDescriptionLabel;
    protected Label amountBtcLabel, volumeCurrencyLabel, minAmountBtcLabel;
    private ComboBox<PaymentAccount> paymentAccountsComboBox;
    private ComboBox<TradeCurrency> currencyComboBox;
    private ImageView qrCodeImageView;
    private VBox currencySelection, fixedPriceBox, percentagePriceBox, currencyTextFieldBox, triggerPriceVBox;
    private HBox fundingHBox, firstRowHBox, secondRowHBox, placeOfferBox, amountValueCurrencyBox,
            priceAsPercentageValueCurrencyBox, volumeValueCurrencyBox, priceValueCurrencyBox,
            minAmountValueCurrencyBox, advancedOptionsBox, triggerPriceHBox;

    private Subscription isWaitingForFundsSubscription, balanceSubscription;
    private ChangeListener<Boolean> amountFocusedListener, minAmountFocusedListener, volumeFocusedListener,
            buyerSecurityDepositFocusedListener, priceFocusedListener, placeOfferCompletedListener,
            priceAsPercentageFocusedListener, getShowWalletFundedNotificationListener,
            isMinBuyerSecurityDepositListener, triggerPriceFocusedListener;
    private ChangeListener<BigInteger> missingCoinListener;
    private ChangeListener<String> tradeCurrencyCodeListener, errorMessageListener,
            marketPriceMarginListener, volumeListener, buyerSecurityDepositInBTCListener;
    private ChangeListener<Number> marketPriceAvailableListener;
    private EventHandler<ActionEvent> currencyComboBoxSelectionHandler, paymentAccountsComboBoxSelectionHandler;
    private OfferView.CloseHandler closeHandler;

    protected int gridRow = 0;
    private final List<Node> editOfferElements = new ArrayList<>();
    private final HashMap<String, Boolean> paymentAccountWarningDisplayed = new HashMap<>();
    private boolean zelleWarningDisplayed, fasterPaymentsWarningDisplayed, isActivated;
    private InfoInputTextField marketBasedPriceInfoInputTextField, volumeInfoInputTextField,
            buyerSecurityDepositInfoInputTextField, triggerPriceInfoInputTextField;
    private Text xIcon, fakeXIcon;

    @Setter
    private OfferView.OfferActionHandler offerActionHandler;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    public MutableOfferView(M model,
                            Navigation navigation,
                            Preferences preferences,
                            OfferDetailsWindow offerDetailsWindow,
                            CoinFormatter btcFormatter) {
        super(model);

        this.navigation = navigation;
        this.preferences = preferences;
        this.offerDetailsWindow = offerDetailsWindow;
        this.xmrFormatter = btcFormatter;
    }

    @Override
    protected void initialize() {
        addScrollPane();
        addGridPane();
        addPaymentGroup();
        addAmountPriceGroup();
        addOptionsGroup();
        addFundingGroup();

        createListeners();

        balanceTextField.setFormatter(model.getBtcFormatter());

        paymentAccountsComboBox.setConverter(GUIUtil.getPaymentAccountsComboBoxStringConverter());
        paymentAccountsComboBox.setButtonCell(GUIUtil.getComboBoxButtonCell(Res.get("shared.chooseTradingAccount"),
                paymentAccountsComboBox, false));
        paymentAccountsComboBox.setCellFactory(model.getPaymentAccountListCellFactory(paymentAccountsComboBox));

        doSetFocus();
    }

    protected void doSetFocus() {
        GUIUtil.focusWhenAddedToScene(amountTextField);
    }

    @Override
    protected void activate() {
        if (model.getDataModel().isTabSelected)
            doActivate();
    }

    protected void doActivate() {
        if (!isActivated) {
            isActivated = true;
            currencyComboBox.setPrefWidth(250);
            paymentAccountsComboBox.setPrefWidth(250);

            addBindings();
            addListeners();
            addSubscriptions();

            // temporarily disabled due to high CPU usage (per issue #4649)
            // if (waitingForFundsSpinner != null)
            //     waitingForFundsSpinner.play();

            amountDescriptionLabel.setText(model.getAmountDescription());
            addressTextField.setAddress(model.getAddressAsString());
            addressTextField.setPaymentLabel(model.getPaymentLabel());

            currencyComboBox.getSelectionModel().select(model.getTradeCurrency());
            paymentAccountsComboBox.setItems(getPaymentAccounts());
            paymentAccountsComboBox.getSelectionModel().select(model.getPaymentAccount());

            onPaymentAccountsComboBoxSelected();

            balanceTextField.setTargetAmount(model.getDataModel().totalToPayAsProperty().get());
            updatePriceToggle();

            Label popOverLabel = OfferViewUtil.createPopOverLabel(Res.get("createOffer.triggerPrice.tooltip"));
            triggerPriceInfoInputTextField.setContentForPopOver(popOverLabel, AwesomeIcon.SHIELD);
        }
    }

    @Override
    protected void deactivate() {
        if (isActivated) {
            isActivated = false;
            removeBindings();
            removeListeners();
            removeSubscriptions();

            // temporarily disabled due to high CPU usage (per issue #4649)
            //if (waitingForFundsSpinner != null)
            //    waitingForFundsSpinner.stop();
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onTabSelected(boolean isSelected) {
        if (isSelected && !model.getDataModel().isTabSelected) {
            doActivate();
        } else {
            deactivate();
        }

        isActivated = isSelected;
        model.getDataModel().onTabSelected(isSelected);
    }

    public void initWithData(OfferDirection direction, TradeCurrency tradeCurrency,
                             OfferView.OfferActionHandler offerActionHandler) {
        this.offerActionHandler = offerActionHandler;

        boolean result = model.initWithData(direction, tradeCurrency);

        if (!result) {
            new Popup().headLine(Res.get("popup.warning.noTradingAccountSetup.headline"))
                    .instruction(Res.get("popup.warning.noTradingAccountSetup.msg"))
                    .actionButtonTextWithGoTo("navigation.account")
                    .onAction(() -> {
                        navigation.setReturnPath(navigation.getCurrentPath());
                        navigation.navigateTo(MainView.class, AccountView.class, TraditionalAccountsView.class);
                    }).show();
        }

        String placeOfferButtonLabel;

        if (OfferViewUtil.isShownAsBuyOffer(direction, tradeCurrency)) {
            placeOfferButton.setId("buy-button-big");
            if (CurrencyUtil.isTraditionalCurrency(tradeCurrency.getCode())) {
                placeOfferButtonLabel = Res.get("createOffer.placeOfferButton", Res.get("shared.buy"));
            } else {
                placeOfferButtonLabel = Res.get("createOffer.placeOfferButtonCrypto", Res.get("shared.buy"), tradeCurrency.getCode());
            }
            nextButton.setId("buy-button");
            fundFromSavingsWalletButton.setId("buy-button");
        } else {
            placeOfferButton.setId("sell-button-big");
            if (CurrencyUtil.isTraditionalCurrency(tradeCurrency.getCode())) {
                placeOfferButtonLabel = Res.get("createOffer.placeOfferButton", Res.get("shared.sell"));
            } else {
                placeOfferButtonLabel = Res.get("createOffer.placeOfferButtonCrypto", Res.get("shared.sell"), tradeCurrency.getCode());
            }
            nextButton.setId("sell-button");
            fundFromSavingsWalletButton.setId("sell-button");
        }

        placeOfferButton.updateText(placeOfferButtonLabel);
        updatePriceToggle();
    }

    // called from parent as the view does not get notified when the tab is closed
    public void onClose() {
        // we use model.placeOfferCompleted to not react on close which was triggered by a successful placeOffer
        if (model.getDataModel().getBalance().get().compareTo(BigInteger.valueOf(0)) > 0 && !model.placeOfferCompleted.get()) {
            model.getDataModel().swapTradeToSavings();
        }
    }

    @Override
    public void setCloseHandler(OfferView.CloseHandler closeHandler) {
        this.closeHandler = closeHandler;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI actions
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onPlaceOffer() {
        if (model.getDataModel().canPlaceOffer()) {
            Offer offer = model.createAndGetOffer();
            if (!DevEnv.isDevMode()) {
                offerDetailsWindow.onPlaceOffer(() ->
                        model.onPlaceOffer(offer, offerDetailsWindow::hide))
                        .show(offer);
            } else {
                balanceSubscription.unsubscribe();
                model.onPlaceOffer(offer, () -> {
                });
            }
        }
    }

    private void onShowPayFundsScreen() {
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        nextButton.setVisible(false);
        nextButton.setManaged(false);
        nextButton.setOnAction(null);
        cancelButton1.setVisible(false);
        cancelButton1.setManaged(false);
        cancelButton1.setOnAction(null);

        setDepositTitledGroupBg.setVisible(false);
        setDepositTitledGroupBg.setManaged(false);

        advancedOptionsBox.setVisible(false);
        advancedOptionsBox.setManaged(false);

        model.onShowPayFundsScreen(() -> {
            if (!DevEnv.isDevMode()) {
                String key = "createOfferFundWalletInfo";
                String tradeAmountText = model.isSellOffer() ?
                        Res.get("createOffer.createOfferFundWalletInfo.tradeAmount", model.getTradeAmount()) : "";

                String message = Res.get("createOffer.createOfferFundWalletInfo.msg",
                        model.getTotalToPayInfo(),
                        tradeAmountText,
                        model.getSecurityDepositInfo(),
                        model.getTradeFee()
                );
                new Popup().headLine(Res.get("createOffer.createOfferFundWalletInfo.headline"))
                        .instruction(message)
                        .dontShowAgainId(key)
                        .show();
            }

            totalToPayTextField.setFundsStructure(model.getFundsStructure());
            totalToPayTextField.setContentForInfoPopOver(createInfoPopover());
        });

        paymentAccountsComboBox.setDisable(true);

        editOfferElements.forEach(node -> {
            node.setMouseTransparent(true);
            node.setFocusTraversable(false);
        });

        updateOfferElementsStyle();

        if (triggerPriceInputTextField.getText().isEmpty()) {
            triggerPriceVBox.setVisible(false);
        }

        balanceTextField.setTargetAmount(model.getDataModel().totalToPayAsProperty().get());

        // temporarily disabled due to high CPU usage (per issue #4649)
        // waitingForFundsSpinner.play();

        payFundsTitledGroupBg.setVisible(true);
        totalToPayTextField.setVisible(true);
        addressTextField.setVisible(true);
        qrCodeImageView.setVisible(true);
        balanceTextField.setVisible(true);
        cancelButton2.setVisible(true);
    }

    private void updateOfferElementsStyle() {
        GridPane.setColumnSpan(firstRowHBox, 2);

        String activeInputStyle = "input-with-border";
        String readOnlyInputStyle = "input-with-border-readonly";
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
        triggerPriceHBox.getStyleClass().remove(activeInputStyle);
        triggerPriceHBox.getStyleClass().add(readOnlyInputStyle);

        GridPane.setColumnSpan(secondRowHBox, 1);
        priceTypeToggleButton.setVisible(false);
        HBox.setMargin(priceTypeToggleButton, new Insets(16, -14, 0, 0));

        resultLabel.getStyleClass().add("small");
        xLabel.getStyleClass().add("small");
        xIcon.setStyle(String.format("-fx-font-family: %s; -fx-font-size: %s;", MaterialDesignIcon.CLOSE.fontFamily(), "1em"));
        fakeXIcon.setStyle(String.format("-fx-font-family: %s; -fx-font-size: %s;", MaterialDesignIcon.CLOSE.fontFamily(), "1em"));
        fakeXLabel.getStyleClass().add("small");
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
        String msgKey = paymentAccount.getPreTradeMessage(isBuyer);
        OfferViewUtil.showPaymentAccountWarning(msgKey, paymentAccountWarningDisplayed);
    }

    protected void onPaymentAccountsComboBoxSelected() {
        // Temporary deactivate handler as the payment account change can populate a new currency list and causes
        // unwanted selection events (item 0)
        currencyComboBox.setOnAction(null);

        PaymentAccount paymentAccount = paymentAccountsComboBox.getSelectionModel().getSelectedItem();
        if (paymentAccount != null) {
            maybeShowZelleWarning(paymentAccount);
            maybeShowFasterPaymentsWarning(paymentAccount);
            maybeShowAccountWarning(paymentAccount, model.getDataModel().isBuyOffer());

            currencySelection.setVisible(paymentAccount.hasMultipleCurrencies());
            currencySelection.setManaged(paymentAccount.hasMultipleCurrencies());
            currencyTextFieldBox.setVisible(!paymentAccount.hasMultipleCurrencies());

            model.onPaymentAccountSelected(paymentAccount);
            model.onCurrencySelected(model.getTradeCurrency());

            if (paymentAccount.hasMultipleCurrencies()) {
                final List<TradeCurrency> tradeCurrencies = paymentAccount.getTradeCurrencies();
                currencyComboBox.setItems(FXCollections.observableArrayList(tradeCurrencies));
                currencyComboBox.getSelectionModel().select(model.getTradeCurrency());
            } else {
                TradeCurrency singleTradeCurrency = paymentAccount.getSingleTradeCurrency();
                if (singleTradeCurrency != null)
                    currencyTextField.setText(singleTradeCurrency.getNameAndCode());
            }
        } else {
            currencySelection.setVisible(false);
            currencySelection.setManaged(false);
            currencyTextFieldBox.setVisible(true);

            currencyTextField.setText("");
        }

        currencyComboBox.setOnAction(currencyComboBoxSelectionHandler);

        updatePriceToggle();
    }

    private void onCurrencyComboBoxSelected() {
        model.onCurrencySelected(currencyComboBox.getSelectionModel().getSelectedItem());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Navigation
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected void close() {
        if (closeHandler != null)
            closeHandler.close();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Bindings, Listeners
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void addBindings() {
        priceCurrencyLabel.textProperty().bind(createStringBinding(() -> CurrencyUtil.getCounterCurrency(model.tradeCurrencyCode.get()), model.tradeCurrencyCode));
        triggerPriceCurrencyLabel.textProperty().bind(createStringBinding(() ->
                CurrencyUtil.getCounterCurrency(model.tradeCurrencyCode.get()), model.tradeCurrencyCode));
        triggerPriceDescriptionLabel.textProperty().bind(model.triggerPriceDescription);
        percentagePriceDescriptionLabel.textProperty().bind(model.percentagePriceDescription);
        marketBasedPriceLabel.prefWidthProperty().bind(priceCurrencyLabel.widthProperty());
        volumeCurrencyLabel.textProperty().bind(model.tradeCurrencyCode);
        priceDescriptionLabel.textProperty().bind(createStringBinding(() -> CurrencyUtil.getPriceWithCurrencyCode(model.tradeCurrencyCode.get(), "shared.fixedPriceInCurForCur"), model.tradeCurrencyCode));
        volumeDescriptionLabel.textProperty().bind(createStringBinding(model.volumeDescriptionLabel::get, model.tradeCurrencyCode, model.volumeDescriptionLabel));
        amountTextField.textProperty().bindBidirectional(model.amount);
        minAmountTextField.textProperty().bindBidirectional(model.minAmount);
        fixedPriceTextField.textProperty().bindBidirectional(model.price);
        triggerPriceInputTextField.textProperty().bindBidirectional(model.triggerPrice);
        marketBasedPriceTextField.textProperty().bindBidirectional(model.marketPriceMargin);
        volumeTextField.textProperty().bindBidirectional(model.volume);
        volumeTextField.promptTextProperty().bind(model.volumePromptLabel);
        totalToPayTextField.textProperty().bind(model.totalToPay);
        addressTextField.amountAsProperty().bind(model.getDataModel().getMissingCoin());
        buyerSecurityDepositInputTextField.textProperty().bindBidirectional(model.buyerSecurityDeposit);
        buyerSecurityDepositLabel.textProperty().bind(model.buyerSecurityDepositLabel);
        tradeFeeInXmrLabel.textProperty().bind(model.tradeFeeInXmrWithFiat);
        tradeFeeDescriptionLabel.textProperty().bind(model.tradeFeeDescription);

        // Validation
        amountTextField.validationResultProperty().bind(model.amountValidationResult);
        minAmountTextField.validationResultProperty().bind(model.minAmountValidationResult);
        fixedPriceTextField.validationResultProperty().bind(model.priceValidationResult);
        triggerPriceInputTextField.validationResultProperty().bind(model.triggerPriceValidationResult);
        volumeTextField.validationResultProperty().bind(model.volumeValidationResult);
        buyerSecurityDepositInputTextField.validationResultProperty().bind(model.buyerSecurityDepositValidationResult);

        // funding
        fundingHBox.visibleProperty().bind(model.getDataModel().getIsXmrWalletFunded().not().and(model.showPayFundsScreenDisplayed));
        fundingHBox.managedProperty().bind(model.getDataModel().getIsXmrWalletFunded().not().and(model.showPayFundsScreenDisplayed));
        waitingForFundsLabel.textProperty().bind(model.waitingForFundsText);
        placeOfferBox.visibleProperty().bind(model.getDataModel().getIsXmrWalletFunded().and(model.showPayFundsScreenDisplayed));
        placeOfferBox.managedProperty().bind(model.getDataModel().getIsXmrWalletFunded().and(model.showPayFundsScreenDisplayed));
        placeOfferButton.disableProperty().bind(model.isPlaceOfferButtonDisabled);
        cancelButton2.disableProperty().bind(model.cancelButtonDisabled);

        // trading account
        paymentAccountsComboBox.managedProperty().bind(paymentAccountsComboBox.visibleProperty());
        paymentTitledGroupBg.managedProperty().bind(paymentTitledGroupBg.visibleProperty());
        currencyComboBox.prefWidthProperty().bind(paymentAccountsComboBox.widthProperty());
        currencyComboBox.managedProperty().bind(currencyComboBox.visibleProperty());
        currencyTextFieldBox.managedProperty().bind(currencyTextFieldBox.visibleProperty());
    }

    private void removeBindings() {
        priceCurrencyLabel.textProperty().unbind();
        triggerPriceCurrencyLabel.textProperty().unbind();
        triggerPriceDescriptionLabel.textProperty().unbind();
        percentagePriceDescriptionLabel.textProperty().unbind();
        volumeCurrencyLabel.textProperty().unbind();
        priceDescriptionLabel.textProperty().unbind();
        volumeDescriptionLabel.textProperty().unbind();
        amountTextField.textProperty().unbindBidirectional(model.amount);
        minAmountTextField.textProperty().unbindBidirectional(model.minAmount);
        fixedPriceTextField.textProperty().unbindBidirectional(model.price);
        triggerPriceInputTextField.textProperty().unbindBidirectional(model.triggerPrice);
        marketBasedPriceTextField.textProperty().unbindBidirectional(model.marketPriceMargin);
        marketBasedPriceLabel.prefWidthProperty().unbind();
        volumeTextField.textProperty().unbindBidirectional(model.volume);
        volumeTextField.promptTextProperty().unbindBidirectional(model.volume);
        totalToPayTextField.textProperty().unbind();
        addressTextField.amountAsProperty().unbind();
        buyerSecurityDepositInputTextField.textProperty().unbindBidirectional(model.buyerSecurityDeposit);
        buyerSecurityDepositLabel.textProperty().unbind();
        tradeFeeInXmrLabel.textProperty().unbind();
        tradeFeeDescriptionLabel.textProperty().unbind();
        tradeFeeInXmrLabel.visibleProperty().unbind();
        tradeFeeDescriptionLabel.visibleProperty().unbind();

        // Validation
        amountTextField.validationResultProperty().unbind();
        minAmountTextField.validationResultProperty().unbind();
        fixedPriceTextField.validationResultProperty().unbind();
        triggerPriceInputTextField.validationResultProperty().unbind();
        volumeTextField.validationResultProperty().unbind();
        buyerSecurityDepositInputTextField.validationResultProperty().unbind();

        // funding
        fundingHBox.visibleProperty().unbind();
        fundingHBox.managedProperty().unbind();
        waitingForFundsLabel.textProperty().unbind();
        placeOfferBox.visibleProperty().unbind();
        placeOfferBox.managedProperty().unbind();
        placeOfferButton.disableProperty().unbind();
        cancelButton2.disableProperty().unbind();

        // trading account
        paymentTitledGroupBg.managedProperty().unbind();
        paymentAccountsComboBox.managedProperty().unbind();
        currencyComboBox.managedProperty().unbind();
        currencyComboBox.prefWidthProperty().unbind();
        currencyTextFieldBox.managedProperty().unbind();
    }

    private void addSubscriptions() {
        isWaitingForFundsSubscription = EasyBind.subscribe(model.isWaitingForFunds, isWaitingForFunds -> {

            // temporarily disabled due to high CPU usage (per issue #4649)
            //if (isWaitingForFunds) {
            //    waitingForFundsSpinner.play();
            //} else {
            //    waitingForFundsSpinner.stop();
            //}

            waitingForFundsLabel.setVisible(isWaitingForFunds);
            waitingForFundsLabel.setManaged(isWaitingForFunds);
        });

        balanceSubscription = EasyBind.subscribe(model.getDataModel().getBalance(), balanceTextField::setBalance);
    }

    private void removeSubscriptions() {
        isWaitingForFundsSubscription.unsubscribe();
        balanceSubscription.unsubscribe();
    }

    private void createListeners() {
        amountFocusedListener = (o, oldValue, newValue) -> {
            model.onFocusOutAmountTextField(oldValue, newValue);
            amountTextField.setText(model.amount.get());
        };
        minAmountFocusedListener = (o, oldValue, newValue) -> {
            model.onFocusOutMinAmountTextField(oldValue, newValue);
            minAmountTextField.setText(model.minAmount.get());
        };
        priceFocusedListener = (o, oldValue, newValue) -> {
            model.onFocusOutPriceTextField(oldValue, newValue);
            fixedPriceTextField.setText(model.price.get());
        };
        priceAsPercentageFocusedListener = (o, oldValue, newValue) -> {
            model.onFocusOutPriceAsPercentageTextField(oldValue, newValue);
            marketBasedPriceTextField.setText(model.marketPriceMargin.get());
        };
        volumeFocusedListener = (o, oldValue, newValue) -> {
            model.onFocusOutVolumeTextField(oldValue, newValue);
            volumeTextField.setText(model.volume.get());
        };
        buyerSecurityDepositFocusedListener = (o, oldValue, newValue) -> {
            model.onFocusOutBuyerSecurityDepositTextField(oldValue, newValue);
            buyerSecurityDepositInputTextField.setText(model.buyerSecurityDeposit.get());
        };

        triggerPriceFocusedListener = (o, oldValue, newValue) -> {
            model.onFocusOutTriggerPriceTextField(oldValue, newValue);
            triggerPriceInputTextField.setText(model.triggerPrice.get());
        };

        errorMessageListener = (o, oldValue, newValue) -> {
            if (newValue != null)
                UserThread.runAfter(() -> new Popup().error(Res.get("createOffer.amountPriceBox.error.message", model.errorMessage.get()))
                        .show(), 100, TimeUnit.MILLISECONDS);
        };

        paymentAccountsComboBoxSelectionHandler = e -> onPaymentAccountsComboBoxSelected();
        currencyComboBoxSelectionHandler = e -> onCurrencyComboBoxSelected();

        tradeCurrencyCodeListener = (observable, oldValue, newValue) -> {
            fixedPriceTextField.clear();
            marketBasedPriceTextField.clear();
            volumeTextField.clear();
            triggerPriceInputTextField.clear();
            if (!CurrencyUtil.isTraditionalCurrency(newValue)) {
                if (model.isShownAsBuyOffer()) {
                    placeOfferButton.updateText(Res.get("createOffer.placeOfferButtonCrypto", Res.get("shared.buy"),
                            model.getTradeCurrency().getCode()));
                } else {
                    placeOfferButton.updateText(Res.get("createOffer.placeOfferButtonCrypto", Res.get("shared.sell"),
                            model.getTradeCurrency().getCode()));
                }
            }
        };

        placeOfferCompletedListener = (o, oldValue, newValue) -> {
            if (DevEnv.isDevMode()) {
                close();
            } else if (newValue) {
                // We need a bit of delay to avoid issues with fade out/fade in of 2 popups
                String key = "createOfferSuccessInfo";
                if (DontShowAgainLookup.showAgain(key)) {
                    UserThread.runAfter(() -> new Popup().headLine(Res.get("createOffer.success.headline"))
                                    .feedback(Res.get("createOffer.success.info"))
                                    .dontShowAgainId(key)
                                    .actionButtonTextWithGoTo("navigation.portfolio.myOpenOffers")
                                    .onAction(this::closeAndGoToOpenOffers)
                                    .onClose(this::closeAndGoToOpenOffers)
                                    .show(),
                            1);
                } else {
                    closeAndGoToOpenOffers();
                }
            }
        };

        marketPriceAvailableListener = (observable, oldValue, newValue) -> updatePriceToggle();

        getShowWalletFundedNotificationListener = (observable, oldValue, newValue) -> {
            if (newValue) {
                Notification walletFundedNotification = new Notification()
                        .headLine(Res.get("notification.walletUpdate.headline"))
                        .notification(Res.get("notification.walletUpdate.msg", HavenoUtils.formatXmr(model.getDataModel().getTotalToPay().get(), true)))
                        .autoClose();

                walletFundedNotification.show();
            }
        };

        buyerSecurityDepositInBTCListener = (observable, oldValue, newValue) -> {
            if (!newValue.equals("")) {
                Label depositInBTCInfo = OfferViewUtil.createPopOverLabel(model.getSecurityDepositPopOverLabel(newValue));
                buyerSecurityDepositInfoInputTextField.setContentForInfoPopOver(depositInBTCInfo);
            } else {
                buyerSecurityDepositInfoInputTextField.setContentForInfoPopOver(null);
            }
        };

        volumeListener = (observable, oldValue, newValue) -> {
            if (!newValue.equals("") && CurrencyUtil.isFiatCurrency(model.tradeCurrencyCode.get())) {
                Label popOverLabel = OfferViewUtil.createPopOverLabel(Res.get("offerbook.info.roundedFiatVolume"));
                volumeInfoInputTextField.setContentForPrivacyPopOver(popOverLabel);
            } else {
                volumeInfoInputTextField.hideIcon();
            }
        };

        missingCoinListener = (observable, oldValue, newValue) -> {
            if (!newValue.toString().equals("")) {
                final byte[] imageBytes = QRCode
                        .from(getMoneroURI())
                        .withSize(300, 300)
                        .to(ImageType.PNG)
                        .stream()
                        .toByteArray();
                Image qrImage = new Image(new ByteArrayInputStream(imageBytes));
                qrCodeImageView.setImage(qrImage);
            }
        };

        marketPriceMarginListener = (observable, oldValue, newValue) -> {
            if (marketBasedPriceInfoInputTextField != null) {
                String tooltip;
                if (newValue.equals("0.00")) {
                    if (model.isSellOffer()) {
                        tooltip = Res.get("createOffer.info.sellAtMarketPrice");
                    } else {
                        tooltip = Res.get("createOffer.info.buyAtMarketPrice");
                    }
                    final Label atMarketPriceLabel = OfferViewUtil.createPopOverLabel(tooltip);
                    marketBasedPriceInfoInputTextField.setContentForInfoPopOver(atMarketPriceLabel);
                } else if (newValue.contains("-")) {
                    if (model.isSellOffer()) {
                        tooltip = Res.get("createOffer.warning.sellBelowMarketPrice", newValue.substring(1));
                    } else {
                        tooltip = Res.get("createOffer.warning.buyAboveMarketPrice", newValue.substring(1));
                    }
                    final Label negativePercentageLabel = OfferViewUtil.createPopOverLabel(tooltip);
                    marketBasedPriceInfoInputTextField.setContentForWarningPopOver(negativePercentageLabel);
                } else if (!newValue.equals("")) {
                    if (model.isSellOffer()) {
                        tooltip = Res.get("createOffer.info.sellAboveMarketPrice", newValue);
                    } else {
                        tooltip = Res.get("createOffer.info.buyBelowMarketPrice", newValue);
                    }
                    Label positivePercentageLabel = OfferViewUtil.createPopOverLabel(tooltip);
                    marketBasedPriceInfoInputTextField.setContentForInfoPopOver(positivePercentageLabel);
                }
            }
        };

        isMinBuyerSecurityDepositListener = ((observable, oldValue, newValue) -> {
            if (newValue) {
                // show BTC
                buyerSecurityDepositPercentageLabel.setText(Res.getBaseCurrencyCode());
                buyerSecurityDepositInputTextField.setDisable(true);
            } else {
                // show %
                buyerSecurityDepositPercentageLabel.setText("%");
                buyerSecurityDepositInputTextField.setDisable(false);
            }
        });
    }

    private void closeAndGoToOpenOffers() {
        //go to open offers
        UserThread.runAfter(() ->
                        navigation.navigateTo(MainView.class, PortfolioView.class,
                                OpenOffersView.class),
                1, TimeUnit.SECONDS);
        close();
    }

    protected void updatePriceToggle() {
        int marketPriceAvailableValue = model.marketPriceAvailableProperty.get();
        if (marketPriceAvailableValue > -1) {
            boolean showPriceToggle = marketPriceAvailableValue == 1 &&
                    !model.getDataModel().paymentAccount.hasPaymentMethodWithId(HAL_CASH_ID);
            percentagePriceBox.setVisible(showPriceToggle);
            priceTypeToggleButton.setVisible(showPriceToggle);
            boolean fixedPriceSelected = !model.getDataModel().getUseMarketBasedPrice().get() || !showPriceToggle;
            updatePriceToggleButtons(fixedPriceSelected);
        }
    }

    private void addListeners() {
        model.tradeCurrencyCode.addListener(tradeCurrencyCodeListener);
        model.marketPriceAvailableProperty.addListener(marketPriceAvailableListener);
        model.marketPriceMargin.addListener(marketPriceMarginListener);
        model.volume.addListener(volumeListener);
        model.getDataModel().missingCoin.addListener(missingCoinListener);
        model.buyerSecurityDepositInBTC.addListener(buyerSecurityDepositInBTCListener);
        model.isMinBuyerSecurityDeposit.addListener(isMinBuyerSecurityDepositListener);

        // focus out
        amountTextField.focusedProperty().addListener(amountFocusedListener);
        minAmountTextField.focusedProperty().addListener(minAmountFocusedListener);
        fixedPriceTextField.focusedProperty().addListener(priceFocusedListener);
        triggerPriceInputTextField.focusedProperty().addListener(triggerPriceFocusedListener);
        marketBasedPriceTextField.focusedProperty().addListener(priceAsPercentageFocusedListener);
        volumeTextField.focusedProperty().addListener(volumeFocusedListener);
        buyerSecurityDepositInputTextField.focusedProperty().addListener(buyerSecurityDepositFocusedListener);

        // notifications
        model.getDataModel().getShowWalletFundedNotification().addListener(getShowWalletFundedNotificationListener);

        // warnings
        model.errorMessage.addListener(errorMessageListener);
        // model.getDataModel().feeFromFundingTxProperty.addListener(feeFromFundingTxListener);

        model.placeOfferCompleted.addListener(placeOfferCompletedListener);

        // UI actions
        paymentAccountsComboBox.setOnAction(paymentAccountsComboBoxSelectionHandler);
        currencyComboBox.setOnAction(currencyComboBoxSelectionHandler);
    }

    private void removeListeners() {
        model.tradeCurrencyCode.removeListener(tradeCurrencyCodeListener);
        model.marketPriceAvailableProperty.removeListener(marketPriceAvailableListener);
        model.marketPriceMargin.removeListener(marketPriceMarginListener);
        model.volume.removeListener(volumeListener);
        model.getDataModel().missingCoin.removeListener(missingCoinListener);
        model.buyerSecurityDepositInBTC.removeListener(buyerSecurityDepositInBTCListener);
        model.isMinBuyerSecurityDeposit.removeListener(isMinBuyerSecurityDepositListener);

        // focus out
        amountTextField.focusedProperty().removeListener(amountFocusedListener);
        minAmountTextField.focusedProperty().removeListener(minAmountFocusedListener);
        fixedPriceTextField.focusedProperty().removeListener(priceFocusedListener);
        triggerPriceInputTextField.focusedProperty().removeListener(triggerPriceFocusedListener);
        marketBasedPriceTextField.focusedProperty().removeListener(priceAsPercentageFocusedListener);
        volumeTextField.focusedProperty().removeListener(volumeFocusedListener);
        buyerSecurityDepositInputTextField.focusedProperty().removeListener(buyerSecurityDepositFocusedListener);

        // notifications
        model.getDataModel().getShowWalletFundedNotification().removeListener(getShowWalletFundedNotificationListener);

        // warnings
        model.errorMessage.removeListener(errorMessageListener);
        // model.getDataModel().feeFromFundingTxProperty.removeListener(feeFromFundingTxListener);

        model.placeOfferCompleted.removeListener(placeOfferCompletedListener);

        // UI actions
        paymentAccountsComboBox.setOnAction(null);
        currencyComboBox.setOnAction(null);
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
        gridPane.setPadding(new Insets(30, 25, -1, 25));
        gridPane.setHgap(5);
        gridPane.setVgap(5);
        GUIUtil.setDefaultTwoColumnConstraintsForGridPane(gridPane);
        scrollPane.setContent(gridPane);
    }

    private void addPaymentGroup() {
        paymentTitledGroupBg = addTitledGroupBg(gridPane, gridRow, 1, Res.get("offerbook.createOffer"));
        GridPane.setColumnSpan(paymentTitledGroupBg, 2);

        HBox paymentGroupBox = new HBox();
        paymentGroupBox.setAlignment(Pos.CENTER_LEFT);
        paymentGroupBox.setSpacing(12);
        paymentGroupBox.setPadding(new Insets(10, 0, 18, 0));

        final Tuple3<VBox, Label, ComboBox<PaymentAccount>> tradingAccountBoxTuple = addTopLabelComboBox(
                Res.get("shared.chooseTradingAccount"), Res.get("shared.chooseTradingAccount"));
        final Tuple3<VBox, Label, ComboBox<TradeCurrency>> currencyBoxTuple = addTopLabelComboBox(
                Res.get("shared.currency"), Res.get("list.currency.select"));

        currencySelection = currencyBoxTuple.first;
        paymentGroupBox.getChildren().addAll(tradingAccountBoxTuple.first, currencySelection);

        GridPane.setRowIndex(paymentGroupBox, gridRow);
        GridPane.setColumnSpan(paymentGroupBox, 2);
        GridPane.setMargin(paymentGroupBox, new Insets(Layout.FIRST_ROW_DISTANCE, 0, 0, 0));
        gridPane.getChildren().add(paymentGroupBox);

        tradingAccountBoxTuple.first.setMinWidth(800);
        paymentAccountsComboBox = tradingAccountBoxTuple.third;
        paymentAccountsComboBox.setMinWidth(tradingAccountBoxTuple.first.getMinWidth());
        paymentAccountsComboBox.setPrefWidth(tradingAccountBoxTuple.first.getMinWidth());
        editOfferElements.add(tradingAccountBoxTuple.first);

        // we display either currencyComboBox (multi currency account) or currencyTextField (single)
        currencyComboBox = currencyBoxTuple.third;
        currencyComboBox.setMaxWidth(tradingAccountBoxTuple.first.getMinWidth() / 2);
        editOfferElements.add(currencySelection);
        currencyComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(TradeCurrency tradeCurrency) {
                return tradeCurrency.getNameAndCode();
            }

            @Override
            public TradeCurrency fromString(String s) {
                return null;
            }
        });

        final Tuple3<Label, TextField, VBox> currencyTextFieldTuple = addTopLabelTextField(gridPane, gridRow, Res.get("shared.currency"), "", 5d);
        currencyTextField = currencyTextFieldTuple.second;
        currencyTextFieldBox = currencyTextFieldTuple.third;
        currencyTextFieldBox.setVisible(false);
        editOfferElements.add(currencyTextFieldBox);

        paymentGroupBox.getChildren().add(currencyTextFieldBox);
    }

    private void addAmountPriceGroup() {
        amountTitledGroupBg = addTitledGroupBg(gridPane, ++gridRow, 2,
                Res.get("createOffer.setAmountPrice"), Layout.COMPACT_GROUP_DISTANCE);
        GridPane.setColumnSpan(amountTitledGroupBg, 2);

        addAmountPriceFields();
        addSecondRow();
    }

    private void addOptionsGroup() {
        setDepositTitledGroupBg = addTitledGroupBg(gridPane, ++gridRow, 1,
                Res.get("shared.advancedOptions"), Layout.COMPACT_GROUP_DISTANCE);

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
        advancedOptionsBox.getChildren().addAll(getBuyerSecurityDepositBox(), tradeFeeFieldsBox);

        Tuple2<Button, Button> tuple = add2ButtonsAfterGroup(gridPane, ++gridRow,
                Res.get("shared.nextStep"), Res.get("shared.cancel"));
        nextButton = (AutoTooltipButton) tuple.first;
        nextButton.setMaxWidth(200);
        editOfferElements.add(nextButton);
        nextButton.disableProperty().bind(model.isNextButtonDisabled);
        cancelButton1 = (AutoTooltipButton) tuple.second;
        cancelButton1.setMaxWidth(200);
        editOfferElements.add(cancelButton1);
        cancelButton1.setDefaultButton(false);
        cancelButton1.setOnAction(e -> {
            close();
            model.getDataModel().swapTradeToSavings();
        });

        nextButton.setOnAction(e -> {
            if (model.isPriceInRange()) {
                onShowPayFundsScreen();
            }
        });
    }

    protected void hideOptionsGroup() {
        setDepositTitledGroupBg.setVisible(false);
        setDepositTitledGroupBg.setManaged(false);
        nextButton.setVisible(false);
        nextButton.setManaged(false);
        cancelButton1.setVisible(false);
        cancelButton1.setManaged(false);
        advancedOptionsBox.setVisible(false);
        advancedOptionsBox.setManaged(false);
    }

    private VBox getBuyerSecurityDepositBox() {
        Tuple3<HBox, InfoInputTextField, Label> tuple = getEditableValueBoxWithInfo(
                Res.get("createOffer.securityDeposit.prompt"));
        buyerSecurityDepositInfoInputTextField = tuple.second;
        buyerSecurityDepositInputTextField = buyerSecurityDepositInfoInputTextField.getInputTextField();
        buyerSecurityDepositPercentageLabel = tuple.third;
        // getEditableValueBox delivers BTC, so we overwrite it with %
        buyerSecurityDepositPercentageLabel.setText("%");

        Tuple2<Label, VBox> tradeInputBoxTuple = getTradeInputBox(tuple.first, model.getSecurityDepositLabel());
        VBox depositBox = tradeInputBoxTuple.second;
        buyerSecurityDepositLabel = tradeInputBoxTuple.first;
        depositBox.setMaxWidth(310);

        editOfferElements.add(buyerSecurityDepositInputTextField);
        editOfferElements.add(buyerSecurityDepositPercentageLabel);

        return depositBox;
    }

    private void addFundingGroup() {
        // don't increase gridRow as we removed button when this gets visible
        payFundsTitledGroupBg = addTitledGroupBg(gridPane, gridRow, 3,
                Res.get("createOffer.fundsBox.title"), Layout.COMPACT_GROUP_DISTANCE);
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

        addressTextField = addAddressTextField(gridPane, ++gridRow,
                Res.get("shared.tradeWalletAddress"));
        addressTextField.setVisible(false);

        balanceTextField = addBalanceTextField(gridPane, ++gridRow,
                Res.get("shared.tradeWalletBalance"));
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
        waitingForFundsSpinner = new BusyAnimation(false);
        waitingForFundsLabel = new AutoTooltipLabel();
        waitingForFundsLabel.setPadding(new Insets(5, 0, 0, 0));

        fundingHBox.getChildren().addAll(fundFromSavingsWalletButton,
                label,
                fundFromExternalWalletButton,
                waitingForFundsSpinner,
                waitingForFundsLabel);

        GridPane.setRowIndex(fundingHBox, ++gridRow);
        GridPane.setColumnSpan(fundingHBox, 2);
        GridPane.setMargin(fundingHBox, new Insets(5, 0, 0, 0));
        gridPane.getChildren().add(fundingHBox);

        placeOfferBox = new HBox();
        placeOfferBox.setSpacing(10);
        GridPane.setRowIndex(placeOfferBox, gridRow);
        GridPane.setColumnSpan(placeOfferBox, 2);
        GridPane.setMargin(placeOfferBox, new Insets(5, 20, 0, 0));
        gridPane.getChildren().add(placeOfferBox);

        placeOfferButton = new AutoTooltipButton();
        placeOfferButton.setOnAction(e -> onPlaceOffer());
        placeOfferButton.setMinHeight(40);
        placeOfferButton.setPadding(new Insets(0, 20, 0, 20));

        placeOfferBox.getChildren().add(placeOfferButton);
        placeOfferBox.visibleProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue) {
                fundingHBox.getChildren().remove(cancelButton2);
                placeOfferBox.getChildren().add(cancelButton2);
            } else if (!fundingHBox.getChildren().contains(cancelButton2)) {
                placeOfferBox.getChildren().remove(cancelButton2);
                fundingHBox.getChildren().add(cancelButton2);
            }
        });

        cancelButton2 = new AutoTooltipButton(Res.get("shared.cancel"));

        fundingHBox.getChildren().add(cancelButton2);

        cancelButton2.setOnAction(e -> {
            String key = "CreateOfferCancelAndFunded";
            if (model.getDataModel().getIsXmrWalletFunded().get() &&
                    preferences.showAgain(key)) {
                new Popup().backgroundInfo(Res.get("createOffer.warnCancelOffer"))
                        .closeButtonText(Res.get("shared.no"))
                        .actionButtonText(Res.get("shared.yesCancel"))
                        .onAction(() -> {
                            close();
                            model.getDataModel().swapTradeToSavings();
                        })
                        .dontShowAgainId(key)
                        .show();
            } else {
                close();
                model.getDataModel().swapTradeToSavings();
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
                addressTextField.getAddress(),
                model.getDataModel().getMissingCoin().get(),
                model.getPaymentLabel(),
                model.dataModel.getXmrWalletService().getWallet());
    }

    private void addAmountPriceFields() {
        // amountBox
        Tuple3<HBox, InputTextField, Label> amountValueCurrencyBoxTuple = getEditableValueBox(Res.get("createOffer.amount.prompt"));
        amountValueCurrencyBox = amountValueCurrencyBoxTuple.first;
        amountTextField = amountValueCurrencyBoxTuple.second;
        editOfferElements.add(amountTextField);
        amountBtcLabel = amountValueCurrencyBoxTuple.third;
        editOfferElements.add(amountBtcLabel);
        Tuple2<Label, VBox> amountInputBoxTuple = getTradeInputBox(amountValueCurrencyBox, model.getAmountDescription());
        amountDescriptionLabel = amountInputBoxTuple.first;
        editOfferElements.add(amountDescriptionLabel);
        VBox amountBox = amountInputBoxTuple.second;

        // x
        xLabel = new Label();
        xIcon = getIconForLabel(MaterialDesignIcon.CLOSE, "2em", xLabel);
        xIcon.getStyleClass().add("opaque-icon");
        xLabel.getStyleClass().add("opaque-icon-character");

        // price as percent
        Tuple3<HBox, InfoInputTextField, Label> priceAsPercentageTuple = getEditableValueBoxWithInfo(Res.get("createOffer.price.prompt"));

        priceAsPercentageValueCurrencyBox = priceAsPercentageTuple.first;
        marketBasedPriceInfoInputTextField = priceAsPercentageTuple.second;
        marketBasedPriceTextField = marketBasedPriceInfoInputTextField.getInputTextField();
        editOfferElements.add(marketBasedPriceTextField);
        marketBasedPriceLabel = priceAsPercentageTuple.third;
        editOfferElements.add(marketBasedPriceLabel);
        Tuple2<Label, VBox> priceAsPercentageInputBoxTuple = getTradeInputBox(priceAsPercentageValueCurrencyBox,
                model.getPercentagePriceDescription());
        percentagePriceDescriptionLabel = priceAsPercentageInputBoxTuple.first;

        getSmallIconForLabel(MaterialDesignIcon.CHART_LINE, percentagePriceDescriptionLabel, "small-icon-label");

        percentagePriceBox = priceAsPercentageInputBoxTuple.second;

        // =
        resultLabel = new AutoTooltipLabel("=");
        resultLabel.getStyleClass().add("opaque-icon-character");

        // volume
        Tuple3<HBox, InfoInputTextField, Label> volumeValueCurrencyBoxTuple = getEditableValueBoxWithInfo(Res.get("createOffer.volume.prompt"));
        volumeValueCurrencyBox = volumeValueCurrencyBoxTuple.first;
        volumeInfoInputTextField = volumeValueCurrencyBoxTuple.second;
        volumeTextField = volumeInfoInputTextField.getInputTextField();
        editOfferElements.add(volumeTextField);
        volumeCurrencyLabel = volumeValueCurrencyBoxTuple.third;
        editOfferElements.add(volumeCurrencyLabel);
        Tuple2<Label, VBox> volumeInputBoxTuple = getTradeInputBox(volumeValueCurrencyBox, model.volumeDescriptionLabel.get());
        volumeDescriptionLabel = volumeInputBoxTuple.first;
        editOfferElements.add(volumeDescriptionLabel);
        VBox volumeBox = volumeInputBoxTuple.second;

        firstRowHBox = new HBox();
        firstRowHBox.setSpacing(5);
        firstRowHBox.setAlignment(Pos.CENTER_LEFT);
        firstRowHBox.getChildren().addAll(amountBox, xLabel, percentagePriceBox, resultLabel, volumeBox);
        GridPane.setColumnSpan(firstRowHBox, 2);
        GridPane.setRowIndex(firstRowHBox, gridRow);
        GridPane.setMargin(firstRowHBox, new Insets(Layout.COMPACT_FIRST_ROW_AND_GROUP_DISTANCE, 10, 0, 0));
        gridPane.getChildren().add(firstRowHBox);
    }

    private void updatePriceToggleButtons(boolean fixedPriceSelected) {
        int marketPriceAvailable = model.marketPriceAvailableProperty.get();
        fixedPriceSelected = fixedPriceSelected || (marketPriceAvailable == 0);

        model.getDataModel().setUseMarketBasedPrice(marketPriceAvailable == 1 && !fixedPriceSelected);

        percentagePriceBox.setDisable(fixedPriceSelected);
        fixedPriceBox.setDisable(!fixedPriceSelected);

        if (fixedPriceSelected) {
            firstRowHBox.getChildren().remove(percentagePriceBox);
            secondRowHBox.getChildren().remove(fixedPriceBox);

            if (!firstRowHBox.getChildren().contains(fixedPriceBox))
                firstRowHBox.getChildren().add(2, fixedPriceBox);
            if (!secondRowHBox.getChildren().contains(percentagePriceBox))
                secondRowHBox.getChildren().add(2, percentagePriceBox);

            model.triggerPrice.set("");
            model.onTriggerPriceTextFieldChanged();
        } else {
            firstRowHBox.getChildren().remove(fixedPriceBox);
            secondRowHBox.getChildren().remove(percentagePriceBox);

            if (!firstRowHBox.getChildren().contains(percentagePriceBox))
                firstRowHBox.getChildren().add(2, percentagePriceBox);
            if (!secondRowHBox.getChildren().contains(fixedPriceBox))
                secondRowHBox.getChildren().add(2, fixedPriceBox);
        }

        triggerPriceVBox.setVisible(!fixedPriceSelected);
        model.onFixPriceToggleChange(fixedPriceSelected);
    }

    private void addSecondRow() {
        // price as traditional currency
        Tuple3<HBox, InputTextField, Label> priceValueCurrencyBoxTuple = getEditableValueBox(
                Res.get("createOffer.price.prompt"));
        priceValueCurrencyBox = priceValueCurrencyBoxTuple.first;
        fixedPriceTextField = priceValueCurrencyBoxTuple.second;
        editOfferElements.add(fixedPriceTextField);
        priceCurrencyLabel = priceValueCurrencyBoxTuple.third;
        editOfferElements.add(priceCurrencyLabel);
        Tuple2<Label, VBox> priceInputBoxTuple = getTradeInputBox(priceValueCurrencyBox, "");
        priceDescriptionLabel = priceInputBoxTuple.first;

        getSmallIconForLabel(MaterialDesignIcon.LOCK, priceDescriptionLabel, "small-icon-label");

        editOfferElements.add(priceDescriptionLabel);
        fixedPriceBox = priceInputBoxTuple.second;

        marketBasedPriceTextField.setPromptText(Res.get("shared.enterPercentageValue"));
        marketBasedPriceLabel.setText("%");

        Tuple3<HBox, InputTextField, Label> amountValueCurrencyBoxTuple = getEditableValueBox(Res.get("createOffer.amount.prompt"));
        minAmountValueCurrencyBox = amountValueCurrencyBoxTuple.first;
        minAmountTextField = amountValueCurrencyBoxTuple.second;
        editOfferElements.add(minAmountTextField);
        minAmountBtcLabel = amountValueCurrencyBoxTuple.third;
        editOfferElements.add(minAmountBtcLabel);

        Tuple2<Label, VBox> amountInputBoxTuple = getTradeInputBox(minAmountValueCurrencyBox, Res.get("createOffer.amountPriceBox.minAmountDescription"));

        fakeXLabel = new Label();
        fakeXIcon = getIconForLabel(MaterialDesignIcon.CLOSE, "2em", fakeXLabel);
        fakeXLabel.getStyleClass().add("opaque-icon-character");
        fakeXLabel.setVisible(false); // we just use it to get the same layout as the upper row

        // Fixed/Percentage toggle
        priceTypeToggleButton = getIconButton(MaterialDesignIcon.SWAP_VERTICAL);
        editOfferElements.add(priceTypeToggleButton);
        HBox.setMargin(priceTypeToggleButton, new Insets(16, 1.5, 0, 0));
        priceTypeToggleButton.setOnAction((actionEvent) ->
                updatePriceToggleButtons(model.getDataModel().getUseMarketBasedPrice().getValue()));

        // triggerPrice
        Tuple3<HBox, InfoInputTextField, Label> triggerPriceTuple3 = getEditableValueBoxWithInfo(Res.get("createOffer.triggerPrice.prompt"));
        triggerPriceHBox = triggerPriceTuple3.first;
        triggerPriceInfoInputTextField = triggerPriceTuple3.second;
        editOfferElements.add(triggerPriceInfoInputTextField);
        triggerPriceInputTextField = triggerPriceInfoInputTextField.getInputTextField();
        triggerPriceCurrencyLabel = triggerPriceTuple3.third;
        editOfferElements.add(triggerPriceCurrencyLabel);
        Tuple2<Label, VBox> triggerPriceTuple2 = getTradeInputBox(triggerPriceHBox, model.getTriggerPriceDescriptionLabel());
        triggerPriceDescriptionLabel = triggerPriceTuple2.first;
        triggerPriceDescriptionLabel.setPrefWidth(290);
        triggerPriceVBox = triggerPriceTuple2.second;

        secondRowHBox = new HBox();
        secondRowHBox.setSpacing(5);
        secondRowHBox.setAlignment(Pos.CENTER_LEFT);
        secondRowHBox.getChildren().addAll(amountInputBoxTuple.second, fakeXLabel, fixedPriceBox, priceTypeToggleButton, triggerPriceVBox);
        GridPane.setColumnSpan(secondRowHBox, 2);
        GridPane.setRowIndex(secondRowHBox, ++gridRow);
        GridPane.setColumnIndex(secondRowHBox, 0);
        GridPane.setMargin(secondRowHBox, new Insets(0, 10, 10, 0));
        gridPane.getChildren().add(secondRowHBox);
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
    // PayInfo
    ///////////////////////////////////////////////////////////////////////////////////////////

    private GridPane createInfoPopover() {
        GridPane infoGridPane = new GridPane();
        infoGridPane.setHgap(5);
        infoGridPane.setVgap(5);
        infoGridPane.setPadding(new Insets(10, 10, 10, 10));

        int i = 0;
        if (model.isSellOffer()) {
            addPayInfoEntry(infoGridPane, i++, Res.getWithCol("shared.tradeAmount"), model.getTradeAmount());
        }

        addPayInfoEntry(infoGridPane, i++, Res.getWithCol("shared.yourSecurityDeposit"), model.getSecurityDepositInfo());
        addPayInfoEntry(infoGridPane, i++, Res.getWithCol("createOffer.fundsBox.offerFee"), model.getTradeFee());
        Separator separator = new Separator();
        separator.setOrientation(Orientation.HORIZONTAL);
        separator.getStyleClass().add("offer-separator");
        GridPane.setConstraints(separator, 1, i++);
        infoGridPane.getChildren().add(separator);
        addPayInfoEntry(infoGridPane, i, Res.getWithCol("shared.total"),
                model.getTotalToPayInfo());
        return infoGridPane;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Helpers
    ///////////////////////////////////////////////////////////////////////////////////////////

    private ObservableList<PaymentAccount> getPaymentAccounts() {
        return filterPaymentAccounts(model.getDataModel().getPaymentAccounts());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Abstract Methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected abstract ObservableList<PaymentAccount> filterPaymentAccounts(ObservableList<PaymentAccount> paymentAccounts);
}
