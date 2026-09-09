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

package haveno.desktop.main.portfolio.pendingtrades.steps;

import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon;
import haveno.core.locale.Res;
import haveno.desktop.util.GlyphsDude;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.geometry.Orientation;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class TradeWizardItem extends HBox {
    private static final PseudoClass CURRENT = PseudoClass.getPseudoClass("current");
    private static final PseudoClass COMPLETE = PseudoClass.getPseudoClass("complete");
    private static final PseudoClass WARNING = PseudoClass.getPseudoClass("warning");
    private final String number;
    private final String title;
    private final Label circle;
    private final Label caption = new Label();
    private final Class<? extends TradeStepView> viewClass;
    private String stepCaption = "";
    private String warningCaption;

    public TradeWizardItem(Class<? extends TradeStepView> viewClass, String title, String number) {
        this.viewClass = viewClass;
        this.title = title;
        this.number = number;
        circle = new Label(number);
        circle.setGraphic(GlyphsDude.createIcon(FontAwesomeIcon.CHECK, "14"));
        circle.getStyleClass().add("trade-step-circle");
        Label heading = new Label(title);
        heading.setWrapText(true);
        heading.getStyleClass().add("trade-step-title");
        caption.getStyleClass().add("trade-step-caption");
        caption.setWrapText(true);
        VBox text = new VBox(3, heading, caption);
        text.setMinWidth(0);
        getChildren().addAll(circle, text);
        getStyleClass().add("trade-step");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(10);
        setMinWidth(0);
        setMaxWidth(Double.MAX_VALUE);
        setMouseTransparent(true);
        setDisabled();
    }

    public Class<? extends TradeStepView> getViewClass() {
        return viewClass;
    }

    public void addConnector() {
        Separator connector = new Separator(Orientation.HORIZONTAL);
        connector.setMinWidth(12);
        connector.setPrefWidth(12);
        connector.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(connector, Priority.ALWAYS);
        getChildren().add(connector);
    }

    public void setDisabled() {
        pseudoClassStateChanged(CURRENT, false);
        pseudoClassStateChanged(COMPLETE, false);
        circle.setText(number);
        circle.setContentDisplay(ContentDisplay.TEXT_ONLY);
        warningCaption = null;
        setCaption("");
    }

    public void setActive() {
        pseudoClassStateChanged(CURRENT, true);
        pseudoClassStateChanged(COMPLETE, false);
        circle.setText(number);
        circle.setContentDisplay(ContentDisplay.TEXT_ONLY);
        setCaption(Res.get("portfolio.pending.tradeView.yourTurn"));
    }

    public void setCompleted() {
        pseudoClassStateChanged(CURRENT, false);
        pseudoClassStateChanged(COMPLETE, true);
        circle.setText("✓");
        circle.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        warningCaption = null;
        setCaption(number.equals("4") ? "" : Res.get("portfolio.pending.tradeView.complete"));
    }

    public void setCaption(String value) {
        stepCaption = value;
        updateCaption();
    }

    public void setWarningCaption(String value) {
        warningCaption = value;
        updateCaption();
    }

    private void updateCaption() {
        boolean warning = warningCaption != null && getPseudoClassStates().contains(CURRENT);
        String value = warning ? warningCaption : stepCaption;
        pseudoClassStateChanged(WARNING, warning);
        caption.setText(value);
        caption.setVisible(!value.isEmpty());
        caption.setManaged(!value.isEmpty());
        setAccessibleText(value.isEmpty() ? title : title + ": " + value);
    }
}
