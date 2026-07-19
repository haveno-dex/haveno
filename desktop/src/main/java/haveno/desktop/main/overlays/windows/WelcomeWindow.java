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
import haveno.common.config.BaseCurrencyNetwork;
import haveno.common.config.Config;
import haveno.core.locale.Res;
import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

/**
 * First-run welcome window: a headline with icon, feature highlights separated
 * by dividers, and an optional test-funds warning.
 */
public class WelcomeWindow extends HeroInfoWindow<WelcomeWindow> {

    @Override
    public void show() {
        if (closeButtonText == null) closeButtonText = Res.get("welcomeWindow.gotIt");
        super.show();
    }

    @Override
    protected void addMessage() {
        boolean isTestInstance = Config.baseCurrencyNetwork() == BaseCurrencyNetwork.XMR_STAGENET;
        String prefix = isTestInstance ? "welcomeWindow.stagenet" : "welcomeWindow.mainnet";

        List<Node> children = new ArrayList<>();
        children.add(createHeader(MaterialDesignIcon.HUMAN_GREETING,
                Res.get(prefix + ".headline"),
                Res.get(prefix + ".subtitle")));
        children.add(createFeatureRow(isTestInstance ? MaterialDesignIcon.FLASK_OUTLINE : MaterialDesignIcon.SWAP_HORIZONTAL,
                ACCENT_ORANGE,
                Res.get(prefix + ".trade.title"),
                Res.get(prefix + ".trade.body")));
        children.add(createSeparator());
        children.add(createFeatureRow(MaterialDesignIcon.ROCKET,
                ACCENT_GREEN,
                Res.get(prefix + ".start.title"),
                Res.get(prefix + ".start.body")));
        children.add(createSeparator());
        children.add(createFeatureRow(MaterialDesignIcon.FORUM,
                ACCENT_PURPLE,
                Res.get("welcomeWindow.support.title"),
                Res.get("welcomeWindow.support.body"),
                Res.get("welcomeWindow.support.link"), MATRIX_URL));

        if (isTestInstance) {
            HBox callout = createWarningCallout(Res.get("welcomeWindow.stagenet.warning"));
            VBox.setMargin(callout, new Insets(8, 0, 0, 0));
            children.add(callout);
        }

        addContent(ACCENT_PRIMARY, children.toArray(new Node[0]));
    }

    private HBox createWarningCallout(String text) {
        Text icon = createIcon(MaterialDesignIcon.ALERT, "1.4em", "welcome-warning-icon");

        Label label = new Label(text);
        label.getStyleClass().add("welcome-warning-text");
        label.setWrapText(true);
        HBox.setHgrow(label, Priority.ALWAYS);

        HBox callout = new HBox(12, icon, label);
        callout.getStyleClass().add("welcome-warning-callout");
        callout.setAlignment(Pos.CENTER_LEFT);
        return callout;
    }
}
