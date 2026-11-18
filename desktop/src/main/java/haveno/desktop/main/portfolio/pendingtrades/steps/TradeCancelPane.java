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

package haveno.desktop.main.portfolio.pendingtrades.steps;

import haveno.common.UserThread;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.core.locale.Res;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.protocol.TradePeer.CancelState;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.portfolio.pendingtrades.PendingTradesDataModel;
import haveno.desktop.util.GUIUtil;
import javafx.beans.property.BooleanProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import lombok.Getter;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets the traders cancel a trade by agreement: a link asks the peer, and the pane shows the state of a request
 * and answers the peer's.
 */
public class TradeCancelPane extends VBox {
    private final Trade trade;
    private final PendingTradesDataModel dataModel;
    @Getter
    private final Hyperlink askLink = new Hyperlink(Res.get("portfolio.pending.cancel.ask"));
    private final Label info = new Label();
    private final Hyperlink cancelLink = new Hyperlink();
    private final Hyperlink declineLink = new Hyperlink(Res.get("portfolio.pending.cancel.decline"));
    private final HBox actions = new HBox(16, cancelLink, declineLink);
    private final List<Subscription> subscriptions = new ArrayList<>();
    private final Runnable updateHandler;
    private final BooleanProperty busy; // from the data model, since this pane is rebuilt when its trade is selected again

    public TradeCancelPane(Trade trade, PendingTradesDataModel dataModel, Runnable updateHandler) {
        this.trade = trade;
        this.dataModel = dataModel;
        this.updateHandler = updateHandler;
        busy = dataModel.getCancelBusyProperty(trade);
        setSpacing(10);
        getStyleClass().addAll("trade-panel", "trade-cancel");
        Label heading = new Label(Res.get("portfolio.pending.cancel.headline"));
        heading.getStyleClass().add("trade-section-heading");
        info.setWrapText(true);
        info.getStyleClass().add("trade-secondary");
        askLink.setWrapText(true);
        for (Node node : List.of(this, askLink, actions, declineLink)) node.managedProperty().bind(node.visibleProperty());
        askLink.setOnAction(event -> onCancel());
        cancelLink.setOnAction(event -> onCancel());
        declineLink.setOnAction(event -> {
            if (dataModel.isBootstrappedOrShowPopup()) dataModel.onCancelDeclined(trade);
        });
        getChildren().addAll(heading, info, actions);
        update();
    }

    public void activate() {
        for (ObservableValue<?> property : List.of(trade.stateProperty(), trade.payoutStateProperty(), trade.disputeStateProperty(),
                trade.getSelf().getCancelStateProperty(), trade.getTradePeer().getCancelStateProperty(), trade.getBuyer().getCancelPayoutTxHexProperty(), busy)) {
            subscriptions.add(EasyBind.subscribe(property, value -> UserThread.execute(this::update)));
        }
    }

    public void deactivate() {
        subscriptions.forEach(Subscription::unsubscribe);
        subscriptions.clear();
    }

    private void update() {
        CancelState self = trade.getSelf().getCancelState();
        CancelState peer = trade.getTradePeer().getCancelState();
        boolean peerRequested = peer == CancelState.REQUESTED;
        boolean agreed = peerRequested && self == CancelState.REQUESTED;
        boolean permitted = trade.isCancelPermitted() && trade.isDepositsUnlocked();
        boolean signed = trade.isCancelSigned() && !trade.isPayoutPublished(); // the buyer can only finish canceling, and is warned even in a dispute
        info.setText(signed ? Res.get("portfolio.pending.cancel.signed") :
                agreed ? trade.isBuyer() ? Res.get("portfolio.pending.cancel.peerAgreed", getRefund()) : Res.get("portfolio.pending.cancel.agreed") :
                peerRequested ? Res.get("portfolio.pending.cancel.peerRequested", getRefund()) :
                self == CancelState.REQUESTED ? Res.get("portfolio.pending.cancel.requested") :
                self == CancelState.DECLINED ? Res.get(peer == CancelState.DECLINED ? "portfolio.pending.cancel.bothDeclined" : "portfolio.pending.cancel.declined") : "");
        setVisible((permitted || signed) && !info.getText().isEmpty());
        askLink.setVisible(permitted && self == CancelState.UNDEFINED && !peerRequested);

        // the buyer cancels the trade once the seller asked or agreed, and cannot decline after signing
        actions.setVisible(trade.isCancelActionable());
        cancelLink.setText(Res.get(agreed && !signed ? "portfolio.pending.cancel.confirm.agreed" : "portfolio.pending.cancel.confirm"));
        declineLink.setVisible(!agreed && !signed);
        for (Node node : List.of(askLink, cancelLink, declineLink)) node.setDisable(busy.get());
        keepFocus();
        updateHandler.run();
    }

    // keeps focus in the sidebar so a disabled or hidden link cannot pass it to the next control
    private void keepFocus() {
        Node owner = getScene() == null ? null : getScene().getFocusOwner();
        if (owner != askLink && owner != cancelLink && owner != declineLink) return;
        boolean usable = !busy.get();
        for (Node node = owner; usable && node != null; node = node.getParent()) usable = node.isVisible();
        if (!usable) getParent().requestFocus();
    }

    // brings the answer to the peer's request into view and focuses it, opening its confirmation when it is the only answer
    public boolean focusAction(ScrollPane scrollPane) {
        if (!actions.isVisible()) return false;
        GUIUtil.scrollIntoView(scrollPane, this);
        cancelLink.requestFocus();
        if (!declineLink.isVisible()) cancelLink.fire();
        return true;
    }

    private String getRefund() {
        return HavenoUtils.formatXmr(trade.getReservedAmount(), true);
    }

    private void onCancel() {
        if (!dataModel.isReadyForTxBroadcast()) return;
        boolean agreeing = trade.getTradePeer().getCancelState() == CancelState.REQUESTED;
        boolean confirming = agreeing && trade.isBuyer(); // the buyer cancels the trade only after this popup
        String messageKey = agreeing ?
                trade.isBuyer() ? "portfolio.pending.cancel.confirm.buyer" : "portfolio.pending.cancel.confirm.seller" :
                trade.isBuyer() ? "portfolio.pending.cancel.ask.buyer" : "portfolio.pending.cancel.ask.seller";
        Popup popup = new Popup().headLine(Res.get(agreeing ? "portfolio.pending.cancel.confirm.headline" : "portfolio.pending.cancel.ask.headline"));
        String message = Res.get(messageKey, getRefund());
        if (agreeing) popup.warning(message).destructive(); // both traders then agree, and a canceled trade cannot be restored
        else popup.confirmation(message);
        popup.actionButtonText(Res.get(agreeing ? "portfolio.pending.cancel.confirm" : "portfolio.pending.cancel.ask"))
                .closeButtonText(Res.get("shared.close"))
                .onAction(() -> {
                    ErrorMessageHandler errorMessageHandler = errorMessage -> UserThread.execute(() -> new Popup().warning(errorMessage).show());
                    if (confirming) dataModel.onCancelConfirmed(trade, () -> {}, errorMessageHandler);
                    else dataModel.onCancelRequested(trade, () -> {}, errorMessageHandler);
                })
                .show();
    }
}
