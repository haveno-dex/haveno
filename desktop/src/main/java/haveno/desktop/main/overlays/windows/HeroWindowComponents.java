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
import haveno.desktop.util.FormBuilder;
import haveno.desktop.util.GUIUtil;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.monetary.Volume;
import haveno.core.util.FormattingUtils;
import haveno.core.util.VolumeUtil;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.TextFlow;
import java.text.DateFormat;
import java.util.Date;

/** Shared icon rows and amount treatments for hero windows. */
final class HeroWindowComponents {
    private HeroWindowComponents() {
    }

    static Text icon(MaterialDesignIcon icon, String size, String styleClass) {
        Text text = FormBuilder.getIcon(icon, size);
        text.getStyleClass().add(styleClass);
        text.setMouseTransparent(true);
        return text;
    }

    static HBox featureRow(Node icon, String accent, String titleText, String bodyText,
                           String linkText, String linkUrl, double textWidth) {
        StackPane badge = new StackPane(icon);
        badge.getStyleClass().addAll("hero-feature-icon-box", accent);
        Label title = label(titleText, "hero-feature-title");
        title.setPrefWidth(textWidth);
        VBox text = new VBox(3, title);
        text.setMinWidth(0);
        text.setMinHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(text, Priority.ALWAYS);
        if (bodyText != null && !bodyText.isBlank()) {
            Label body = label(bodyText, "hero-feature-body");
            body.setPrefWidth(textWidth);
            text.getChildren().add(body);
        }
        if (linkText != null) {
            HyperlinkWithIcon link = new ExternalHyperlink(linkText);
            link.setOnAction(e -> GUIUtil.openWebPage(linkUrl));
            text.getChildren().add(link);
        }
        HBox row = new HBox(18, badge, text);
        row.getStyleClass().add("hero-feature-row");
        row.setAlignment(Pos.TOP_LEFT);
        row.setFillHeight(false);
        row.setMinHeight(Region.USE_PREF_SIZE);
        return row;
    }

    static HBox featureRow(MaterialDesignIcon icon, String accent, String title, String body) {
        return featureRow(icon(icon, "21", "hero-feature-icon"), accent, title, body, null, null, 1000);
    }

    static VBox rowText(HBox row) {
        return (VBox) row.getChildren().get(1);
    }

    static Label label(String text, String style) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.getStyleClass().add(style);
        return label;
    }

    static String dateTime(Date date) {
        return FormattingUtils.formatDateTime(date,
                DateFormat.getDateInstance(DateFormat.MEDIUM, GlobalSettings.getLocale()),
                DateFormat.getTimeInstance(DateFormat.SHORT, GlobalSettings.getLocale()));
    }

    static String volume(Volume volume) {
        if (volume == null) return Res.get("shared.na");
        return CurrencyUtil.isVolumeRoundedToNearestUnit(volume.getCurrencyCode()) ?
                VolumeUtil.formatAverageVolumeWithCode(volume).replace(" " + volume.getCurrencyCode(), "") : VolumeUtil.formatVolume(volume);
    }

    static TextFlow amount(String value, String currency, String style, boolean centered) {
        Text number = new Text(value);
        number.getStyleClass().add("review-amount-number");
        Text code = new Text(" " + currency);
        code.getStyleClass().add("review-amount-currency");
        TextFlow flow = new TextFlow(number, code);
        flow.getStyleClass().add(style);
        flow.setTextAlignment(centered ? TextAlignment.CENTER : TextAlignment.LEFT);
        return flow;
    }

    static HBox copyValue(String value, boolean mono) {
        Label text = label(value, mono ? "review-mono" : "review-name");
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox box = new HBox(10, text, TxHeroWindow.copyIcon(value));
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMaxWidth(Region.USE_PREF_SIZE);
        box.getStyleClass().add("review-copy-value");
        return box;
    }

    static VBox rows(Node... rows) {
        VBox box = new VBox();
        for (Node row : rows) {
            if (!box.getChildren().isEmpty()) {
                Region line = new Region();
                line.getStyleClass().add("hero-separator");
                box.getChildren().add(line);
            }
            box.getChildren().add(row);
        }
        return box;
    }
}
