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

package haveno.desktop.app;

import com.jfoenix.controls.JFXComboBox;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.common.config.Config;
import haveno.common.util.Utilities;
import haveno.core.locale.Res;
import haveno.core.user.Preferences;
import haveno.core.xmr.nodes.XmrNodes;
import haveno.desktop.components.AutoTooltipLabel;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.util.StringConverter;
import javax.annotation.Nullable;

// Wizard step to connect to the default Monero nodes or exclusively to the user's own custom nodes, and to choose when
// Monero traffic uses Tor, applied before the first connection. Skipped on a quick start or with a node argument.
public class StartupWizardNodeStep implements StartupWizard.Step {

    private static final double FIELD_WIDTH = 620;
    private static final double CARD_GAP = 20;

    private final BooleanSupplier quickStart;
    private final boolean nodesSetByOptions, torSetByOptions;
    private final VBox content;
    private final StartupWizard.ChoiceCard defaultCard, customCard;
    private final TextField nodesField;
    private final ComboBox<Preferences.UseTorForXmr> torCombo;
    private final Label statusLabel = new AutoTooltipLabel();
    // gates the next button while custom nodes are selected but do not parse
    private final BooleanProperty nextBlocked = new SimpleBooleanProperty(false);

    public StartupWizardNodeStep(BooleanSupplier quickStart, Config config) {
        this.quickStart = quickStart;
        this.nodesSetByOptions = !config.xmrNode.isEmpty() || !config.xmrNodes.isEmpty();
        this.torSetByOptions = config.useTorForXmrOptionSetExplicitly;

        defaultCard = new StartupWizard.ChoiceCard(MaterialDesignIcon.CLOUD_OUTLINE,
                Res.get("startupWizard.node.default"),
                Res.get("startupWizard.mode.quick.badge"),
                Res.get("startupWizard.node.default.body"));
        customCard = new StartupWizard.ChoiceCard(MaterialDesignIcon.SERVER,
                Res.get("startupWizard.node.custom"),
                null,
                Res.get("startupWizard.node.custom.body"));

        double cardWidth = (StartupWizard.PAGE_WIDTH - CARD_GAP) / 2;
        for (StartupWizard.ChoiceCard card : new StartupWizard.ChoiceCard[]{defaultCard, customCard}) {
            card.setPrefWidth(cardWidth);
            card.setMaxWidth(cardWidth);
            HBox.setHgrow(card, Priority.ALWAYS);
        }

        HBox cardBox = new HBox(CARD_GAP, defaultCard, customCard);
        cardBox.setAlignment(Pos.CENTER);

        nodesField = new TextField();
        nodesField.getStyleClass().add("login-password-field");
        nodesField.setPromptText(Res.get("startupWizard.node.prompt"));
        nodesField.setMaxWidth(FIELD_WIDTH);
        nodesField.textProperty().addListener((observable, oldValue, newValue) -> {
            clearError();
            updateNextBlocked();
        });
        nodesField.focusedProperty().addListener((observable, oldValue, focused) -> {
            if (!focused && !nodesField.getText().trim().isEmpty() && !isNodeListValid()) showError();
        });

        Label nodesInfo = new AutoTooltipLabel(Res.get("startupWizard.node.nodesInfo"));
        nodesInfo.getStyleClass().add("startup-wizard-footer-label");
        nodesInfo.setWrapText(true);
        nodesInfo.setMaxWidth(FIELD_WIDTH);

        VBox customBox = new VBox(10, nodesField, nodesInfo);
        customBox.setAlignment(Pos.TOP_CENTER);
        VBox.setMargin(customBox, new Insets(5, 0, 0, 0));

        StartupWizard.ChoiceCard.group(() -> {
            clearError();
            updateNextBlocked();
            boolean customSelected = customCard.isSelected();
            customBox.setVisible(customSelected);
            customBox.setManaged(customSelected);
            if (customSelected) nodesField.requestFocus();
        }, defaultCard, customCard);
        defaultCard.setSelected(true);
        customBox.setVisible(false);
        customBox.setManaged(false);

        torCombo = new JFXComboBox<>();
        torCombo.getItems().addAll(Preferences.UseTorForXmr.values());
        torCombo.getSelectionModel().select(Preferences.UseTorForXmr.AFTER_SYNC);
        // fixed width so a shorter selection cannot shrink and re-center the row
        torCombo.setPrefWidth(240);
        torCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Preferences.UseTorForXmr value) {
                if (value == null) return "";
                switch (value) {
                    case OFF: return Res.get("settings.net.useTorForXmrOffRadio");
                    case ON: return Res.get("settings.net.useTorForXmrOnRadio");
                    default: return Res.get("settings.net.useTorForXmrAfterSyncRadio");
                }
            }

            @Override
            public Preferences.UseTorForXmr fromString(String string) {
                return null;
            }
        });

        Label torLabel = new AutoTooltipLabel(Res.get("startupWizard.node.tor"));
        torLabel.getStyleClass().add("startup-wizard-footer-label");
        HBox torRow = new HBox(10, torLabel, torCombo);
        torRow.setAlignment(Pos.CENTER);
        if (torSetByOptions) {
            torRow.setVisible(false);
            torRow.setManaged(false);
        }

        statusLabel.setMinHeight(24);
        statusLabel.setAlignment(Pos.CENTER);

        // anchor the settings hint at the bottom of the page slot so the card does not end in dead space
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Label info = new AutoTooltipLabel(Res.get("startupWizard.node.info"));
        info.getStyleClass().add("startup-wizard-footer-label");
        info.setWrapText(true);
        info.setMaxWidth(544);
        info.setAlignment(Pos.CENTER);
        info.setTextAlignment(TextAlignment.CENTER);

        content = new VBox(12,
                StartupWizard.createHeaderSection(MaterialDesignIcon.SERVER_NETWORK,
                        Res.get("startupWizard.node.headline"),
                        Res.get("startupWizard.node.subtitle")),
                cardBox,
                customBox,
                torRow,
                statusLabel,
                spacer,
                info);
        content.setAlignment(Pos.TOP_CENTER);
        VBox.setMargin(cardBox, new Insets(8, 0, 0, 0));
        VBox.setMargin(torRow, new Insets(6, 0, 0, 0));
    }

    @Override
    public Region getContent() {
        return content;
    }

    @Override
    public boolean isSkipped() {
        return quickStart.getAsBoolean() || nodesSetByOptions;
    }

    @Override
    public void validate(Consumer<Boolean> resultHandler) {
        clearError();
        if (customCard.isSelected() && !isNodeListValid()) {
            showError();
            resultHandler.accept(false);
            return;
        }
        resultHandler.accept(true);
    }

    @Override
    public String getNextButtonText() {
        return Res.get("startupWizard.next");
    }

    @Override
    public ObservableBooleanValue nextBlocked() {
        return nextBlocked;
    }

    /** The comma separated custom nodes to connect to exclusively, or null to use the default nodes. */
    @Nullable
    public String getCustomNodes() {
        if (isSkipped() || !customCard.isSelected()) return null;
        String nodes = nodesField.getText().trim();
        return nodes.isEmpty() ? null : nodes;
    }

    /** When to route Monero traffic over Tor, or null to leave the preference unchanged. */
    @Nullable
    public Preferences.UseTorForXmr getUseTorForXmr() {
        return isSkipped() || torSetByOptions ? null : torCombo.getSelectionModel().getSelectedItem();
    }

    // valid when every comma separated entry parses as [scheme://]host[:port], matching how they are read
    private boolean isNodeListValid() {
        Set<String> entries = Utilities.commaSeparatedListToSet(nodesField.getText(), false);
        if (entries.isEmpty()) return false;
        try {
            XmrNodes.toCustomXmrNodesList(entries);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void showError() {
        if (!statusLabel.getStyleClass().contains("error-text")) statusLabel.getStyleClass().add("error-text");
        statusLabel.setText(Res.get("startupWizard.node.error.nodes"));
        if (!nodesField.getStyleClass().contains("validation-error")) nodesField.getStyleClass().add("validation-error");
    }

    private void clearError() {
        statusLabel.getStyleClass().remove("error-text");
        statusLabel.setText("");
        nodesField.getStyleClass().remove("validation-error");
    }

    // block advancing while custom nodes are selected but do not parse
    private void updateNextBlocked() {
        nextBlocked.set(customCard.isSelected() && !isNodeListValid());
    }
}
