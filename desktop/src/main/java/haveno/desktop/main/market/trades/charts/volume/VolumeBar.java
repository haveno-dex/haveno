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

 package haveno.desktop.main.market.trades.charts.volume;

 import haveno.core.locale.Res;
 import haveno.core.util.VolumeUtil;
 import haveno.desktop.main.market.trades.charts.CandleData;
 import javafx.application.Platform;
 import javafx.scene.Group;
 import javafx.scene.control.Tooltip;
 import javafx.scene.layout.Region;
 import javafx.util.StringConverter;
 
 /**
  * VolumeBar node used for displaying volume data on the chart
  */
 public class VolumeBar extends Group {
     private String seriesStyleClass;
     private String dataStyleClass;
     private final StringConverter<Number> volumeStringConverter;
 
     private final Region bar = new Region();
     private final Tooltip tooltip;
 
     /**
      * Constructor to initialize the VolumeBar.
      * @param seriesStyleClass the style class for the series (e.g., "bullish", "bearish")
      * @param dataStyleClass the style class for the data
      * @param volumeStringConverter the converter to convert the volume value into a string
      */
     public VolumeBar(String seriesStyleClass, String dataStyleClass, StringConverter<Number> volumeStringConverter) {
         this.seriesStyleClass = seriesStyleClass;
         this.dataStyleClass = dataStyleClass;
         this.volumeStringConverter = volumeStringConverter;
 
         // Disable auto resizing of children and add the bar as the child of the group
         setAutoSizeChildren(false);
         getChildren().add(bar);
         updateStyleClasses();
 
         // Initialize the tooltip for volume bar
         tooltip = new Tooltip();
         Tooltip.install(this, tooltip);
     }
 
     /**
      * Sets the style classes for the series and data of this volume bar.
      * @param seriesStyleClass the style class for the series
      * @param dataStyleClass the style class for the data
      */
     public void setSeriesAndDataStyleClasses(String seriesStyleClass, String dataStyleClass) {
         this.seriesStyleClass = seriesStyleClass;
         this.dataStyleClass = dataStyleClass;
         updateStyleClasses();
     }
 
     /**
      * Updates the visual properties of the volume bar and its tooltip.
      * @param height the height of the volume bar
      * @param candleWidth the width of the candle
      * @param candleData the data related to the candle
      */
     public void update(double height, double candleWidth, CandleData candleData) {
         // Resize and reposition the volume bar based on the provided height and width
         bar.resizeRelocate(-candleWidth / 2, 0, candleWidth, height);
 
         // Format the volume data into a displayable string
         String volumeInXmr = volumeStringConverter.toString(candleData.accumulatedAmount);
         String volumeInUsd = VolumeUtil.formatLargeFiat(candleData.volumeInUsd, "USD");
 
         // Update the tooltip text with the relevant volume data
         // Ensure that the tooltip update happens on the JavaFX Application Thread
         Platform.runLater(() -> {
             tooltip.setText(Res.get("market.trades.tooltip.volumeBar", volumeInXmr, volumeInUsd, candleData.numTrades, candleData.date));
         });
     }
 
     /**
      * Updates the style classes for the volume bar.
      * This is called when series or data style classes are changed.
      */
     private void updateStyleClasses() {
         bar.getStyleClass().setAll("volume-bar", seriesStyleClass, dataStyleClass, "bg");
     }
 }
 
