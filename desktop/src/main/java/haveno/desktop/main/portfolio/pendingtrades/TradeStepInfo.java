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

package haveno.desktop.main.portfolio.pendingtrades;

import haveno.core.locale.Res;
import haveno.core.trade.Trade;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.SimpleMarkdownLabel;
import javafx.scene.control.Label;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.layout.VBox;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.function.Supplier;

@Slf4j
public class TradeStepInfo extends VBox {

    public enum State {
        UNDEFINED,
        SHOW_GET_HELP_BUTTON,
        IN_MEDIATION_SELF_REQUESTED,
        IN_MEDIATION_PEER_REQUESTED,
        MEDIATION_RESULT,
        MEDIATION_RESULT_SELF_ACCEPTED,
        MEDIATION_RESULT_PEER_ACCEPTED,
        IN_ARBITRATION_SELF_REQUESTED,
        IN_ARBITRATION_PEER_REQUESTED,
        IN_REFUND_REQUEST_SELF_REQUESTED,
        IN_REFUND_REQUEST_PEER_REQUESTED,
        WARN_HALF_PERIOD,
        WARN_PERIOD_OVER,
        DEPOSIT_MISSING,
        TRADE_COMPLETED
    }

    private final Label titledGroupBg;
    private final SimpleMarkdownLabel label;
    private final AutoTooltipButton button;
    @Nullable
    @Setter
    private Trade trade;
    @Getter
    private State state = State.UNDEFINED;
    private Supplier<String> firstHalfOverWarnTextSupplier = () -> "";
    private Supplier<String> periodOverWarnTextSupplier = () -> "";
    private Supplier<String> depositTxMissingWarnTextSupplier = () -> "";

    public TradeStepInfo() {
        setSpacing(12);
        getStyleClass().add("trade-support-state");
        titledGroupBg = new Label();
        titledGroupBg.setWrapText(true);
        titledGroupBg.getStyleClass().add("trade-section-heading");
        label = new SimpleMarkdownLabel("");
        button = new AutoTooltipButton();
        button.setMaxWidth(Double.MAX_VALUE);
        getChildren().addAll(titledGroupBg, label, button);
        setState(State.SHOW_GET_HELP_BUTTON);
    }

    public void setOnAction(EventHandler<ActionEvent> e) {
        button.setOnAction(e);
    }

    public void setFirstHalfOverWarnTextSupplier(Supplier<String> firstHalfOverWarnTextSupplier) {
        this.firstHalfOverWarnTextSupplier = firstHalfOverWarnTextSupplier;
    }

    public void setPeriodOverWarnTextSupplier(Supplier<String> periodOverWarnTextSupplier) {
        this.periodOverWarnTextSupplier = periodOverWarnTextSupplier;
    }

    public void setDepositTxMissingWarnTextSupplier(Supplier<String> depositTxMissingWarnTextSupplier) {
        this.depositTxMissingWarnTextSupplier = depositTxMissingWarnTextSupplier;
    }

    public void setState(State state) {
        this.state = state;
        boolean visible = state != State.SHOW_GET_HELP_BUTTON && state != State.TRADE_COMPLETED && state != State.UNDEFINED;
        setVisible(visible);
        setManaged(visible);
        boolean showTitle = state != State.WARN_HALF_PERIOD && state != State.WARN_PERIOD_OVER &&
                state != State.IN_ARBITRATION_SELF_REQUESTED && state != State.IN_ARBITRATION_PEER_REQUESTED;
        titledGroupBg.setVisible(showTitle);
        titledGroupBg.setManaged(showTitle);
        button.getStyleClass().remove("action-button");
        switch (state) {
            case UNDEFINED:
                break;
            case SHOW_GET_HELP_BUTTON:
                // grey button
                titledGroupBg.setText(Res.get("portfolio.pending.support.headline.getHelp"));
                label.updateContent("");
                button.setText(Res.get("portfolio.pending.support.button.getHelp"));
                button.setId(null);
                button.getStyleClass().remove("action-button");
                button.setDisable(false);
                break;
            case IN_ARBITRATION_SELF_REQUESTED:
                // red button
                String text = trade.getDisputeState().isOpen() ? Res.get("portfolio.pending.supportTicketOpened") : Res.get("portfolio.pending.arbitrationRequested");
                titledGroupBg.setText(text);
                label.updateContent(Res.get("portfolio.pending.tradeView.disputeSelf"));
                button.setText(trade.getDisputeState().isOpen() ? Res.get("portfolio.pending.tradeView.viewDispute") : text);
                button.setId("open-dispute-button");
                button.getStyleClass().remove("action-button");
                button.setDisable(!trade.getDisputeState().isOpen());
                break;
            case IN_ARBITRATION_PEER_REQUESTED:
                // red button
                text = trade.getDisputeState().isOpen() ? Res.get("portfolio.pending.supportTicketOpened") : Res.get("portfolio.pending.arbitrationRequested");
                titledGroupBg.setText(text);
                label.updateContent(Res.get("portfolio.pending.tradeView.disputePeer"));
                button.setText(trade.getDisputeState().isOpen() ? Res.get("portfolio.pending.tradeView.viewDispute") : text);
                button.setId("open-dispute-button");
                button.getStyleClass().remove("action-button");
                button.setDisable(!trade.getDisputeState().isOpen());
                break;
            case MEDIATION_RESULT:
                // green button
                titledGroupBg.setText(Res.get("portfolio.pending.mediationResult.headline"));
                label.updateContent(Res.get("portfolio.pending.mediationResult.info.noneAccepted"));
                button.setText(Res.get("portfolio.pending.mediationResult.button"));
                button.setId(null);
                button.getStyleClass().add("action-button");
                button.setDisable(false);
                break;
            case MEDIATION_RESULT_SELF_ACCEPTED:
                // green button deactivated
                titledGroupBg.setText(Res.get("portfolio.pending.mediationResult.headline"));
                label.updateContent(Res.get("portfolio.pending.mediationResult.info.selfAccepted"));
                button.setText(Res.get("portfolio.pending.mediationResult.button"));
                button.setId(null);
                button.getStyleClass().add("action-button");
                button.setDisable(false);
                break;
            case MEDIATION_RESULT_PEER_ACCEPTED:
                // green button
                titledGroupBg.setText(Res.get("portfolio.pending.mediationResult.headline"));
                label.updateContent(Res.get("portfolio.pending.mediationResult.info.peerAccepted"));
                button.setText(Res.get("portfolio.pending.mediationResult.button"));
                button.setId(null);
                button.getStyleClass().add("action-button");
                button.setDisable(false);
                break;
            case IN_REFUND_REQUEST_SELF_REQUESTED:
                // red button
                titledGroupBg.setText(Res.get("portfolio.pending.refundRequested"));
                label.updateContent(Res.get("portfolio.pending.disputeOpenedByUser", Res.get("portfolio.pending.communicateWithArbitrator")));
                button.setText(Res.get("portfolio.pending.refundRequested"));
                button.setId("open-dispute-button");
                button.getStyleClass().remove("action-button");
                button.setDisable(true);
                break;
            case IN_REFUND_REQUEST_PEER_REQUESTED:
                // red button
                titledGroupBg.setText(Res.get("portfolio.pending.refundRequested"));
                label.updateContent(Res.get("portfolio.pending.disputeOpenedByPeer", Res.get("portfolio.pending.communicateWithArbitrator")));
                button.setText(Res.get("portfolio.pending.refundRequested"));
                button.setId("open-dispute-button");
                button.getStyleClass().remove("action-button");
                button.setDisable(true);
                break;
            case WARN_HALF_PERIOD:
                // orange button
                titledGroupBg.setText(Res.get("portfolio.pending.support.headline.halfPeriodOver"));
                label.updateContent(firstHalfOverWarnTextSupplier.get());
                button.setText(Res.get("portfolio.pending.support.button.getHelp"));
                button.setId(null);
                button.getStyleClass().remove("action-button");
                button.setDisable(false);
                break;
            case WARN_PERIOD_OVER:
                // red button
                titledGroupBg.setText(Res.get("portfolio.pending.support.headline.periodOver"));
                label.updateContent(periodOverWarnTextSupplier.get());
                button.setText(Res.get("portfolio.pending.tradeView.openDispute"));
                button.setId("open-dispute-button");
                button.getStyleClass().remove("action-button");
                button.setDisable(false);
                break;
            case DEPOSIT_MISSING:
                // red button
                titledGroupBg.setText(Res.get("portfolio.pending.support.headline.depositTxMissing"));
                label.updateContent(depositTxMissingWarnTextSupplier.get());
                button.setText(Res.get("portfolio.pending.tradeView.openDispute"));
                button.setId("open-dispute-button");
                button.getStyleClass().remove("action-button");
                button.setDisable(false);
                break;
            case TRADE_COMPLETED:
                break;
            default:
                break;
        }

        if (trade != null && trade.getPayoutTxId() != null) {
            button.setDisable(true);
        }
    }
}
