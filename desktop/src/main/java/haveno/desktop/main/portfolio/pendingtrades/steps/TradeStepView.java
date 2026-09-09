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

package haveno.desktop.main.portfolio.pendingtrades.steps;

import monero.wallet.model.MoneroTxWallet;
import haveno.core.util.FormattingUtils;
import javafx.css.PseudoClass;
import javafx.geometry.VPos;
import java.util.function.Consumer;
import static com.google.common.base.Preconditions.checkNotNull;
import haveno.desktop.util.GlyphsDude;
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon;
import haveno.common.ClockWatcher;
import haveno.common.UserThread;
import haveno.common.util.Tuple4;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeResult;
import haveno.core.support.dispute.mediation.MediationResultState;
import haveno.core.trade.ArbitratorTrade;
import haveno.core.trade.Contract;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.MakerTrade;
import haveno.core.trade.TakerTrade;
import haveno.core.trade.Trade;
import haveno.core.user.Preferences;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.BusyAnimation;
import haveno.desktop.components.PopOverWrapper;
import haveno.desktop.components.controlsfx.control.PopOver;
import haveno.desktop.util.GUIUtil;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.beans.binding.Bindings;
import javafx.scene.control.ToggleButton;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.portfolio.pendingtrades.PendingTradesViewModel;
import haveno.desktop.main.portfolio.pendingtrades.TradeStepInfo;
import haveno.desktop.main.portfolio.pendingtrades.TradeSubView;
import static haveno.desktop.util.FormBuilder.addButtonBusyAnimationLabel;
import static haveno.desktop.util.FormBuilder.addMultilineLabel;

import haveno.network.p2p.BootstrapListener;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.beans.property.BooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import haveno.desktop.components.SimpleMarkdownLabel;
import haveno.desktop.main.MainView;
import haveno.desktop.main.support.SupportView;
import haveno.desktop.main.support.dispute.client.arbitration.ArbitrationClientView;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class TradeStepView extends VBox {
    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    protected final PendingTradesViewModel model;
    protected final Trade trade;
    protected final Preferences preferences;
    protected final GridPane gridPane;

    private Subscription tradePeriodStateSubscription, tradeStateSubscription, disputeStateSubscription, mediationResultStateSubscription, syncUpdateSubscription;
    protected int gridRow = 0;
    private Label timeLeftTextField;
    private Label deadlineDate;
    private Label duration;
    private final VBox actionPane;
    private VBox deadlinePane;
    private VBox sidebar;
    private VBox helpPane;
    private final PopOverWrapper durationPopover = new PopOverWrapper();
    private VBox depositsPane;
    private VBox depositDetails;
    private ToggleButton depositsToggle;
    private Label depositsTitle;
    private Label depositsIcon;
    private Label depositsChevron;
    private Subscription payoutSubscription;
    private final Label syncLabel = new Label();
    private boolean confirmationInProgress;
    protected TradeConfirmationPane confirmationPane;
    private ChangeListener<Boolean> walletSyncedListener;
    private boolean active;
    private boolean completed;
    private Consumer<String> stepCaptionHandler;
    private Consumer<String> stepWarningHandler;
    private String stepCaption;
    private boolean subscriptionsRegistered;
    private ProgressBar timeLeftProgressBar;
    private TradeDepositView selfDeposit;
    private TradeDepositView peerDeposit;
    private TradeDepositView payout;
    private TradeStepInfo tradeStepInfo;
    private Subscription selfTxIdSubscription;
    private Subscription peerTxIdSubscription;
    private ClockWatcher.Listener clockListener;
    private final ChangeListener<String> errorMessageListener;
    protected Label infoLabel;
    private Popup acceptMediationResultPopup;
    private BootstrapListener bootstrapListener;
    private TradeSubView.ChatCallback chatCallback;
    protected Runnable closeCallback;
    private ChangeListener<Boolean> pendingTradesInitializedListener;
    protected Label statusLabel;
    protected String syncStatus;
    protected String tradeStatus;
    private ChangeListener<Number> depositsListener;
    private Label infoHeading;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, Initialisation
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected TradeStepView(PendingTradesViewModel model) {
        this.model = model;
        preferences = model.dataModel.preferences;
        trade = model.dataModel.getTrade();
        checkNotNull(trade, "Trade must not be null at TradeStepView");

        setMinWidth(0);
        gridPane = new GridPane();
        gridPane.setMinWidth(0);
        gridPane.setHgap(20);
        gridPane.setVgap(16);
        ColumnConstraints first = new ColumnConstraints();
        first.setPercentWidth(50);
        first.setMinWidth(0);
        ColumnConstraints second = new ColumnConstraints();
        second.setPercentWidth(50);
        second.setMinWidth(0);
        gridPane.getColumnConstraints().addAll(first, second);

        syncLabel.getStyleClass().addAll("trade-secondary", "trade-sync-status");
        syncLabel.setMinWidth(0);
        syncLabel.setMinHeight(20);
        syncLabel.setPrefHeight(20);
        syncLabel.setMaxHeight(20);
        syncLabel.setMaxWidth(Double.MAX_VALUE);
        actionPane = new VBox(12, gridPane, syncLabel);
        actionPane.getStyleClass().addAll("trade-panel", "trade-action-panel");
        actionPane.setMinWidth(0);
        sidebar = new VBox();
        sidebar.getStyleClass().add("trade-sidebar");
        sidebar.setMinWidth(0);
        GridPane workspace = new GridPane();
        ColumnConstraints mainColumn = new ColumnConstraints();
        mainColumn.setHgrow(Priority.ALWAYS);
        mainColumn.setMinWidth(0);
        ColumnConstraints sideColumn = new ColumnConstraints(280);
        workspace.getColumnConstraints().addAll(mainColumn, sideColumn);
        workspace.add(actionPane, 0, 0);
        workspace.add(sidebar, 1, 0);
        GridPane.setValignment(actionPane, VPos.TOP);
        GridPane.setFillHeight(actionPane, false);
        GridPane.setValignment(sidebar, VPos.TOP);
        widthProperty().addListener((observable, oldValue, newValue) -> {
            boolean compact = newValue.doubleValue() < 850;
            sidebar.pseudoClassStateChanged(PseudoClass.getPseudoClass("compact"), compact);
            GridPane.setColumnIndex(sidebar, compact ? 0 : 1);
            GridPane.setRowIndex(sidebar, compact ? 1 : 0);
            double sideWidth = compact ? 0 : Math.min(340, Math.max(280, newValue.doubleValue() * 0.21));
            sideColumn.setMinWidth(sideWidth);
            sideColumn.setPrefWidth(sideWidth);
            sideColumn.setMaxWidth(sideWidth);
        });
        getChildren().add(workspace);
        createSidebar();

        addContent();
        updateTimeLeft();

        errorMessageListener = (observable, oldValue, newValue) -> {
            if (newValue != null) {
                log.warn("Showing popup for trade error {} {}", trade.getClass().getSimpleName(), trade.getId(), new RuntimeException(newValue));
                new Popup().error(newValue).show();
            }
        };

        clockListener = new ClockWatcher.Listener() {
            @Override
            public void onSecondTick() {
            }

            @Override
            public void onMinuteTick() {
                updateTimeLeft();
            }
        };
    }

    public void activate() {
        active = true;
        if (selfDeposit != null)
            selfTxIdSubscription = EasyBind.subscribe(model.dataModel.isMaker() ? model.dataModel.makerTxId : model.dataModel.takerTxId,
                    id -> updateDepositSummary());
        if (peerDeposit != null)
            peerTxIdSubscription = EasyBind.subscribe(model.dataModel.isMaker() ? model.dataModel.takerTxId : model.dataModel.makerTxId,
                    id -> updateDepositSummary());
        payoutSubscription = EasyBind.subscribe(trade.payoutStateProperty(), state -> UserThread.execute(() -> {
            if (active) {
                boolean showHelp = !completed && !trade.isPayoutPublished();
                helpPane.setVisible(showHelp);
                helpPane.setManaged(showHelp);
            }
        }));
        trade.errorMessageProperty().addListener(errorMessageListener);

        tradeStepInfo.setOnAction(e -> {
            if (tradeStepInfo.getState() == TradeStepInfo.State.IN_ARBITRATION_SELF_REQUESTED ||
                    tradeStepInfo.getState() == TradeStepInfo.State.IN_ARBITRATION_PEER_REQUESTED) {
                model.getNavigation().navigateToWithData(trade, MainView.class, SupportView.class, ArbitrationClientView.class);
            } else if (!isArbitrationOpenedState() && (this.isTradePeriodOver() || trade.wasWalletSyncedAndPolledProperty.get() && trade.isMissingUnlockedDepositTx())) {
                openSupportTicket();
            } else {
                openChat();
            }
        });

        // We get mailbox messages processed after we have bootstrapped. This will influence the states we
        // handle in our disputeStateSubscription and mediationResultStateSubscriptions. To avoid that we show
        // popups from incorrect states we wait until we have bootstrapped and the mailbox messages processed.
        if (model.p2PService.isBootstrapped()) {
            registerSubscriptions();
        } else {
            bootstrapListener = new BootstrapListener() {
                @Override
                public void onDataReceived() {
                    registerSubscriptions();
                }
            };
            model.p2PService.addP2PServiceListener(bootstrapListener);
        }

        tradePeriodStateSubscription = EasyBind.subscribe(trade.tradePeriodStateProperty(), newValue -> {
            if (newValue != null) {
                UserThread.execute(() -> {
                    if (active) updateTradePeriodState(newValue);
                });
            }
        });

        model.clockWatcher.addListener(clockListener);

        if (infoLabel != null) {
            infoLabel.setText(getInfoText());
        }

        BooleanProperty initialized = model.dataModel.tradeManager.getTradesInitialized();
        if (initialized.get()) {
            onPendingTradesInitialized();
        } else {
            pendingTradesInitializedListener = (observable, oldValue, newValue) -> {
                if (newValue) {
                    onPendingTradesInitialized();
                    UserThread.execute(() -> initialized.removeListener(pendingTradesInitializedListener));
                }
            };
            initialized.addListener(pendingTradesInitializedListener);
        }

        depositsListener = (observable, oldValue, newValue) -> {
            if (newValue != null) {
                UserThread.execute(() -> {
                    if (active) {
                        updateDepositSummary();
                        onDepositTxsUpdate();
                    }
                });
            }
        };
        trade.getDepositTxsUpdateCounter().addListener(depositsListener);
        updateDepositSummary();
        updateTimeLeft();
    }

    protected void onPendingTradesInitialized() {
//        model.dataModel.xmrWalletService.addNewBestBlockListener(newBestBlockListener); // TODO (woodser): different listener?
//        checkIfLockTimeIsOver();
    }

    private void registerSubscriptions() {
        if (!active || subscriptionsRegistered) return;
        subscriptionsRegistered = true;
        disputeStateSubscription = EasyBind.subscribe(trade.disputeStateProperty(), newValue -> {
            if (newValue != null) {
                updateDisputeState(newValue);
            }
        });

        mediationResultStateSubscription = EasyBind.subscribe(trade.mediationResultStateProperty(), newValue -> {
            if (newValue != null) {
                updateMediationResultState(true);
            }
        });

        if (trade.wasWalletSyncedAndPolledProperty.get()) addTradeStateSubscription();
        else {
            walletSyncedListener = (observable, oldValue, newValue) -> {
                if (active && newValue) addTradeStateSubscription();
            };
            trade.wasWalletSyncedAndPolledProperty.addListener(walletSyncedListener);
        }

        syncUpdateSubscription = EasyBind.subscribe(trade.getSyncProgressListener().numUpdates(), newValue -> {
            if (newValue != null) updateSyncProgress();
        });
        UserThread.execute(() -> model.p2PService.removeP2PServiceListener(bootstrapListener));
    }

    protected void updateSyncProgress() {

        long blocksRemaining = trade.blocksRemainingProperty().get();
        // set status with percentage and blocks remaining if available
        double percent = trade.downloadPercentageProperty().get();
        if (percent < 0.0 || percent >= 1.0) setSyncStatus("");
        else {
            if (trade.blocksRemainingProperty().get() < 0) {
                setSyncStatus(Res.get("portfolio.pending.syncing"));
            } else {
                if (trade.blocksRemainingProperty().get() == 1) {
                    setSyncStatus(Res.get("portfolio.pending.syncing.blockRemaining"));
                } else {
                    setSyncStatus(Res.get("portfolio.pending.syncing.blocksRemaining", blocksRemaining));
                }
            }
        }
    }

    private void addTradeStateSubscription() {
        if (tradeStateSubscription != null) return;
        tradeStateSubscription = EasyBind.subscribe(trade.stateProperty(), newValue -> {
            if (newValue != null) {
                UserThread.execute(() -> {
                    if (active) updateTradeState(newValue);
                });
            }
        });
    }

    private void openSupportTicket() {
        applyOnDisputeOpened();
        model.dataModel.onOpenDispute();
    }

    private void openChat() {
        // call up the chain to open chat
        if (this.chatCallback != null) {
            this.chatCallback.onOpenChat(this.trade);
        }
    }

    public void deactivate() {
        active = false;
        durationPopover.hidePopOver();
        if (walletSyncedListener != null)
            trade.wasWalletSyncedAndPolledProperty.removeListener(walletSyncedListener);
        if (pendingTradesInitializedListener != null)
            model.dataModel.tradeManager.getTradesInitialized().removeListener(pendingTradesInitializedListener);
        if (bootstrapListener != null)
            model.p2PService.removeP2PServiceListener(bootstrapListener);
        if (selfTxIdSubscription != null)
            selfTxIdSubscription.unsubscribe();
        if (peerTxIdSubscription != null)
            peerTxIdSubscription.unsubscribe();

        if (selfDeposit != null) selfDeposit.cleanup();
        if (peerDeposit != null) peerDeposit.cleanup();
        if (payout != null) payout.cleanup();
        if (payoutSubscription != null) payoutSubscription.unsubscribe();

        if (errorMessageListener != null)
            trade.errorMessageProperty().removeListener(errorMessageListener);

        if (disputeStateSubscription != null)
            disputeStateSubscription.unsubscribe();

        if (mediationResultStateSubscription != null)
            mediationResultStateSubscription.unsubscribe();

        if (syncUpdateSubscription != null)
            syncUpdateSubscription.unsubscribe();

        if (tradePeriodStateSubscription != null)
            tradePeriodStateSubscription.unsubscribe();

        if (tradeStateSubscription != null)
            tradeStateSubscription.unsubscribe();

        if (clockListener != null)
            model.clockWatcher.removeListener(clockListener);

        if (tradeStepInfo != null)
            tradeStepInfo.setOnAction(null);

        if (acceptMediationResultPopup != null) { 
            acceptMediationResultPopup.hide();
            acceptMediationResultPopup = null;
        }

        if (depositsListener != null) {
            trade.getDepositTxsUpdateCounter().removeListener(depositsListener);
            depositsListener = null;
        }
    }

    protected void onDepositTxsUpdate() {
        // no default action
    }

    protected long getNumMinutesToUnlock() {
        long numDepositConfirmations = trade.getNumDepositConfirmations() == null ? 0 : trade.getNumDepositConfirmations();
        long numMinutesToUnlock = (Math.max(0, XmrWalletService.NUM_BLOCKS_UNLOCK - numDepositConfirmations)) * 2;
        return numMinutesToUnlock;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Content
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected void addContent() {
        addInfoBlock();
    }

    private void createSidebar() {
        Label title = new Label(Res.get("portfolio.pending.tradeView.deadline"));
        title.getStyleClass().add("trade-section-heading");
        timeLeftTextField = new Label();
        timeLeftTextField.setWrapText(true);
        timeLeftTextField.getStyleClass().add("trade-deadline-value");
        timeLeftProgressBar = new ProgressBar();
        timeLeftProgressBar.getStyleClass().add("trade-progress");
        timeLeftProgressBar.setMaxWidth(Double.MAX_VALUE);
        deadlineDate = new Label();
        deadlineDate.setWrapText(true);
        deadlineDate.getStyleClass().add("trade-secondary");
        title.setGraphic(GlyphsDude.createIcon(FontAwesomeIcon.CLOCK_ALT, "16"));
        title.getGraphic().getStyleClass().add("trade-muted-icon");
        title.setGraphicTextGap(10);
        duration = new Label();
        duration.setWrapText(true);
        duration.getStyleClass().add("trade-secondary");
        Button durationHelp = new Button();
        durationHelp.getStyleClass().add("trade-info-button");
        durationHelp.setGraphic(GlyphsDude.createIcon(FontAwesomeIcon.INFO_CIRCLE, "12"));
        durationHelp.getGraphic().getStyleClass().add("trade-muted-icon");
        durationHelp.setAccessibleText(Res.get("payment.maxPeriod"));
        durationHelp.setOnMouseEntered(event -> durationPopover.showPopOver(() -> createDurationPopover(durationHelp)));
        durationHelp.setOnMouseExited(event -> durationPopover.hidePopOver());
        durationHelp.setOnAction(event -> durationPopover.showPopOver(() -> createDurationPopover(durationHelp)));
        durationHelp.focusedProperty().addListener((observable, oldValue, focused) -> {
            if (!focused) durationPopover.hidePopOver();
        });
        HBox durationRow = new HBox(6, duration, durationHelp);
        durationRow.setAlignment(Pos.CENTER_LEFT);
        VBox period = new VBox(4, durationRow, deadlineDate);
        deadlinePane = new VBox(10, title, timeLeftTextField, timeLeftProgressBar, period);
        deadlinePane.getStyleClass().add("trade-panel");

        depositDetails = new VBox(16);
        depositDetails.getStyleClass().add("trade-deposit-details");
        if (model.dataModel.isMaker() || !trade.hasBuyerAsTakerWithoutDeposit()) {
            selfDeposit = new TradeDepositView(Res.get("portfolio.pending.tradeView.yourDeposit"), preferences);
            depositDetails.getChildren().add(selfDeposit);
        }
        if (!model.dataModel.isMaker() || !trade.hasBuyerAsTakerWithoutDeposit()) {
            peerDeposit = new TradeDepositView(Res.get("portfolio.pending.tradeView.peerDeposit"), preferences);
            depositDetails.getChildren().add(peerDeposit);
        }
        depositsIcon = new Label();
        depositsIcon.setMinWidth(Region.USE_PREF_SIZE);
        depositsIcon.getStyleClass().add("trade-deposit-status-icon");
        depositsTitle = new Label();
        depositsTitle.setMinWidth(0);
        depositsTitle.setWrapText(true);
        depositsChevron = new Label();
        depositsChevron.setMinWidth(Region.USE_PREF_SIZE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox heading = new HBox(12, depositsIcon, depositsTitle, spacer, depositsChevron);
        heading.setAlignment(Pos.CENTER_LEFT);
        heading.setMouseTransparent(true);
        depositsToggle = new ToggleButton();
        depositsToggle.getStyleClass().add("trade-deposits-toggle");
        depositsToggle.setGraphic(heading);
        // measure the wrapped heading with the available width, including the focus border
        depositsToggle.setWrapText(true);
        depositsToggle.setMaxWidth(Double.MAX_VALUE);
        // keep the heading aligned while padding the hover and focus area
        VBox.setMargin(depositsToggle, new Insets(0, -12, 0, -12));
        depositsToggle.selectedProperty().addListener((observable, oldValue, expanded) -> {
            depositDetails.setVisible(expanded);
            depositDetails.setManaged(expanded);
            GlyphsDude.setIcon(depositsChevron, expanded ? FontAwesomeIcon.ANGLE_UP : FontAwesomeIcon.ANGLE_DOWN, "14");
        });
        depositsPane = new VBox(depositsToggle, depositDetails);
        depositsPane.getStyleClass().addAll("trade-panel", "trade-deposits-panel");
        setDepositDetailsExpanded(false);
        updateDepositSummary();
        Label helpTitle = new Label(Res.get("portfolio.pending.support.headline.getHelp"));
        helpTitle.getStyleClass().add("trade-section-heading");
        SimpleMarkdownLabel help = new SimpleMarkdownLabel(Res.get("portfolio.pending.tradeView.communityHelp"));
        helpPane = new VBox(8, helpTitle, help);
        helpPane.getStyleClass().add("trade-help");
        sidebar.getChildren().addAll(deadlinePane, depositsPane, helpPane);
    }

    public boolean isDepositDetailsExpanded() {
        return depositsToggle.isSelected();
    }

    public void setDepositDetailsExpanded(boolean expanded) {
        depositsToggle.setSelected(expanded);
        depositDetails.setVisible(expanded);
        depositDetails.setManaged(expanded);
        GlyphsDude.setIcon(depositsChevron, expanded ? FontAwesomeIcon.ANGLE_UP : FontAwesomeIcon.ANGLE_DOWN, "14");
    }

    private void updateDepositSummary() {
        Long count = getNumDepositConfirmations();
        boolean confirmed = trade.isDepositsFinalized();
        if (confirmationPane != null) {
            boolean arbitratorConfirmed = trade.isArbitrator() && confirmed;
            confirmationPane.update(count, trade.isDepositsUnlocked(), arbitratorConfirmed);
            if (trade.isArbitrator() && infoHeading != null) {
                infoHeading.setText(arbitratorConfirmed ? Res.get("portfolio.pending.tradeView.depositsConfirmed") : getInfoBlockTitle());
                infoLabel.setVisible(!arbitratorConfirmed);
                infoLabel.setManaged(!arbitratorConfirmed);
                updateStepCaption(Res.get(arbitratorConfirmed ? "portfolio.pending.tradeView.complete" : "portfolio.pending.tradeView.confirming"));
                updateStepWarning();
                if (tradeStepInfo != null) updateTradePeriodState(trade.tradePeriodStateProperty().get());
            }
        }
        depositsTitle.setText(Res.get(confirmed ? "portfolio.pending.tradeView.depositsConfirmed" : "portfolio.pending.tradeView.depositTransactions"));
        if (count != null && count >= 0)
            depositsTitle.setText(depositsTitle.getText() + " · " + Res.get("portfolio.pending.tradeView.depositCount", count));
        depositsToggle.setAccessibleText(depositsTitle.getText());
        GlyphsDude.setIcon(depositsIcon, confirmed ? FontAwesomeIcon.CHECK : FontAwesomeIcon.CLOCK_ALT, "14");
        depositsIcon.pseudoClassStateChanged(PseudoClass.getPseudoClass("confirmed"), confirmed);
        if (selfDeposit != null) updateDeposit(selfDeposit, model.dataModel.isMaker());
        if (peerDeposit != null) updateDeposit(peerDeposit, !model.dataModel.isMaker());
    }

    protected Long getNumDepositConfirmations() {
        Long count = trade.getNumDepositConfirmations();
        if (count != null) return count;
        // unconfirmed deposits have no block height, but their counts can already be known
        Long makerCount = TradeDepositView.getNumConfirmations(trade.getMakerDepositTx());
        if (trade.hasBuyerAsTakerWithoutDeposit()) return makerCount;
        Long takerCount = TradeDepositView.getNumConfirmations(trade.getTakerDepositTx());
        return makerCount == null || takerCount == null ? null : Math.min(makerCount, takerCount);
    }

    private void updateDeposit(TradeDepositView view, boolean maker) {
        MoneroTxWallet transaction = maker ? trade.getMakerDepositTx() : trade.getTakerDepositTx();
        String hash = maker ? trade.getMaker().getDepositTxHash() : trade.getTaker().getDepositTxHash();
        if (hash == null) hash = maker ? model.dataModel.makerTxId.get() : model.dataModel.takerTxId.get();
        view.update(transaction, hash, trade.isDepositsUnlocked());
    }

    protected void addInfoBlock() {
        infoHeading = addHeading(getInfoBlockTitle());
        infoLabel = new Label();
        infoLabel.setWrapText(true);
        infoLabel.getStyleClass().add("trade-body");
        infoLabel.setMaxWidth(640);
        gridPane.add(infoLabel, 0, ++gridRow, 2, 1);
    }

    protected HBox createAmountPanel(String caption) {
        Label label = new Label(caption);
        label.getStyleClass().add("trade-field-label");
        String volume = model.getFiatVolume();
        int currencyStart = volume.lastIndexOf(' ');
        String number = currencyStart < 0 ? volume : volume.substring(0, currencyStart);
        String currencyCode = currencyStart < 0 ? "" : volume.substring(currencyStart + 1);
        TextField amount = new TextField(number);
        amount.setEditable(false);
        amount.getStyleClass().add("trade-amount-value");
        Text measure = new Text(number);
        measure.fontProperty().bind(amount.fontProperty());
        amount.prefWidthProperty().bind(Bindings.createDoubleBinding(() -> measure.getLayoutBounds().getWidth() + 4,
                measure.layoutBoundsProperty()));
        amount.setMinWidth(Region.USE_PREF_SIZE);
        amount.setMaxWidth(Region.USE_PREF_SIZE);
        Label currency = new Label(currencyCode);
        currency.getStyleClass().add("trade-amount-currency");
        Button copyAmount = new Button();
        GUIUtil.configureCopyIcon(copyAmount, () -> volume.split(" ")[0]);
        copyAmount.getStyleClass().add("trade-copy-button");
        copyAmount.getGraphic().getStyleClass().add("trade-copy-icon");
        HBox value = new HBox(8, amount, currency, copyAmount);
        value.setAlignment(Pos.CENTER_LEFT);
        VBox metric = new VBox(6, label, value);
        Label xmrAmount = new Label(model.getTradeVolume());
        xmrAmount.setWrapText(true);
        Label rate = new Label(Res.get("portfolio.pending.tradeView.atPrice", model.getTradePrice()));
        rate.getStyleClass().add("trade-secondary");
        rate.setWrapText(true);
        VBox exchange = new VBox(4, xmrAmount, rate);
        exchange.setAlignment(Pos.CENTER_RIGHT);
        exchange.setMinWidth(0);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox panel = new HBox(20, metric, spacer, exchange);
        panel.setAlignment(Pos.CENTER_LEFT);
        panel.getStyleClass().add("trade-amount-panel");
        panel.setMinWidth(0);
        return panel;
    }

    protected HBox createAccountSummary(String caption, String details) {
        Label label = new Label(caption);
        label.getStyleClass().add("trade-secondary");
        label.setWrapText(true);
        TextArea value = new TextArea(details);
        value.setEditable(false);
        value.setWrapText(true);
        value.setMinSize(0, 0);
        value.getStyleClass().addAll("selectable-label", "trade-account-details");
        GUIUtil.adjustHeightAutomatically(value, null, false);
        HBox.setHgrow(value, Priority.ALWAYS);
        HBox summary = new HBox(10, label, value);
        summary.setMinWidth(0);
        summary.setAlignment(Pos.BASELINE_LEFT);
        summary.getStyleClass().add("trade-account-summary");
        return summary;
    }

    protected Tuple4<Button, BusyAnimation, Label, HBox> addConfirmationButton(GridPane pane, int row, String title) {
        Tuple4<Button, BusyAnimation, Label, HBox> action = addButtonBusyAnimationLabel(pane, row, 0, title, 4);
        Button button = action.first;
        BusyAnimation busy = action.second;
        action.fourth.getChildren().remove(busy);
        button.getStyleClass().add("trade-confirmation-button");

        Label idleText = new AutoTooltipLabel(title);
        Label busyText = new AutoTooltipLabel(Res.get("portfolio.pending.tradeView.confirmingAction"));
        idleText.setMinWidth(0);
        busyText.setMinWidth(0);
        idleText.fontProperty().bind(button.fontProperty());
        busyText.fontProperty().bind(button.fontProperty());
        idleText.textFillProperty().bind(button.textFillProperty());
        busyText.textFillProperty().bind(button.textFillProperty());
        busy.setMinSize(14, 14);
        busy.setPrefSize(14, 14);
        busy.setMaxSize(14, 14);
        StackPane spinner = new StackPane(busy);
        spinner.setMinSize(14, 14);
        spinner.setPrefSize(14, 14);
        spinner.setMaxSize(14, 14);
        HBox progress = new HBox(8, spinner, busyText);
        progress.setAlignment(Pos.CENTER);

        // measure both states so confirmation never changes the button's size
        StackPane content = new StackPane(idleText, progress);
        idleText.visibleProperty().bind(busy.isRunningProperty().not());
        progress.visibleProperty().bind(busy.isRunningProperty());
        button.setGraphic(content);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.textProperty().bind(Bindings.when(busy.isRunningProperty()).then(busyText.getText()).otherwise(title));
        busy.isRunningProperty().addListener((observable, oldValue, running) -> {
            button.pseudoClassStateChanged(PseudoClass.getPseudoClass("busy"), running);
            confirmationInProgress = running;
            updateStatus();
        });

        action.third.getStyleClass().add("trade-secondary");
        // reserve the status gap and let the message use the remaining row space
        action.third.setPrefWidth(0);
        action.third.setMaxWidth(Double.MAX_VALUE);
        action.third.visibleProperty().bind(action.third.textProperty().isNotEmpty());
        return action;
    }

    private PopOver createDurationPopover(Button owner) {
        PopOver popover = new PopOver(createInfoPopover());
        popover.setDetachable(false);
        popover.setArrowLocation(PopOver.ArrowLocation.RIGHT_CENTER);
        // keep the popup window's shadow clear of the hover target
        if (owner.getScene() != null && owner.getScene().getWindow() != null) popover.show(owner, -20);
        return popover;
    }

    protected Label addHeading(String text) {
        Label heading = new Label(text);
        heading.setWrapText(true);
        heading.getStyleClass().add("trade-action-title");
        gridPane.add(heading, 0, gridRow, 2, 1);
        return heading;
    }

    protected void addConfirmationInfo() {
        addInfoBlock();
        confirmationPane = new TradeConfirmationPane();
        gridPane.add(confirmationPane, 0, ++gridRow, 2, 1);
        updateStepCaption(Res.get("portfolio.pending.tradeView.confirming"));
        updateDepositSummary();
    }

    protected String getInfoText() {
        return "";
    }

    protected String getInfoBlockTitle() {
        return "";
    }

    private void updateTimeLeft() {
        updateStepWarning();
        if (!trade.isInitialized()) {
            setTimeLeft(Res.get("portfolio.pending.tradeView.awaitingInformation"), false);
            timeLeftProgressBar.setProgress(0);
            return;
        }
        boolean expired = isTradePeriodOver();
        boolean started = trade.isDepositsFinalized();
        setTimeLeft(expired ? Res.get("portfolio.pending.tradeView.expired") : started ?
                formatRemainingTime(model.getRemainingTradeDuration()) : Res.get("portfolio.pending.tradeView.notStarted"), started && !expired);
        deadlineDate.setText(started ? Res.get(expired ? "portfolio.pending.tradeView.endedDate" :
                "portfolio.pending.tradeView.deadlineDate", model.getDateForOpenDispute()) :
                Res.get("portfolio.pending.remainingTimeDetail.startsAfter", Trade.NUM_BLOCKS_DEPOSITS_FINALIZED));
        String period = FormattingUtils.formatDurationAsWords(trade.getOffer().getPaymentMethod().getMaxTradePeriod(), false, false);
        duration.setText(Res.get(expired ? "portfolio.pending.tradeView.periodWas" : started ?
                "portfolio.pending.tradeView.periodRemaining" : "portfolio.pending.tradeView.maximumPeriod", period));
        timeLeftProgressBar.setProgress(expired ? 1 : started ? Math.min(1, Math.max(0, 1 - model.getRemainingTradeDurationAsPercentage())) : 0);
        deadlinePane.pseudoClassStateChanged(PseudoClass.getPseudoClass("expired"), expired);
        deadlinePane.pseudoClassStateChanged(PseudoClass.getPseudoClass("second-half"), started && model.getRemainingTradeDurationAsPercentage() >= 0.5);
    }

    private String formatRemainingTime(long milliseconds) {
        long minutes = Math.max(0, milliseconds / 60_000);
        return Res.get("portfolio.pending.tradeView.timeRemaining", minutes / 1440, minutes / 60 % 24, minutes % 60);
    }

    private void setTimeLeft(String text, boolean countdown) {
        timeLeftTextField.setText(text);
        timeLeftTextField.setContentDisplay(countdown ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.TEXT_ONLY);
        if (!countdown) {
            timeLeftTextField.setGraphic(null);
            return;
        }
        TextFlow display = new TextFlow();
        Matcher parts = Pattern.compile("\\p{Nd}+|\\s+|[^\\p{Nd}\\s]+").matcher(text);
        while (parts.find()) {
            Text part = new Text(parts.group());
            part.getStyleClass().add(Character.isDigit(part.getText().codePointAt(0)) || part.getText().isBlank() ?
                    "trade-deadline-number" : "trade-deadline-unit");
            display.getChildren().add(part);
        }
        timeLeftTextField.setGraphic(display);
    }

    public void setStepCaptionHandler(Consumer<String> handler) {
        stepCaptionHandler = handler;
        if (stepCaption != null) handler.accept(stepCaption);
    }

    public void setStepWarningHandler(Consumer<String> handler) {
        stepWarningHandler = handler;
        updateStepWarning();
    }

    private void updateStepWarning() {
        if (stepWarningHandler == null) return;
        Trade.DisputeState state = trade.getDisputeState();
        boolean disputed = state == Trade.DisputeState.DISPUTE_PREPARING || state == Trade.DisputeState.DISPUTE_REQUESTED ||
                state == Trade.DisputeState.DISPUTE_OPENED || state == Trade.DisputeState.MEDIATION_REQUESTED ||
                state == Trade.DisputeState.MEDIATION_STARTED_BY_PEER;
        String key = disputed ? "portfolio.pending.tradeView.inDispute" :
                isTradePeriodOver() && !(trade.isArbitrator() && trade.isDepositsFinalized()) ? "portfolio.pending.tradeView.overdue" : null;
        stepWarningHandler.accept(completed || trade.isPayoutPublished() || key == null ? null : Res.get(key));
    }

    protected void updateStepCaption(String caption) {
        stepCaption = caption;
        if (stepCaptionHandler != null) stepCaptionHandler.accept(caption);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Dispute/warning label and button
    ///////////////////////////////////////////////////////////////////////////////////////////

    // The dispute controls share the deadline panel; their content depends on the current step.
    public void setTradeStepInfo(TradeStepInfo tradeStepInfo) {
        this.tradeStepInfo = tradeStepInfo;
        if (tradeStepInfo.getParent() instanceof VBox) ((VBox) tradeStepInfo.getParent()).getChildren().remove(tradeStepInfo);
        deadlinePane.getChildren().add(tradeStepInfo);

        tradeStepInfo.setFirstHalfOverWarnTextSupplier(this::getFirstHalfOverWarnText);
        tradeStepInfo.setPeriodOverWarnTextSupplier(this::getPeriodOverWarnText);
        tradeStepInfo.setDepositTxMissingWarnTextSupplier(this::getDepositTxMissingWarnText);
    }

    protected void hideTradeStepInfo() {
        completed = true;
        updateStepWarning();
        syncLabel.setVisible(false);
        syncLabel.setManaged(false);
        actionPane.pseudoClassStateChanged(PseudoClass.getPseudoClass("completed"), true);
        helpPane.setVisible(false);
        helpPane.setManaged(false);
        deadlinePane.setVisible(false);
        deadlinePane.setManaged(false);
        tradeStepInfo.setState(TradeStepInfo.State.TRADE_COMPLETED);
        String payoutId = trade.getPayoutTxId();
        if (payout == null && payoutId != null && !payoutId.isEmpty()) {
            Label title = new Label(Res.get("portfolio.pending.tradeView.payoutTransaction"));
            title.getStyleClass().add("trade-section-heading");
            payout = new TradeDepositView(Res.get("shared.txId"), preferences, false);
            payout.update(null, payoutId, false);
            VBox payoutPane = new VBox(16, title, payout);
            payoutPane.getStyleClass().addAll("trade-panel", "trade-payout-panel");
            sidebar.getChildren().add(0, payoutPane);
        }
    }

    protected String getFirstHalfOverWarnText() {
        return "";
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Dispute
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected String getPeriodOverWarnText() {
        return "";
    }

    protected String getDepositTxMissingWarnText() {
        return Res.get("portfolio.pending.support.depositTxMissing");
    }

    protected void applyOnDisputeOpened() {
    }

    protected void updateDisputeState(Trade.DisputeState disputeState) {
        updateStepWarning();
        Optional<Dispute> ownDispute;
        switch (disputeState) {
            case NO_DISPUTE:
                break;

            case DISPUTE_PREPARING:
            case DISPUTE_REQUESTED:
            case DISPUTE_OPENED:
                if (tradeStepInfo != null) {
                    tradeStepInfo.setFirstHalfOverWarnTextSupplier(this::getFirstHalfOverWarnText);
                }
                applyOnDisputeOpened();

                // update trade view unless arbitrator
                if (trade instanceof ArbitratorTrade) break;
                ownDispute = model.dataModel.arbitrationManager.findDispute(trade.getId());
                ownDispute.ifPresent(dispute -> {
                    if (tradeStepInfo != null) {
                        boolean isOpener = dispute.isDisputeOpenerIsBuyer() ? trade.isBuyer() : trade.isSeller();
                        tradeStepInfo.setState(isOpener ? TradeStepInfo.State.IN_ARBITRATION_SELF_REQUESTED : TradeStepInfo.State.IN_ARBITRATION_PEER_REQUESTED);
                    }
                });
                break;

            case DISPUTE_CLOSED:
                break;
            case MEDIATION_REQUESTED:
                if (tradeStepInfo != null) {
                    tradeStepInfo.setFirstHalfOverWarnTextSupplier(this::getFirstHalfOverWarnText);
                }
                applyOnDisputeOpened();

                ownDispute = model.dataModel.mediationManager.findDispute(trade.getId());
                ownDispute.ifPresent(dispute -> {
                    if (tradeStepInfo != null)
                        tradeStepInfo.setState(TradeStepInfo.State.IN_MEDIATION_SELF_REQUESTED);
                });
                break;
            case MEDIATION_STARTED_BY_PEER:
                if (tradeStepInfo != null) {
                    tradeStepInfo.setFirstHalfOverWarnTextSupplier(this::getFirstHalfOverWarnText);
                }
                applyOnDisputeOpened();

                ownDispute = model.dataModel.mediationManager.findDispute(trade.getId());
                ownDispute.ifPresent(dispute -> {
                    if (tradeStepInfo != null) {
                        tradeStepInfo.setState(TradeStepInfo.State.IN_MEDIATION_PEER_REQUESTED);
                    }
                });
                break;
            case MEDIATION_CLOSED:
              if (tradeStepInfo != null) {
                tradeStepInfo.setOnAction(e -> {
                    updateMediationResultState(false);
                });
              }

              if (tradeStepInfo != null) {
                tradeStepInfo.setState(TradeStepInfo.State.MEDIATION_RESULT);
              }

              updateMediationResultState(true);
              break;
            case REFUND_REQUESTED:
                  throw new RuntimeException("Unhandled case: " + Trade.DisputeState.REFUND_REQUESTED);
//                if (tradeStepInfo != null) {
//                    tradeStepInfo.setFirstHalfOverWarnTextSupplier(this::getFirstHalfOverWarnText);
//                }
//                applyOnDisputeOpened();
//
//                ownDispute = model.dataModel.refundManager.findOwnDispute(trade.getId());
//                ownDispute.ifPresent(dispute -> {
//                    if (tradeStepInfo != null)
//                        tradeStepInfo.setState(TradeStepInfo.State.IN_REFUND_REQUEST_SELF_REQUESTED);
//                });
//
//                if (acceptMediationResultPopup != null) {
//                    acceptMediationResultPopup.hide();
//                    acceptMediationResultPopup = null;
//                }
//
//                break;
            case REFUND_REQUEST_STARTED_BY_PEER:
                throw new RuntimeException("Unhandled case: " + Trade.DisputeState.REFUND_REQUEST_STARTED_BY_PEER);
//                if (tradeStepInfo != null) {
//                    tradeStepInfo.setFirstHalfOverWarnTextSupplier(this::getFirstHalfOverWarnText);
//                }
//                applyOnDisputeOpened();
//
//                ownDispute = model.dataModel.refundManager.findOwnDispute(trade.getId());
//                ownDispute.ifPresent(dispute -> {
//                    if (tradeStepInfo != null)
//                        tradeStepInfo.setState(TradeStepInfo.State.IN_REFUND_REQUEST_PEER_REQUESTED);
//                });
//
//                if (acceptMediationResultPopup != null) {
//                    acceptMediationResultPopup.hide();
//                    acceptMediationResultPopup = null;
//                }
//                break;
            case REFUND_REQUEST_CLOSED:
                break;
            default:
                break;
        }
    }

    private void updateMediationResultState(boolean blockOpeningOfResultAcceptedPopup) {
        if (isInMediation()) {
            if (isRefundRequestStartedByPeer()) {
                tradeStepInfo.setState(TradeStepInfo.State.IN_REFUND_REQUEST_PEER_REQUESTED);
            } else if (isRefundRequestSelfStarted()) {
                tradeStepInfo.setState(TradeStepInfo.State.IN_REFUND_REQUEST_SELF_REQUESTED);
            }
        } else if (isMediationClosedState()) {
            // We do not use the state itself as it is not guaranteed the last state reflects relevant information
            // (e.g. we might receive a RECEIVED_SIG_MSG but then later a SIG_MSG_IN_MAILBOX).
            if (hasSelfAccepted()) {
                tradeStepInfo.setState(TradeStepInfo.State.MEDIATION_RESULT_SELF_ACCEPTED);
                if (!blockOpeningOfResultAcceptedPopup)
                    openMediationResultPopup(Res.get("portfolio.pending.mediationResult.popup.headline", trade.getShortId()));
            } else if (peerAccepted()) {
                tradeStepInfo.setState(TradeStepInfo.State.MEDIATION_RESULT_PEER_ACCEPTED);
                if (acceptMediationResultPopup == null) {
                    openMediationResultPopup(Res.get("portfolio.pending.mediationResult.popup.headline.peerAccepted", trade.getShortId()));
                }
            } else {
                tradeStepInfo.setState(TradeStepInfo.State.MEDIATION_RESULT);
                openMediationResultPopup(Res.get("portfolio.pending.mediationResult.popup.headline", trade.getShortId()));
            }
        }
    }

    private boolean isInMediation() {
        return isRefundRequestStartedByPeer() || isRefundRequestSelfStarted();
    }

    private boolean isRefundRequestStartedByPeer() {
        return trade.getDisputeState() == Trade.DisputeState.REFUND_REQUEST_STARTED_BY_PEER;
    }

    private boolean isRefundRequestSelfStarted() {
        return trade.getDisputeState() == Trade.DisputeState.REFUND_REQUESTED;
    }

    private boolean isMediationClosedState() {
        return trade.getDisputeState() == Trade.DisputeState.MEDIATION_CLOSED;
    }

    private boolean isArbitrationOpenedState() {
        return trade.getDisputeState().isOpen();
    }

    private boolean isTradePeriodOver() {
        return Trade.TradePeriodState.TRADE_PERIOD_OVER == trade.tradePeriodStateProperty().get();
    }

    private boolean hasSelfAccepted() {
        return trade.getProcessModel().getMediatedPayoutTxSignature() != null;
    }

    private boolean peerAccepted() {
        return trade.getTradePeer().getMediatedPayoutTxSignature() != null;
    }

    private void openMediationResultPopup(String headLine) {
        if (acceptMediationResultPopup != null) {
            return;
        }

        Optional<Dispute> optionalDispute = model.dataModel.mediationManager.findDispute(trade.getId());
        if (optionalDispute.isEmpty()) {
            return;
        }

        if (trade.getPayoutTxId() != null) {
            return;
        }

        if (trade instanceof MakerTrade && trade.getMakerDepositTx() == null) {
          log.error("trade.getMakerDepositTx() was null at openMediationResultPopup. " +
                  "We add the trade to failed trades. TradeId={}", trade.getId());
          //model.dataModel.addTradeToFailedTrades(); // TODO (woodser): new way to move trade to failed trades?
          model.dataModel.onMoveInvalidTradeToFailedTrades(trade);
          new Popup().warning(Res.get("portfolio.pending.mediationResult.error.depositTxNull")).show(); // TODO (woodser): separate error messages for maker/taker
          return;
        } else if (trade instanceof TakerTrade && trade.getTakerDepositTx() == null && !trade.hasBuyerAsTakerWithoutDeposit()) {
          log.error("trade.getTakerDepositTx() was null at openMediationResultPopup. " +
                  "We add the trade to failed trades. TradeId={}", trade.getId());
          //model.dataModel.addTradeToFailedTrades();
          model.dataModel.onMoveInvalidTradeToFailedTrades(trade);
          new Popup().warning(Res.get("portfolio.pending.mediationResult.error.depositTxNull")).show();
          return;
        }

        DisputeResult disputeResult = optionalDispute.get().getDisputeResultProperty().get();
        Contract contract = checkNotNull(trade.getContract(), "contract must not be null");
        boolean isMyRoleBuyer = contract.isMyRoleBuyer(model.dataModel.getPubKeyRingProvider().get());
        String buyerPayoutAmount = HavenoUtils.formatXmr(disputeResult.getBuyerPayoutAmountBeforeCost(), true);
        String sellerPayoutAmount = HavenoUtils.formatXmr(disputeResult.getSellerPayoutAmountBeforeCost(), true);
        String myPayoutAmount = isMyRoleBuyer ? buyerPayoutAmount : sellerPayoutAmount;
        String peersPayoutAmount = isMyRoleBuyer ? sellerPayoutAmount : buyerPayoutAmount;

        String actionButtonText = hasSelfAccepted() ?
                Res.get("portfolio.pending.mediationResult.popup.alreadyAccepted") : Res.get("shared.accept");

        String message;
        MediationResultState mediationResultState = checkNotNull(trade).getMediationResultState();
        if (mediationResultState == null) {
            return;
        }

        switch (mediationResultState) {
            case MEDIATION_RESULT_ACCEPTED:
            case SIG_MSG_SENT:
            case SIG_MSG_ARRIVED:
            case SIG_MSG_IN_MAILBOX:
            case SIG_MSG_SEND_FAILED:
                message = Res.get("portfolio.pending.mediationResult.popup.selfAccepted.lockTimeOver",
                        "N/A",  // TODO (woodser): no timelocked tx in xmr, so part of popup message is n/a
                        -1);
                break;
            default:
                message = Res.get("portfolio.pending.mediationResult.popup.info",
                        myPayoutAmount,
                        peersPayoutAmount,
                        "N/A",  // TODO (woodser): no timelocked tx in xmr, so part of popup message is n/a
                        -1);
                break;
        }

        acceptMediationResultPopup = new Popup().width(900)
                .headLine(headLine)
                .instruction(message)
                .actionButtonText(actionButtonText)
                .onAction(() -> {
                    model.dataModel.mediationManager.onAcceptMediationResult(trade,
                            () -> {
                                log.info("onAcceptMediationResult completed");
                                acceptMediationResultPopup = null;
                            },
                            errorMessage -> {
                                UserThread.execute(() -> {
                                    new Popup().error(errorMessage).show();
                                    if (acceptMediationResultPopup != null) {
                                        acceptMediationResultPopup.hide();
                                        acceptMediationResultPopup = null;
                                    }
                                });
                            });
                })
                .secondaryActionButtonText(Res.get("portfolio.pending.mediationResult.popup.openArbitration"))
                .onSecondaryAction(() -> {
                    model.dataModel.mediationManager.rejectMediationResult(trade);
                    model.dataModel.onOpenDispute();
                    acceptMediationResultPopup = null;
                })
                .onClose(() -> {
                    acceptMediationResultPopup = null;
                });

        if (hasSelfAccepted()) {
            acceptMediationResultPopup.disableActionButton();
        }

        acceptMediationResultPopup.show();
    }

    protected String getCurrencyName(Trade trade) {
        return CurrencyUtil.getNameByCode(getCurrencyCode(trade));
    }

    protected String getCurrencyCode(Trade trade) {
        return checkNotNull(trade.getOffer()).getCounterCurrencyCode();
    }

    protected boolean isXmrTrade() {
        return getCurrencyCode(trade).equals("XMR");
    }

    private void updateTradePeriodState(Trade.TradePeriodState tradePeriodState) {
        if (completed) return;
        updateTimeLeft();
        if (trade.isArbitrator() && trade.isDepositsFinalized()) {
            if (tradeStepInfo != null && (tradeStepInfo.getState() == TradeStepInfo.State.WARN_HALF_PERIOD ||
                    tradeStepInfo.getState() == TradeStepInfo.State.WARN_PERIOD_OVER)) {
                tradeStepInfo.setState(TradeStepInfo.State.SHOW_GET_HELP_BUTTON);
            }
            return;
        }
        if (!trade.getDisputeState().isDisputed()) {
            switch (tradePeriodState) {
                case FIRST_HALF:
                    // just for dev testing. not possible to go back in time ;-)
                    if (tradeStepInfo.getState() == TradeStepInfo.State.WARN_PERIOD_OVER) {
                        tradeStepInfo.setState(TradeStepInfo.State.WARN_HALF_PERIOD);
                    } else if (tradeStepInfo.getState() == TradeStepInfo.State.WARN_HALF_PERIOD) {
                        tradeStepInfo.setState(TradeStepInfo.State.SHOW_GET_HELP_BUTTON);
                        tradeStepInfo.setFirstHalfOverWarnTextSupplier(this::getFirstHalfOverWarnText);
                    }
                    break;
                case SECOND_HALF:
                    if (!trade.isPaymentReceived()) {
                        if (tradeStepInfo != null) {
                            tradeStepInfo.setFirstHalfOverWarnTextSupplier(this::getFirstHalfOverWarnText);
                            tradeStepInfo.setState(TradeStepInfo.State.WARN_HALF_PERIOD);
                        }
                    } else {
                        tradeStepInfo.setState(TradeStepInfo.State.SHOW_GET_HELP_BUTTON);
                    }
                    break;
                case TRADE_PERIOD_OVER:
                    if (tradeStepInfo != null) {
                        tradeStepInfo.setFirstHalfOverWarnTextSupplier(this::getPeriodOverWarnText);
                        tradeStepInfo.setState(TradeStepInfo.State.WARN_PERIOD_OVER);
                    }
                    break;
            }
        }
    }

    private void updateTradeState(Trade.State tradeState) {
        if (completed) return;
        updateTimeLeft();
        if (!trade.getDisputeState().isOpen() && trade.isMissingUnlockedDepositTx()) {
            tradeStepInfo.setState(TradeStepInfo.State.DEPOSIT_MISSING);
        }
    }

    // private void checkIfLockTimeIsOver() {
    //     if (trade.getDisputeState() == Trade.DisputeState.MEDIATION_CLOSED) {
    //         Transaction delayedPayoutTx = trade.getDelayedPayoutTx();
    //         if (delayedPayoutTx != null) {
    //             long lockTime = delayedPayoutTx.getLockTime();
    //             int bestChainHeight = model.dataModel.btcWalletService.getBestChainHeight();
    //             long remaining = lockTime - bestChainHeight;
    //             if (remaining <= 0) {
    //                 openMediationResultPopup(Res.get("portfolio.pending.mediationResult.popup.headline", trade.getShortId()));
    //             }
    //         }
    //     }
    // }

    // protected void checkForUnconfirmedTimeout() {
    //     if (trade.isDepositsConfirmed()) return;
    //     long unconfirmedHours = Duration.between(trade.getDate().toInstant(), Instant.now()).toHours();
    //     if (unconfirmedHours >= 3 && !trade.hasFailed()) {
    //         String key = "tradeUnconfirmedTooLong_" + trade.getShortId();
    //         if (DontShowAgainLookup.showAgain(key)) {
    //             new Popup().warning(Res.get("portfolio.pending.unconfirmedTooLong", trade.getShortId(), unconfirmedHours))
    //                     .dontShowAgainId(key)
    //                     .closeButtonText(Res.get("shared.ok"))
    //                     .show();
    //         }
    //     }
    // }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // TradeDurationLimitInfo
    ///////////////////////////////////////////////////////////////////////////////////////////

    private GridPane createInfoPopover() {
        GridPane infoGridPane = new GridPane();
        int rowIndex = 0;
        infoGridPane.setHgap(5);
        infoGridPane.setVgap(10);
        infoGridPane.setPadding(new Insets(10, 10, 10, 10));
        Label label = addMultilineLabel(infoGridPane, rowIndex++, Res.get("portfolio.pending.tradePeriodInfo", Trade.NUM_BLOCKS_DEPOSITS_FINALIZED));
        GridPane.setMargin(label, Insets.EMPTY);
        label.setMaxWidth(450);

        HBox warningBox = new HBox();
        warningBox.setMinHeight(30);
        warningBox.setPadding(new Insets(5));
        warningBox.getStyleClass().add("warning-box");
        GridPane.setRowIndex(warningBox, rowIndex);
        GridPane.setColumnSpan(warningBox, 2);

        Label warningIcon = new Label();
        GlyphsDude.setIcon(warningIcon, FontAwesomeIcon.WARNING);
        warningIcon.getStyleClass().add("warning");

        Label warning = new Label(Res.get("portfolio.pending.tradePeriodWarning"));
        warning.setWrapText(true);
        warning.setMaxWidth(410);

        warningBox.getChildren().addAll(warningIcon, warning);
        infoGridPane.getChildren().add(warningBox);

        return infoGridPane;
    }

    public void setChatCallback(TradeSubView.ChatCallback chatCallback) {
        this.chatCallback = chatCallback;
    }

    public void setCloseCallback(Runnable closeCallback) {
        this.closeCallback = closeCallback;
    }

    protected void setSyncStatus(String text) {
        syncStatus = text;
        updateStatus();
    }

    protected void setTradeStatus(String text) {
        tradeStatus = text;
        updateStatus();
    }

    protected void disableConfirmationButton(Button button) {
        // keep focus in the action row so disabling the button cannot scroll to the sidebar
        if (button.getScene() != null && button.getScene().getFocusOwner() == button)
            button.getParent().requestFocus();
        button.setDisable(true);
    }

    protected void updateStatus() {
        // keep the footer space while confirmation progress moves into the action row
        String syncText = confirmationInProgress || syncStatus == null ? "" : syncStatus;
        if (syncText.equals(Res.get("portfolio.pending.syncing"))) syncText += "…";
        syncLabel.setText(syncText);
        String text = getStatusText();
        if (statusLabel != null) {
            statusLabel.setMinWidth(0);
            statusLabel.setWrapText(!confirmationInProgress);
            HBox.setHgrow(statusLabel, Priority.ALWAYS);
            statusLabel.setText(text == null ? "" : text);
        }
    }

    private String getStatusText() {
        if (confirmationInProgress && syncStatus != null && !syncStatus.isEmpty()) return syncStatus;
        return tradeStatus;
    }
}
