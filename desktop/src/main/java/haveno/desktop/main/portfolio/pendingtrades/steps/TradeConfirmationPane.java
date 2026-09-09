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

import haveno.core.locale.Res;
import haveno.core.trade.Trade;
import haveno.core.xmr.wallet.XmrWalletService;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public class TradeConfirmationPane extends VBox {
    private final Label caption = new Label();
    private final Label count = new Label();
    private final Label target = new Label();
    private final Label estimate = new Label();
    private final ProgressBar progress = new ProgressBar(0);

    public TradeConfirmationPane() {
        getStyleClass().add("trade-confirmations");
        setSpacing(16);
        caption.getStyleClass().add("trade-field-label");
        count.getStyleClass().add("trade-confirmation-count");
        target.getStyleClass().add("trade-confirmation-target");
        estimate.getStyleClass().add("trade-secondary");
        estimate.setWrapText(true);
        HBox numbers = new HBox(6, count, target);
        numbers.setAlignment(Pos.BASELINE_LEFT);
        VBox metric = new VBox(4, caption, numbers);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox heading = new HBox(12, metric, spacer, estimate);
        heading.setAlignment(Pos.BOTTOM_LEFT);
        progress.getStyleClass().add("trade-progress");
        progress.setMaxWidth(Double.MAX_VALUE);
        getChildren().addAll(heading, progress);
    }

    public void update(Long confirmations, boolean unlocked) {
        update(confirmations, unlocked, false);
    }

    public void update(Long confirmations, boolean unlocked, boolean complete) {
        int required = unlocked ? Trade.NUM_BLOCKS_DEPOSITS_FINALIZED : XmrWalletService.NUM_BLOCKS_UNLOCK;
        caption.setText(Res.get(unlocked && !complete ? "portfolio.pending.tradeView.additionalConfirmations" : "portfolio.pending.tradeView.depositConfirmations"));
        count.setText(confirmations == null ? "—" : Long.toString(confirmations));
        target.setText(complete ? "" : "/ " + required);
        progress.setVisible(!complete);
        progress.setManaged(!complete);
        progress.setProgress(confirmations == null ? 0 : Math.min(1, (double) confirmations / required));
        estimate.setText(complete ? "" : confirmations == null ? Res.get("portfolio.pending.tradeView.awaitingDeposit") : confirmations >= required ? "" :
                Res.get("portfolio.pending.tradeView.confirmationEstimate", (required - confirmations) * 2));
        progress.setAccessibleText(confirmations == null ? Res.get("portfolio.pending.tradeView.awaitingDeposit") :
                Res.get("portfolio.pending.tradeView.confirmationCount", confirmations, required));
    }
}
