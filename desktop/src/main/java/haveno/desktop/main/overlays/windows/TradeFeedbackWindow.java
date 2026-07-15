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
import haveno.desktop.components.ExternalHyperlink;
import haveno.desktop.components.HyperlinkWithIcon;
import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.util.FormBuilder;
import haveno.desktop.util.GUIUtil;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
 * Shown after a first successful trade: a success header with icon, then rows
 * inviting feedback and pointing to community support.
 */
public class TradeFeedbackWindow extends Overlay<TradeFeedbackWindow> {

    private static final String MATRIX_URL = "https://matrix.to/#/#haveno:monero.social";

    // wrap width of row texts: window width minus window padding, row padding, icon box and gap
    private static final double WINDOW_HORIZONTAL_PADDING = 128;
    private static final double FEATURE_HORIZONTAL_PADDING = 48;
    private static final double FEATURE_ICON_BOX_WIDTH = 44;
    private static final double FEATURE_TEXT_GAP = 18;

    public TradeFeedbackWindow() {
        type = Type.Attention;
    }

    @Override
    public void show() {
        hideCloseButton();
        if (actionButtonText == null) actionButtonText = Res.get("tradeFeedbackWindow.done");
        super.show();
    }

    @Override
    protected void onShow() {
        display();
    }

    @Override
    protected void addMessage() {
        VBox header = createHeader(Res.get("tradeFeedbackWindow.title"), Res.get("tradeFeedbackWindow.subtitle"));
        VBox.setMargin(header, new Insets(0, 0, 6, 0));

        VBox content = new VBox(10);
        content.getChildren().addAll(
                header,
                createFeatureRow(MaterialDesignIcon.STAR,
                        "trade-feedback-accent-feedback",
                        Res.get("tradeFeedbackWindow.feedback.title"),
                        Res.get("tradeFeedbackWindow.feedback.body"),
                        null, null),
                createSeparator(),
                createFeatureRow(MaterialDesignIcon.FORUM,
                        "trade-feedback-accent-support",
                        Res.get("tradeFeedbackWindow.support.title"),
                        Res.get("tradeFeedbackWindow.support.body"),
                        Res.get("tradeFeedbackWindow.support.link"), MATRIX_URL));

        GridPane.setHalignment(content, HPos.LEFT);
        GridPane.setHgrow(content, Priority.ALWAYS);
        GridPane.setMargin(content, new Insets(10, 0, 0, 0));
        GridPane.setRowIndex(content, ++rowIndex);
        GridPane.setColumnSpan(content, 2);
        gridPane.getChildren().add(content);
    }

    private VBox createHeader(String titleText, String subtitleText) {
        StackPane iconBox = new StackPane(createIcon(MaterialDesignIcon.CHECK_CIRCLE, "1.9em", "trade-feedback-header-icon"));
        iconBox.getStyleClass().add("trade-feedback-header-icon-box");
        iconBox.setMinWidth(50);
        iconBox.setMaxWidth(50);

        Label title = new Label(titleText);
        title.getStyleClass().add("trade-feedback-header-title");
        title.setWrapText(true);

        Label subtitle = new Label(subtitleText);
        subtitle.getStyleClass().add("trade-feedback-header-subtitle");
        subtitle.setWrapText(true);

        VBox titleBox = new VBox(3, title, subtitle);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        HBox header = new HBox(14, iconBox, titleBox);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setFillHeight(false);

        Region divider = new Region();
        divider.getStyleClass().add("trade-feedback-header-divider");

        return new VBox(10, header, divider);
    }

    private HBox createFeatureRow(MaterialDesignIcon icon,
                                  String accentClass,
                                  String titleText,
                                  String bodyText,
                                  @Nullable String linkText,
                                  @Nullable String linkUrl) {
        double textWidth = width - WINDOW_HORIZONTAL_PADDING - FEATURE_HORIZONTAL_PADDING
                - FEATURE_ICON_BOX_WIDTH - FEATURE_TEXT_GAP;

        Text iconText = createIcon(icon, "1.65em", "trade-feedback-feature-icon");
        iconText.getStyleClass().add(accentClass);
        StackPane iconBox = new StackPane(iconText);
        iconBox.getStyleClass().addAll("trade-feedback-feature-icon-box", accentClass);

        Label title = new Label(titleText);
        title.getStyleClass().add("trade-feedback-feature-title");
        title.setWrapText(true);
        title.setPrefWidth(textWidth);

        Label body = new Label(bodyText);
        body.getStyleClass().add("trade-feedback-feature-body");
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
        row.getStyleClass().add("trade-feedback-feature-row");
        row.setAlignment(Pos.TOP_LEFT);
        row.setFillHeight(false);
        row.setMinHeight(Region.USE_PREF_SIZE);
        return row;
    }

    private Region createSeparator() {
        Region separator = new Region();
        separator.getStyleClass().add("trade-feedback-separator");
        return separator;
    }

    private static Text createIcon(MaterialDesignIcon icon, String size, String styleClass) {
        Text textIcon = FormBuilder.getIcon(icon, size);
        textIcon.getStyleClass().add(styleClass);
        textIcon.setMouseTransparent(true);
        return textIcon;
    }
}
