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
import haveno.common.UserThread;
import haveno.common.util.Utilities;
import haveno.core.locale.Res;
import haveno.core.trade.HavenoUtils;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.util.GlyphsDude;
import haveno.desktop.util.Layout;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.math.BigInteger;
import java.util.function.Function;

/** Confirmation showing the amount, destination and fee of a pending withdrawal. */
public class WithdrawConfirmationWindow extends Overlay<WithdrawConfirmationWindow> {

    private final BigInteger amount;
    private final BigInteger fee;
    private final String address;
    private final Function<BigInteger, String> fiatText; // approximate fiat for an amount, or null while no price

    public WithdrawConfirmationWindow(BigInteger amount, BigInteger fee, String address, Function<BigInteger, String> fiatText) {
        type = Type.Confirmation;
        this.amount = amount;
        this.fee = fee;
        this.address = address;
        this.fiatText = fiatText;
    }

    @Override
    public void show() {
        width = 560;
        createGridPane();
        gridPane.setPadding(new Insets(76, 78, 74, 78)); // popup-bg reserves 44 per side for the dropshadow
        addContent();
        addButtons();
        applyStyles();
        display();
    }

    private void addContent() {
        // hero: the amount being sent, front and center
        Label icon = new Label();
        icon.setGraphic(GlyphsDude.createIcon(MaterialDesignIcon.SEND, "1.6em"));
        icon.getStyleClass().add("confirm-send-icon");
        icon.setMinSize(50, 50);
        icon.setMaxSize(50, 50);
        icon.setAlignment(Pos.CENTER);

        Label title = new AutoTooltipLabel(Res.get("funds.withdrawal.confirm.headline"));
        title.getStyleClass().add("confirm-send-title");
        Label amountLabel = new AutoTooltipLabel(HavenoUtils.formatXmr(amount, true));
        amountLabel.getStyleClass().add("confirm-send-amount");

        VBox hero = new VBox(icon, title, amountLabel);
        hero.setAlignment(Pos.CENTER);
        VBox.setMargin(title, new Insets(16, 0, 0, 0));
        VBox.setMargin(amountLabel, new Insets(8, 0, 0, 0));
        String amountFiat = fiatText.apply(amount);
        if (amountFiat != null) {
            Label fiatLabel = new AutoTooltipLabel(amountFiat);
            fiatLabel.getStyleClass().add("confirm-send-fiat");
            VBox.setMargin(fiatLabel, new Insets(3, 0, 0, 0));
            hero.getChildren().add(fiatLabel);
        }

        // destination with a copy shortcut; the address wraps in full below
        Label copyIcon = new Label();
        copyIcon.setGraphic(GlyphsDude.createIcon(MaterialDesignIcon.CONTENT_COPY, "1.1em"));
        copyIcon.getStyleClass().add("confirm-send-copy");
        copyIcon.setCursor(Cursor.HAND);
        copyIcon.setTooltip(new Tooltip(Res.get("shared.copyToClipboard")));
        copyIcon.setOnMouseClicked(e -> {
            Utilities.copyToClipboard(address);
            Tooltip tp = new Tooltip(Res.get("shared.copiedToClipboard"));
            tp.show(copyIcon, e.getScreenX() + Layout.PADDING, e.getScreenY() + Layout.PADDING);
            UserThread.runAfter(tp::hide, 1);
        });
        Region toSpacer = new Region();
        HBox.setHgrow(toSpacer, Priority.ALWAYS);
        HBox toRow = new HBox(rowLabel(Res.get("funds.withdrawal.confirm.to")), toSpacer, copyIcon);
        toRow.setAlignment(Pos.CENTER_LEFT);
        Label addressLabel = new Label(address);
        addressLabel.setWrapText(true);
        addressLabel.getStyleClass().add("confirm-send-address");
        VBox toGroup = new VBox(3, toRow, addressLabel);

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

        VBox sheet = new VBox(14, toGroup, divider(), feeRow, divider(), totalRow);
        sheet.getStyleClass().add("confirm-send-sheet");

        VBox content = new VBox(24, hero, sheet);
        content.setFillWidth(true);
        gridPane.add(content, 0, ++rowIndex, 2, 1);
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

    private static Label rowLabel(String text) {
        Label label = new AutoTooltipLabel(text);
        label.getStyleClass().add("confirm-send-row-label");
        return label;
    }

    private static Label valueLabel(String text) {
        Label label = new AutoTooltipLabel(text);
        label.getStyleClass().add("confirm-send-row-value");
        return label;
    }

    private static HBox detailRow(String labelText, Node value) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(rowLabel(labelText), spacer, value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Region divider() {
        Region line = new Region();
        line.getStyleClass().add("confirm-send-divider");
        return line;
    }
}
