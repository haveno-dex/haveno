/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.desktop.util;

import de.jensd.fx.glyphs.GlyphIcons;
import de.jensd.fx.glyphs.GlyphsFactory;
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeItem;
import javafx.scene.text.Text;

/**
 * Compatibility wrapper for legacy {@code de.jensd.fx.glyphs.GlyphsDude} API on FontAwesomeFX 9.x.
 */
public final class GlyphsDude {
    private static final String FONT_AWESOME_TTF = "/de/jensd/fx/glyphs/fontawesome/fontawesome-webfont.ttf";
    private static final String MATERIAL_DESIGN_TTF =
            "/de/jensd/fx/glyphs/materialdesignicons/materialdesignicons-webfont.ttf";

    private static final GlyphsFactory FONT_AWESOME_FACTORY = new GlyphsFactory(FONT_AWESOME_TTF);
    private static final GlyphsFactory MATERIAL_FACTORY = new GlyphsFactory(MATERIAL_DESIGN_TTF);

    private GlyphsDude() {
    }

    public static Text createIcon(GlyphIcons icon) {
        return factoryFor(icon).createIcon(icon);
    }

    public static Text createIcon(GlyphIcons icon, String size) {
        return factoryFor(icon).createIcon(icon, size);
    }

    public static void setIcon(Labeled labeled, GlyphIcons icon) {
        setLabelGlyph(labeled, icon, "16.0"); // AwesomeDude's default icon size
    }

    public static void setIcon(Labeled labeled, GlyphIcons icon, String size) {
        setLabelGlyph(labeled, icon, size);
    }

    public static void setLabelGlyph(Labeled labeled, GlyphIcons icon, String size) {
        labeled.setText(icon.unicode());
        labeled.setStyle("-fx-font-family: '" + icon.fontFamily() + "'; -fx-font-size: " + size + ";");
    }

    public static void setIcon(Labeled labeled, GlyphIcons icon, ContentDisplay contentDisplay) {
        factoryFor(icon).setIcon(labeled, icon, contentDisplay);
    }

    public static void setIcon(Labeled labeled, GlyphIcons icon, String size, ContentDisplay contentDisplay) {
        factoryFor(icon).setIcon(labeled, icon, size, contentDisplay);
    }

    public static void setIcon(MenuItem menuItem, GlyphIcons icon) {
        factoryFor(icon).setIcon(menuItem, icon);
    }

    public static void setIcon(MenuItem menuItem, GlyphIcons icon, String size) {
        factoryFor(icon).setIcon(menuItem, icon, size);
    }

    public static void setIcon(Tab tab, GlyphIcons icon) {
        factoryFor(icon).setIcon(tab, icon);
    }

    public static void setIcon(Tab tab, GlyphIcons icon, String size) {
        factoryFor(icon).setIcon(tab, icon, size);
    }

    public static void setIcon(TreeItem<?> treeItem, GlyphIcons icon) {
        factoryFor(icon).setIcon(treeItem, icon);
    }

    public static void setIcon(TreeItem<?> treeItem, GlyphIcons icon, String size) {
        factoryFor(icon).setIcon(treeItem, icon, size);
    }

    public static Button createIconButton(GlyphIcons icon) {
        return factoryFor(icon).createIconButton(icon);
    }

    public static Button createIconButton(GlyphIcons icon, String text) {
        return factoryFor(icon).createIconButton(icon, text);
    }

    public static Button createIconButton(GlyphIcons icon, String text, String iconSize,
                                          String styleClass, ContentDisplay contentDisplay) {
        return factoryFor(icon).createIconButton(icon, text, iconSize, styleClass, contentDisplay);
    }

    public static ToggleButton createIconToggleButton(GlyphIcons icon) {
        return factoryFor(icon).createIconToggleButton(icon);
    }

    public static ToggleButton createIconToggleButton(GlyphIcons icon, String text) {
        return factoryFor(icon).createIconToggleButton(icon, text);
    }

    public static ToggleButton createIconToggleButton(GlyphIcons icon, String text, String iconSize,
                                                      ContentDisplay contentDisplay) {
        Label iconLabel = new Label();
        setLabelGlyph(iconLabel, icon, iconSize);
        ToggleButton button = new ToggleButton(text);
        button.setGraphic(iconLabel);
        if (contentDisplay != null) {
            button.setContentDisplay(contentDisplay);
        }
        return button;
    }

    private static GlyphsFactory factoryFor(GlyphIcons icon) {
        if (icon instanceof FontAwesomeIcon) {
            return FONT_AWESOME_FACTORY;
        }
        if (icon instanceof MaterialDesignIcon) {
            return MATERIAL_FACTORY;
        }
        throw new IllegalArgumentException("Unsupported icon type: " + icon.getClass().getName());
    }
}
