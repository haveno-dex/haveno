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
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.TextFlow;

public class ShutdownConfirmationWindow extends ConfirmationWindow<ShutdownConfirmationWindow> {
    private final long offerCount;

    public ShutdownConfirmationWindow(long offerCount) {
        super(500);
        this.offerCount = offerCount;
        headLine = Res.get("popup.shutdown.title");
        actionButtonText = Res.get("shared.shutDown");
        closeButtonText = Res.get("popup.shutdown.keepRunning");
    }

    @Override
    protected void addContent() {
        VBox header = hero(MaterialDesignIcon.POWER, "confirm-send-icon", headLine, (Node) null, null);
        header.getStyleClass().add("hero-accent-amber");
        String count = Res.get(offerCount == 1 ? "popup.shutdown.count.one" : "popup.shutdown.count", offerCount);
        // a translation without the placeholder drops the count rather than failing the shutdown prompt
        String[] sentence = Res.get("popup.shutdown.subtitle", "{count}").split("\\{count}", -1);
        Text before = new Text(sentence[0]);
        Text value = new Text(count);
        Text after = new Text(sentence.length > 1 ? sentence[1] : "");
        before.getStyleClass().add("review-subtitle-text");
        after.getStyleClass().add("review-subtitle-text");
        value.getStyleClass().add("review-count");
        TextFlow subtitle = new TextFlow(before, value, after);
        subtitle.setTextAlignment(TextAlignment.CENTER);
        header.getChildren().add(subtitle);
        VBox.setMargin(subtitle, new Insets(6, 0, 0, 0));
        VBox rows = HeroWindowComponents.rows(
                HeroWindowComponents.featureRow(MaterialDesignIcon.AUTORENEW, "hero-accent-primary",
                        Res.get("popup.shutdown.return.title"), Res.get("popup.shutdown.return.body")),
                HeroWindowComponents.featureRow(MaterialDesignIcon.LAPTOP, "hero-accent-amber",
                        Res.get("popup.shutdown.online.title"), Res.get("popup.shutdown.online.body")));
        rows.getStyleClass().add("review-compact-rows");
        addReview(header, new VBox(divider(), rows, divider()));
    }
}
