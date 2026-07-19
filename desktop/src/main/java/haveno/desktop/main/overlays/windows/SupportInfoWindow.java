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

/**
 * First-visit support window: a header with icon, then rows explaining how
 * disputes are resolved and where to find community help.
 */
public class SupportInfoWindow extends HeroInfoWindow<SupportInfoWindow> {

    @Override
    public void show() {
        if (closeButtonText == null) closeButtonText = Res.get("supportInfoWindow.gotIt");
        super.show();
    }

    @Override
    protected void addMessage() {
        addContent(ACCENT_PRIMARY,
                createHeader(MaterialDesignIcon.SHIELD_HALF_FULL,
                        Res.get("supportInfoWindow.title"),
                        Res.get("supportInfoWindow.subtitle")),
                createFeatureRow(MaterialDesignIcon.FORUM,
                        ACCENT_PURPLE,
                        Res.get("supportInfoWindow.chat.title"),
                        Res.get("supportInfoWindow.chat.body")),
                createSeparator(),
                createFeatureRow(MaterialDesignIcon.SCALE_BALANCE,
                        ACCENT_GREEN,
                        Res.get("supportInfoWindow.arbitration.title"),
                        Res.get("supportInfoWindow.arbitration.body")),
                createSeparator(),
                createFeatureRow(MaterialDesignIcon.ACCOUNT_MULTIPLE,
                        ACCENT_ORANGE,
                        Res.get("supportInfoWindow.community.title"),
                        Res.get("supportInfoWindow.community.body"),
                        Res.get("supportInfoWindow.community.link"), MATRIX_URL));
    }
}
