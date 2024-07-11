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

package haveno.desktop.components;

import com.jfoenix.controls.JFXTextField;
import com.jfoenix.skins.JFXTextFieldSkin;
import javafx.scene.control.Skin;
import javafx.scene.control.TextField;

public class AutoTooltipTextField extends JFXTextField {

    public AutoTooltipTextField() {
        super();
    }

    public AutoTooltipTextField(String text) {
        super(text);
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new AutoTooltipTextFieldSkin(this);
    }

    private class AutoTooltipTextFieldSkin extends JFXTextFieldSkin {
        public AutoTooltipTextFieldSkin(TextField textField) {
            super(textField);
        }
    }
}
