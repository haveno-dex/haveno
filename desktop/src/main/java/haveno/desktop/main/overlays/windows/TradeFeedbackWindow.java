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
 * Shown after a first successful trade: a success header with icon, then rows
 * inviting feedback and pointing to community support.
 */
public class TradeFeedbackWindow extends HeroInfoWindow<TradeFeedbackWindow> {

    @Override
    public void show() {
        hideCloseButton();
        if (actionButtonText == null) actionButtonText = Res.get("tradeFeedbackWindow.done");
        super.show();
    }

    @Override
    protected void addMessage() {
        addContent(ACCENT_GREEN,
                createHeader(MaterialDesignIcon.CHECK_CIRCLE,
                        Res.get("tradeFeedbackWindow.title"),
                        Res.get("tradeFeedbackWindow.subtitle")),
                createFeatureRow(MaterialDesignIcon.STAR,
                        ACCENT_AMBER,
                        Res.get("tradeFeedbackWindow.feedback.title"),
                        Res.get("tradeFeedbackWindow.feedback.body")),
                createSeparator(),
                createFeatureRow(MaterialDesignIcon.FORUM,
                        ACCENT_PURPLE,
                        Res.get("tradeFeedbackWindow.support.title"),
                        Res.get("tradeFeedbackWindow.support.body"),
                        Res.get("tradeFeedbackWindow.support.link"), MATRIX_URL));
    }
}
