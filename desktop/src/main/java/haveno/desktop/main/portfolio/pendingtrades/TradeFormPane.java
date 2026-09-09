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

package haveno.desktop.main.portfolio.pendingtrades;

import haveno.desktop.components.TextFieldWithCopyIcon;
import haveno.desktop.components.TitledGroupBg;
import haveno.desktop.util.GUIUtil;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class TradeFormPane extends GridPane {
    private final List<Node> fields = new ArrayList<>();
    private final List<Node> fullWidth = new ArrayList<>();
    private boolean singleColumn;

    public TradeFormPane() {
        getStyleClass().add("trade-payment-fields");
        setHgap(28);
        setVgap(12);
        setMinWidth(0);
        ColumnConstraints first = new ColumnConstraints();
        first.setHgrow(Priority.ALWAYS);
        first.setMinWidth(0);
        first.setPercentWidth(50);
        ColumnConstraints second = new ColumnConstraints();
        second.setHgrow(Priority.ALWAYS);
        second.setMinWidth(0);
        second.setPercentWidth(50);
        getColumnConstraints().addAll(first, second);
        widthProperty().addListener((observable, oldValue, newValue) -> {
            boolean narrow = newValue.doubleValue() < 560;
            if (narrow != singleColumn) {
                singleColumn = narrow;
                arrangeFields();
            }
        });
    }

    public void finish(boolean allFullWidth) {
        fields.clear();
        fullWidth.clear();
        fields.addAll(getChildren());
        fields.sort(Comparator.comparingInt((Node node) -> index(getRowIndex(node)))
                .thenComparingInt(node -> index(getColumnIndex(node))));
        for (Node node : fields) {
            setMargin(node, Insets.EMPTY);
            setRowSpan(node, 1);
            setFillWidth(node, true);
            setHgrow(node, Priority.ALWAYS);
            if (node instanceof Region) {
                ((Region) node).setMinWidth(0);
                ((Region) node).setMaxWidth(Double.MAX_VALUE);
            }
            if (node instanceof VBox && !((VBox) node).getChildren().isEmpty() && ((VBox) node).getChildren().get(0) instanceof Label) {
                VBox field = (VBox) node;
                field.setSpacing(4);
                field.getStyleClass().add("trade-payment-field");
                Label label = (Label) field.getChildren().get(0);
                label.getStyleClass().add("trade-field-label");
                label.setWrapText(true);
                for (Node control : field.getChildren()) {
                    if (control instanceof TextArea) {
                        TextArea textArea = (TextArea) control;
                        if (!textArea.isEditable()) {
                            textArea.setMinHeight(0);
                            textArea.setMaxHeight(Double.MAX_VALUE);
                            GUIUtil.adjustHeightAutomatically(textArea, null, false, 8.0);
                            fullWidth.add(field);
                        }
                    } else if (control instanceof TextFieldWithCopyIcon) {
                        ((TextFieldWithCopyIcon) control).setWrapText(true);
                    } else if (control instanceof TextInputControl) {
                        TextInputControl input = (TextInputControl) control;
                        if (!input.isEditable() && input.getTooltip() == null) {
                            Tooltip tooltip = new Tooltip();
                            tooltip.textProperty().bind(input.textProperty());
                            input.setTooltip(tooltip);
                        }
                    }
                }
            } else {
                fullWidth.add(node);
                if (node instanceof TitledGroupBg) node.getStyleClass().add("trade-section-heading");
            }
            Integer span = getColumnSpan(node);
            if (allFullWidth || span != null && span > 1) fullWidth.add(node);
        }
        arrangeFields();
    }

    public String getPaymentDetails() {
        List<String> details = new ArrayList<>();
        for (Node node : fields) {
            if (!(node instanceof VBox) || !node.isVisible()) continue;
            VBox field = (VBox) node;
            if (field.getChildren().isEmpty() || !(field.getChildren().get(0) instanceof Label)) continue;
            String label = ((Label) field.getChildren().get(0)).getText().stripTrailing();
            for (Node control : field.getChildren()) {
                String value = control instanceof TextFieldWithCopyIcon ? ((TextFieldWithCopyIcon) control).getText() :
                        control instanceof TextInputControl ? ((TextInputControl) control).getText() : null;
                if (value != null && !value.isEmpty()) details.add(label + (label.endsWith(":") ? " " : ": ") + value);
            }
        }
        return String.join("\n", details);
    }

    private void arrangeFields() {
        getColumnConstraints().get(0).setPercentWidth(singleColumn ? 100 : 50);
        getColumnConstraints().get(1).setPercentWidth(singleColumn ? 0 : 50);
        long fieldCount = fields.stream()
                .filter(node -> node.isManaged() && node.getStyleClass().contains("trade-payment-field"))
                .count();
        int row = 0;
        int column = 0;
        for (Node node : fields) {
            if (!node.isManaged()) continue;
            boolean wide = singleColumn || fieldCount == 1 || fullWidth.contains(node);
            if (wide && column != 0) {
                row++;
                column = 0;
            }
            setRowIndex(node, row);
            setColumnIndex(node, column);
            setColumnSpan(node, wide ? 2 : 1);
            if (wide || column == 1) {
                row++;
                column = 0;
            } else {
                column = 1;
            }
        }
    }

    private static int index(Integer value) {
        return value == null ? 0 : value;
    }
}
