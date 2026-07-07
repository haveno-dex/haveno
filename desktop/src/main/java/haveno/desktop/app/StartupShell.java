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

import haveno.common.config.BaseCurrencyNetwork;
import haveno.common.config.Config;
import haveno.core.user.Preferences;
import haveno.core.util.FormattingUtils;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.DarkModeToggle;
import haveno.desktop.util.Transitions;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Persistent startup surface shown for the whole pre-app phase. It is the scene root from the
 * first frame and owns the branding chrome (logo, version, theme toggle) once, swapping only its
 * center content between the password prompt and the loading/connection status. Because the shell
 * is never rebuilt or swapped, the logo stays fixed across the whole login → connecting → app flow.
 */
public class StartupShell extends StackPane {

    // logo size, fixed so the branding never shifts across the startup phases
    private static final double LOGO_FIT_WIDTH = 450;

    private final StackPane appLayer = new StackPane();
    private final StackPane overlay = new StackPane();
    private final StackPane contentSlot = new StackPane();
    private final Transitions transitions;

    public StartupShell(Preferences preferences, Transitions transitions) {
        this.transitions = transitions;
        setId("splash");

        ImageView logo = new ImageView();
        logo.setId(Config.baseCurrencyNetwork() == BaseCurrencyNetwork.XMR_MAINNET ? "image-logo-splash" : "image-logo-splash-testnet");
        logo.setFitWidth(LOGO_FIT_WIDTH);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        logo.setCache(true);

        contentSlot.setAlignment(Pos.TOP_CENTER);

        Label versionLabel = new AutoTooltipLabel(FormattingUtils.formatVersion());
        versionLabel.setStyle("-fx-font-size: 0.9em; -fx-text-fill: -bs-color-gray-6;");

        // logo above the swappable content slot, sized to its content so the whole block can be centered
        // (and recenter on resize) rather than hugging the top
        VBox column = new VBox(logo, contentSlot);
        column.setAlignment(Pos.TOP_CENTER);
        column.setMaxHeight(Region.USE_PREF_SIZE);
        VBox.setMargin(contentSlot, new Insets(30, 0, 0, 0));

        // theme toggle so the user can switch theme while logging in / connecting
        DarkModeToggle themeToggle = new DarkModeToggle(preferences);
        themeToggle.setFitHeight(20);

        // version (centered) and theme toggle (right) share one fixed bottom bar so they sit on the same line
        StackPane bottomBar = new StackPane(versionLabel, themeToggle);
        bottomBar.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(versionLabel, Pos.CENTER);
        StackPane.setAlignment(themeToggle, Pos.CENTER_RIGHT);
        StackPane.setMargin(themeToggle, new Insets(0, 12, 0, 0));

        overlay.setId("splash");
        overlay.getChildren().addAll(column, bottomBar);
        // float the block vertically centered so it recenters when the window is resized
        StackPane.setAlignment(column, Pos.CENTER);
        StackPane.setAlignment(bottomBar, Pos.BOTTOM_CENTER);
        StackPane.setMargin(bottomBar, new Insets(0, 0, 12, 0));

        getChildren().addAll(appLayer, overlay);
    }

    /**
     * Swap the center content: the password prompt during login, the sync status while connecting. The first
     * content sizes the slot and is centered with the logo; before a shorter screen replaces it, the slot is
     * locked to the outgoing (taller) height so the logo stays put across the swap.
     */
    public void setContent(Region content) {
        if (!contentSlot.getChildren().isEmpty() && contentSlot.getHeight() > 0) {
            contentSlot.setMinHeight(contentSlot.getHeight());
        }
        contentSlot.getChildren().setAll(content);
    }

    /** Place the main app UI behind the overlay, ready to be revealed when startup completes. */
    public void setAppContent(Node app) {
        appLayer.getChildren().setAll(app);
    }

    /** Fade the branding overlay out to reveal the app, then run the given cleanup. */
    public void fadeOutOverlay(Runnable onFinished) {
        transitions.fadeOutAndRemove(overlay, 1500, e -> {
            if (onFinished != null) onFinished.run();
        });
    }
}
