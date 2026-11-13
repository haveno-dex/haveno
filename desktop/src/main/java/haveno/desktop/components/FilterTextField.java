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

package haveno.desktop.components;

import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.desktop.util.Accessibility;
import haveno.desktop.util.FormBuilder;
import javafx.scene.control.Skin;
import javafx.scene.text.Text;

/**
 * Pill shaped text field for filtering a list, with a leading search icon.
 */
public class FilterTextField extends InputTextField {

    private static final double ICON_GAP = 6; // space between the icon and the text

    private final Text icon = FormBuilder.getIcon(MaterialDesignIcon.MAGNIFY, "1.231em");

    public FilterTextField() {
        getStyleClass().add("filter-pill");
        icon.getStyleClass().add("filter-icon");
        icon.setManaged(false);
        icon.setMouseTransparent(true); // clicks reach the field beneath
        Accessibility.mute(icon);
    }

    // the skin looks up its own nodes by position, so the icon joins after them
    @Override
    protected Skin<?> createDefaultSkin() {
        Skin<?> skin = super.createDefaultSkin();
        if (icon.getParent() == null) getChildren().add(icon);
        return skin;
    }

    // place the icon at the end of the left padding the stylesheet reserves for it
    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        icon.relocate(snapPositionX(snappedLeftInset() - ICON_GAP - icon.getLayoutBounds().getWidth()),
                snapPositionY((getHeight() - icon.getLayoutBounds().getHeight()) / 2));
    }
}
