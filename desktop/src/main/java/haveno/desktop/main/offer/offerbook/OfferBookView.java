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

package haveno.desktop.main.offer.offerbook;

import de.jensd.fx.fontawesome.AwesomeDude;
import de.jensd.fx.fontawesome.AwesomeIcon;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIconView;
import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.common.util.Tuple3;
import haveno.core.account.sign.SignedWitnessService;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.alert.PrivateNotificationManager;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.monetary.Price;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferFilterService;
import haveno.core.offer.OfferRestrictions;
import haveno.core.offer.OpenOffer;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.DontShowAgainLookup;
import haveno.core.util.coin.CoinFormatter;
import haveno.desktop.Navigation;
import haveno.desktop.common.view.ActivatableViewAndModel;
import haveno.desktop.components.AccountStatusTooltipLabel;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.AutoTooltipTableColumn;
import haveno.desktop.components.AutoTooltipTextField;
import haveno.desktop.components.AutocompleteComboBox;
import haveno.desktop.components.ColoredDecimalPlacesWithZerosText;
import haveno.desktop.components.HyperlinkWithIcon;
import haveno.desktop.components.InfoAutoTooltipLabel;
import haveno.desktop.components.PeerInfoIconTrading;
import haveno.desktop.main.MainView;
import haveno.desktop.main.account.AccountView;
import haveno.desktop.main.account.content.cryptoaccounts.CryptoAccountsView;
import haveno.desktop.main.account.content.traditionalaccounts.TraditionalAccountsView;
import haveno.desktop.main.funds.FundsView;
import haveno.desktop.main.funds.withdrawal.WithdrawalView;
import haveno.desktop.main.offer.OfferView;
import haveno.desktop.main.offer.OfferViewUtil;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.OfferDetailsWindow;
import haveno.desktop.main.portfolio.PortfolioView;
import haveno.desktop.main.portfolio.editoffer.EditOfferView;
import haveno.desktop.util.FormBuilder;
import haveno.desktop.util.GUIUtil;
import haveno.network.p2p.NodeAddress;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.util.Callback;
import javafx.util.StringConverter;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;
import org.fxmisc.easybind.monadic.MonadicBinding;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

import static haveno.desktop.util.FormBuilder.addTopLabelAutoToolTipTextField;

abstract public class OfferBookView<R extends GridPane, M extends OfferBookViewModel> extends ActivatableViewAndModel<R, M> {

    private final Navigation navigation;
    private final OfferDetailsWindow offerDetailsWindow;
    private final CoinFormatter formatter;
    private final PrivateNotificationManager privateNotificationManager;
    private final boolean useDevPrivilegeKeys;
    private final AccountAgeWitnessService accountAgeWitnessService;
    private final SignedWitnessService signedWitnessService;

    protected AutocompleteComboBox<TradeCurrency> currencyComboBox;
    private AutocompleteComboBox<PaymentMethod> paymentMethodComboBox;
    private AutoTooltipButton createOfferButton;
    private AutoTooltipTextField filterInputField;
    private ToggleButton matchingOffersToggleButton;
    private ToggleButton noDepositOffersToggleButton;
    private AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> amountColumn;
    private AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> volumeColumn;
    private AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> marketColumn;
    private AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> priceColumn;
    private AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> depositColumn;
    private AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> signingStateColumn;
    private AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> avatarColumn;
    private TableView<OfferBookListItem> tableView;

    private int gridRow = 0;
    private Label nrOfOffersLabel;
    private ListChangeListener<OfferBookListItem> offerListListener;
    private ChangeListener<Number> priceFeedUpdateCounterListener;
    private Subscription currencySelectionSubscriber;
    private static final int SHOW_ALL = 0;
    private Label disabledCreateOfferButtonTooltip;
    protected VBox currencyComboBoxContainer;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    OfferBookView(M model,
                  Navigation navigation,
                  OfferDetailsWindow offerDetailsWindow,
                  CoinFormatter formatter,
                  PrivateNotificationManager privateNotificationManager,
                  boolean useDevPrivilegeKeys,
                  AccountAgeWitnessService accountAgeWitnessService,
                  SignedWitnessService signedWitnessService) {
        super(model);

        this.navigation = navigation;
        this.offerDetailsWindow = offerDetailsWindow;
        this.formatter = formatter;
        this.privateNotificationManager = privateNotificationManager;
        this.useDevPrivilegeKeys = useDevPrivilegeKeys;
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.signedWitnessService = signedWitnessService;
    }

    @Override
    public void initialize() {
        root.setPadding(new Insets(15, 15, 5, 15));

        HBox offerToolsBox = new HBox();
        offerToolsBox.setAlignment(Pos.BOTTOM_LEFT);
        offerToolsBox.setSpacing(10);
        offerToolsBox.setPadding(new Insets(0, 0, 0, 0));

        Tuple3<VBox, Label, AutocompleteComboBox<TradeCurrency>> currencyBoxTuple = FormBuilder.addTopLabelAutocompleteComboBox(
                Res.get("offerbook.filterByCurrency"));
        currencyComboBoxContainer = currencyBoxTuple.first;
        currencyComboBox = currencyBoxTuple.third;
        currencyComboBox.setPrefWidth(250);
        currencyComboBox.getStyleClass().add("input-with-border");

        Tuple3<VBox, Label, AutocompleteComboBox<PaymentMethod>> paymentBoxTuple = FormBuilder.addTopLabelAutocompleteComboBox(
                Res.get("offerbook.filterByPaymentMethod"));
        paymentMethodComboBox = paymentBoxTuple.third;
        paymentMethodComboBox.setCellFactory(GUIUtil.getPaymentMethodCellFactory());
        paymentMethodComboBox.setPrefWidth(250);
        paymentMethodComboBox.getStyleClass().add("input-with-border");

        noDepositOffersToggleButton = new ToggleButton(Res.get("offerbook.filterNoDeposit"));
        noDepositOffersToggleButton.getStyleClass().add("toggle-button-no-slider");
        Tooltip noDepositOffersTooltip = new Tooltip(Res.get("offerbook.noDepositOffers"));
        Tooltip.install(noDepositOffersToggleButton, noDepositOffersTooltip);

        matchingOffersToggleButton = AwesomeDude.createIconToggleButton(AwesomeIcon.USER, null, "1.5em", null);
        matchingOffersToggleButton.getStyleClass().add("toggle-button-no-slider");
        matchingOffersToggleButton.setPrefHeight(27);
        Tooltip matchingOffersTooltip = new Tooltip(Res.get("offerbook.matchingOffers"));
        Tooltip.install(matchingOffersToggleButton, matchingOffersTooltip);

        createOfferButton = new AutoTooltipButton("");
        createOfferButton.setMinHeight(40);
        createOfferButton.setGraphicTextGap(10);
        createOfferButton.setStyle("-fx-padding: 7 25 7 25;");
        disabledCreateOfferButtonTooltip = new Label("");
        disabledCreateOfferButtonTooltip.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        disabledCreateOfferButtonTooltip.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        disabledCreateOfferButtonTooltip.prefWidthProperty().bind(createOfferButton.widthProperty());
        disabledCreateOfferButtonTooltip.prefHeightProperty().bind(createOfferButton.heightProperty());
        disabledCreateOfferButtonTooltip.setTooltip(new Tooltip(Res.get("offerbook.createOfferDisabled.tooltip")));
        disabledCreateOfferButtonTooltip.setManaged(false);
        disabledCreateOfferButtonTooltip.setVisible(false);

        var createOfferVBox = new VBox(createOfferButton, disabledCreateOfferButtonTooltip);
        createOfferVBox.setAlignment(Pos.BOTTOM_RIGHT);

        Tuple3<VBox, Label, AutoTooltipTextField> autoToolTipTextField = addTopLabelAutoToolTipTextField("");
        VBox filterBox = autoToolTipTextField.first;
        filterInputField = autoToolTipTextField.third;
        filterInputField.setPromptText(Res.get("shared.filter"));
        filterInputField.getStyleClass().add("input-with-border");

        offerToolsBox.getChildren().addAll(currencyBoxTuple.first, paymentBoxTuple.first,
                filterBox, noDepositOffersToggleButton, matchingOffersToggleButton, getSpacer(), createOfferVBox);

        GridPane.setHgrow(offerToolsBox, Priority.ALWAYS);
        GridPane.setRowIndex(offerToolsBox, gridRow);
        GridPane.setColumnSpan(offerToolsBox, 2);
        GridPane.setMargin(offerToolsBox, new Insets(0, 0, 0, 0));
        root.getChildren().add(offerToolsBox);

        tableView = new TableView<>();
        GUIUtil.applyTableStyle(tableView);

        GridPane.setRowIndex(tableView, ++gridRow);
        GridPane.setColumnIndex(tableView, 0);
        GridPane.setColumnSpan(tableView, 2);
        GridPane.setMargin(tableView, new Insets(10, 0, -10, 0));
        GridPane.setVgrow(tableView, Priority.ALWAYS);
        root.getChildren().add(tableView);

        marketColumn = getMarketColumn();

        priceColumn = getPriceColumn();
        tableView.getColumns().add(priceColumn);
        amountColumn = getAmountColumn();
        tableView.getColumns().add(amountColumn);
        volumeColumn = getVolumeColumn();
        tableView.getColumns().add(volumeColumn);
        AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> paymentMethodColumn = getPaymentMethodColumn();
        tableView.getColumns().add(paymentMethodColumn);
        depositColumn = getDepositColumn();
        tableView.getColumns().add(depositColumn);
        signingStateColumn = getSigningStateColumn();
        tableView.getColumns().add(signingStateColumn);
        avatarColumn = getAvatarColumn();
        tableView.getColumns().add(getActionColumn());
        tableView.getColumns().add(avatarColumn);

        tableView.getSortOrder().add(priceColumn);
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        Label placeholder = new AutoTooltipLabel(Res.get("table.placeholder.noItems", Res.get("shared.multipleOffers")));
        placeholder.setWrapText(true);
        tableView.setPlaceholder(placeholder);

        marketColumn.setComparator(Comparator.comparing(
                o -> CurrencyUtil.getCurrencyPair(o.getOffer().getCurrencyCode()),
                Comparator.nullsFirst(Comparator.naturalOrder())
        ));

        // We sort by % so we can also sort if SHOW ALL is selected
        Comparator<OfferBookListItem> marketBasedPriceComparator = (o1, o2) -> {
            Optional<Double> marketBasedPrice1 = model.getMarketBasedPrice(o1.getOffer());
            Optional<Double> marketBasedPrice2 = model.getMarketBasedPrice(o2.getOffer());
            if (marketBasedPrice1.isPresent() && marketBasedPrice2.isPresent()) {
                return Double.compare(marketBasedPrice1.get(), marketBasedPrice2.get());
            } else {
                return 0;
            }
        };
        // If we do not have a % price we use only fix price and sort by that
        priceColumn.setComparator(marketBasedPriceComparator.thenComparing((o1, o2) -> {
            Price price2 = o2.getOffer().getPrice();
            Price price1 = o1.getOffer().getPrice();
            if (price2 == null || price1 == null) {
                return 0;
            }
            if (OfferViewUtil.isShownAsSellOffer(model.getSelectedTradeCurrency().getCode(), model.getDirection())) {
                return price1.compareTo(price2);
            } else {
                return price2.compareTo(price1);
            }
        }));

        amountColumn.setComparator(Comparator.comparing(o -> o.getOffer().getMinAmount()));
        volumeColumn.setComparator(Comparator.comparing(o -> o.getOffer().getMinVolume(), Comparator.nullsFirst(Comparator.naturalOrder())));
        paymentMethodColumn.setComparator(Comparator.comparing(o -> Res.get(o.getOffer().getPaymentMethod().getId())));
        avatarColumn.setComparator(Comparator.comparing(o -> model.getNumTrades(o.getOffer())));
        depositColumn.setComparator(Comparator.comparing(item -> {
            boolean isSellOffer = item.getOffer().getDirection() == OfferDirection.SELL;
            BigInteger deposit = isSellOffer ?
                    item.getOffer().getMaxBuyerSecurityDeposit() :
                    item.getOffer().getMaxSellerSecurityDeposit();

            long amountValue = item.getOffer().getAmount().longValueExact();
            if ((deposit == null || amountValue == 0)) {
                return 0d;
            } else {
                return HavenoUtils.divide(deposit, BigInteger.valueOf(amountValue));
            }

        }, Comparator.nullsFirst(Comparator.naturalOrder())));

        signingStateColumn.setComparator(Comparator.comparing(e -> e.getWitnessAgeData(accountAgeWitnessService, signedWitnessService), Comparator.nullsFirst(Comparator.naturalOrder())));

        nrOfOffersLabel = new AutoTooltipLabel("");
        nrOfOffersLabel.setId("num-offers");
        GridPane.setHalignment(nrOfOffersLabel, HPos.LEFT);
        GridPane.setVgrow(nrOfOffersLabel, Priority.NEVER);
        GridPane.setValignment(nrOfOffersLabel, VPos.TOP);
        GridPane.setRowIndex(nrOfOffersLabel, ++gridRow);
        GridPane.setColumnIndex(nrOfOffersLabel, 0);
        GridPane.setMargin(nrOfOffersLabel, new Insets(10, 0, 0, 0));
        root.getChildren().add(nrOfOffersLabel);

        offerListListener = c -> UserThread.execute(() -> nrOfOffersLabel.setText(Res.get("offerbook.nrOffers", model.getOfferList().size())));

        // Fixes incorrect ordering of Available offers:
        // https://github.com/bisq-network/bisq-desktop/issues/588
        priceFeedUpdateCounterListener = (observable, oldValue, newValue) -> tableView.sort();
    }

    abstract protected String getMarketTitle();

    @Override
    protected void activate() {

        Map<String, Integer> offerCounts = OfferViewUtil.isShownAsBuyOffer(model.getDirection(), model.getSelectedTradeCurrency()) ? model.getSellOfferCounts() : model.getBuyOfferCounts();
        currencyComboBox.setCellFactory(GUIUtil.getTradeCurrencyCellFactory(Res.get("shared.oneOffer"),
                Res.get("shared.multipleOffers"),
                offerCounts));

        currencyComboBox.setConverter(new CurrencyStringConverter(currencyComboBox));
        currencyComboBox.getEditor().getStyleClass().add("combo-box-editor-bold");

        currencyComboBox.setAutocompleteItems(model.getTradeCurrencies(), model.getAllCurrencies());
        currencyComboBox.setVisibleRowCount(Math.min(currencyComboBox.getItems().size(), 10));

        currencyComboBox.setOnChangeConfirmed(e -> {
            if (currencyComboBox.getEditor().getText().isEmpty())
                currencyComboBox.getSelectionModel().select(SHOW_ALL);
            model.onSetTradeCurrency(currencyComboBox.getSelectionModel().getSelectedItem());
            paymentMethodComboBox.setAutocompleteItems(model.getPaymentMethods());
            model.updateSelectedPaymentMethod();
            updatePaymentMethodComboBoxEditor();
            model.onSetPaymentMethod(paymentMethodComboBox.getSelectionModel().getSelectedItem());
            updateCreateOfferButton();
        });
        updateCurrencyComboBoxFromModel();

        currencyComboBox.getEditor().setText(new CurrencyStringConverter(currencyComboBox).toString(currencyComboBox.getSelectionModel().getSelectedItem()));

        matchingOffersToggleButton.setSelected(model.useOffersMatchingMyAccountsFilter);
        matchingOffersToggleButton.disableProperty().bind(model.disableMatchToggle);
        matchingOffersToggleButton.setOnAction(e -> model.onShowOffersMatchingMyAccounts(matchingOffersToggleButton.isSelected()));

        noDepositOffersToggleButton.setSelected(model.showPrivateOffers);
        noDepositOffersToggleButton.setOnAction(e -> model.onShowPrivateOffers(noDepositOffersToggleButton.isSelected()));

        model.getOfferList().comparatorProperty().bind(tableView.comparatorProperty());

        amountColumn.sortTypeProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == TableColumn.SortType.DESCENDING) {
                amountColumn.setComparator(Comparator.comparing(o -> o.getOffer().getAmount(), Comparator.nullsFirst(Comparator.naturalOrder())));
            } else {
                amountColumn.setComparator(Comparator.comparing(o -> o.getOffer().getMinAmount(), Comparator.nullsFirst(Comparator.naturalOrder())));
            }
        });
        volumeColumn.sortTypeProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == TableColumn.SortType.DESCENDING) {
                volumeColumn.setComparator(Comparator.comparing(o -> o.getOffer().getVolume(), Comparator.nullsFirst(Comparator.naturalOrder())));
            } else {
                volumeColumn.setComparator(Comparator.comparing(o -> o.getOffer().getMinVolume(), Comparator.nullsFirst(Comparator.naturalOrder())));
            }
        });

        paymentMethodComboBox.setConverter(new PaymentMethodStringConverter(paymentMethodComboBox));
        paymentMethodComboBox.getEditor().getStyleClass().add("combo-box-editor-bold");

        paymentMethodComboBox.setAutocompleteItems(model.getPaymentMethods());
        paymentMethodComboBox.setVisibleRowCount(Math.min(paymentMethodComboBox.getItems().size(), 10));

        paymentMethodComboBox.setOnChangeConfirmed(e -> {
            if (paymentMethodComboBox.getEditor().getText().isEmpty())
                paymentMethodComboBox.getSelectionModel().select(SHOW_ALL);
            model.onSetPaymentMethod(paymentMethodComboBox.getSelectionModel().getSelectedItem());
            updateCurrencyComboBoxFromModel();
            updateSigningStateColumn();
        });

        updatePaymentMethodComboBoxEditor();

        createOfferButton.setOnAction(e -> onCreateOffer());

        MonadicBinding<Void> currencySelectionBinding = EasyBind.combine(
                model.showAllTradeCurrenciesProperty, model.tradeCurrencyCode,
                (showAll, code) -> {
                    if (showAll) {
                        volumeColumn.setTitleWithHelpText(Res.get("shared.amountMinMax"), Res.get("shared.amountHelp"));
                        priceColumn.setTitle(Res.get("shared.price"));

                        if (!tableView.getColumns().contains(marketColumn))
                            tableView.getColumns().add(0, marketColumn);
                    } else {
                        volumeColumn.setTitleWithHelpText(Res.get("offerbook.volume", code), Res.get("shared.amountHelp"));
                        priceColumn.setTitle(CurrencyUtil.getPriceWithCurrencyCode(code));

                        tableView.getColumns().remove(marketColumn);
                    }

                    updateSigningStateColumn();

                    return null;
                });

        currencySelectionSubscriber = currencySelectionBinding.subscribe((observable, oldValue, newValue) -> {
        });

        UserThread.execute(() -> tableView.setItems(model.getOfferList()));

        model.getOfferList().addListener(offerListListener);
        nrOfOffersLabel.setText(Res.get("offerbook.nrOffers", model.getOfferList().size()));

        model.priceFeedService.updateCounterProperty().addListener(priceFeedUpdateCounterListener);

        filterInputField.setOnKeyTyped(event -> {
            model.onFilterKeyTyped(filterInputField.getText());
        });
    }

    private void updatePaymentMethodComboBoxEditor() {
        if (model.showAllPaymentMethods)
            paymentMethodComboBox.getSelectionModel().select(SHOW_ALL);
        else
            paymentMethodComboBox.getSelectionModel().select(model.selectedPaymentMethod);
        paymentMethodComboBox.getEditor().setText(new PaymentMethodStringConverter(paymentMethodComboBox).toString(paymentMethodComboBox.getSelectionModel().getSelectedItem()));
    }

    protected void updateCurrencyComboBoxFromModel() {
        if (model.showAllTradeCurrenciesProperty.get()) {
            currencyComboBox.getSelectionModel().select(SHOW_ALL);
        } else {
            currencyComboBox.getSelectionModel().select(model.getSelectedTradeCurrency());
        }
    }

    private void updateSigningStateColumn() {
        if (model.hasSelectionAccountSigning()) {
            if (!tableView.getColumns().contains(signingStateColumn)) {
                tableView.getColumns().add(tableView.getColumns().indexOf(depositColumn) + 1, signingStateColumn);
            }
        } else {
            tableView.getColumns().remove(signingStateColumn);
        }
    }

    @Override
    protected void deactivate() {
        createOfferButton.setOnAction(null);
        matchingOffersToggleButton.setOnAction(null);
        matchingOffersToggleButton.disableProperty().unbind();
        noDepositOffersToggleButton.setOnAction(null);
        noDepositOffersToggleButton.disableProperty().unbind();
        model.getOfferList().comparatorProperty().unbind();

        volumeColumn.sortableProperty().unbind();
        priceColumn.sortableProperty().unbind();
        amountColumn.sortableProperty().unbind();
        model.getOfferList().comparatorProperty().unbind();

        model.getOfferList().removeListener(offerListListener);
        model.priceFeedService.updateCounterProperty().removeListener(priceFeedUpdateCounterListener);

        currencySelectionSubscriber.unsubscribe();
    }

    static class CurrencyStringConverter extends StringConverter<TradeCurrency> {
        private final ComboBox<TradeCurrency> comboBox;

        CurrencyStringConverter(ComboBox<TradeCurrency> comboBox) {
            this.comboBox = comboBox;
        }

        @Override
        public String toString(TradeCurrency item) {
            return item != null ? asString(item) : "";
        }

        @Override
        public TradeCurrency fromString(String query) {
            if (comboBox.getItems().isEmpty())
                return null;
            if (query.isEmpty())
                return specialShowAllItem();
            return comboBox.getItems().stream().
                    filter(item -> asString(item).equals(query)).
                    findAny().orElse(null);
        }

        private String asString(TradeCurrency item) {
            if (isSpecialShowAllItem(item))
                return Res.get(GUIUtil.SHOW_ALL_FLAG);
            if (isSpecialEditItem(item))
                return Res.get(GUIUtil.EDIT_FLAG);
            return item.getCode() + "  -  " + item.getName();
        }

        private boolean isSpecialShowAllItem(TradeCurrency item) {
            return item.getCode().equals(GUIUtil.SHOW_ALL_FLAG);
        }

        private boolean isSpecialEditItem(TradeCurrency item) {
            return item.getCode().equals(GUIUtil.EDIT_FLAG);
        }

        private TradeCurrency specialShowAllItem() {
            return comboBox.getItems().get(SHOW_ALL);
        }
    }

    static class PaymentMethodStringConverter extends StringConverter<PaymentMethod> {
        private final ComboBox<PaymentMethod> comboBox;

        PaymentMethodStringConverter(ComboBox<PaymentMethod> comboBox) {
            this.comboBox = comboBox;
        }

        @Override
        public String toString(PaymentMethod item) {
            return item != null ? asString(item) : "";
        }

        @Override
        public PaymentMethod fromString(String query) {
            if (comboBox.getItems().isEmpty())
                return null;
            if (query.isEmpty())
                return specialShowAllItem();
            return comboBox.getItems().stream().
                    filter(item -> asString(item).equals(query)).
                    findAny().orElse(null);
        }

        private String asString(PaymentMethod item) {
            if (isSpecialShowAllItem(item))
                return Res.get(GUIUtil.SHOW_ALL_FLAG);
            return Res.get(item.getId());
        }

        private boolean isSpecialShowAllItem(PaymentMethod item) {
            return item.getId().equals(GUIUtil.SHOW_ALL_FLAG);
        }

        private PaymentMethod specialShowAllItem() {
            return comboBox.getItems().get(SHOW_ALL);
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void enableCreateOfferButton() {
        createOfferButton.setDisable(false);
        disabledCreateOfferButtonTooltip.setManaged(false);
        disabledCreateOfferButtonTooltip.setVisible(false);
    }

    private void disableCreateOfferButton() {
        createOfferButton.setDisable(true);
        disabledCreateOfferButtonTooltip.setManaged(true);
        disabledCreateOfferButtonTooltip.setVisible(true);

        model.onCreateOffer();
    }

    public void setDirection(OfferDirection direction) {
        model.initWithDirection(direction);
        createOfferButton.setGraphic(GUIUtil.getCurrencyIconWithBorder(Res.getBaseCurrencyCode()));
        createOfferButton.setContentDisplay(ContentDisplay.RIGHT);
        createOfferButton.setId(direction == OfferDirection.SELL ? "sell-button-big" : "buy-button-big");
        avatarColumn.setTitle(direction == OfferDirection.SELL ? Res.get("shared.buyerUpperCase") : Res.get("shared.sellerUpperCase"));
        if (direction == OfferDirection.SELL) {
            noDepositOffersToggleButton.setVisible(false);
            noDepositOffersToggleButton.setManaged(false);
        } 
    }

    public void setOfferActionHandler(OfferView.OfferActionHandler offerActionHandler) {
        model.setOfferActionHandler(offerActionHandler);
    }

    public void onTabSelected(boolean isSelected) {
        if (model.isTabSelected == isSelected) {
            return;
        }
        model.onTabSelected(isSelected);

        if (isSelected) {
            updateCurrencyComboBoxFromModel();
            root.requestFocus();
            updateCreateOfferButton();
        }
        updateCreateOfferButton();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI actions
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onCreateOffer() {
        if (model.canCreateOrTakeOffer()) {
            if (!model.hasPaymentAccountForCurrency()) {
                new Popup().headLine(Res.get("offerbook.warning.noTradingAccountForCurrency.headline"))
                        .instruction(Res.get("offerbook.warning.noTradingAccountForCurrency.msg"))
                        .actionButtonText(Res.get("offerbook.setupNewAccount"))
                        .onAction(() -> {
                            navigation.setReturnPath(navigation.getCurrentPath());
                            if (CurrencyUtil.isTraditionalCurrency(model.getSelectedTradeCurrency().getCode())) {
                                navigation.navigateTo(MainView.class, AccountView.class, TraditionalAccountsView.class);
                            } else {
                                navigation.navigateTo(MainView.class, AccountView.class, CryptoAccountsView.class);
                            }
                        })
                        .width(725)
                        .show();
                return;
            }

            disableCreateOfferButton();
        }
    }

    private void onShowInfo(Offer offer, OfferFilterService.Result result) {
        switch (result) {
            case API_DISABLED:
                DevEnv.logErrorAndThrowIfDevMode("We are in desktop and in the taker position " +
                        "viewing offers, so it cannot be that we got that result as we are not an API user.");
                break;
            case HAS_NO_PAYMENT_ACCOUNT_VALID_FOR_OFFER:
                openPopupForMissingAccountSetup(offer);
                break;
            case HAS_NOT_SAME_PROTOCOL_VERSION:
                new Popup().warning(Res.get("offerbook.warning.wrongTradeProtocol")).show();
                break;
            case IS_IGNORED:
                new Popup().warning(Res.get("offerbook.warning.userIgnored")).show();
                break;
            case IS_OFFER_BANNED:
                new Popup().warning(Res.get("offerbook.warning.offerBlocked")).show();
                break;
            case IS_CURRENCY_BANNED:
                new Popup().warning(Res.get("offerbook.warning.currencyBanned")).show();
                break;
            case IS_PAYMENT_METHOD_BANNED:
                new Popup().warning(Res.get("offerbook.warning.paymentMethodBanned")).show();
                break;
            case IS_NODE_ADDRESS_BANNED:
                new Popup().warning(Res.get("offerbook.warning.nodeBlocked")).show();
                break;
            case REQUIRE_UPDATE_TO_NEW_VERSION:
                new Popup().warning(Res.get("offerbook.warning.requireUpdateToNewVersion")).show();
                break;
            case IS_INSUFFICIENT_COUNTERPARTY_TRADE_LIMIT:
                new Popup().warning(Res.get("offerbook.warning.counterpartyTradeRestrictions")).show();
                break;
            case IS_MY_INSUFFICIENT_TRADE_LIMIT:
                Optional<PaymentAccount> account = model.getMostMaturePaymentAccountForOffer(offer);
                if (account.isPresent()) {
                    long tradeLimit = model.accountAgeWitnessService.getMyTradeLimit(account.get(),
                            offer.getCurrencyCode(), offer.getMirroredDirection(), offer.hasBuyerAsTakerWithoutDeposit());
                    new Popup()
                            .warning(Res.get("popup.warning.tradeLimitDueAccountAgeRestriction.buyer",
                                    HavenoUtils.formatXmr(tradeLimit, true),
                                    Res.get("offerbook.warning.newVersionAnnouncement")))
                            .show();
                } else {
                    DevEnv.logErrorAndThrowIfDevMode("We don't found a payment account but got called the " +
                            "isInsufficientTradeLimit case.");
                }
                break;
            case ARBITRATOR_NOT_VALIDATED:
                new Popup().warning(Res.get("offerbook.warning.arbitratorNotValidated")).show();
                break;
            case SIGNATURE_NOT_VALIDATED:
                new Popup().warning(Res.get("offerbook.warning.signatureNotValidated")).show();
                break;
            case RESERVE_FUNDS_SPENT:
                new Popup().warning(Res.get("offerbook.warning.reserveFundsSpent")).show();
                break;
            case VALID:
                break;
            default:
                log.warn("Unhandled offer filter service result: " + result);
                break;
        }
    }

    private void onTakeOffer(Offer offer) {
        if (model.canCreateOrTakeOffer()) {
            if (offer.getDirection() == OfferDirection.SELL &&
                    offer.getPaymentMethod().getId().equals(PaymentMethod.CASH_DEPOSIT.getId())) {
                new Popup().confirmation(Res.get("popup.info.cashDepositInfo", offer.getBankId()))
                        .actionButtonText(Res.get("popup.info.cashDepositInfo.confirm"))
                        .onAction(() -> model.onTakeOffer(offer))
                        .show();
            } else {
                model.onTakeOffer(offer);
            }
        }
    }

    private void onRemoveOpenOffer(Offer offer) {
        if (model.isBootstrappedOrShowPopup()) {
            String key = "RemoveOfferWarning";
            if (DontShowAgainLookup.showAgain(key)) {
                String message = Res.get("popup.warning.removeOffer");
                new Popup().warning(message)
                        .actionButtonText(Res.get("shared.removeOffer"))
                        .onAction(() -> doRemoveOffer(offer))
                        .closeButtonText(Res.get("shared.dontRemoveOffer"))
                        .dontShowAgainId(key)
                        .show();
            } else {
                doRemoveOffer(offer);
            }
        }
    }

    private void onEditOpenOffer(Offer offer) {
        OpenOffer openOffer = model.getOpenOffer(offer);
        if (openOffer != null) {
            navigation.navigateToWithData(openOffer, MainView.class, PortfolioView.class, EditOfferView.class);
        }
    }

    private void doRemoveOffer(Offer offer) {
        String key = "WithdrawFundsAfterRemoveOfferInfo";
        model.onRemoveOpenOffer(offer,
                () -> {
                    log.debug(Res.get("offerbook.removeOffer.success"));
                    if (DontShowAgainLookup.showAgain(key))
                        new Popup().instruction(Res.get("offerbook.withdrawFundsHint", Res.get("funds.tab.withdrawal")))
                                .actionButtonTextWithGoTo("funds.tab.withdrawal")
                                .onAction(() -> navigation.navigateTo(MainView.class, FundsView.class, WithdrawalView.class))
                                .dontShowAgainId(key)
                                .show();
                },
                (message) -> {
                    log.error(message);
                    new Popup().warning(Res.get("offerbook.removeOffer.failed", message)).show();
                });
    }

    private void openPopupForMissingAccountSetup(Offer offer) {
        String headline = Res.get("offerbook.warning.noMatchingAccount.headline");

        var accountViewClass = offer.isTraditionalOffer() ? TraditionalAccountsView.class : CryptoAccountsView.class;

        new Popup().headLine(headline)
                .instruction(Res.get("offerbook.warning.noMatchingAccount.msg"))
                .actionButtonTextWithGoTo("mainView.menu.account")
                .onAction(() -> {
                    navigation.setReturnPath(navigation.getCurrentPath());
                    navigation.navigateTo(MainView.class, AccountView.class, accountViewClass);
                }).show();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Table
    ///////////////////////////////////////////////////////////////////////////////////////////

    private AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> getAmountColumn() {
        AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> column = new AutoTooltipTableColumn<>(Res.get("shared.XMRMinMax"), Res.get("shared.amountHelp"));
        column.setMinWidth(100);
        column.setSortable(true);
        column.getStyleClass().add("number-column");
        column.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OfferBookListItem, OfferBookListItem> call(
                            TableColumn<OfferBookListItem, OfferBookListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final OfferBookListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty)
                                    setGraphic(new ColoredDecimalPlacesWithZerosText(model.getAmount(item), GUIUtil.AMOUNT_DECIMALS_WITH_ZEROS));
                                else
                                    setGraphic(null);
                            }
                        };
                    }
                });
        return column;
    }

    private AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> getMarketColumn() {
        AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> column = new AutoTooltipTableColumn<>(Res.get("shared.market")) {
            {
                setMinWidth(40);
            }
        };
        column.getStyleClass().addAll("number-column");
        column.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OfferBookListItem, OfferBookListItem> call(
                            TableColumn<OfferBookListItem, OfferBookListItem> column) {
                        return new TableCell<>() {

                            @Override
                            public void updateItem(final OfferBookListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty)
                                    setText(CurrencyUtil.getCurrencyPair(item.getOffer().getCurrencyCode()));
                                else
                                    setText("");
                            }
                        };
                    }
                });
        return column;
    }

    private ObservableValue<OfferBookListItem> asPriceDependentObservable(OfferBookListItem item) {
        return item.getOffer().isUseMarketBasedPrice()
                ? EasyBind.map(model.priceFeedService.updateCounterProperty(), n -> item)
                : new ReadOnlyObjectWrapper<>(item);
    }

    private AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> getPriceColumn() {
        AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> column = new AutoTooltipTableColumn<>("") {
            {
                setMinWidth(130);
            }
        };
        column.getStyleClass().add("number-column");
        column.setCellValueFactory(offer -> asPriceDependentObservable(offer.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OfferBookListItem, OfferBookListItem> call(
                            TableColumn<OfferBookListItem, OfferBookListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final OfferBookListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    setGraphic(getPriceAndPercentage(item));
                                } else {
                                    setGraphic(null);
                                }
                            }

                            private HBox getPriceAndPercentage(OfferBookListItem item) {
                                Offer offer = item.getOffer();
                                boolean useMarketBasedPrice = offer.isUseMarketBasedPrice();
                                boolean isShownAsBuyOffer = OfferViewUtil.isShownAsBuyOffer(offer);
                                MaterialDesignIcon icon = useMarketBasedPrice ? MaterialDesignIcon.CHART_LINE : MaterialDesignIcon.LOCK;
                                String info;

                                if (useMarketBasedPrice) {
                                    double marketPriceMargin = offer.getMarketPriceMarginPct();
                                    if (marketPriceMargin == 0) {
                                        if (isShownAsBuyOffer) {
                                            info = Res.get("offerbook.info.sellAtMarketPrice");
                                        } else {
                                            info = Res.get("offerbook.info.buyAtMarketPrice");
                                        }
                                    } else {
                                        String absolutePriceMargin = model.getAbsolutePriceMargin(offer);
                                        if (marketPriceMargin > 0) {
                                            if (isShownAsBuyOffer) {
                                                info = Res.get("offerbook.info.sellBelowMarketPrice", absolutePriceMargin);
                                            } else {
                                                info = Res.get("offerbook.info.buyAboveMarketPrice", absolutePriceMargin);
                                            }
                                        } else {
                                            if (isShownAsBuyOffer) {
                                                info = Res.get("offerbook.info.sellAboveMarketPrice", absolutePriceMargin);
                                            } else {
                                                info = Res.get("offerbook.info.buyBelowMarketPrice", absolutePriceMargin);
                                            }
                                        }
                                    }
                                } else {
                                    if (isShownAsBuyOffer) {
                                        info = Res.get("offerbook.info.sellAtFixedPrice");
                                    } else {
                                        info = Res.get("offerbook.info.buyAtFixedPrice");
                                    }
                                }
                                InfoAutoTooltipLabel priceLabel = new InfoAutoTooltipLabel(model.getPrice(item),
                                        icon, ContentDisplay.RIGHT, info);
                                priceLabel.setTextAlignment(TextAlignment.RIGHT);
                                AutoTooltipLabel percentageLabel = new AutoTooltipLabel(model.getPriceAsPercentage(item));
                                percentageLabel.setOpacity(useMarketBasedPrice ? 1 : 0.4);

                                HBox hBox = new HBox();
                                hBox.setSpacing(5);
                                hBox.getChildren().addAll(priceLabel, percentageLabel);
                                hBox.setPadding(new Insets(7, 0, 0, 0));
                                return hBox;
                            }
                        };
                    }
                });
        return column;
    }

    private AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> getVolumeColumn() {
        AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> column = new AutoTooltipTableColumn<>("") {
            {
                setMinWidth(125);
                setSortable(true);
            }
        };
        column.getStyleClass().add("number-column");
        column.setCellValueFactory(offer -> asPriceDependentObservable(offer.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OfferBookListItem, OfferBookListItem> call(
                            TableColumn<OfferBookListItem, OfferBookListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final OfferBookListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    if (item.getOffer().getPrice() == null) {
                                        setText(Res.get("shared.na"));
                                        setGraphic(null);
                                    } else {
                                        setText("");
                                        setGraphic(new ColoredDecimalPlacesWithZerosText(model.getVolume(item),
                                                model.getNumberOfDecimalsForVolume(item)));
                                    }
                                } else {
                                    setText("");
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
        return column;
    }

    private AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> getPaymentMethodColumn() {
        AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> column = new AutoTooltipTableColumn<>(Res.get("shared.paymentMethod")) {
            {
                setMinWidth(80);
            }
        };

        column.getStyleClass().add("number-column");
        column.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OfferBookListItem, OfferBookListItem> call(TableColumn<OfferBookListItem, OfferBookListItem> column) {
                        return new TableCell<>() {
                            private HyperlinkWithIcon field;

                            @Override
                            public void updateItem(final OfferBookListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {

                                    Offer offer = item.getOffer();
                                    if (model.isOfferBanned(offer)) {
                                        setGraphic(new AutoTooltipLabel(model.getPaymentMethod(item)));
                                    } else {
                                        if (offer.isXmrAutoConf()) {
                                            field = new HyperlinkWithIcon(model.getPaymentMethod(item), AwesomeIcon.ROCKET);
                                        } else {
                                            field = new HyperlinkWithIcon(model.getPaymentMethod(item));
                                        }
                                        field.setOnAction(event -> {
                                            offerDetailsWindow.show(offer);
                                        });
                                        field.setTooltip(new Tooltip(model.getPaymentMethodToolTip(item)));
                                        setGraphic(field);
                                    }
                                } else {
                                    setGraphic(null);
                                    if (field != null)
                                        field.setOnAction(null);
                                }
                            }
                        };
                    }
                });
        return column;
    }


    private AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> getDepositColumn() {
        AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> column = new AutoTooltipTableColumn<>(
                Res.get("offerbook.deposit"),
                Res.get("offerbook.deposit.help")) {
            {
                setMinWidth(70);
                setSortable(true);
            }
        };

        column.getStyleClass().add("number-column");
        column.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        column.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OfferBookListItem, OfferBookListItem> call(
                            TableColumn<OfferBookListItem, OfferBookListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final OfferBookListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    var isSellOffer = item.getOffer().getDirection() == OfferDirection.SELL;
                                    var deposit = isSellOffer ? item.getOffer().getMaxBuyerSecurityDeposit() :
                                            item.getOffer().getMaxSellerSecurityDeposit();
                                    if (deposit == null) {
                                        setText(Res.get("shared.na"));
                                        setGraphic(null);
                                    } else {
                                        setText("");
                                        String rangePrefix = item.getOffer().isRange() ? "<= " : "";
                                        setGraphic(new ColoredDecimalPlacesWithZerosText(rangePrefix + model.formatDepositString(
                                                deposit, item.getOffer().getAmount().longValueExact()),
                                                GUIUtil.AMOUNT_DECIMALS_WITH_ZEROS));
                                    }
                                } else {
                                    setText("");
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
        return column;
    }

    private TableColumn<OfferBookListItem, OfferBookListItem> getActionColumn() {
        TableColumn<OfferBookListItem, OfferBookListItem> column = new AutoTooltipTableColumn<>(Res.get("shared.actions")) {
            {
                setMinWidth(180);
                setSortable(false);
            }
        };
        column.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        column.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<OfferBookListItem, OfferBookListItem> call(TableColumn<OfferBookListItem, OfferBookListItem> column) {
                        return new TableCell<>() {
                            OfferFilterService.Result canTakeOfferResult = null;

                            @Override
                            public void updateItem(final OfferBookListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                final ImageView iconView = new ImageView();
                                final AutoTooltipButton button = new AutoTooltipButton();
    
                                {
                                    button.setGraphic(iconView);
                                    button.setGraphicTextGap(10);
                                    button.setPrefWidth(10000);
                                }
    
                                MaterialDesignIconView iconView2 = new MaterialDesignIconView(MaterialDesignIcon.PENCIL);
                                final AutoTooltipButton button2 = new AutoTooltipButton();
    
                                {
                                    button2.setGraphic(iconView2);
                                    button2.setGraphicTextGap(10);
                                    button2.setPrefWidth(10000);
                                }
    
                                final HBox hbox = new HBox();
    
                                {
                                    hbox.setSpacing(8);
                                    hbox.setAlignment(Pos.CENTER);
                                    hbox.getChildren().add(button);
                                    hbox.getChildren().add(button2);
                                    HBox.setHgrow(button, Priority.ALWAYS);
                                    HBox.setHgrow(button2, Priority.ALWAYS);
                                }

                                TableRow<OfferBookListItem> tableRow = getTableRow();
                                if (item != null && !empty) {
                                    Offer offer = item.getOffer();
                                    boolean myOffer = model.isMyOffer(offer);

                                    // https://github.com/bisq-network/bisq/issues/4986
                                    if (tableRow != null) {
                                        canTakeOfferResult = model.offerFilterService.canTakeOffer(offer, false);
                                        if (canTakeOfferResult.isValid() || myOffer) {
                                            tableRow.getStyleClass().remove("row-faded");
                                        } else {
                                            if (!tableRow.getStyleClass().contains("row-faded")) tableRow.getStyleClass().add("row-faded");
                                            hbox.getStyleClass().add("cell-faded");
                                        }

                                        if (myOffer) {
                                            button.setDefaultButton(false);
                                            tableRow.setOnMousePressed(null);
                                        } else if (canTakeOfferResult.isValid()) {
                                            // set first row button as default
                                            button.setDefaultButton(getIndex() == 0);
                                            tableRow.setOnMousePressed(null);
                                        } else {
                                            button.setDefaultButton(false);
                                            tableRow.setOnMousePressed(e -> {
                                                // ugly hack to get the icon clickable when deactivated
                                                if (!(e.getTarget() instanceof ImageView || e.getTarget() instanceof Canvas))
                                                    onShowInfo(offer, canTakeOfferResult);
                                            });
                                        }
                                    }

                                    String title;
                                    if (myOffer) {
                                        iconView.setId("image-remove");
                                        title = Res.get("shared.remove");
                                        button.setOnAction(e -> onRemoveOpenOffer(offer));

                                        iconView2.setSize("16px");
                                        button2.updateText(Res.get("shared.edit"));
                                        button2.setOnAction(e -> onEditOpenOffer(offer));
                                        button2.setManaged(true);
                                        button2.setVisible(true);
                                    } else {
                                        boolean isSellOffer = OfferViewUtil.isShownAsSellOffer(offer);
                                        boolean isPrivateOffer = offer.isPrivateOffer();
                                        if (isPrivateOffer) {
                                            Label lockLabel = FormBuilder.getIcon(AwesomeIcon.LOCK, "16px");
                                            lockLabel.setStyle(lockLabel.getStyle() + "; -fx-text-fill: white;");
                                            button.setGraphic(lockLabel);
                                        } else {
                                            iconView.setId(isSellOffer ? "image-buy-white" : "image-sell-white");
                                            iconView.setFitHeight(16);
                                            iconView.setFitWidth(16);
                                        }
                                        button.setId(isSellOffer ? "buy-button" : "sell-button");
                                        button.setStyle("-fx-text-fill: white");
                                        title = Res.get(isSellOffer ? "mainView.menu.buyXmr" : "mainView.menu.sellXmr");
                                        button.setTooltip(new Tooltip(Res.get("offerbook.takeOfferButton.tooltip", model.getDirectionLabelTooltip(offer))));
                                        button.setOnAction(e -> onTakeOffer(offer));
                                        button2.setManaged(false);
                                        button2.setVisible(false);
                                    }

                                    if (!myOffer) {
                                        if (canTakeOfferResult == null) {
                                            canTakeOfferResult = model.offerFilterService.canTakeOffer(offer, false);
                                        }

                                        if (!canTakeOfferResult.isValid()) {
                                            button.setOnAction(e -> onShowInfo(offer, canTakeOfferResult));
                                        }
                                    }

                                    button.updateText(title);
                                    setPadding(new Insets(0, 15, 0, 0));
                                    setGraphic(hbox);
                                } else {
                                    setGraphic(null);
                                    button.setOnAction(null);
                                    button2.setOnAction(null);
                                    if (tableRow != null) {
                                        tableRow.setOnMousePressed(null);
                                        tableRow.getStyleClass().remove("row-faded");
                                    }
                                }
                            }
                        };
                    }
                });
        return column;
    }

    private AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> getSigningStateColumn() {
        AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> column = new AutoTooltipTableColumn<>(
                Res.get("offerbook.timeSinceSigning"),
                Res.get("offerbook.timeSinceSigning.help",
                        SignedWitnessService.SIGNER_AGE_DAYS,
                        HavenoUtils.formatXmr(OfferRestrictions.TOLERATED_SMALL_TRADE_AMOUNT, true))) {
            {
                setMinWidth(60);
                setSortable(true);
            }
        };

        column.getStyleClass().add("number-column");
        column.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        column.setCellFactory(new Callback<>() {
            @Override
            public TableCell<OfferBookListItem, OfferBookListItem> call(TableColumn<OfferBookListItem, OfferBookListItem> column) {
                return new TableCell<>() {
                    @Override
                    public void updateItem(final OfferBookListItem item, boolean empty) {
                        super.updateItem(item, empty);

                        if (item != null && !empty) {
                            var witnessAgeData = item.getWitnessAgeData(accountAgeWitnessService, signedWitnessService);
                            var label = witnessAgeData.isSigningRequired()
                                    ? new AccountStatusTooltipLabel(witnessAgeData)
                                    : new InfoAutoTooltipLabel(witnessAgeData.getDisplayString(), witnessAgeData.getIcon(), ContentDisplay.RIGHT, witnessAgeData.getInfo());
                            setGraphic(label);
                        } else {
                            setGraphic(null);
                        }
                    }
                };
            }
        });
        return column;
    }

    private AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> getAvatarColumn() {
        AutoTooltipTableColumn<OfferBookListItem, OfferBookListItem> column = new AutoTooltipTableColumn<>(Res.get("offerbook.trader")) {
            {
                setMinWidth(60);
                setMaxWidth(60);
                setSortable(true);
            }
        };
        column.getStyleClass().addAll("avatar-column");
        column.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        column.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<OfferBookListItem, OfferBookListItem> call(TableColumn<OfferBookListItem, OfferBookListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final OfferBookListItem newItem, boolean empty) {
                                super.updateItem(newItem, empty);
                                if (newItem != null && !empty) {
                                    final Offer offer = newItem.getOffer();
                                    final NodeAddress makersNodeAddress = offer.getOwnerNodeAddress();
                                    String role = Res.get("peerInfoIcon.tooltip.maker");
                                    int numTrades = model.getNumTrades(offer);
                                    PeerInfoIconTrading peerInfoIcon = new PeerInfoIconTrading(makersNodeAddress,
                                            role,
                                            numTrades,
                                            privateNotificationManager,
                                            offer,
                                            model.preferences,
                                            model.accountAgeWitnessService,
                                            useDevPrivilegeKeys);
                                    setGraphic(peerInfoIcon);
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
        return column;
    }

    @NotNull
    private Region getSpacer() {
        final Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private void updateCreateOfferButton() {
        createOfferButton.setText(Res.get("offerbook.createNewOffer",
                model.getDirection() == OfferDirection.BUY ? Res.get("shared.buy").toUpperCase() : Res.get("shared.sell").toUpperCase(),
                getTradeCurrencyCode()));
    }

    abstract String getTradeCurrencyCode();
}
