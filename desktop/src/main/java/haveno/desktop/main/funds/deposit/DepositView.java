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

package haveno.desktop.main.funds.deposit;

import com.google.inject.Inject;
import com.google.inject.name.Named;

import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon;

import haveno.common.ThreadUtils;
import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.common.util.Tuple2;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.Preferences;
import haveno.core.util.validation.NumberValidator;
import haveno.core.util.FormattingUtils;
import haveno.core.util.ParsingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.xmr.listeners.XmrBalanceListener;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.Navigation;
import haveno.desktop.common.view.ActivatableView;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.AddressTextField;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.HyperlinkWithIcon;
import haveno.desktop.components.InputTextField;
import haveno.desktop.components.indicator.TxConfidenceIndicator;
import haveno.desktop.components.list.FilterBox;
import haveno.desktop.main.MainView;
import haveno.desktop.main.funds.FundsView;
import haveno.desktop.main.funds.transactions.TransactionsView;
import haveno.desktop.main.overlays.windows.QRCodeWindow;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.GlyphsDude;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.util.Callback;
import monero.wallet.model.MoneroTxWallet;
import monero.wallet.model.MoneroWalletListener;
import org.bitcoinj.core.Coin;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;
import org.jetbrains.annotations.NotNull;

@FxmlView
public class DepositView extends ActivatableView<VBox, Void> {

    @FXML
    VBox heroBox;
    @FXML
    TableView<DepositListItem> tableView;
    private FilterBox filterBox;
    @FXML
    TableColumn<DepositListItem, DepositListItem> addressColumn, balanceColumn, confirmationsColumn, usageColumn;
    private ImageView qrCodeImageView;
    private StackPane qrCodePane;
    private ImageView qrCodeImageViewSmall;
    private StackPane qrCodePaneSmall;
    private AddressTextField addressTextField;
    private Hyperlink generateNewAddressLink;
    private HyperlinkWithIcon toggleAddressesLink;
    private InputTextField amountTextField;
    private VBox heroContent;
    private Region heroTopSpacer;
    private Region heroBottomSpacer;
    private Label heroTitleLabel;
    private VBox balanceBox;
    private Label balanceLabel;
    private Label fiatLabel;
    private HBox statusBox;
    private Label statusLabel;
    private TxConfidenceIndicator statusIndicator;
    private Tooltip statusTooltip;
    private static final String THREAD_ID = DepositView.class.getName();
    private static final double HERO_TOP_MAX = 130; // cap on the hero's top spacer so it stops descending on tall windows

    private final XmrWalletService xmrWalletService;
    private final Preferences preferences;
    private final PriceFeedService priceFeedService;
    private final Navigation navigation;
    private final CoinFormatter formatter;
    private final DecimalFormat fiatFormat;
    private BigInteger totalBalance = BigInteger.ZERO;
    private BigInteger pendingBalance = BigInteger.ZERO;
    private MoneroTxWallet pendingTx;
    private final ChangeListener<Number> priceChangeListener = (observable, oldValue, newValue) -> updateBalanceDisplay();
    private String paymentLabelString;
    private final ObservableList<DepositListItem> observableList = FXCollections.observableArrayList();
    private final FilteredList<DepositListItem> filteredList = new FilteredList<>(observableList);
    private final SortedList<DepositListItem> sortedList = new SortedList<>(filteredList);
    private XmrBalanceListener balanceListener;
    private MoneroWalletListener walletListener;
    private Subscription amountTextFieldSubscription;
    private ChangeListener<DepositListItem> tableViewSelectionListener;
    private Tooltip transientTooltip;
    private int numAddresses;

    // amount is optional, so empty is valid, but a non-number or negative value is flagged
    private final NumberValidator amountValidator = new NumberValidator() {
        @Override
        public ValidationResult validate(String input) {
            if (input == null || input.trim().isEmpty()) return new ValidationResult(true);
            ValidationResult result = validateIfNumber(input);
            return result.isValid ? result.and(validateIfNotNegative(input)) : result;
        }
    };

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    private DepositView(XmrWalletService xmrWalletService,
                        Preferences preferences,
                        PriceFeedService priceFeedService,
                        Navigation navigation,
                        @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter) {
        this.xmrWalletService = xmrWalletService;
        this.preferences = preferences;
        this.priceFeedService = priceFeedService;
        this.navigation = navigation;
        this.formatter = formatter;
        fiatFormat = (DecimalFormat) NumberFormat.getNumberInstance(GlobalSettings.getLocale());
        fiatFormat.setMinimumFractionDigits(2);
        fiatFormat.setMaximumFractionDigits(2);
    }

    @Override
    public void initialize() {
        GUIUtil.applyTableStyle(tableView);
        filterBox = new FilterBox();
        filterBox.initialize(filteredList, tableView);
        filterBox.setPromptText(Res.get("shared.filter"));
        filterBox.setInputFillWidth(120);

        paymentLabelString = Res.get("funds.deposit.fundHavenoWallet");
        addressColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.address")));
        balanceColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.balanceWithCur", Res.getBaseCurrencyCode())));
        confirmationsColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.confirmations")));
        usageColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.usage")));

        // set loading placeholder
        Label placeholderLabel = new Label("Loading...");
        tableView.setPlaceholder(placeholderLabel);
        tableView.getStyleClass().addAll("non-interactive-table", "clickable-rows");

        ThreadUtils.execute(() -> {

            // ensure an unused address exists and get the main wallet address
            XmrAddressEntry baseAddressEntry = null;
            try {
                xmrWalletService.getFreshAddressEntry();
                baseAddressEntry = xmrWalletService.getBaseAddressEntry();
            } catch (Exception e) {
                log.warn("Failed to initialize deposit addresses", e);
            }
            String baseAddress = baseAddressEntry == null ? null : baseAddressEntry.getAddressString();

            UserThread.execute(() -> {
                tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
                tableView.setPlaceholder(new AutoTooltipLabel(Res.get("funds.deposit.noAddresses")));
                tableViewSelectionListener = (observableValue, oldValue, newValue) -> {
                    if (newValue != null) setAddress(newValue.getAddressString(), newValue.isBaseAddress());
                };

                setAddressColumnCellFactory();
                setBalanceColumnCellFactory();
                setUsageColumnCellFactory();
                setConfidenceColumnCellFactory();

                addressColumn.setComparator(Comparator.comparing(DepositListItem::getAddressString));
                balanceColumn.setComparator(Comparator.comparing(DepositListItem::getBalanceAsBI));
                confirmationsColumn.setComparator(Comparator.comparingLong(o -> o.getNumConfirmationsSinceFirstUsed()));
                usageColumn.setComparator(Comparator.comparing(DepositListItem::getUsage));
                tableView.getSortOrder().add(usageColumn);
                tableView.setItems(sortedList);

                Tuple2<StackPane, ImageView> qrCodeTuple = GUIUtil.getBigXmrQrCodePane(220); // under the default size for vertical room
                qrCodePane = qrCodeTuple.first;
                qrCodeImageView = qrCodeTuple.second;
                Tuple2<StackPane, ImageView> qrCodeTupleSmall = GUIUtil.getSmallXmrQrCodePane();
                qrCodePaneSmall = qrCodeTupleSmall.first;
                qrCodeImageViewSmall = qrCodeTupleSmall.second;

                for (StackPane pane : List.of(qrCodePane, qrCodePaneSmall)) {
                    Tooltip.install(pane, new Tooltip(Res.get("shared.openLargeQRWindow")));
                    pane.setOnMouseClicked(e -> UserThread.runAfter(
                                    () -> new QRCodeWindow(getPaymentUri()).show(),
                                    200, TimeUnit.MILLISECONDS));
                }

                addressTextField = new AddressTextField(Res.get("funds.deposit.mainWalletAddress"));
                addressTextField.setPaymentLabel(paymentLabelString);
                addressTextField.setMaxWidth(820);
                VBox.setMargin(addressTextField, new Insets(10, 0, 0, 0));

                amountTextField = new InputTextField();
                amountTextField.setLabelFloat(true);
                amountTextField.getStyleClass().add("label-float");
                amountTextField.setPromptText(Res.get("funds.deposit.amount"));
                amountTextField.setMaxWidth(260);
                amountTextField.setValidator(amountValidator);
                VBox.setMargin(amountTextField, new Insets(8, 0, 0, 0)); // headroom for the floating label
                if (DevEnv.isDevMode())
                    amountTextField.setText("10");

                generateNewAddressLink = new Hyperlink(Res.get("funds.deposit.generateAddress"));
                generateNewAddressLink.setOnAction(event -> {
                    generateNewAddressLink.setVisited(false);
                    onGenerateNewAddress();
                });
                toggleAddressesLink = new HyperlinkWithIcon(Res.get("funds.deposit.showAddresses", numAddresses), FontAwesomeIcon.ANGLE_DOWN);
                toggleAddressesLink.setOnAction(event -> {
                    toggleAddressesLink.setVisited(false);
                    boolean show = !tableView.isVisible();
                    setAddressListVisible(show);
                    preferences.setDepositAddressesExpanded(show);
                });
                HBox linksBox = new HBox(30, generateNewAddressLink, toggleAddressesLink);
                linksBox.setAlignment(Pos.CENTER);

                // filter sits left in a zero-base zone growing equally with the right spacer, keeping the links centered
                filterBox.setMaxWidth(360);
                HBox filterZone = new HBox(filterBox);
                filterZone.setAlignment(Pos.CENTER_LEFT);
                filterZone.setMinWidth(0);
                filterZone.setPrefWidth(0);
                HBox.setHgrow(filterBox, Priority.ALWAYS);
                Region actionSpacer = new Region();
                HBox actionRow = new HBox(filterZone, linksBox, actionSpacer);
                actionRow.setAlignment(Pos.CENTER);
                HBox.setHgrow(filterZone, Priority.ALWAYS);
                HBox.setHgrow(actionSpacer, Priority.ALWAYS);

                createBalanceBox();

                heroTitleLabel = new AutoTooltipLabel(Res.get("funds.deposit.fundWallet"));
                heroTitleLabel.getStyleClass().add("deposit-hero-title");

                heroContent = new VBox(12, heroTitleLabel, qrCodePane, qrCodePaneSmall, balanceBox, addressTextField, amountTextField, actionRow);
                heroContent.setAlignment(Pos.TOP_CENTER);

                // spacers seat the collapsed hero in the upper third; unmanaged when expanded so it sits compact at the top
                heroTopSpacer = new Region();
                heroBottomSpacer = new Region();
                heroTopSpacer.setMinHeight(8);
                heroBottomSpacer.setMinHeight(0);
                VBox.setVgrow(heroBottomSpacer, Priority.ALWAYS);
                // pref height, not the lagging actual, keeps the first layout pass correct so the hero doesn't visibly shift
                heroTopSpacer.prefHeightProperty().bind(Bindings.createDoubleBinding(() -> {
                    double available = heroBox.getHeight() - heroBox.getInsets().getTop() - heroBox.getInsets().getBottom();
                    double third = Math.max(0, (available - heroContent.prefHeight(-1)) * 0.3);
                    return Math.min(third, HERO_TOP_MAX);
                }, heroBox.heightProperty(), heroContent.heightProperty(), heroTitleLabel.managedProperty()));

                heroBox.getChildren().addAll(heroTopSpacer, heroContent, heroBottomSpacer);

                // hide the title when the window is too short, rather than clipping it under the tabs
                heroBox.heightProperty().addListener((o, ov, nv) -> updateHeroTitleVisibility());
                heroContent.heightProperty().addListener((o, ov, nv) -> updateHeroTitleVisibility());

                // restore the saved show/hide state; deposit to the main wallet by default
                setAddressListVisible(preferences.isDepositAddressesExpanded());
                if (baseAddress != null) setAddress(baseAddress, true);

                balanceListener = new XmrBalanceListener() {
                    @Override
                    public void onBalanceChanged(BigInteger balance) {
                        updateList();
                    }
                };

                walletListener = new MoneroWalletListener() {
                    @Override
                    public void onNewBlock(long height) {
                        updateList();
                    }
                };
            });
        }, THREAD_ID);
    }

    @Override
    protected void activate() {
        ThreadUtils.execute(() -> {
            UserThread.execute(() -> {
                filterBox.activate();
                tableView.getSelectionModel().selectedItemProperty().addListener(tableViewSelectionListener);
                sortedList.comparatorProperty().bind(tableView.comparatorProperty());

                // restore the saved show/hide state on each visit
                if (toggleAddressesLink != null) setAddressListVisible(preferences.isDepositAddressesExpanded());

                // try to update deposits list
                try {
                    updateList();
                } catch (Exception e) {
                    log.warn("Could not update deposits list");
                    e.printStackTrace();
                }

                xmrWalletService.addBalanceListener(balanceListener);
                xmrWalletService.addWalletListener(walletListener);
                priceFeedService.updateCounterProperty().addListener(priceChangeListener);

                amountTextFieldSubscription = EasyBind.subscribe(amountTextField.textProperty(), t -> {
                    addressTextField.setAmount(HavenoUtils.parseXmrOrElse(t, BigInteger.ZERO));
                    updateQRCode();
                });
            });
        }, THREAD_ID);
    }

    @Override
    protected void deactivate() {
        ThreadUtils.execute(() -> {
            filterBox.deactivate();
            tableView.getSelectionModel().selectedItemProperty().removeListener(tableViewSelectionListener);
            sortedList.comparatorProperty().unbind();
            observableList.forEach(DepositListItem::cleanup);
            xmrWalletService.removeBalanceListener(balanceListener);
            xmrWalletService.removeWalletListener(walletListener);
            priceFeedService.updateCounterProperty().removeListener(priceChangeListener);
            amountTextFieldSubscription.unsubscribe();
        }, THREAD_ID);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI handlers
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onGenerateNewAddress() {
        ThreadUtils.execute(() -> {

            // reuse an unused address if available, otherwise create one
            XmrAddressEntry entry = null;
            try {
                entry = xmrWalletService.getFreshAddressEntry();
                updateList();
            } catch (Exception e) {
                log.warn("Failed to get fresh address entry", e);
            }
            XmrAddressEntry freshAddressEntry = entry;

            UserThread.execute(() -> {
                if (freshAddressEntry == null) return;
                boolean alreadyShown = freshAddressEntry.getAddressString().equals(addressTextField.getAddress());
                selectAddress(freshAddressEntry.getAddressString());
                if (alreadyShown) showTransientTooltip(generateNewAddressLink, Res.get("funds.deposit.stillUnused"));
            });
        }, THREAD_ID);
    }

    // total balance with a fiat approximation and incoming funds grouped beneath the QR
    private void createBalanceBox() {
        balanceLabel = new AutoTooltipLabel();
        balanceLabel.getStyleClass().add("deposit-balance-amount");

        fiatLabel = new AutoTooltipLabel();
        fiatLabel.getStyleClass().add("deposit-balance-fiat");

        // incoming funds status, shown while deposits confirm
        statusIndicator = new TxConfidenceIndicator();
        statusIndicator.setId("funds-confidence");
        statusTooltip = new Tooltip();
        statusIndicator.setTooltip(statusTooltip);
        statusLabel = new AutoTooltipLabel();
        statusBox = new HBox(8, statusIndicator, statusLabel);
        statusBox.getStyleClass().add("deposit-incoming");
        statusBox.setAlignment(Pos.CENTER);
        statusBox.setVisible(false);
        statusBox.setManaged(false);
        VBox.setMargin(statusBox, new Insets(4, 0, 0, 0));

        // incoming funds are viewable in the transactions list
        Tooltip.install(statusBox, new Tooltip(Res.get("funds.deposit.incoming.goToTransactions")));
        statusBox.setOnMouseClicked(e -> navigation.navigateTo(MainView.class, FundsView.class, TransactionsView.class));

        balanceBox = new VBox(2, balanceLabel, fiatLabel, statusBox);
        balanceBox.setAlignment(Pos.CENTER);
        balanceBox.setMaxWidth(Region.USE_PREF_SIZE);
    }

    private void updateBalanceDisplay() {
        if (balanceLabel == null) return;
        balanceLabel.setText(HavenoUtils.formatXmr(totalBalance, true));
        String fiatText = getFiatText();
        fiatLabel.setText(fiatText == null ? "" : fiatText);
        fiatLabel.setVisible(fiatText != null);
        fiatLabel.setManaged(fiatText != null);

        boolean hasPending = pendingBalance.compareTo(BigInteger.ZERO) > 0;
        statusBox.setVisible(hasPending);
        statusBox.setManaged(hasPending);
        if (hasPending) {
            statusLabel.setText(Res.get("funds.deposit.incoming", HavenoUtils.formatXmr(pendingBalance, true)));
            statusIndicator.setVisible(pendingTx != null);
            statusIndicator.setManaged(pendingTx != null);
            if (pendingTx != null) GUIUtil.updateConfidence(pendingTx, statusTooltip, statusIndicator);
        }
    }

    // the total approximated in the user's preferred currency, or null when zero or no price is available
    private String getFiatText() {
        if (totalBalance.signum() == 0) return null; // zero needs no approximation
        TradeCurrency currency = preferences.getPreferredTradeCurrency();
        if (currency == null) return null;
        MarketPrice marketPrice = priceFeedService.getMarketPrice(currency.getCode());
        if (marketPrice == null || !marketPrice.isPriceAvailable()) return null;
        double fiatValue = HavenoUtils.atomicUnitsToXmr(totalBalance) * marketPrice.getPrice();
        fiatFormat.setMaximumFractionDigits(CurrencyUtil.isPricePrecise(currency.getCode()) ? 8 : 2); // crypto/metals need more precision than fiat's 2 decimals
        return "≈ " + fiatFormat.format(fiatValue) + " " + currency.getCode();
    }

    private void setAddress(String address, boolean isBaseAddress) {
        addressTextField.setLabel(Res.get(isBaseAddress ? "funds.deposit.mainWalletAddress" : "funds.deposit.depositAddress"));
        addressTextField.setAddress(address);
        updateQRCode();
    }

    private void updateQRCode() {
        if (addressTextField.getAddress() != null && !addressTextField.getAddress().isEmpty()) {
            final byte[] imageBytes = GUIUtil.generateQrCodePng(getPaymentUri(), 300, 300);
            Image qrImage = new Image(new ByteArrayInputStream(imageBytes));
            qrCodeImageView.setImage(qrImage);
            qrCodeImageViewSmall.setImage(qrImage);
        }
    }

    private void showTransientTooltip(Node node, String text) {
        if (transientTooltip != null) transientTooltip.hide();
        Tooltip tooltip = new Tooltip(text);
        tooltip.getStyleClass().add("notice-tooltip");
        Text infoIcon = GlyphsDude.createIcon(FontAwesomeIcon.INFO_CIRCLE, "1.231em");
        infoIcon.getStyleClass().add("notice-tooltip-icon");
        tooltip.setGraphic(infoIcon);
        transientTooltip = tooltip;
        Bounds bounds = node.localToScreen(node.getBoundsInLocal());
        tooltip.show(node, bounds.getMinX(), bounds.getMaxY() + 5);
        UserThread.runAfter(tooltip::hide, 2);
    }

    // hide the title when the collapsed hero has no room for it, so it never clips under the tabs;
    // the reserve normalizes the test to the with-title height so toggling cannot oscillate,
    // and pref heights make it correct before layout so deciding never causes a visible shift
    private void updateHeroTitleVisibility() {
        if (tableView.isVisible()) return; // expanded keeps the title hidden
        double available = heroBox.getHeight() - heroBox.getInsets().getTop() - heroBox.getInsets().getBottom();
        double reserve = heroTitleLabel.isManaged() ? 0 : heroTitleLabel.prefHeight(-1) + heroContent.getSpacing();
        boolean roomy = available >= heroContent.prefHeight(-1) + reserve + 6;
        if (roomy != heroTitleLabel.isVisible()) {
            heroTitleLabel.setVisible(roomy);
            heroTitleLabel.setManaged(roomy);
            heroTopSpacer.setMinHeight(roomy ? 8 : 0); // without the title the top pad is dead space
        }
    }

    private void setAddressListVisible(boolean visible) {
        filterBox.setVisible(visible); // stays managed so its zone keeps the links centered
        tableView.setVisible(visible);
        tableView.setManaged(visible);

        // collapsed: title, big QR, and balance centered via the spacers; expanded: compact small-QR hero over the table
        heroTopSpacer.setManaged(!visible);
        heroBottomSpacer.setManaged(!visible);
        heroTitleLabel.setVisible(!visible);
        heroTitleLabel.setManaged(!visible);
        qrCodePane.setVisible(!visible);
        qrCodePane.setManaged(!visible);
        qrCodePaneSmall.setVisible(visible);
        qrCodePaneSmall.setManaged(visible);
        balanceBox.setVisible(!visible); // the table shows per-address balances when expanded
        balanceBox.setManaged(!visible);
        VBox.setVgrow(heroBox, visible ? Priority.NEVER : Priority.ALWAYS);
        // collapsed: shrink to the visible area so the title-hide logic sees the clip;
        // expanded: hold the content height so the growing table cannot eat into the hero
        heroBox.setMinHeight(visible ? Region.USE_COMPUTED_SIZE : 0);
        heroBox.setPadding(visible ? new Insets(8, 0, 5, 0) : new Insets(0, 0, 5, 0)); // collapsed relies on the top spacer

        updateToggleAddressesText();
        Label chevron = new Label();
        GlyphsDude.setIcon(chevron, visible ? FontAwesomeIcon.ANGLE_UP : FontAwesomeIcon.ANGLE_DOWN, "1.231em");
        chevron.setMinWidth(20);
        chevron.setOpacity(0.7);
        chevron.setPadding(Insets.EMPTY);
        chevron.getStyleClass().addAll("hyperlink", "no-underline");
        toggleAddressesLink.setIcon(chevron);
    }

    private void updateToggleAddressesText() {
        toggleAddressesLink.setText(tableView.isVisible() ? Res.get("funds.deposit.hideAddresses") : Res.get("funds.deposit.showAddresses", numAddresses));
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void updateList() {

        // create deposit list items
        List<XmrAddressEntry> addressEntries = xmrWalletService.getAddressEntries();
        List<DepositListItem> items = new ArrayList<>();
        for (XmrAddressEntry addressEntry : addressEntries) {
            if (addressEntry.isTradePayout()) continue; // do not show trade payout addresses
            items.add(new DepositListItem(addressEntry, xmrWalletService, formatter));
        }

        // find the total balance, incoming funds, and the tx with fewest confirmations
        BigInteger total = BigInteger.ZERO;
        BigInteger pendingAmount = BigInteger.ZERO;
        MoneroTxWallet fewestConfirmationsTx = null;
        try {
            total = xmrWalletService.getBalance();
            pendingAmount = total.subtract(xmrWalletService.getAvailableBalance());
            if (pendingAmount.compareTo(BigInteger.ZERO) > 0) {
                long fewestConfirmations = Long.MAX_VALUE;
                for (MoneroTxWallet tx : xmrWalletService.getTxsWithIncomingOutputs()) {
                    long confirmations = tx.getNumConfirmations() == null ? 0 : tx.getNumConfirmations();
                    if (confirmations >= XmrWalletService.NUM_BLOCKS_UNLOCK) continue;
                    if (confirmations < fewestConfirmations) {
                        fewestConfirmations = confirmations;
                        fewestConfirmationsTx = tx;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error checking for incoming funds", e);
        }
        BigInteger totalFinal = total;
        BigInteger pendingAmountFinal = pendingAmount;
        MoneroTxWallet pendingTxFinal = fewestConfirmationsTx;

        // update list, keeping the shown address selected or defaulting to the main wallet
        UserThread.execute(() -> {

            // update the balance block and address count
            totalBalance = totalFinal;
            pendingBalance = pendingAmountFinal;
            pendingTx = pendingTxFinal;
            updateBalanceDisplay();
            numAddresses = items.size();
            if (toggleAddressesLink != null) updateToggleAddressesText();

            String shownAddress = addressTextField == null ? null : addressTextField.getAddress();
            observableList.forEach(DepositListItem::cleanup);
            observableList.setAll(items);
            observableList.stream()
                    .filter(item -> item.getAddressString().equals(shownAddress))
                    .findAny()
                    .or(() -> observableList.stream().filter(DepositListItem::isBaseAddress).findAny())
                    .ifPresentOrElse(item -> tableView.getSelectionModel().select(item), () -> {
                        if (!sortedList.isEmpty()) tableView.getSelectionModel().select(0);
                    });
        });
    }

    private void selectAddress(String address) {
        observableList.stream()
                .filter(item -> item.getAddressString().equals(address))
                .findAny()
                .ifPresent(item -> tableView.getSelectionModel().select(item));
    }

    private Coin getAmount() {
        return ParsingUtils.parseToCoin(amountTextField.getText(), formatter);
    }

    @NotNull
    private String getPaymentUri() {
        return GUIUtil.getMoneroURI(addressTextField.getAddress(), HavenoUtils.coinToAtomicUnits(getAmount()), paymentLabelString);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // ColumnCellFactories
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void setUsageColumnCellFactory() {
        usageColumn.setCellValueFactory((addressListItem) -> new ReadOnlyObjectWrapper<>(addressListItem.getValue()));
        usageColumn.setCellFactory(new Callback<>() {

            @Override
            public TableCell<DepositListItem, DepositListItem> call(TableColumn<DepositListItem,
                    DepositListItem> column) {
                return new TableCell<>() {

                    @Override
                    public void updateItem(final DepositListItem item, boolean empty) {
                        super.updateItem(item, empty);
                        if (item != null && !empty) {
                            Label usageLabel = new AutoTooltipLabel(item.getUsage());
                            usageLabel.getStyleClass().add("highlight-text");
                            setGraphic(usageLabel);
                        } else {
                            setGraphic(null);
                        }
                    }
                };
            }
        });
    }

    private void setAddressColumnCellFactory() {
        addressColumn.setCellValueFactory((addressListItem) -> new ReadOnlyObjectWrapper<>(addressListItem.getValue()));

        addressColumn.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<DepositListItem, DepositListItem> call(TableColumn<DepositListItem,
                            DepositListItem> column) {
                        return new TableCell<>() {

                            private HyperlinkWithIcon field;

                            @Override
                            public void updateItem(final DepositListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    String address = item.getAddressString();
                                    setGraphic(new AutoTooltipLabel(address));
                                } else {
                                    setGraphic(null);
                                    if (field != null)
                                        field.setOnAction(null);
                                }
                            }
                        };
                    }
                });
    }

    private void setBalanceColumnCellFactory() {
        balanceColumn.setCellValueFactory((addressListItem) -> new ReadOnlyObjectWrapper<>(addressListItem.getValue()));
        balanceColumn.getStyleClass().add("highlight-text");
        balanceColumn.setCellFactory(new Callback<>() {

            @Override
            public TableCell<DepositListItem, DepositListItem> call(TableColumn<DepositListItem,
                    DepositListItem> column) {
                return new TableCell<>() {

                    @Override
                    public void updateItem(final DepositListItem item, boolean empty) {
                        super.updateItem(item, empty);
                        if (item != null && !empty) {
                            if (textProperty().isBound())
                                textProperty().unbind();

                            textProperty().bind(item.balanceProperty());
                        } else {
                            textProperty().unbind();
                            setText("");
                        }
                    }
                };
            }
        });
    }


    private void setConfidenceColumnCellFactory() {
        confirmationsColumn.setCellValueFactory((addressListItem) ->
                new ReadOnlyObjectWrapper<>(addressListItem.getValue()));
        confirmationsColumn.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<DepositListItem, DepositListItem> call(TableColumn<DepositListItem,
                            DepositListItem> column) {
                        return new TableCell<>() {

                            @Override
                            public void updateItem(final DepositListItem item, boolean empty) {
                                super.updateItem(item, empty);

                                if (item != null && !empty) {
                                    setGraphic(item.getTxConfidenceIndicator());
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
    }
}
