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

package haveno.desktop.main.market.trades.charts;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.geometry.HPos;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.chart.Axis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.shape.Line;

import java.util.List;

/**
 * A chart of candle data which marks the candle nearest to the cursor with a line and describes it in a
 * tooltip beside it. Charts sharing the hovered candle and the tooltip width track it together.
 */
public abstract class CandleDataChart extends XYChart<Number, Number> {

    private static final double TOOLTIP_GAP = 12;

    private final ObjectProperty<CandleData> hoveredCandle;
    private final DoubleProperty tooltipWidth; // of the widest tooltip since the hover began
    private final GridPane tooltip = new GridPane();
    private final Line candleLine = new Line();

    protected CandleDataChart(Axis<Number> xAxis, Axis<Number> yAxis, ObjectProperty<CandleData> hoveredCandle,
                              DoubleProperty tooltipWidth) {
        super(xAxis, yAxis);
        this.hoveredCandle = hoveredCandle;
        this.tooltipWidth = tooltipWidth;
        tooltip.getStyleClass().add("chart-tooltip-box");
        candleLine.getStyleClass().add("chart-candle-line");
        for (Node overlay : List.of(candleLine, tooltip)) {
            overlay.setManaged(false);
            overlay.setMouseTransparent(true);
            overlay.setVisible(false);
        }
        getPlotChildren().add(candleLine);
        getChartChildren().add(tooltip); // above the plot, which clips its children
        setOnMouseMoved(this::trackCursor);
        setOnMouseExited(e -> hoveredCandle.set(null));
        hoveredCandle.addListener((observable, oldValue, newValue) -> showHoveredCandle(newValue));
        tooltipWidth.addListener((observable, oldValue, newValue) -> layoutOverlays());
    }

    /**
     * Called to update and layout the candles of the plot.
     */
    protected abstract void layoutCandles();

    /**
     * Returns the tooltip entries which describe the given candle, each a name ending with a colon and its value.
     */
    protected abstract List<String> getTooltipEntries(CandleData candleData);

    @Override
    protected final void layoutPlotChildren() {
        layoutCandles();
        layoutOverlays();
    }

    // hover the candle nearest to the cursor anywhere in the plot area
    private void trackCursor(MouseEvent e) {
        Point2D cursor = candleLine.getParent().sceneToLocal(e.getSceneX(), e.getSceneY());
        boolean insidePlotArea = cursor.getX() >= 0 && cursor.getX() <= getXAxis().getWidth() &&
                cursor.getY() >= 0 && cursor.getY() <= getYAxis().getHeight();
        hoveredCandle.set(insidePlotArea ? findNearestCandle(cursor.getX()) : null);
    }

    private CandleData findNearestCandle(double plotX) {
        if (getData() == null) {
            return null;
        }
        CandleData nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (XYChart.Series<Number, Number> series : getData()) {
            for (XYChart.Data<Number, Number> item : series.getData()) {
                if (!(item.getExtraValue() instanceof CandleData)) {
                    continue;
                }
                double distance = Math.abs(getXAxis().getDisplayPosition(item.getXValue()) - plotX);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = (CandleData) item.getExtraValue();
                }
            }
        }
        return nearest;
    }

    private void showHoveredCandle(CandleData candleData) {
        candleLine.setVisible(candleData != null);
        tooltip.setVisible(candleData != null);
        if (candleData == null) {
            tooltipWidth.set(0);
            return;
        }

        // a row for the name and the value of each entry
        tooltip.getChildren().clear();
        for (String entry : getTooltipEntries(candleData)) {
            String[] nameAndValue = entry.split("(?<=[:\uFF1A])\\s*", 2); // Chinese uses the full width colon
            Label name = new Label(nameAndValue.length > 1 ? nameAndValue[0] : "");
            name.getStyleClass().add("chart-tooltip-name");
            Label value = new Label(nameAndValue[nameAndValue.length - 1]);
            GridPane.setHalignment(value, HPos.RIGHT);
            tooltip.addRow(tooltip.getRowCount(), name, value);
        }
        tooltip.applyCss();
        tooltipWidth.set(Math.max(tooltipWidth.get(), tooltip.prefWidth(-1)));
        layoutOverlays();
        candleLine.toFront(); // above candles added since the last hover
    }

    // align the line and the tooltip with the hovered candle, which moves when the plot is resized
    private void layoutOverlays() {
        CandleData candleData = hoveredCandle.get();
        if (candleData == null) {
            return;
        }
        double candleX = getXAxis().getDisplayPosition(candleData.tick);
        candleLine.setStartX(candleX);
        candleLine.setEndX(candleX);
        candleLine.setEndY(getYAxis().getHeight());

        // the tooltip sits before the candle, which leaves the later candles in view, or after it where the
        // start of the plot lacks the room for the widest tooltip, so the tooltips of the charts switch together
        tooltip.autosize();
        boolean hasRoomBefore = candleX - TOOLTIP_GAP - tooltipWidth.get() >= 0;
        double tooltipX = hasRoomBefore ? candleX - TOOLTIP_GAP - tooltip.getWidth() : candleX + TOOLTIP_GAP;
        tooltip.relocate(getYAxis().getLayoutX() + getYAxis().getWidth() + tooltipX, getYAxis().getLayoutY());
    }
}
