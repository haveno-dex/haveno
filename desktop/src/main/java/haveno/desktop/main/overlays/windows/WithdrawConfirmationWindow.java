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
import haveno.core.trade.HavenoUtils;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipLabel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.math.BigInteger;
import java.util.function.Function;

/** Confirmation showing the amount, destination and fee of a pending withdrawal. */
public class WithdrawConfirmationWindow extends TxHeroWindow<WithdrawConfirmationWindow> {

    private final BigInteger amount;
    private final BigInteger fee;
    private final String address;
    private final Function<BigInteger, String> fiatText; // approximate fiat for an amount, or null while no price

    public WithdrawConfirmationWindow(BigInteger amount, BigInteger fee, String address, Function<BigInteger, String> fiatText) {
        this.amount = amount;
        this.fee = fee;
        this.address = address;
        this.fiatText = fiatText;
    }

    @Override
    public void show() {
        showHeroWindow();
    }

    @Override
    protected void addContent() {
        VBox toGroup = sheetGroup(Res.get("funds.withdrawal.confirm.to"), wrappedLabel(address, "confirm-send-address"), copyIcon(address));
        HBox feeRow = detailRow(Res.get("funds.withdrawal.confirm.networkFee"), valueLabel(HavenoUtils.formatXmr(fee, true)));

        // total debited from the wallet, with a fiat approximation
        BigInteger total = amount.add(fee);
        Label totalValue = valueLabel(HavenoUtils.formatXmr(total, true));
        totalValue.getStyleClass().add("confirm-send-total");
        VBox totalBox = new VBox(totalValue);
        totalBox.setAlignment(Pos.CENTER_RIGHT);
        String totalFiat = fiatText.apply(total);
        if (totalFiat != null) {
            Label totalFiatLabel = new AutoTooltipLabel(totalFiat);
            totalFiatLabel.getStyleClass().add("confirm-send-row-fiat");
            totalBox.getChildren().add(totalFiatLabel);
        }
        HBox totalRow = detailRow(Res.get("funds.withdrawal.confirm.total"), totalBox);

        addHeroContent(
                hero(MaterialDesignIcon.SEND, "confirm-send-icon", Res.get("funds.withdrawal.confirm.headline"), amount, fiatText.apply(amount)),
                sheet(toGroup, feeRow, totalRow));
    }

    @Override
    protected void addButtons() {
        actionButton = new AutoTooltipButton(Res.get("funds.withdrawal.confirm.send"));
        actionButton.setDefaultButton(true);
        actionButton.getStyleClass().add("action-button");
        actionButton.setMaxWidth(Double.MAX_VALUE);
        actionButton.setOnAction(e -> {
            hide();
            actionHandlerOptional.ifPresent(Runnable::run);
        });

        closeButton = new AutoTooltipButton(closeButtonText == null ? Res.get("shared.cancel") : closeButtonText);
        closeButton.getStyleClass().add("confirm-send-cancel");
        closeButton.setOnAction(e -> doClose());

        VBox buttons = new VBox(8, actionButton, closeButton);
        buttons.setAlignment(Pos.CENTER);
        buttons.setFillWidth(true);
        gridPane.add(buttons, 0, ++rowIndex, 2, 1);
        GridPane.setMargin(buttons, new Insets(26, 0, 0, 0));
    }
}
