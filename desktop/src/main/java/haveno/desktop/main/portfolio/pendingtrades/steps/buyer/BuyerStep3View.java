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

package haveno.desktop.main.portfolio.pendingtrades.steps.buyer;

import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon;
import haveno.core.locale.Res;
import haveno.core.network.MessageState;
import haveno.desktop.main.portfolio.pendingtrades.PendingTradesViewModel;
import haveno.desktop.main.portfolio.pendingtrades.steps.TradeStepView;
import haveno.desktop.util.GlyphsDude;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

public class BuyerStep3View extends TradeStepView {
    private final ChangeListener<MessageState> messageStateChangeListener;
    private Label messageStatus;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, Initialisation
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BuyerStep3View(PendingTradesViewModel model) {
        super(model);

        messageStateChangeListener = (observable, oldValue, newValue) -> {
            updateMessageStateInfo();
        };
    }

    @Override
    public void activate() {
        super.activate();

        model.getPaymentSentMessageStateProperty().addListener(messageStateChangeListener);

        updateMessageStateInfo();
    }

    public void deactivate() {
        super.deactivate();

        model.getPaymentSentMessageStateProperty().removeListener(messageStateChangeListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Info
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void addInfoBlock() {
        super.addInfoBlock();
        messageStatus = new Label();
        messageStatus.setWrapText(true);
        messageStatus.setGraphicTextGap(8);
        messageStatus.getStyleClass().add("trade-message-status");
        statusLabel = new Label();
        statusLabel.getStyleClass().add("trade-secondary");
        statusLabel.setWrapText(true);
        statusLabel.visibleProperty().bind(statusLabel.textProperty().isNotEmpty());
        statusLabel.managedProperty().bind(statusLabel.visibleProperty());
        VBox status = new VBox(8, messageStatus, statusLabel);
        gridPane.add(status, 0, ++gridRow, 2, 1);
    }

    @Override
    protected String getInfoBlockTitle() {
        return Res.get("portfolio.pending.tradeView.waitingSellerTitle");
    }

    @Override
    protected String getInfoText() {
        return Res.get("portfolio.pending.tradeView.waitingSellerInfo", getCurrencyCode(trade));
    }

    private void updateMessageStateInfo() {
        MessageState messageState = model.getPaymentSentMessageStateProperty().get();
        if (messageState == null) messageState = MessageState.UNDEFINED;
        messageStatus.setText(Res.get("portfolio.pending.tradeView.notification." + messageState.name()));
        messageStatus.pseudoClassStateChanged(PseudoClass.getPseudoClass("delivered"),
                messageState == MessageState.ARRIVED || messageState == MessageState.ACKNOWLEDGED);
        messageStatus.pseudoClassStateChanged(PseudoClass.getPseudoClass("failed"),
                messageState == MessageState.FAILED || messageState == MessageState.NACKED);
        FontAwesomeIcon icon;
        switch (messageState) {
            case SENT:
                icon = FontAwesomeIcon.ARROW_RIGHT;
                break;
            case ARRIVED:
                icon = FontAwesomeIcon.CHECK;
                break;
            case STORED_IN_MAILBOX:
                icon = FontAwesomeIcon.ENVELOPE_ALT;
                break;
            case ACKNOWLEDGED:
                icon = FontAwesomeIcon.CHECK_CIRCLE;
                break;
            case FAILED:
            case NACKED:
                icon = FontAwesomeIcon.EXCLAMATION_CIRCLE;
                break;
            default:
                icon = FontAwesomeIcon.CLOCK_ALT;
                break;
        }
        messageStatus.setGraphic(GlyphsDude.createIcon(icon, "14"));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Warning
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected String getFirstHalfOverWarnText() {
        return Res.get("portfolio.pending.tradeView.waitingSellerHalf");
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Dispute
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected String getPeriodOverWarnText() {
        return Res.get("portfolio.pending.tradeView.waitingSellerExpired");
    }

    @Override
    protected void applyOnDisputeOpened() {
    }
}


