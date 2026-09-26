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

import haveno.desktop.util.Accessibility;
import javafx.scene.shape.SVGPath;

public class ChatIcon extends SVGPath {
    public ChatIcon() {
        setContent("M4 1 H15 A3 3 0 0 1 18 4 V10 A3 3 0 0 1 15 13 H7 L2 17 V12.2 " +
                "A3 3 0 0 1 1 10 V4 A3 3 0 0 1 4 1 Z");
        getStyleClass().add("chat-icon");
        Accessibility.mute(this);
        setMouseTransparent(true);
    }
}
