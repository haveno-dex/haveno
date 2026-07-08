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

import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

/**
 * A lightweight, in-scene replacement for {@link javafx.scene.control.Tooltip} used by the trade
 * statistics charts.
 * <p>
 * A {@code Tooltip} is rendered in a heavyweight popup (a separate native window). Showing it forces
 * a full re-composite of the parent scene, which momentarily re-snaps text to the pixel grid and
 * shifts sub-pixel layout, producing the visible "flicker/sharpen" of the whole pane on hover.
 * Keeping the tooltip content inside the chart's plot area avoids the popup entirely.
 * <p>
 * The content node is attached once to an overlay pane that sits above the chart, <b>outside the
 * plot area's clip</b> so it is never cut off by the axes (unlike plot children, which are clipped
 * to the plot area). It is kept mouse-transparent so it never steals hover events from the bars
 * underneath, and positioned near the cursor while being flipped and clamped so it stays fully
 * within the overlay pane.
 */
public class PlotAreaTooltip {

    // Gap between the cursor and the tooltip, matching the feel of a native tooltip.
    private static final double CURSOR_OFFSET = 12;

    private final Region content;

    public PlotAreaTooltip(Region content) {
        this.content = content;
        content.getStyleClass().add("chart-tooltip-box");
        content.setManaged(false); // positioned manually via resizeRelocate
        content.setMouseTransparent(true); // never intercept hover events from the bars below
        content.setVisible(false);
    }

    public Region getContent() {
        return content;
    }

    /**
     * Show the tooltip anchored to the source node's horizontal center, following the cursor vertically.
     */
    public void show(Node source, double sceneY, Pane overlay) {
        Bounds bounds = source.localToScene(source.getBoundsInLocal());
        show((bounds.getMinX() + bounds.getMaxX()) / 2, sceneY, overlay);
    }

    /**
     * Show the tooltip near the given cursor position (in scene coordinates), attaching it to the
     * given overlay pane on first use. The overlay pane must sit above the chart and outside the
     * plot area's clip, so the tooltip is never cut off by the axes. The tooltip is flipped to the
     * opposite side of the cursor when it would overflow the pane, then clamped so it remains fully
     * within the pane.
     *
     * @param sceneX  cursor x in scene coordinates
     * @param sceneY  cursor y in scene coordinates
     * @param overlay the pane the tooltip is rendered in (the chart's containing pane)
     */
    public void show(double sceneX, double sceneY, Pane overlay) {
        if (overlay == null) {
            return;
        }
        ObservableList<Node> children = overlay.getChildren();
        if (!children.contains(content)) {
            children.add(content);
        }
        content.setVisible(true);
        content.toFront(); // stay above the chart and any siblings in the overlay pane
        content.applyCss();
        content.layout();

        double width = content.prefWidth(-1);
        double height = content.prefHeight(-1);

        Point2D local = overlay.sceneToLocal(sceneX, sceneY);
        double overlayWidth = overlay.getWidth();
        double overlayHeight = overlay.getHeight();

        double x = local.getX() + CURSOR_OFFSET;
        if (x + width > overlayWidth) {
            x = local.getX() - CURSOR_OFFSET - width; // flip to the left of the cursor
        }
        x = clamp(x, 0, Math.max(0, overlayWidth - width));

        double y = local.getY() + CURSOR_OFFSET;
        if (y + height > overlayHeight) {
            y = local.getY() - CURSOR_OFFSET - height; // flip above the cursor
        }
        y = clamp(y, 0, Math.max(0, overlayHeight - height));

        content.resizeRelocate(x, y, width, height);
    }

    public void hide() {
        content.setVisible(false);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }
}
