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

package haveno.desktop.main.overlays.windows;

import haveno.desktop.main.overlays.Overlay;
import haveno.desktop.util.GUIUtil;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import static com.google.common.base.Preconditions.checkNotNull;
import static haveno.desktop.util.FormBuilder.addMultilineLabel;
import static haveno.desktop.util.FormBuilder.addTextArea;

public class GenericMessageWindow extends Overlay<GenericMessageWindow> {
    private String preamble;
    private static final double MAX_TEXT_AREA_HEIGHT = 400;

    public GenericMessageWindow() {
        super();
    }

    public void show() {
        createGridPane();
        addHeadLine();
        addContent();
        addButtons();
        applyStyles();
        display();
    }

    public GenericMessageWindow preamble(String preamble) {
        this.preamble = preamble;
        return this;
    }

    private void addContent() {
        if (preamble != null) {
            Label label = addMultilineLabel(gridPane, ++rowIndex, preamble, 0, width);
            GridPane.setColumnSpan(label, 2);
            GridPane.setMargin(label, new Insets(10, 0, 0, 0));
        }
        checkNotNull(message, "message must not be null");
        TextArea textArea = addTextArea(gridPane, ++rowIndex, "");
        GridPane.setColumnSpan(textArea, 2);
        GridPane.setHalignment(textArea, HPos.LEFT);
        GridPane.setHgrow(textArea, Priority.ALWAYS);
        GridPane.setMargin(textArea, new Insets(15, 0, 0, 0));
        textArea.getStyleClass().add("flat-text-area-with-border");
        textArea.setText(message);
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setPrefWidth(width);
        GUIUtil.adjustHeightAutomatically(textArea, MAX_TEXT_AREA_HEIGHT);
    }
}
