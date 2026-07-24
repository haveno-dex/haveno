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

package haveno.desktop.main.overlays.windows;

import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.core.locale.Res;
import haveno.core.user.DontShowAgainLookup;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipCheckBox;
import haveno.desktop.components.AutoTooltipLabel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Info shown before funding an offer or trade: hero deposit amount over the breakdown sheet. */
public class FundWalletInfoWindow extends TxHeroWindow<FundWalletInfoWindow> {
    private String totalToPay, tradeAmount, securityDeposit, tradeFee;
    private boolean showReservedNote;

    // values are the model's preformatted "X XMR (≈ Y USD ...)" strings
    public FundWalletInfoWindow totalToPay(String totalToPay) {
        this.totalToPay = totalToPay;
        return this;
    }

    public FundWalletInfoWindow tradeAmount(@Nullable String tradeAmount) {
        this.tradeAmount = tradeAmount;
        return this;
    }

    public FundWalletInfoWindow securityDeposit(String securityDeposit) {
        this.securityDeposit = securityDeposit;
        return this;
    }

    public FundWalletInfoWindow tradeFee(String tradeFee) {
        this.tradeFee = tradeFee;
        return this;
    }

    public FundWalletInfoWindow showReservedNote() {
        this.showReservedNote = true;
        return this;
    }

    @Override
    public void show() {
        if (dontShowAgainId != null && !DontShowAgainLookup.showAgain(dontShowAgainId)) return;
        showHeroWindow();
    }

    @Override
    protected void addContent() {
        List<Node> groups = new ArrayList<>();
        if (tradeAmount != null) groups.add(valueRow(Res.get("shared.tradeAmount"), tradeAmount));
        groups.add(valueRow(Res.get("shared.yourSecurityDeposit"), securityDeposit));
        groups.add(valueRow(Res.get("createOffer.fundsBox.offerFee"), tradeFee));

        String[] total = splitApprox(totalToPay);
        List<Node> blocks = new ArrayList<>();
        blocks.add(hero(MaterialDesignIcon.WALLET, "confirm-send-icon", headLine, total[0], total[1]));
        blocks.add(sheet(groups.toArray(new Node[0])));

        if (showReservedNote) {
            Label note = wrappedLabel(Res.get("fundWalletInfoWindow.reserved"), "fund-info-note");
            blocks.add(note);
        }

        addHeroContent(blocks.toArray(new Node[0]));
    }

    // a detail row whose value stacks the XMR amount over its fiat/percentage detail
    private static HBox valueRow(String labelText, String composedValue) {
        String[] parts = splitApprox(composedValue);
        VBox valueBox = new VBox(valueLabel(parts[0]));
        valueBox.setAlignment(Pos.CENTER_RIGHT);
        if (parts[1] != null) {
            Label detail = new AutoTooltipLabel(parts[1]);
            detail.getStyleClass().add("confirm-send-row-fiat");
            valueBox.getChildren().add(detail);
        }
        return detailRow(labelText, valueBox);
    }

    // splits "0.15 XMR (≈ 53.18 USD / 15.00% of trade amount)" into amount and detail
    private static String[] splitApprox(String composedValue) {
        int idx = composedValue.indexOf(" (");
        if (idx > 0 && composedValue.endsWith(")"))
            return new String[]{composedValue.substring(0, idx), composedValue.substring(idx + 2, composedValue.length() - 1)};
        return new String[]{composedValue, null};
    }

    // full-width close with the don't-show-again choice beneath
    @Override
    protected void addButtons() {
        actionButton = new AutoTooltipButton(Res.get("shared.close"));
        actionButton.setDefaultButton(true);
        actionButton.getStyleClass().add("action-button");
        actionButton.setMaxWidth(Double.MAX_VALUE);
        actionButton.setOnAction(e -> doClose());

        VBox buttons = new VBox(14, actionButton);
        buttons.setFillWidth(true);
        if (dontShowAgainId != null) {
            CheckBox checkBox = new AutoTooltipCheckBox(Res.get("popup.doNotShowAgain"));
            checkBox.setOnAction(e -> DontShowAgainLookup.dontShowAgain(dontShowAgainId, checkBox.isSelected()));
            HBox checkBoxRow = new HBox(checkBox);
            checkBoxRow.setAlignment(Pos.CENTER);
            buttons.getChildren().add(checkBoxRow);
        }
        gridPane.add(buttons, 0, ++rowIndex, 2, 1);
        GridPane.setMargin(buttons, new Insets(26, 0, 0, 0));
    }
}
