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

import haveno.core.locale.LanguageUtil;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Instruction layout with the header, summary and buttons outside the scrolling rows. */
public abstract class ReviewInfoWindow<T extends ReviewInfoWindow<T>> extends HeroInfoWindow<T> {
    protected ReviewInfoWindow() {
        width = 728;
    }

    @Override
    protected void createContent(boolean showAgainChecked) {
        rowIndex = -1;
        createGridPane();
        gridPane.setPadding(new Insets(76, 78, 74, 78));
        gridPane.setHgap(0); // spanning rows are measured without the gap but laid out with it
        gridPane.setVgap(0);
        gridPane.getStyleClass().add("review-popup");
        if (LanguageUtil.isDefaultLanguageRTL()) gridPane.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        addMessage();
        addButtons();
        applyStyles();
    }

    protected void addReview(String accent, VBox header, Node summary, VBox rows) {
        gridPane.getStyleClass().add(accent);
        header.setSpacing(14);
        VBox.setMargin(header, Insets.EMPTY);
        gridPane.add(header, 0, ++rowIndex, 2, 1);
        gridPane.add(summary, 0, ++rowIndex, 2, 1);
        GridPane.setMargin(summary, new Insets(14, 0, 4, 0));
        gridPane.add(createPinnedScrollPane(rows), 0, ++rowIndex, 2, 1);
    }

    @Override
    protected void setupKeyHandler(Scene scene) {
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                doClose();
            }
        });
    }

    @Override
    protected void addButtons() {
        super.addButtons();
        HBox buttons = buttonBox;
        buttons.getStyleClass().add("review-info-buttons");
        GridPane.setMargin(buttons, new Insets(18, 0, 0, 0));
        if (actionButton != null && closeButton != null) {
            buttons.getChildren().remove(closeButton);
            buttons.getChildren().add(buttons.getChildren().indexOf(actionButton), closeButton);
        }
    }
}
