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
import haveno.desktop.components.ExternalHyperlink;
import haveno.desktop.components.HyperlinkWithIcon;
import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.util.FormBuilder;
import haveno.desktop.util.GUIUtil;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javax.annotation.Nullable;

/**
 * Base for hero-style info overlays: an accent-colored header with icon and
 * divider, then icon-chip feature rows separated by thin lines.
 */
public abstract class HeroInfoWindow<T extends HeroInfoWindow<T>> extends Overlay<T> {

    protected static final String MATRIX_URL = "https://matrix.to/#/#haveno:monero.social";

    // accent style classes; the window accent is set via addContent, per-row accents via createFeatureRow
    protected static final String ACCENT_PRIMARY = "hero-accent-primary";
    protected static final String ACCENT_GREEN = "hero-accent-green";
    protected static final String ACCENT_ORANGE = "hero-accent-orange";
    protected static final String ACCENT_PURPLE = "hero-accent-purple";
    protected static final String ACCENT_AMBER = "hero-accent-amber";

    // wrap width of row texts: window width minus window padding, row padding, icon box and gap
    private static final double WINDOW_HORIZONTAL_PADDING = 128;
    private static final double FEATURE_HORIZONTAL_PADDING = 48;
    private static final double FEATURE_ICON_BOX_WIDTH = 44;
    private static final double FEATURE_TEXT_GAP = 18;

    protected HeroInfoWindow() {
        type = Type.Attention;
    }

    @Override
    protected void onShow() {
        display();
    }

    // the lone close button is the primary action; windows using an action button hide it
    @Override
    protected void addButtons() {
        super.addButtons();
        if (closeButton != null) {
            closeButton.getStyleClass().remove("compact-button");
            closeButton.getStyleClass().add("action-button");
        }
    }

    protected void addContent(String accentClass, Node... children) {
        VBox content = new VBox(10, children);
        content.getStyleClass().add(accentClass);
        GridPane.setHalignment(content, HPos.LEFT);
        GridPane.setHgrow(content, Priority.ALWAYS);
        GridPane.setMargin(content, new Insets(10, 0, 0, 0));
        GridPane.setRowIndex(content, ++rowIndex);
        GridPane.setColumnSpan(content, 2);
        gridPane.getChildren().add(content);
    }

    protected VBox createHeader(MaterialDesignIcon icon, String titleText, String subtitleText) {
        StackPane iconBox = new StackPane(createIcon(icon, "1.9em", "hero-header-icon"));
        iconBox.getStyleClass().add("hero-header-icon-box");
        iconBox.setMinWidth(50);
        iconBox.setMaxWidth(50);

        Label title = new Label(titleText);
        title.getStyleClass().add("hero-header-title");
        title.setWrapText(true);

        Label subtitle = new Label(subtitleText);
        subtitle.getStyleClass().add("hero-header-subtitle");
        subtitle.setWrapText(true);

        VBox titleBox = new VBox(3, title, subtitle);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        HBox headerRow = new HBox(14, iconBox, titleBox);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.setFillHeight(false);

        Region divider = new Region();
        divider.getStyleClass().add("hero-header-divider");

        // the accent divider reads heavier than the gray row separators, so give it extra air
        VBox header = new VBox(10, headerRow, divider);
        VBox.setMargin(header, new Insets(0, 0, 6, 0));
        return header;
    }

    protected HBox createFeatureRow(MaterialDesignIcon icon, String accentClass, String titleText, String bodyText) {
        return createFeatureRow(icon, accentClass, titleText, bodyText, null, null);
    }

    protected HBox createFeatureRow(MaterialDesignIcon icon,
                                    String accentClass,
                                    String titleText,
                                    String bodyText,
                                    @Nullable String linkText,
                                    @Nullable String linkUrl) {
        double textWidth = width - WINDOW_HORIZONTAL_PADDING - FEATURE_HORIZONTAL_PADDING
                - FEATURE_ICON_BOX_WIDTH - FEATURE_TEXT_GAP;

        StackPane iconBox = new StackPane(createIcon(icon, "1.65em", "hero-feature-icon"));
        iconBox.getStyleClass().addAll("hero-feature-icon-box", accentClass);

        Label title = new Label(titleText);
        title.getStyleClass().add("hero-feature-title");
        title.setWrapText(true);
        title.setPrefWidth(textWidth);

        Label body = new Label(bodyText);
        body.getStyleClass().add("hero-feature-body");
        body.setWrapText(true);
        body.setPrefWidth(textWidth);

        VBox textBox = new VBox(3, title, body);
        textBox.setMinHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        if (linkText != null) {
            HyperlinkWithIcon link = new ExternalHyperlink(linkText);
            link.setOnAction(e -> GUIUtil.openWebPage(linkUrl));
            textBox.getChildren().add(link);
        }

        HBox row = new HBox(FEATURE_TEXT_GAP, iconBox, textBox);
        row.getStyleClass().add("hero-feature-row");
        row.setAlignment(Pos.TOP_LEFT);
        row.setFillHeight(false);
        row.setMinHeight(Region.USE_PREF_SIZE);
        return row;
    }

    protected Region createSeparator() {
        Region separator = new Region();
        separator.getStyleClass().add("hero-separator");
        return separator;
    }

    protected static Text createIcon(MaterialDesignIcon icon, String size, String styleClass) {
        Text textIcon = FormBuilder.getIcon(icon, size);
        textIcon.getStyleClass().add(styleClass);
        textIcon.setMouseTransparent(true);
        return textIcon;
    }
}
