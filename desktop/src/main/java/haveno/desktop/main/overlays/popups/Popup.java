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

package haveno.desktop.main.overlays.popups;

import haveno.desktop.main.overlays.Overlay;
import javafx.scene.control.Button;
import javafx.scene.layout.Region;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Popup extends Overlay<Popup> {
    private static final double NESTED_WIDTH = 700; // narrower than standard popups so the dimmed parent frames it
    protected final Logger log = LoggerFactory.getLogger(this.getClass());
    private boolean nested;

    public Popup() {
    }

    // for prompts opened from within the displayed popup
    public Popup nested() {
        this.nested = true;
        this.width = NESTED_WIDTH;
        return this;
    }

    @Override
    protected void addButtons() {
        super.addButtons();
        // widen the narrow prompt for long button labels instead of truncating them
        if (nested) {
            for (Button button : new Button[]{actionButton, secondaryActionButton, closeButton})
                if (button != null) button.setMinWidth(Region.USE_PREF_SIZE);
        }
    }

    @Override
    protected void setupInitialFocus() {
        super.setupInitialFocus();
        if (messageTextArea == null) {
            if (actionButton != null && actionButton.isDefaultButton())
                actionButton.requestFocus();
            else if (closeButton != null && closeButton.isDefaultButton())
                closeButton.requestFocus();
        }
    }

    @Override
    protected void onShow() {
        if (nested) PopupManager.displayNested(this);
        else PopupManager.queueForDisplay(this);
    }

    @Override
    protected void onHidden() {
        PopupManager.onHidden(this);
    }
}
