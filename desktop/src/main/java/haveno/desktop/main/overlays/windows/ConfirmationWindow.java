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
import haveno.core.locale.LanguageUtil;
import haveno.core.locale.Res;
import haveno.core.user.DontShowAgainLookup;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipCheckBox;
import haveno.desktop.main.overlays.popups.PopupManager;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/** A confirmation with a fixed hero and actions around a scrollable review. */
public abstract class ConfirmationWindow<T extends ConfirmationWindow<T>> extends TxHeroWindow<T> {
    protected boolean destructive;
    protected String acknowledgement;
    private VBox buttons;

    protected ConfirmationWindow(double cardWidth) {
        width = cardWidth + 88; // popup shadow inset
    }

    @Override
    protected void createContent(boolean showAgainChecked) {
        rowIndex = -1;
        createGridPane();
        gridPane.setPadding(new Insets(76, 78, 74, 78));
        gridPane.setHgap(0); // spanning rows are measured without the gap but laid out with it
        gridPane.setVgap(0);
        gridPane.getStyleClass().add("review-popup");
        if (LanguageUtil.isDefaultLanguageRTL()) gridPane.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        addContent();
        addButtons();
        addDontShowAgainCheckBox(showAgainChecked);
        applyStyles();
    }

    protected void addReview(Node hero, VBox content) {
        gridPane.add(hero, 0, ++rowIndex, 2, 1);
        gridPane.add(createPinnedScrollPane(content), 0, ++rowIndex, 2, 1);
        GridPane.setMargin(pinnedScrollPane, new Insets(22, 0, 0, 0));
    }

    @Override
    protected void addButtons() {
        actionButton = new AutoTooltipButton(actionButtonText);
        actionButton.defaultButtonProperty().bind(actionButton.disableProperty().not());
        actionButton.setMaxWidth(Double.MAX_VALUE);
        actionButton.getStyleClass().add("action-button");
        if (destructive) actionButton.getStyleClass().add("review-danger");
        actionButton.setOnAction(e -> {
            if (actionButton.isDisabled()) return;
            actionButton.setDisable(true);
            hide();
            actionHandlerOptional.ifPresent(Runnable::run);
        });
        closeButton = new AutoTooltipButton(closeButtonText == null ? Res.get("shared.cancel") : closeButtonText);
        closeButton.getStyleClass().add("confirm-send-cancel");
        closeButton.setOnAction(e -> doClose());
        buttons = new VBox(6);
        buttons.setAlignment(Pos.CENTER);
        if (acknowledgement != null) {
            CheckBox gate = new AutoTooltipCheckBox(acknowledgement);
            gate.setWrapText(true);
            gate.getStyleClass().add("review-acknowledgement");
            actionButton.setDisable(true);
            actionButton.getStyleClass().add("action-button-gated");
            gate.selectedProperty().addListener((o, oldValue, selected) -> {
                actionButton.setDisable(!selected);
                if (selected) actionButton.getStyleClass().remove("action-button-gated");
                else if (!actionButton.getStyleClass().contains("action-button-gated")) actionButton.getStyleClass().add("action-button-gated");
            });
            buttons.getChildren().add(gate);
            VBox.setMargin(gate, new Insets(0, 0, 10, 0));
        }
        buttons.getChildren().addAll(actionButton, closeButton);
        gridPane.add(buttons, 0, ++rowIndex, 2, 1);
        GridPane.setMargin(buttons, new Insets(22, 0, 0, 0));
    }

    @Override
    protected void addDontShowAgainCheckBox(boolean checked) {
        if (dontShowAgainId == null) return;
        CheckBox check = new AutoTooltipCheckBox(dontShowAgainText == null ? Res.get("popup.doNotShowAgain") : dontShowAgainText);
        check.getStyleClass().add("review-dont-show");
        check.setWrapText(true);
        check.setSelected(checked);
        DontShowAgainLookup.dontShowAgain(dontShowAgainId, checked);
        check.setOnAction(e -> onDontShowAgainSelected(check.isSelected()));
        buttons.getChildren().add(check);
        VBox.setMargin(check, new Insets(10, 0, 0, 0));
    }

    protected void onDontShowAgainSelected(boolean selected) {
        DontShowAgainLookup.dontShowAgain(dontShowAgainId, selected);
    }

    @Override
    protected void onShow() {
        PopupManager.queueForDisplay(this);
    }

    @Override
    protected void onHidden() {
        super.onHidden();
        PopupManager.onHidden(this);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + ": " + super.toString();
    }

    static HBox footer(MaterialDesignIcon glyph, String text) {
        var label = HeroWindowComponents.label(text, "review-footer");
        HBox.setHgrow(label, Priority.ALWAYS);
        HBox footer = new HBox(8, HeroWindowComponents.icon(glyph, "15", "review-footer-icon"), label);
        footer.setAlignment(Pos.TOP_LEFT);
        return footer;
    }

    static HBox callout(MaterialDesignIcon glyph, String accent, String title, String body, String value) {
        HBox row = HeroWindowComponents.featureRow(glyph, accent, title, body);
        row.getStyleClass().addAll("review-callout", accent);
        if (value != null && !value.isBlank()) {
            HBox chip = HeroWindowComponents.copyValue(value, false);
            HeroWindowComponents.rowText(row).getChildren().add(1, chip);
            VBox.setMargin(chip, new Insets(6, 0, 4, 0));
        }
        return row;
    }
}
