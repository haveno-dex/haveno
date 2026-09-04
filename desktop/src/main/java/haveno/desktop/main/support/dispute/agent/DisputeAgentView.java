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

package haveno.desktop.main.support.dispute.agent;

import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.common.crypto.KeyRing;
import haveno.common.util.Utilities;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.alert.PrivateNotificationManager;
import haveno.core.locale.Res;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeList;
import haveno.core.support.dispute.DisputeManager;
import haveno.core.support.dispute.DisputeValidation;
import haveno.core.support.dispute.agent.MultipleHolderNameDetection;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.user.DontShowAgainLookup;
import haveno.core.user.Preferences;
import haveno.core.util.VolumeUtil;
import haveno.core.util.coin.CoinFormatter;
import haveno.desktop.components.AutoTooltipTableColumn;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.ContractWindow;
import haveno.desktop.main.overlays.windows.DisputeSummaryWindow;
import haveno.desktop.main.overlays.windows.TradeDetailsWindow;
import haveno.desktop.main.support.dispute.DisputeView;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.text.Text;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import haveno.desktop.util.Accessibility;
import haveno.desktop.util.DisplayUtils;
import static haveno.desktop.util.FormBuilder.getIconForLabel;

public abstract class DisputeAgentView extends DisputeView implements MultipleHolderNameDetection.Listener {

    private final MultipleHolderNameDetection multipleHolderNameDetection;
    private final Map<String, List<Trade>> ambiguousPaymentTradesByTradeId = new HashMap<>();
    private long ambiguousPaymentTradesTime;
    private ListChangeListener<DisputeValidation.ValidationException> validationExceptionListener;

    public DisputeAgentView(DisputeManager<? extends DisputeList<Dispute>> disputeManager,
                            KeyRing keyRing,
                            TradeManager tradeManager,
                            CoinFormatter formatter,
                            Preferences preferences,
                            DisputeSummaryWindow disputeSummaryWindow,
                            PrivateNotificationManager privateNotificationManager,
                            ContractWindow contractWindow,
                            TradeDetailsWindow tradeDetailsWindow,
                            AccountAgeWitnessService accountAgeWitnessService,
                            ArbitratorManager arbitratorManager,
                            boolean useDevPrivilegeKeys) {
        super(disputeManager,
                keyRing,
                tradeManager,
                formatter,
                preferences,
                disputeSummaryWindow,
                privateNotificationManager,
                contractWindow,
                tradeDetailsWindow,
                accountAgeWitnessService,
                arbitratorManager,
                useDevPrivilegeKeys);

        multipleHolderNameDetection = new MultipleHolderNameDetection(disputeManager);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Life cycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void initialize() {
        super.initialize();

        filterTextField.setText("open");

        sendPrivateNotificationButton.setVisible(true);
        sendPrivateNotificationButton.setManaged(true);

        reportButton.setVisible(true);
        reportButton.setManaged(true);

        fullReportButton.setVisible(true);
        fullReportButton.setManaged(true);

        multipleHolderNameDetection.detectMultipleHolderNames();

        validationExceptionListener = c -> {
            c.next();
            if (c.wasAdded()) {
                showWarningForValidationExceptions(c.getAddedSubList());
            }
        };
    }

    protected void showWarningForValidationExceptions(List<? extends DisputeValidation.ValidationException> exceptions) {
        exceptions.stream()
                .filter(ex -> ex.getDispute() != null)
                .filter(ex -> !ex.getDispute().isClosed()) // we show warnings only for open cases
                .filter(ex -> DontShowAgainLookup.showAgain(getKey(ex)))
                .forEach(ex -> new Popup().width(900).warning(getValidationExceptionMessage(ex)).dontShowAgainId(getKey(ex)).show());
    }

    private String getKey(DisputeValidation.ValidationException exception) {
        Dispute dispute = exception.getDispute();
        if (dispute != null) {
            return "ValExcPopup-" + dispute.getTradeId() + "-" + dispute.getTraderId();
        }
        return "ValExcPopup-" + exception.toString();
    }

    private String getValidationExceptionMessage(DisputeValidation.ValidationException exception) {
        Dispute dispute = exception.getDispute();
        if (dispute != null && exception instanceof DisputeValidation.AddressException) {
            return getAddressExceptionMessage(dispute);
        } else if (exception.getMessage() != null && !exception.getMessage().isEmpty()) {
            return exception.getMessage();
        } else {
            return exception.toString();
        }
    }

    @NotNull
    private String getAddressExceptionMessage(Dispute dispute) {
        return Res.get("support.warning.disputesWithInvalidDonationAddress",
                dispute.getDonationAddressOfDelayedPayoutTx(),
                dispute.getTradeId(),
                "");
    }

    @Override
    protected void activate() {
        super.activate();

        multipleHolderNameDetection.addListener(this);
        if (multipleHolderNameDetection.hasSuspiciousDisputesDetected()) {
            suspiciousDisputeDetected();
        }

        disputeManager.getValidationExceptions().addListener(validationExceptionListener);
        showWarningForValidationExceptions(disputeManager.getValidationExceptions());
    }

    @Override
    protected void deactivate() {
        super.deactivate();

        multipleHolderNameDetection.removeListener(this);

        disputeManager.getValidationExceptions().removeListener(validationExceptionListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // MultipleHolderNamesDetection.Listener
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onSuspiciousDisputeDetected() {
        suspiciousDisputeDetected();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // DisputeView
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected DisputeView.FilterResult getFilterResult(Dispute dispute, String filterString) {
        // If in arbitrator view we must only display disputes where we are selected as arbitrator (must not receive others anyway)
        if (!dispute.getAgentPubKeyRing().equals(keyRing.getPubKeyRing())) {
            return FilterResult.NO_MATCH;
        }

        return super.getFilterResult(dispute, filterString);
    }

    @Override
    protected void handleOnProcessDispute(Dispute dispute) {
        onCloseDispute(dispute);
    }

    @Override
    protected void setupTable() {
        super.setupTable();

        tableView.getColumns().add(getAlertColumn());
    }

    protected abstract void onCloseDispute(Dispute dispute);


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void suspiciousDisputeDetected() {
        alertIconLabel.setVisible(true);
        alertIconLabel.setManaged(true);
        alertIconLabel.setTooltip(new Tooltip("You have suspicious disputes where the same trader used different " +
                "account holder names.\nClick for more information."));
        Accessibility.asButton(alertIconLabel, alertIconLabel.getTooltip().getText());
        // Text below is for arbitrators only so no need to translate it
        alertIconLabel.setOnMouseClicked(e -> {
            String reportForAllDisputes = multipleHolderNameDetection.getReportForAllDisputes();
            new Popup()
                    .width(1100)
                    .warning(getReportMessage(reportForAllDisputes, "traders"))
                    .actionButtonText(Res.get("shared.copyToClipboard"))
                    .onAction(() -> Utilities.copyToClipboard(reportForAllDisputes))
                    .show();
        });
    }


    private TableColumn<Dispute, Dispute> getAlertColumn() {
        TableColumn<Dispute, Dispute> column = new AutoTooltipTableColumn<>("Alert") {
            {
                setMinWidth(50);
            }
        };
        column.setCellValueFactory((dispute) -> new ReadOnlyObjectWrapper<>(dispute.getValue()));
        column.setCellFactory(
                c -> new TableCell<>() {
                    Label alertIconLabel;

                    @Override
                    public void updateItem(Dispute dispute, boolean empty) {
                        if (dispute != null && !empty) {
                            if (!showAlertAtDispute(dispute)) {
                                setGraphic(null);
                                if (alertIconLabel != null) {
                                    alertIconLabel.setOnMouseClicked(null);
                                }
                                return;
                            }

                            if (alertIconLabel != null) {
                                alertIconLabel.setOnMouseClicked(null);
                            }

                            alertIconLabel = new Label();
                            Text icon = getIconForLabel(MaterialDesignIcon.ALERT_CIRCLE_OUTLINE, "1.5em", alertIconLabel);
                            icon.getStyleClass().add("alert-icon");
                            Accessibility.asButton(alertIconLabel, "Suspicious dispute detected"); // arbitrator-only, not translated
                            HBox.setMargin(alertIconLabel, new Insets(4, 0, 0, 10));
                            alertIconLabel.setMouseTransparent(false);
                            setGraphic(alertIconLabel);

                            alertIconLabel.setOnMouseClicked(e -> {
                                String key = MultipleHolderNameDetection.getAckKey(dispute);
                                List<Dispute> holderNameDisputes = DontShowAgainLookup.showAgain(key) ? multipleHolderNameDetection.getDisputesForTrader(dispute) : List.of();
                                List<Trade> ambiguousPaymentTrades = getTradesWithIndistinguishablePayment(dispute);
                                String report = "";
                                String message = "";
                                if (!holderNameDisputes.isEmpty()) {
                                    report = multipleHolderNameDetection.getReportForDisputeOfTrader(holderNameDisputes);
                                    message = getReportMessage(report, "this trader");
                                }
                                if (!ambiguousPaymentTrades.isEmpty()) {
                                    String paymentReport = getIndistinguishablePaymentReport(ambiguousPaymentTrades);
                                    report += (report.isEmpty() ? "" : "\n\n") + paymentReport;
                                    message += (message.isEmpty() ? "" : "\n\n") + getIndistinguishablePaymentMessage(dispute, paymentReport);
                                }
                                String fullReport = report;
                                Popup popup = new Popup()
                                        .width(1100)
                                        .warning(message)
                                        .actionButtonText(Res.get("shared.copyToClipboard"))
                                        .onAction(() -> {
                                            Utilities.copyToClipboard(fullReport);
                                            if (!showAlertAtDispute(dispute)) setGraphic(null);
                                        })
                                        .onClose(() -> {
                                            if (!showAlertAtDispute(dispute)) setGraphic(null);
                                        });
                                if (!holderNameDisputes.isEmpty()) {
                                    popup.dontShowAgainId(key).dontShowAgainText("Is not suspicious");
                                }
                                popup.show();
                            });
                        } else {
                            setGraphic(null);
                            if (alertIconLabel != null) {
                                alertIconLabel.setOnMouseClicked(null);
                            }
                        }
                    }
                });

        column.setComparator((o1, o2) -> Boolean.compare(showAlertAtDispute(o1), showAlertAtDispute(o2)));
        column.setSortable(true);
        return column;
    }

    private boolean showAlertAtDispute(Dispute dispute) {
        return (DontShowAgainLookup.showAgain(MultipleHolderNameDetection.getAckKey(dispute)) &&
                !multipleHolderNameDetection.getDisputesForTrader(dispute).isEmpty()) ||
                !getTradesWithIndistinguishablePayment(dispute).isEmpty();
    }

    // memoized briefly since each lookup scans every trade and table sorts compare rows repeatedly
    private List<Trade> getTradesWithIndistinguishablePayment(Dispute dispute) {
        long now = System.currentTimeMillis();
        if (now - ambiguousPaymentTradesTime > 2000) {
            ambiguousPaymentTradesByTradeId.clear();
            ambiguousPaymentTradesTime = now;
        }
        return ambiguousPaymentTradesByTradeId.computeIfAbsent(dispute.getTradeId(), tradeId -> {
            Trade trade = tradeManager.getTrade(tradeId);
            return trade == null ? List.of() : tradeManager.getTradesWithIndistinguishablePayment(trade);
        });
    }

    // Text below is for arbitrators only so no need to translate it
    private String getIndistinguishablePaymentReport(List<Trade> trades) {
        return trades.stream()
                .map(trade -> "    Trade ID: '" + trade.getShortId() +
                        "'\n        Amount: '" + VolumeUtil.formatVolumeWithCode(trade.getVolume()) +
                        "'\n        Started: '" + DisplayUtils.formatDateTime(trade.getDate()) +
                        "'\n        Paid out: '" + (trade.isPayoutPublished() ? "yes" : "no") + "'")
                .collect(Collectors.joining("\n"));
    }

    private String getIndistinguishablePaymentMessage(Dispute dispute, String report) {
        return "Trade " + dispute.getShortTradeId() + " has the same buyer, seller, payment method, and amount as the " +
                "following trade(s), and a payment could be claimed for both at the same time, so one payment " +
                "might be presented as evidence for more than one trade.\n\n" +
                "Verify that each trade was paid with its own transaction, e.g. by comparing transaction dates and identifiers.\n\n" +
                report;
    }

    private String getReportMessage(String report, String subString) {
        return "You have dispute cases where " + subString + " used different account holder names.\n\n" +
                "This might be not critical in case of small variations of the same name " +
                "(e.g. first name and last name are swapped), " +
                "but if the name is completely different you should request information from the trader why they " +
                "used a different name and request proof that the person with the real name is aware " +
                "of the trade. " +
                "It can be that the trader uses the account of their wife/husband, but it also could " +
                "be a case of a stolen bank account or money laundering.\n\n" +
                "Please check below the list of the names which have been detected. " +
                "Search with the trade ID for the dispute case or check out the alert icon at each dispute in " +
                "the list (you might need to remove the 'open' filter) and evaluate " +
                "if it might be a fraudulent account (buyer role is more likely to be fraudulent). " +
                "If you find suspicious disputes, please notify the developers and provide the contract json data " +
                "to them so they can ban those traders.\n\n" +
                Utilities.toTruncatedString(report, 700, false);
    }

    @Override
    protected void maybeAddProcessColumnsForAgent() {
        tableView.getColumns().add(getProcessColumn());
        tableView.getColumns().add(getChatColumn());
    }

    @Override
    protected boolean senderFlag() {
        return true;
    }
}


