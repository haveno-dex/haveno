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

import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.core.locale.Res;
import haveno.desktop.components.AutoTooltipLabel;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Wizard step to choose between starting right away with sensible defaults and a custom
 * setup; a quick start skips the remaining optional steps and completes the wizard.
 */
public class StartupWizardModeStep implements StartupWizard.Step {

    private static final double CARD_GAP = 20;

    private final VBox content;
    private final StartupWizard.ChoiceCard quickCard, customCard;
    private Runnable onSelectionChanged = () -> {
    };

    public StartupWizardModeStep() {
        quickCard = new StartupWizard.ChoiceCard(MaterialDesignIcon.ROCKET,
                Res.get("startupWizard.mode.quick.title"),
                Res.get("startupWizard.mode.quick.badge"),
                Res.get("startupWizard.mode.quick.body"));
        customCard = new StartupWizard.ChoiceCard(MaterialDesignIcon.TUNE,
                Res.get("startupWizard.mode.custom.title"),
                null,
                Res.get("startupWizard.mode.custom.body"));
        StartupWizard.ChoiceCard.group(() -> onSelectionChanged.run(), quickCard, customCard);
        quickCard.setSelected(true);

        double cardWidth = (StartupWizard.PAGE_WIDTH - CARD_GAP) / 2;
        for (StartupWizard.ChoiceCard card : new StartupWizard.ChoiceCard[]{quickCard, customCard}) {
            card.setPrefWidth(cardWidth);
            card.setMaxWidth(cardWidth);
            HBox.setHgrow(card, Priority.ALWAYS);
        }

        HBox cardBox = new HBox(CARD_GAP, quickCard, customCard);
        cardBox.setAlignment(Pos.CENTER);

        Label info = new AutoTooltipLabel(Res.get("startupWizard.mode.info"));
        info.getStyleClass().add("startup-wizard-footer-label");
        info.setWrapText(true);

        content = new VBox(12,
                StartupWizard.createHeaderSection(MaterialDesignIcon.PLAY_CIRCLE_OUTLINE,
                        Res.get("startupWizard.mode.headline"),
                        Res.get("startupWizard.mode.subtitle")),
                cardBox,
                info);
        content.setAlignment(Pos.TOP_CENTER);
        VBox.setMargin(cardBox, new Insets(30, 0, 0, 0));
        VBox.setMargin(info, new Insets(6, 0, 0, 0));
    }

    @Override
    public Region getContent() {
        return content;
    }

    @Override
    public void validate(Consumer<Boolean> resultHandler) {
        resultHandler.accept(true);
    }

    @Override
    public String getNextButtonText() {
        return Res.get("startupWizard.next");
    }

    /** True to start right away with defaults, skipping the remaining setup steps. */
    public boolean isQuickStart() {
        return quickCard.isSelected();
    }

    /** Register the host's handler for selection changes (to refresh navigation). */
    public void setOnSelectionChanged(Runnable onSelectionChanged) {
        this.onSelectionChanged = onSelectionChanged;
    }
}
