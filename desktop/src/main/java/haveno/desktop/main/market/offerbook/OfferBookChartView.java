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

package haveno.desktop.main.market.offerbook;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.jfoenix.controls.JFXTabPane;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.config.Config;
import haveno.common.util.Tuple3;
import haveno.common.util.Tuple4;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.monetary.Volume;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.util.FormattingUtils;
import haveno.core.util.VolumeUtil;
import haveno.core.util.coin.CoinFormatter;
import haveno.desktop.common.view.ActivatableViewAndModel;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.AutoTooltipTableColumn;
import haveno.desktop.components.AutocompleteComboBox;
import haveno.desktop.components.ColoredDecimalPlacesWithZerosText;
import haveno.desktop.components.PeerInfoIconSmall;
import haveno.desktop.main.offer.offerbook.OfferBookListItem;
import haveno.desktop.util.CurrencyListItem;
import haveno.desktop.util.DisplayUtils;
import static haveno.desktop.util.FormBuilder.addTopLabelAutocompleteComboBox;
import haveno.desktop.util.GUIUtil;
import haveno.network.p2p.NodeAddress;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javafx.beans.InvalidationListener;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.Axis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.SingleSelectionModel;
import javafx.scene.control.Tab;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.Line;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.util.Callback;
import javafx.util.StringConverter;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

@FxmlView
public class OfferBookChartView extends ActivatableViewAndModel<VBox, OfferBookChartViewModel> {
    private final boolean useDevPrivilegeKeys;

    private NumberAxis xAxis;
    private NumberAxis yAxis;
    private XYChart.Series<Number, Number> seriesBuy, seriesSell;
    private final CoinFormatter formatter;
    private TableView<OfferListItem> buyOfferTableView;
    private TableView<OfferListItem> sellOfferTableView;
    private AreaChart<Number, Number> areaChart;
    private AnchorPane chartPane;
    private Pane deviationScalePane;
    private Pane gridLinesPane;
    private Pane hoverPane; // over the whole view, holding the point markers, the price guide and the offer card
    private Group hoverMarkers;
    private Point2D hoverMouse; // last mouse position over the chart in scene coordinates, null once it left
    private XYChart.Data<Number, Number> hoveredData; // point at the pointer's price
    private TableRow<OfferListItem> hoveredRow; // table row under the pointer, whose point is marked
    private VBox hoverCard;
    private boolean chartOverlaysRedrawScheduled;
    private AutocompleteComboBox<CurrencyListItem> currencyComboBox;
    private Subscription tradeCurrencySubscriber;
    private final StringProperty volumeSellColumnLabel = new SimpleStringProperty();
    private final StringProperty volumeBuyColumnLabel = new SimpleStringProperty();
    private final StringProperty amountSellColumnLabel = new SimpleStringProperty();
    private final StringProperty amountBuyColumnLabel = new SimpleStringProperty();
    private final StringProperty priceColumnLabel = new SimpleStringProperty();
    private AutoTooltipButton sellButton;
    private AutoTooltipButton buyButton;
    private ChangeListener<Number> selectedTabIndexListener;
    private ChangeListener<Boolean> currencyListShowingListener;
    private SingleSelectionModel<Tab> tabPaneSelectionModel;
    private Label sellHeaderLabel, buyHeaderLabel;
    private ListChangeListener<OfferBookListItem> changeListener;
    private ListChangeListener<CurrencyListItem> currencyListItemsListener;
    private final double initialOfferTableViewHeight = 83; // decrease as MainView's content-pane's top anchor increases
    private ChangeListener<Number> priceFeedUpdateCounterListener;
    private Timer priceRefreshTimer;
    private double chartMarketPrice;

    // Fixed axis scale used while the chart has no data, so the scales don't jump (see updateChartAxisRanging).
    private static final double EMPTY_AXIS_UPPER_BOUND = 4;
    private static final int EMPTY_AXIS_TICK_INTERVALS = 4;

    // Heights (px) of the deviation scale's tick marks above the plot
    private static final int DEVIATION_TICK_HEIGHT = 5;        // labeled ticks
    private static final int DEVIATION_MINOR_TICK_HEIGHT = 4;  // unlabeled midway ticks
    private static final int DEVIATION_LABEL_GAP = 1;          // px between the labels and their ticks (0 = touching)

    // Hovered offer (px): radius within which overlapping points are listed together, marker and halo radii, gap between
    // the point and its card, the card's minimum width, and the gap between the plot and the price chip on the axis
    private static final double HOVER_CLUSTER_RADIUS = 6;
    private static final int HOVER_CLUSTER_MAX_ROWS = 6;
    private static final double HOVER_MARKER_RADIUS = 4.5;
    private static final double HOVER_HALO_RADIUS = 10;
    private static final double HOVER_CARD_GAP = 14;
    private static final double HOVER_CARD_MIN_WIDTH = 240;
    private static final double HOVER_CHIP_GAP = 4;
    private static final PseudoClass LINKED_PSEUDO_CLASS = PseudoClass.getPseudoClass("linked"); // row of the offer hovered on the chart

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public OfferBookChartView(OfferBookChartViewModel model,
                              @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter,
                              @Named(Config.USE_DEV_PRIVILEGE_KEYS) boolean useDevPrivilegeKeys) {
        super(model);
        this.formatter = formatter;
        this.useDevPrivilegeKeys = useDevPrivilegeKeys;
    }

    @Override
    public void initialize() {
        createListener();

        final Tuple3<VBox, Label, AutocompleteComboBox<CurrencyListItem>> currencyComboBoxTuple = addTopLabelAutocompleteComboBox(Res.get("shared.currency"), 0);
        this.currencyComboBox = currencyComboBoxTuple.third;
        this.currencyComboBox.setCellFactory(GUIUtil.getCurrencyListItemCellFactory(Res.get("shared.oneOffer"),
                Res.get("shared.multipleOffers")));
        this.currencyComboBox.getStyleClass().add("input-with-border");
        this.currencyComboBox.setEditorGraphic(GUIUtil::getCurrencyFieldGraphic);

        createChart();

        VBox.setMargin(chartPane, new Insets(0, 0, 5, 0));

        Tuple4<TableView<OfferListItem>, VBox, Button, Label> tupleBuy = getOfferTable(OfferDirection.BUY);
        Tuple4<TableView<OfferListItem>, VBox, Button, Label> tupleSell = getOfferTable(OfferDirection.SELL);
        buyOfferTableView = tupleBuy.first;
        sellOfferTableView = tupleSell.first;

        sellButton = (AutoTooltipButton) tupleBuy.third;
        buyButton = (AutoTooltipButton) tupleSell.third;

        sellHeaderLabel = tupleBuy.fourth;
        buyHeaderLabel = tupleSell.fourth;

        HBox bottomHBox = new HBox();
        bottomHBox.setSpacing(10);
        bottomHBox.setAlignment(Pos.CENTER);
        VBox.setMargin(bottomHBox, new Insets(5, 0, 0, 0));
        VBox.setVgrow(bottomHBox, Priority.ALWAYS); // fill remaining height so the cards end at the view's bottom padding
        HBox.setHgrow(tupleBuy.second, Priority.ALWAYS);
        HBox.setHgrow(tupleSell.second, Priority.ALWAYS);
        tupleBuy.second.setUserData(OfferDirection.BUY.name());
        tupleSell.second.setUserData(OfferDirection.SELL.name());
        bottomHBox.getChildren().addAll(tupleSell.second, tupleBuy.second);

        root.getChildren().addAll(currencyComboBoxTuple.first, chartPane, bottomHBox, hoverPane);
        root.layoutBoundsProperty().addListener((observable, oldValue, newValue) -> hoverPane.resizeRelocate(0, 0, newValue.getWidth(), newValue.getHeight()));
    }

    @Override
    protected void activate() {
        // root.getParent() is null at initialize
        tabPaneSelectionModel = GUIUtil.getParentOfType(root, JFXTabPane.class).getSelectionModel();
        selectedTabIndexListener = (observable, oldValue, newValue) -> model.setSelectedTabIndex((int) newValue);

        model.setSelectedTabIndex(tabPaneSelectionModel.getSelectedIndex());
        tabPaneSelectionModel.selectedIndexProperty().addListener(selectedTabIndexListener);

        currencyComboBox.setConverter(new CurrencyListItemStringConverter(currencyComboBox));
        currencyComboBox.getEditor().getStyleClass().add("combo-box-editor-bold");

        currencyComboBox.setAutocompleteItems(model.getCurrencyListItems());
        currencyComboBox.setVisibleRowCount(10);

        if (model.getSelectedCurrencyListItem().isPresent()) {
            CurrencyListItem selectedItem = model.getSelectedCurrencyListItem().get();
            currencyComboBox.getSelectionModel().select(model.getSelectedCurrencyListItem().get());
            currencyComboBox.getEditor().setText(new CurrencyListItemStringConverter(currencyComboBox).toString(selectedItem));
        }

        currencyComboBox.setOnChangeConfirmed(e -> {
            CurrencyListItem selectedItem = currencyComboBox.getSelectionModel().getSelectedItem();
            if (selectedItem != null) {
                model.onSetTradeCurrency(selectedItem.tradeCurrency);
                UserThread.execute(() -> updateChartData());
            }
        });

        model.currencyListItems.getObservableList().addListener(currencyListItemsListener);

        model.getOfferBookListItems().addListener(changeListener);
        tradeCurrencySubscriber = EasyBind.subscribe(model.selectedTradeCurrencyProperty,
                tradeCurrency -> {
                    String code = tradeCurrency.getCode();
                    xAxis.setTickLabelFormatter(new StringConverter<>() {
                        final int cryptoPrecision = 3;
                        final DecimalFormat df = new DecimalFormat(",###");

                        @Override
                        public String toString(Number object) {
                            try {
                                final double doubleValue = (double) object;
                                if (CurrencyUtil.isCryptoCurrency(model.getCurrencyCode())) {
                                    final String withCryptoPrecision = FormattingUtils.formatRoundedDoubleWithPrecision(doubleValue, cryptoPrecision);
                                    if (withCryptoPrecision.startsWith("0.0")) {
                                        return FormattingUtils.formatRoundedDoubleWithPrecision(doubleValue, 8).replaceFirst("0+$", "");
                                    } else {
                                        return withCryptoPrecision.replaceFirst("0+$", "");
                                    }
                                } else {
                                    return df.format(Double.parseDouble(FormattingUtils.formatRoundedDoubleWithPrecision(doubleValue, 0)));
                                }
                            } catch (IllegalArgumentException e) {
                                log.error("Error converting number to string, tradeCurrency={}, number={}\n", code, object, e);
                                return "NaN"; // TODO: occasionally getting invalid number
                            }
                        }

                        @Override
                        public Number fromString(String string) {
                            return null;
                        }
                    });
                    
                    String viewBaseCurrencyCode = Res.getBaseCurrencyCode();
                    String viewPriceCurrencyCode = code;

                    sellHeaderLabel.setText(Res.get("market.offerBook.sellOffersHeaderLabel", viewBaseCurrencyCode));
                    sellButton.updateText(Res.get("shared.sellCurrency", viewBaseCurrencyCode));
                    sellButton.setGraphic(GUIUtil.getMoneroMark());
                    sellButton.setOnAction(e -> model.goToOfferView(OfferDirection.BUY));
                    sellButton.setId("sell-button-big");

                    buyHeaderLabel.setText(Res.get("market.offerBook.buyOffersHeaderLabel", viewBaseCurrencyCode));
                    buyButton.updateText(Res.get( "shared.buyCurrency", viewBaseCurrencyCode));
                    buyButton.setGraphic(GUIUtil.getMoneroMark());
                    buyButton.setOnAction(e -> model.goToOfferView(OfferDirection.SELL));
                    buyButton.setId("buy-button-big");

                    priceColumnLabel.set(Res.get("shared.priceWithCur", viewPriceCurrencyCode));

                    xAxis.setLabel(CurrencyUtil.getPriceWithCurrencyCode(code));

                    seriesBuy.setName(sellHeaderLabel.getText() + "   ");
                    seriesSell.setName(buyHeaderLabel.getText());
                });

        buyOfferTableView.setItems(model.getTopBuyOfferList());
        sellOfferTableView.setItems(model.getTopSellOfferList());

        model.priceFeedService.updateCounterProperty().addListener(priceFeedUpdateCounterListener);
        currencyComboBox.showingProperty().addListener(currencyListShowingListener);

        updateChartData();
    }

    // Shows the currency's offers, as from a row of the offers by currency tab
    public void showCurrency(String currencyCode) {
        model.getCurrencyListItems().stream().filter(item -> item.tradeCurrency.getCode().equals(currencyCode)).findFirst().ifPresent(item -> {
            currencyComboBox.getSelectionModel().select(item);
            currencyComboBox.getEditor().setText(new CurrencyListItemStringConverter(currencyComboBox).toString(item));
            model.onSetTradeCurrency(item.tradeCurrency);
            updateChartData();
        });
    }

    static class CurrencyListItemStringConverter extends StringConverter<CurrencyListItem> {
        private final ComboBox<CurrencyListItem> comboBox;

        CurrencyListItemStringConverter(ComboBox<CurrencyListItem> comboBox) {
            this.comboBox = comboBox;
        }

        @Override
        public String toString(CurrencyListItem currencyItem) {
            return currencyItem != null ? currencyItem.codeDashNameString() : "";
        }

        @Override
        public CurrencyListItem fromString(String s) {
            return comboBox.getItems().stream().
                    filter(currencyItem -> currencyItem.codeDashNameString().equals(s)).
                    findAny().orElse(null);
        }
    }

    private void createListener() {
        changeListener = c -> UserThread.execute(() -> updateChartData());

        currencyListItemsListener = c -> {
            if (model.getSelectedCurrencyListItem().isPresent())
                currencyComboBox.getSelectionModel().select(model.getSelectedCurrencyListItem().get());
        };

        // the currency list opens over the chart and takes the pointer without the chart seeing it leave
        currencyListShowingListener = (observable, oldValue, showing) -> {
            if (showing) {
                hoverMouse = null;
                updateHover(false);
            }
        };

        // Market prices update ~once per minute; refresh the prices, chart and tables.
        // Debounced to collapse the burst of updates from a single price request cycle.
        priceFeedUpdateCounterListener = (observable, oldValue, newValue) -> {
            if (priceRefreshTimer == null) {
                priceRefreshTimer = UserThread.runAfter(() -> {
                    priceRefreshTimer = null;
                    model.updateChartData();
                    updateChartData();
                }, 1);
            }
        };
    }

    @Override
    protected void deactivate() {
        model.getOfferBookListItems().removeListener(changeListener);
        tabPaneSelectionModel.selectedIndexProperty().removeListener(selectedTabIndexListener);
        model.currencyListItems.getObservableList().removeListener(currencyListItemsListener);
        tradeCurrencySubscriber.unsubscribe();
        model.priceFeedService.updateCounterProperty().removeListener(priceFeedUpdateCounterListener);
        currencyComboBox.showingProperty().removeListener(currencyListShowingListener);
        hoverMouse = null;
        hoveredRow = null;
        updateHover(true);
        if (priceRefreshTimer != null) {
            priceRefreshTimer.stop();
            priceRefreshTimer = null;
        }
    }

    private void createChart() {
        xAxis = new NumberAxis();
        xAxis.setForceZeroInRange(false);
        xAxis.setAutoRanging(true);
        xAxis.setTickMarkVisible(true);
        xAxis.setMinorTickVisible(false);

        yAxis = new NumberAxis();
        yAxis.setForceZeroInRange(false);
        yAxis.setSide(Side.RIGHT);
        yAxis.setAutoRanging(true);
        yAxis.setTickMarkVisible(true);
        yAxis.setMinorTickVisible(false);
        yAxis.getStyleClass().add("axisy");
        yAxis.setLabel(CurrencyUtil.getOfferVolumeCode(Res.getBaseCurrencyCode()));

        seriesBuy = new XYChart.Series<>();
        seriesSell = new XYChart.Series<>();

        areaChart = new DepthChart(xAxis, yAxis, seriesBuy, seriesSell);
        areaChart.setLegendVisible(false);
        areaChart.setAnimated(false);
        areaChart.setHorizontalGridLinesVisible(false); // draw the horizontal grid lines ourselves to fix flickering due to floating-point edge comparison
        areaChart.setId("charts");
        areaChart.setMinHeight(270);
        areaChart.setPrefHeight(270);
        areaChart.setCreateSymbols(true);
        areaChart.setPadding(new Insets(0, 10, 0, 10));
        areaChart.getData().addAll(List.of(seriesBuy, seriesSell));

        chartPane = new AnchorPane();
        chartPane.getStyleClass().add("chart-pane");

        AnchorPane.setTopAnchor(areaChart, 24d);
        AnchorPane.setBottomAnchor(areaChart, 10d);
        AnchorPane.setLeftAnchor(areaChart, 10d);
        AnchorPane.setRightAnchor(areaChart, 0d);

        deviationScalePane = new Pane();
        deviationScalePane.setMouseTransparent(true);
        deviationScalePane.setPickOnBounds(false);
        AnchorPane.setTopAnchor(deviationScalePane, 0d);
        AnchorPane.setBottomAnchor(deviationScalePane, 0d);
        AnchorPane.setLeftAnchor(deviationScalePane, 0d);
        AnchorPane.setRightAnchor(deviationScalePane, 0d);

        // Pane for our self-drawn horizontal grid lines. It sits behind the chart so the lines
        // render beneath the (translucent) area fills, matching the chart's built-in appearance.
        gridLinesPane = new Pane();
        gridLinesPane.setMouseTransparent(true);
        gridLinesPane.setPickOnBounds(false);
        AnchorPane.setTopAnchor(gridLinesPane, 0d);
        AnchorPane.setBottomAnchor(gridLinesPane, 0d);
        AnchorPane.setLeftAnchor(gridLinesPane, 0d);
        AnchorPane.setRightAnchor(gridLinesPane, 0d);

        // over the whole view rather than the chart, so the card isn't cut off at the chart's edge
        hoverPane = new Pane();
        hoverPane.setManaged(false);
        hoverPane.setMouseTransparent(true);
        hoverMarkers = new Group();
        hoverPane.getChildren().add(hoverMarkers);

        chartPane.getChildren().addAll(gridLinesPane, areaChart, deviationScalePane);

        areaChart.setOnMouseMoved(this::onChartMouseMoved);
        areaChart.setOnMouseDragged(this::onChartMouseMoved);
        areaChart.setOnMouseExited(e -> {
            hoverMouse = null;
            updateHover(false);
        });
        areaChart.setOnMouseClicked(e -> {
            if (!isPlainClick(e)) return;
            hoverMouse = new Point2D(e.getSceneX(), e.getSceneY());
            XYChart.Data<Number, Number> nearest = nearestData();
            if (nearest != null) model.showOffer(offerOf(nearest));
        });

        InvalidationListener overlayRedrawTrigger = observable -> scheduleChartOverlaysRedraw();
        xAxis.lowerBoundProperty().addListener(overlayRedrawTrigger);
        xAxis.upperBoundProperty().addListener(overlayRedrawTrigger);
        xAxis.widthProperty().addListener(overlayRedrawTrigger);
        yAxis.lowerBoundProperty().addListener(overlayRedrawTrigger);
        yAxis.upperBoundProperty().addListener(overlayRedrawTrigger);
        areaChart.widthProperty().addListener(overlayRedrawTrigger);
        areaChart.heightProperty().addListener(overlayRedrawTrigger);
        // the page scrolling under a resting pointer; a change listener, since nothing else reads this property
        hoverPane.localToSceneTransformProperty().addListener((observable, oldValue, newValue) -> scheduleChartOverlaysRedraw());
    }

    private synchronized void updateChartData() {
        chartMarketPrice = model.getMarketPriceAsDouble()
                .filter(price -> Double.isFinite(price) && price > 0)
                .orElse(0d);

        // update volume headers
        Volume volumeSell = model.getTotalVolume(OfferDirection.SELL);
        Volume volumeBuy = model.getTotalVolume(OfferDirection.BUY);
        String formattedVolumeSell = volumeSell == null ? null : VolumeUtil.formatVolume(volumeSell);
        String formattedVolumeBuy = volumeBuy == null ? null : VolumeUtil.formatVolume(volumeBuy);
        if (model.getSellData().isEmpty()) formattedVolumeSell = "0.0";
        if (model.getBuyData().isEmpty()) formattedVolumeBuy = "0.0";
        volumeSellColumnLabel.set(Res.get("offerbook.volumeTotal", model.getCurrencyCode(), formattedVolumeSell == null ? "" : "(" + formattedVolumeSell + ")"));
        volumeBuyColumnLabel.set(Res.get("offerbook.volumeTotal", model.getCurrencyCode(), formattedVolumeBuy == null ? "" : "(" + formattedVolumeBuy + ")"));

        // update amount headers
        amountSellColumnLabel.set(Res.get("offerbook.XMRTotal", "" + model.getTotalAmount(OfferDirection.SELL)));
        amountBuyColumnLabel.set(Res.get("offerbook.XMRTotal", "" + model.getTotalAmount(OfferDirection.BUY)));

        seriesSell.getData().clear();
        seriesBuy.getData().clear();
        areaChart.getData().clear();

        seriesSell.getData().addAll(filterOutliers(model.getSellData(), OfferDirection.SELL, chartMarketPrice));
        seriesBuy.getData().addAll(filterOutliers(model.getBuyData(), OfferDirection.BUY, chartMarketPrice));

        areaChart.getData().addAll(List.of(seriesBuy, seriesSell));

        updateChartAxisRanging();

        scheduleChartOverlaysRedraw();
    }

    private void updateChartAxisRanging() {
        boolean hasData = !seriesBuy.getData().isEmpty() || !seriesSell.getData().isEmpty();
        pinAxisWhenEmpty(xAxis, hasData);
        pinAxisWhenEmpty(yAxis, hasData);
    }

    private void pinAxisWhenEmpty(NumberAxis axis, boolean hasData) {
        if (hasData) {
            axis.setAutoRanging(true);
        } else if (axis.isAutoRanging()) {
            axis.setAutoRanging(false);
            axis.setLowerBound(0);
            axis.setUpperBound(EMPTY_AXIS_UPPER_BOUND);
            axis.setTickUnit(EMPTY_AXIS_UPPER_BOUND / EMPTY_AXIS_TICK_INTERVALS);
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Deviation scale (% from market price across the top of the chart)
    ///////////////////////////////////////////////////////////////////////////////////////////

    private static final int DEVIATION_TARGET_TICKS = 6;
    private static final int DEVIATION_MAX_TICKS = 40;

    private void scheduleChartOverlaysRedraw() {
        if (chartOverlaysRedrawScheduled) return;
        chartOverlaysRedrawScheduled = true;
        UserThread.execute(() -> {
            chartOverlaysRedrawScheduled = false;
            redrawGridLines();
            redrawDeviationScale();
            updateHover(true);
        });
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Horizontal grid lines (drawn here so the top/bottom lines don't flicker; see createChart)
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void redrawGridLines() {
        if (gridLinesPane == null || areaChart == null || yAxis == null) return;
        gridLinesPane.getChildren().clear();

        Node plotBackground = areaChart.lookup(".chart-plot-background");
        if (plotBackground == null) return;
        Bounds plotBounds = gridLinesPane.sceneToLocal(plotBackground.localToScene(plotBackground.getBoundsInLocal()));
        double plotLeft = plotBounds.getMinX();
        double plotRight = plotBounds.getMaxX();
        double plotTop = plotBounds.getMinY();
        double plotBottom = plotBounds.getMaxY();
        if (!(plotRight > plotLeft) || !(plotBottom > plotTop)) return;

        // draw a horizontal grid line for every y-axis tick
        for (Axis.TickMark<Number> tick : yAxis.getTickMarks()) {
            double y = gridLineY(tick.getValue());
            if (y < plotTop - 1 || y > plotBottom + 1) continue;
            y = Math.floor(Math.min(plotBottom, Math.max(plotTop, y))) + 0.5;
            Line line = new Line(plotLeft, y, plotRight, y);
            line.getStyleClass().add("chart-horizontal-grid-line");
            line.setManaged(false);
            gridLinesPane.getChildren().add(line);
        }
    }

    private double gridLineY(Number value) {
        Point2D scenePoint = yAxis.localToScene(0, yAxis.getDisplayPosition(value));
        return gridLinesPane.sceneToLocal(scenePoint).getY();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Offer card: the offer at the pointer's price, read only; a click opens it in the offer book
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onChartMouseMoved(MouseEvent event) {
        hoverMouse = new Point2D(event.getSceneX(), event.getSceneY());
        updateHover(false);
    }

    // Re-runs the hit test from the last pointer position; with redraw the markers and the card are laid out again
    private void updateHover(boolean redraw) {
        if (hoverPane == null) return;
        XYChart.Data<Number, Number> nearest = nearestData();
        boolean changed = nearest != hoveredData;
        hoveredData = nearest;
        areaChart.setCursor(nearest == null ? Cursor.DEFAULT : Cursor.HAND);
        if (changed || redraw) {
            redrawMarkers();
            redrawCard();
        }
    }

    // The point at the pointer's price anywhere in the plot area; among offers in that price column, the nearest by height
    private XYChart.Data<Number, Number> nearestData() {
        if (hoverMouse == null || currencyComboBox.isShowing()) return null;
        Point2D mouse = hoverPane.sceneToLocal(hoverMouse);
        Bounds plotBounds = plotBounds();
        if (plotBounds == null || !plotBounds.contains(mouse)) return null;
        List<XYChart.Data<Number, Number>> points = areaChart.getData().stream().flatMap(series -> series.getData().stream()).collect(Collectors.toList());
        double nearestX = points.stream().mapToDouble(data -> Math.abs(mouse.getX() - hoverPoint(data).getX())).min().orElse(0);
        return points.stream()
                .filter(data -> Math.abs(mouse.getX() - hoverPoint(data).getX()) <= nearestX + 1)
                .min(Comparator.comparingDouble(data -> Math.abs(mouse.getY() - hoverPoint(data).getY())))
                .orElse(null);
    }

    // The plot area in the overlay's coordinates, or null before the chart is laid out
    private Bounds plotBounds() {
        Node plotBackground = areaChart.lookup(".chart-plot-background");
        return plotBackground == null ? null : hoverPane.sceneToLocal(plotBackground.localToScene(plotBackground.getBoundsInLocal()));
    }

    private Point2D hoverPoint(XYChart.Data<Number, Number> data) {
        double x = hoverPane.sceneToLocal(xAxis.localToScene(xAxis.getDisplayPosition(data.getXValue()), 0)).getX();
        double y = hoverPane.sceneToLocal(yAxis.localToScene(0, yAxis.getDisplayPosition(data.getYValue()))).getY();
        return new Point2D(x, y);
    }

    private static Offer offerOf(XYChart.Data<Number, Number> data) {
        return (Offer) data.getExtraValue();
    }

    // The offer's chart entry from the model, plotted or filtered out as an outlier, or null once the offer left the book
    private XYChart.Data<Number, Number> dataFor(Offer offer) {
        for (List<XYChart.Data<Number, Number>> data : List.of(model.getBuyData(), model.getSellData())) {
            synchronized (data) {
                for (XYChart.Data<Number, Number> entry : data) {
                    if (offerOf(entry).getId().equals(offer.getId())) return entry;
                }
            }
        }
        return null;
    }

    private boolean isPlotted(XYChart.Data<Number, Number> data) {
        return seriesBuy.getData().contains(data) || seriesSell.getData().contains(data);
    }

    // Marks the hovered point with a guide to its price and the point of the table row under the pointer, and links the
    // rows to the hovered point; the row's offer is read here since a reused row shows another offer after a rebuild
    private void redrawMarkers() {
        hoverMarkers.getChildren().clear();
        XYChart.Data<Number, Number> linkedData = hoveredRow == null || hoveredRow.isEmpty() ? null : dataFor(hoveredRow.getItem().offer);
        if (linkedData != null && linkedData != hoveredData && isPlotted(linkedData)) addMarker(linkedData);
        if (hoveredData != null) {
            addGuide(hoveredData);
            addMarker(hoveredData);
        }
        for (TableView<OfferListItem> table : List.of(buyOfferTableView, sellOfferTableView)) {
            for (Node node : table.lookupAll(".table-row-cell")) updateLinkedRow((TableRow<?>) node);
        }
    }

    private void addMarker(XYChart.Data<Number, Number> data) {
        Point2D point = hoverPoint(data);
        String side = offerOf(data).isBuyOffer() ? "buy" : "sell";
        Circle halo = new Circle(point.getX(), point.getY(), HOVER_HALO_RADIUS);
        halo.getStyleClass().addAll("chart-hover-halo", side);
        Circle marker = new Circle(point.getX(), point.getY(), HOVER_MARKER_RADIUS);
        marker.getStyleClass().addAll("chart-hover-marker", side);
        hoverMarkers.getChildren().addAll(halo, marker);
    }

    // A dashed line from the point down to the price axis, with the price on a chip over the axis
    private void addGuide(XYChart.Data<Number, Number> data) {
        Bounds plotBounds = plotBounds();
        if (plotBounds == null) return;
        Point2D point = hoverPoint(data);
        String side = offerOf(data).isBuyOffer() ? "buy" : "sell";
        Line guide = new Line(point.getX(), point.getY(), point.getX(), plotBounds.getMaxY());
        guide.getStyleClass().addAll("chart-hover-guide", side);
        Label chip = new Label(FormattingUtils.formatPrice(offerOf(data).getPrice()));
        chip.getStyleClass().addAll("chart-hover-chip", side);
        chip.setManaged(false);
        hoverMarkers.getChildren().addAll(guide, chip);
        chip.applyCss();
        double width = chip.prefWidth(-1);
        chip.resizeRelocate(Math.max(0, Math.min(point.getX() - width / 2, hoverPane.getWidth() - width)), plotBounds.getMaxY() + HOVER_CHIP_GAP, width, chip.prefHeight(width));
    }

    // A row is linked while its offer's point is hovered on the chart
    private void updateLinkedRow(TableRow<?> row) {
        Offer offer = row.getItem() instanceof OfferListItem ? ((OfferListItem) row.getItem()).offer : null;
        row.pseudoClassStateChanged(LINKED_PSEUDO_CLASS, offer != null && hoveredData != null && offerOf(hoveredData).getId().equals(offer.getId()));
    }

    // Lays the hovered point's card out beside it, toward the spread and above, kept within the view
    private void redrawCard() {
        hoverPane.getChildren().remove(hoverCard);
        hoverCard = null;
        if (hoveredData == null) return;
        Offer offer = offerOf(hoveredData);
        Point2D point = hoverPoint(hoveredData);

        // same side offers whose points overlap the hovered one are listed in the card, topmost first
        List<XYChart.Data<Number, Number>> cluster = areaChart.getData().stream()
                .flatMap(series -> series.getData().stream())
                .filter(data -> offerOf(data).isBuyOffer() == offer.isBuyOffer() && point.distance(hoverPoint(data)) <= HOVER_CLUSTER_RADIUS)
                .sorted(Comparator.comparingDouble(data -> hoverPoint(data).getY()))
                .collect(Collectors.toList());
        VBox card = createCard(hoveredData, cluster);
        hoverCard = card;
        hoverPane.getChildren().add(card);

        card.applyCss();
        double width = Math.max(HOVER_CARD_MIN_WIDTH, card.prefWidth(-1));
        double height = card.prefHeight(width);
        boolean opensRight = offer.isBuyOffer();
        double x = opensRight ? point.getX() + HOVER_CARD_GAP : point.getX() - HOVER_CARD_GAP - width;
        if (x < 0 || x + width > hoverPane.getWidth()) x = opensRight ? point.getX() - HOVER_CARD_GAP - width : point.getX() + HOVER_CARD_GAP;
        double y = point.getY() - HOVER_CARD_GAP - height;
        card.resizeRelocate(Math.max(0, Math.min(x, hoverPane.getWidth() - width)), Math.max(0, Math.min(y, hoverPane.getHeight() - height)), width, height);
    }

    // The card for an offer: its price and deviation, then its amounts, payment method and maker, or with a cluster the
    // offers sharing its point, with the one the pointer's height picks highlighted
    private VBox createCard(XYChart.Data<Number, Number> shown, List<XYChart.Data<Number, Number>> cluster) {
        Offer offer = offerOf(shown);
        VBox card = new VBox();
        card.getStyleClass().add("chart-hover-card");
        card.setManaged(false);

        String title = offer.isBuyOffer() ? sellHeaderLabel.getText() : buyHeaderLabel.getText();
        if (cluster.size() > 1) title += "  ·  " + cluster.size() + " " + Res.get("shared.multipleOffers");
        HBox header = new HBox(12, hoverLabel(title, "dim", "small"), hoverSpacer());
        if (chartMarketPrice > 0) {
            // the plotted price and the market price come from the same chart build, like the deviation scale
            String deviation = formatDeviation(shown.getXValue().doubleValue() / chartMarketPrice - 1, 2);
            header.getChildren().add(hoverLabel(Res.get("market.offerBook.chart.deviationFromMarket", deviation), "dim", "small"));
        }
        String currencyCode = model.getCurrencyCode();
        HBox priceRow = new HBox(5, hoverLabel(FormattingUtils.formatPrice(offer.getPrice()), "price"), hoverLabel(currencyCode, "dim", "small"));
        priceRow.setAlignment(Pos.BASELINE_LEFT);
        card.getChildren().addAll(header, priceRow);

        if (cluster.size() > 1) {
            // rows around the hovered offer, with an ellipsis where the cluster is longer than the card shows
            int from = Math.max(0, Math.min(cluster.indexOf(shown) - HOVER_CLUSTER_MAX_ROWS / 2, cluster.size() - HOVER_CLUSTER_MAX_ROWS));
            int to = Math.min(cluster.size(), from + HOVER_CLUSTER_MAX_ROWS);
            VBox list = new VBox();
            list.getStyleClass().add("list");
            if (from > 0) list.getChildren().add(hoverLabel("…", "dim", "more"));
            for (XYChart.Data<Number, Number> data : cluster.subList(from, to)) {
                Offer rowOffer = offerOf(data);
                HBox row = new HBox(12, hoverLabel(DisplayUtils.formatAmount(rowOffer, formatter) + " " + Res.getBaseCurrencyCode()), hoverSpacer(),
                        hoverLabel(rowOffer.getPaymentMethodNameWithCountryCode(), "dim"));
                row.getStyleClass().add("row");
                if (data == shown) row.getStyleClass().add("current");
                list.getChildren().add(row);
            }
            if (to < cluster.size()) list.getChildren().add(hoverLabel("…", "dim", "more"));
            card.getChildren().add(list);
            return card;
        }

        boolean myOffer = model.isMyOffer(offer);
        HBox maker = new HBox(6);
        maker.setAlignment(Pos.CENTER_RIGHT);
        if (myOffer) {
            maker.getChildren().add(hoverLabel(Res.get("shared.me")));
        } else {
            maker.getChildren().add(new PeerInfoIconSmall(offer.getOwnerNodeAddress(), Res.get("peerInfoIcon.tooltip.maker"), offer,
                    model.preferences, model.accountAgeWitnessService, useDevPrivilegeKeys));
            long accountAge = CurrencyUtil.isFiatCurrency(currencyCode) ? model.accountAgeWitnessService.getAccountAge(offer) : -1;
            if (accountAge > -1) maker.getChildren().add(hoverLabel(Res.get("market.offerBook.chart.accountAge", DisplayUtils.formatAccountAge(accountAge))));
        }
        VBox rows = new VBox(3,
                hoverRow(Res.get("shared.amountWithCur", Res.getBaseCurrencyCode()), hoverLabel(DisplayUtils.formatAmount(offer, formatter))),
                hoverRow(Res.get("shared.amountWithCur", currencyCode), hoverLabel(VolumeUtil.formatVolume(offer, false, 0))),
                hoverRow(Res.get("shared.paymentMethod"), hoverLabel(offer.getPaymentMethodNameWithCountryCode())),
                hoverRow(Res.get("shared.maker"), maker));
        rows.getStyleClass().add("rows");
        card.getChildren().add(rows);
        return card;
    }

    private static boolean isPlainClick(MouseEvent event) {
        return event.getButton() == MouseButton.PRIMARY && event.isStillSincePress();
    }

    private static HBox hoverRow(String key, Node value) {
        HBox row = new HBox(12, hoverLabel(key, "dim"), hoverSpacer(), value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Region hoverSpacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private static Label hoverLabel(String text, String... styleClasses) {
        Label label = new Label(text);
        label.getStyleClass().addAll(styleClasses);
        return label;
    }

    private void redrawDeviationScale() {
        if (deviationScalePane == null || areaChart == null || xAxis == null) return;
        deviationScalePane.getChildren().clear();

        if (seriesBuy.getData().isEmpty() && seriesSell.getData().isEmpty()) return;
        if (xAxis.getWidth() <= 0) return;

        double marketPrice = chartMarketPrice;
        if (marketPrice <= 0) return;

        double lower = xAxis.getLowerBound();
        double upper = xAxis.getUpperBound();
        if (!(upper > lower)) return;

        Node plotBackground = areaChart.lookup(".chart-plot-background");
        if (plotBackground == null) return;
        Bounds plotBounds = deviationScalePane.sceneToLocal(plotBackground.localToScene(plotBackground.getBoundsInLocal()));
        double plotTop = plotBounds.getMinY();
        double plotBottom = plotBounds.getMaxY();
        if (!(plotBottom > plotTop)) return;

        double lowerDeviation = lower / marketPrice - 1;
        double upperDeviation = upper / marketPrice - 1;
        List<Double> deviations = niceTicks(lowerDeviation, upperDeviation, DEVIATION_TARGET_TICKS);
        if (deviations.isEmpty()) return;
        double step = deviations.size() > 1 ? Math.abs(deviations.get(1) - deviations.get(0)) : Math.abs(deviations.get(0));
        int decimals = percentDecimals(step);

        for (double deviation : deviations) {
            double price = marketPrice * (1 + deviation);
            if (price < lower || price > upper) continue;
            double x = deviationScaleX(price);
            boolean isZero = Math.abs(deviation) < step / 2;

            Label label = new Label(formatDeviation(deviation, decimals));
            label.getStyleClass().add("deviation-scale-label");
            label.setManaged(false);
            deviationScalePane.getChildren().add(label);
            label.applyCss();
            double labelWidth = label.prefWidth(-1);
            double labelHeight = label.prefHeight(labelWidth);
            label.resizeRelocate(x - labelWidth / 2, Math.max(1, plotTop - DEVIATION_TICK_HEIGHT - labelHeight - DEVIATION_LABEL_GAP), labelWidth, labelHeight);

            Line line = isZero ? new Line(x, plotTop, x, plotBottom) : new Line(x, plotTop - DEVIATION_TICK_HEIGHT, x, plotTop);
            line.getStyleClass().add(isZero ? "deviation-scale-zero-line" : "deviation-scale-tick");
            line.setManaged(false);
            deviationScalePane.getChildren().add(line);
        }

        // Minor ticks halfway between the labeled ticks (no label), for finer reading of the scale.
        double minorStep = step / 2;
        if (minorStep > 0) {
            int minorTicks = 0;
            for (double deviation = Math.ceil(lowerDeviation / minorStep) * minorStep;
                    deviation <= upperDeviation + minorStep * 1e-6 && minorTicks < DEVIATION_MAX_TICKS;
                    deviation += minorStep) {
                double majorRatio = deviation / step;
                if (Math.abs(majorRatio - Math.rint(majorRatio)) < 1e-6) continue; // coincides with a labeled tick
                double price = marketPrice * (1 + deviation);
                if (price < lower || price > upper) continue;
                double x = deviationScaleX(price);

                Line minorTick = new Line(x, plotTop - DEVIATION_MINOR_TICK_HEIGHT, x, plotTop);
                minorTick.getStyleClass().add("deviation-scale-minor-tick");
                minorTick.setManaged(false);
                deviationScalePane.getChildren().add(minorTick);
                minorTicks++;
            }
        }
    }

    private double deviationScaleX(double price) {
        Point2D scenePoint = xAxis.localToScene(xAxis.getDisplayPosition(price), 0);
        return deviationScalePane.sceneToLocal(scenePoint).getX();
    }

    private static List<Double> niceTicks(double min, double max, int targetCount) {
        List<Double> result = new ArrayList<>();
        if (!Double.isFinite(min) || !Double.isFinite(max) || max <= min) return result;
        double step = niceStep((max - min) / Math.max(1, targetCount));
        if (!(step > 0) || !Double.isFinite(step)) return result;
        for (double v = Math.ceil(min / step) * step; v <= max + step * 1e-6 && result.size() < DEVIATION_MAX_TICKS; v += step) {
            double rounded = Math.round(v / step) * step;
            result.add(Math.abs(rounded) < step * 1e-9 ? 0 : rounded);
        }
        return result;
    }

    private static double niceStep(double raw) {
        if (!(raw > 0) || !Double.isFinite(raw)) return 0;
        double base = Math.pow(10, Math.floor(Math.log10(raw)));
        double f = raw / base;
        double niceF = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10;
        return niceF * base;
    }

    private static int percentDecimals(double step) {
        int decimals = 0;
        for (double s = step * 100; decimals < 4 && Math.abs(s - Math.rint(s)) > 1e-9; s *= 10) decimals++;
        return decimals;
    }

    private static String formatDeviation(double deviation, int decimals) {
        double percent = deviation * 100;
        if (Math.abs(percent) < Math.pow(10, -decimals) / 2) percent = 0;
        DecimalFormat format = new DecimalFormat();
        format.setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.US));
        format.setGroupingUsed(false);
        format.setMinimumFractionDigits(decimals);
        format.setMaximumFractionDigits(decimals);
        return (percent > 0 ? "+" : "") + format.format(percent) + "%";
    }

    // Filter the far tail with a Tukey fence and a 3x cap on the best price, but keep offers within 30% of the
    // market price, or of the best price without one. The Tukey margin is at least 10% of the outer quartile.
    private static final double OUTLIER_IQR_FACTOR = 1.5;
    private static final double OUTLIER_MIN_FENCE_RATIO = 0.1;
    private static final double OUTLIER_MAX_PRICE_RATIO = 3;
    private static final double OUTLIER_NEAR_PRICE_RATIO = 0.3;
    // Use at least two offers per quartile before trimming statistical outliers.
    private static final int OUTLIER_MIN_OFFERS = 8;

    static List<XYChart.Data<Number, Number>> filterOutliers(List<XYChart.Data<Number, Number>> data, OfferDirection direction, double marketPrice) {
        synchronized (data) {
            double[] prices = data.stream().mapToDouble(o -> o.getXValue().doubleValue()).sorted().toArray();
            if (prices.length == 0) return data;
            double q1 = quantile(prices, 0.25);
            double q3 = quantile(prices, 0.75);
            boolean farSideHigh = direction == OfferDirection.SELL;
            double margin = Math.max((q3 - q1) * OUTLIER_IQR_FACTOR, (farSideHigh ? q3 : q1) * OUTLIER_MIN_FENCE_RATIO);
            double fence = farSideHigh ? q3 + margin : q1 - margin;
            boolean hasMarketPrice = Double.isFinite(marketPrice) && marketPrice > 0;
            double bestPrice = farSideHigh ? prices[0] : prices[prices.length - 1];
            double outerLimit = farSideHigh ? bestPrice * OUTLIER_MAX_PRICE_RATIO : bestPrice / OUTLIER_MAX_PRICE_RATIO;
            double nearFence = (hasMarketPrice ? marketPrice : bestPrice) * (farSideHigh ? 1 + OUTLIER_NEAR_PRICE_RATIO : 1 - OUTLIER_NEAR_PRICE_RATIO);
            // Offer prices round to eight decimal places; allow half a unit plus floating-point error.
            double nearTolerance = 0.5e-8 + 2 * Math.ulp(nearFence);
            return data.stream()
                    .filter(o -> {
                        double price = o.getXValue().doubleValue();
                        if (farSideHigh ? price <= nearFence + nearTolerance : price >= nearFence - nearTolerance) return true;
                        if (prices.length >= OUTLIER_MIN_OFFERS && (farSideHigh ? price > fence : price < fence)) return false;
                        return farSideHigh ? price < outerLimit : price > outerLimit;
                    })
                    .collect(Collectors.toList());
        }
    }

    private static double quantile(double[] sorted, double p) {
        double position = p * (sorted.length - 1);
        int index = (int) position;
        if (index + 1 >= sorted.length) return sorted[index];
        return sorted[index] + (position - index) * (sorted[index + 1] - sorted[index]);
    }

    private Tuple4<TableView<OfferListItem>, VBox, Button, Label> getOfferTable(OfferDirection direction) {
        TableView<OfferListItem> tableView = new TableView<>();
        GUIUtil.applyTableStyle(tableView, false);
        GUIUtil.applyRoundedBottomArc(tableView); // the card's title bar above keeps the header corners square
        tableView.setMinHeight(initialOfferTableViewHeight);
        tableView.setPrefHeight(initialOfferTableViewHeight);
        tableView.setMinWidth(480);
        tableView.getStyleClass().addAll("offer-table", "non-interactive-table");

        // price
        TableColumn<OfferListItem, OfferListItem> priceColumn = new TableColumn<>();
        priceColumn.textProperty().bind(priceColumnLabel);
        priceColumn.setMinWidth(100);
        priceColumn.setMaxWidth(100);
        priceColumn.setSortable(false);
        priceColumn.getStyleClass().add("number-column");
        priceColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        priceColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OfferListItem, OfferListItem> call(TableColumn<OfferListItem, OfferListItem> column) {
                        return new TableCell<>() {
                            private Offer offer;
                            final ChangeListener<Number> listener = new ChangeListener<>() {
                                @Override
                                public void changed(ObservableValue<? extends Number> observable,
                                                    Number oldValue,
                                                    Number newValue) {
                                    if (offer != null && offer.getPrice() != null) {
                                        setText("");
                                        setGraphic(new ColoredDecimalPlacesWithZerosText(model.getPrice(offer),
                                                model.getZeroDecimalsForPrice(offer)));
                                        model.priceFeedService.updateCounterProperty().removeListener(listener);
                                    }
                                }
                            };

                            @Override
                            public void updateItem(final OfferListItem offerListItem, boolean empty) {
                                super.updateItem(offerListItem, empty);
                                if (offerListItem != null && !empty) {

                                    final Offer offer = offerListItem.offer;
                                    if (offer.getPrice() == null) {
                                        this.offer = offer;
                                        model.priceFeedService.updateCounterProperty().addListener(listener);
                                        setText(Res.get("shared.na"));
                                    } else {
                                        setGraphic(new ColoredDecimalPlacesWithZerosText(model.getPrice(offer),
                                                model.getZeroDecimalsForPrice(offer)));
                                    }
                                } else {
                                    model.priceFeedService.updateCounterProperty().removeListener(listener);
                                    this.offer = null;
                                    setText("");
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });

        boolean isSellTable = model.isSellOffer(direction);

        // volume
        TableColumn<OfferListItem, OfferListItem> volumeColumn = new TableColumn<>();
        volumeColumn.setMinWidth(90);
        volumeColumn.setSortable(false);
        volumeColumn.textProperty().bind(isSellTable ? volumeSellColumnLabel : volumeBuyColumnLabel);
        volumeColumn.getStyleClass().addAll("number-column");
        volumeColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        volumeColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OfferListItem, OfferListItem> call(TableColumn<OfferListItem, OfferListItem> column) {
                        return new TableCell<>() {
                            private Offer offer;
                            final ChangeListener<Number> listener = new ChangeListener<>() {
                                @Override
                                public void changed(ObservableValue<? extends Number> observable,
                                                    Number oldValue,
                                                    Number newValue) {
                                    if (offer != null && offer.getPrice() != null) {
                                        renderCellContentRange();
                                        model.priceFeedService.updateCounterProperty().removeListener(listener);
                                    }
                                }
                            };

                            @Override
                            public void updateItem(final OfferListItem offerListItem, boolean empty) {
                                super.updateItem(offerListItem, empty);
                                if (offerListItem != null && !empty) {
                                    this.offer = offerListItem.offer;
                                    if (offer.getPrice() == null) {
                                        this.offer = offerListItem.offer;
                                        model.priceFeedService.updateCounterProperty().addListener(listener);
                                        setText(Res.get("shared.na"));
                                    } else {
                                        renderCellContentRange();
                                    }
                                } else {
                                    model.priceFeedService.updateCounterProperty().removeListener(listener);
                                    this.offer = null;
                                    setText("");
                                    setGraphic(null);
                                }
                            }

                            /**
                             * Renders cell content, if it has a single value or a range.
                             * Should not be called for empty cells
                             */
                            private void renderCellContentRange() {
                                String volumeRange = VolumeUtil.formatVolume(offer, true, 2);

                                setText("");
                                setGraphic(new ColoredDecimalPlacesWithZerosText(volumeRange,
                                        model.getMaxNumberOfPriceZeroDecimalsToColorize(offer)));
                            }

                        };
                    }
                });

        // amount
        TableColumn<OfferListItem, OfferListItem> amountColumn = new TableColumn<>();
        amountColumn.textProperty().bind(isSellTable ? amountSellColumnLabel : amountBuyColumnLabel);
        amountColumn.setMinWidth(90);
        amountColumn.setSortable(false);
        amountColumn.getStyleClass().add("number-column");
        amountColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        amountColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<OfferListItem, OfferListItem> call(TableColumn<OfferListItem, OfferListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final OfferListItem offerListItem, boolean empty) {
                                super.updateItem(offerListItem, empty);
                                if (offerListItem != null && !empty) {
                                    String amountRange = DisplayUtils.formatAmount(offerListItem.offer, formatter);
                                    setGraphic(new ColoredDecimalPlacesWithZerosText(amountRange, GUIUtil.AMOUNT_DECIMALS_WITH_ZEROS));
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });

        // payment method
        TableColumn<OfferListItem, OfferListItem> paymentMethodColumn = new AutoTooltipTableColumn<>(Res.get("shared.paymentMethod"));
        paymentMethodColumn.setMinWidth(120);
        paymentMethodColumn.setSortable(false);
        paymentMethodColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        paymentMethodColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            public void updateItem(final OfferListItem offerListItem, boolean empty) {
                super.updateItem(offerListItem, empty);
                setGraphic(offerListItem != null && !empty ? new AutoTooltipLabel(offerListItem.offer.getPaymentMethodNameWithCountryCode()) : null);
            }
        });

        // trader avatar
        TableColumn<OfferListItem, OfferListItem> avatarColumn = new AutoTooltipTableColumn<>(isSellTable ?
                Res.get("shared.sellerUpperCase") : Res.get("shared.buyerUpperCase")) {
            {
                setMinWidth(60);
                setMaxWidth(60);
                setSortable(true);
            }
        };

        avatarColumn.getStyleClass().addAll("avatar-column");
        avatarColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        avatarColumn.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<OfferListItem, OfferListItem> call(TableColumn<OfferListItem, OfferListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final OfferListItem newItem, boolean empty) {
                                super.updateItem(newItem, empty);
                                if (newItem != null && !empty) {
                                    final Offer offer = newItem.offer;
                                    boolean myOffer = model.isMyOffer(offer);
                                    if (!myOffer) {
                                        final NodeAddress makersNodeAddress = offer.getOwnerNodeAddress();
                                        String role = Res.get("peerInfoIcon.tooltip.maker");
                                        PeerInfoIconSmall peerInfoIcon = new PeerInfoIconSmall(makersNodeAddress,
                                                role,
                                                offer,
                                                model.preferences,
                                                model.accountAgeWitnessService,
                                                useDevPrivilegeKeys);
//                                    setAlignment(Pos.CENTER);
                                        setGraphic(peerInfoIcon);
                                    } else {
                                        setGraphic(new Label(Res.get("shared.me")));
                                    }
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });

        tableView.getColumns().add(volumeColumn);
        tableView.getColumns().add(amountColumn);
        tableView.getColumns().add(priceColumn);
        tableView.getColumns().add(paymentMethodColumn);
        tableView.getColumns().add(avatarColumn);

        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tableView.setRowFactory(tv -> {
            TableRow<OfferListItem> row = new TableRow<>() {
                @Override
                protected void updateItem(OfferListItem item, boolean empty) {
                    super.updateItem(item, empty);
                    updateLinkedRow(this);
                }
            };
            // the row under the pointer marks its point on the chart, and a click opens its offer in the offer book
            row.setOnMouseEntered(e -> {
                hoveredRow = row;
                redrawMarkers();
            });
            row.setOnMouseExited(e -> {
                if (hoveredRow == row) hoveredRow = null;
                redrawMarkers();
            });
            row.setOnMouseClicked(e -> {
                if (!row.isEmpty() && isPlainClick(e)) model.showOffer(row.getItem().offer);
            });
            return row;
        });
        tableView.setOnKeyPressed(e -> {
            OfferListItem selected = tableView.getSelectionModel().getSelectedItem();
            if (e.getCode() == KeyCode.ENTER && selected != null) model.showOffer(selected.offer);
        });
        Label placeholder = new AutoTooltipLabel(Res.get("table.placeholder.noItems", Res.get("shared.multipleOffers")));
        placeholder.setWrapText(true);
        tableView.setPlaceholder(placeholder);

        HBox titleButtonBox = new HBox();
        titleButtonBox.getStyleClass().add("offer-table-top");
        titleButtonBox.setAlignment(Pos.CENTER);

        Label titleLabel = new AutoTooltipLabel();
        titleLabel.getStyleClass().add("table-title");

        AutoTooltipButton button = new AutoTooltipButton();
        button.setContentDisplay(ContentDisplay.RIGHT);
        button.setGraphicTextGap(10);
        button.setMinHeight(32);

        Region spacer = new Region();

        HBox.setHgrow(spacer, Priority.ALWAYS);

        titleButtonBox.getChildren().addAll(titleLabel, spacer, button);

        VBox vBox = new VBox();
        vBox.getStyleClass().add("offer-book-card");
        VBox.setVgrow(tableView, Priority.ALWAYS);
        vBox.setPadding(new Insets(1)); // inset children by the border width so the card border stays visible
        vBox.setSpacing(0);
        vBox.setFillWidth(true);
        //vBox.setMinHeight(190);
        vBox.getChildren().addAll(titleButtonBox, tableView);

        return new Tuple4<>(tableView, vBox, button, titleLabel);
    }

    // Area chart whose bid and ask plateaus run flat to the chart edges, as depth past the outermost offer stays at its total.
    static class DepthChart extends AreaChart<Number, Number> {
        private final XYChart.Series<Number, Number> bids, asks;

        DepthChart(NumberAxis xAxis, NumberAxis yAxis, XYChart.Series<Number, Number> bids, XYChart.Series<Number, Number> asks) {
            super(xAxis, yAxis);
            this.bids = bids;
            this.asks = asks;
        }

        @Override
        protected void layoutPlotChildren() {
            super.layoutPlotChildren();
            drawDepth(bids, true);
            drawDepth(asks, false);
        }

        // Redraw the series outline from the plot edge on its open side through its points, then down to the baseline.
        private void drawDepth(XYChart.Series<Number, Number> series, boolean openLeft) {
            if (!getData().contains(series) || !(series.getNode() instanceof Group)) return;
            List<Point2D> points = new ArrayList<>();
            for (XYChart.Data<Number, Number> item : series.getData()) {
                double x = getXAxis().getDisplayPosition(item.getXValue());
                double y = getYAxis().getDisplayPosition(item.getYValue());
                if (!Double.isNaN(x) && !Double.isNaN(y)) points.add(new Point2D(x, y));
            }
            if (points.isEmpty()) return;
            points.sort(Comparator.comparingDouble(Point2D::getX));
            Point2D first = points.get(0);
            Point2D last = points.get(points.size() - 1);
            double startX = openLeft ? 0 : first.getX();
            double endX = openLeft ? last.getX() : getXAxis().getWidth();
            double baseY = getYAxis().getDisplayPosition(0);
            Group group = (Group) series.getNode();
            Path fill = (Path) group.getChildren().get(0);
            Path line = (Path) group.getChildren().get(1);
            line.getElements().setAll(new MoveTo(startX, first.getY()));
            fill.getElements().setAll(new MoveTo(startX, baseY), new LineTo(startX, first.getY()));
            for (Point2D point : points) {
                line.getElements().add(new LineTo(point.getX(), point.getY()));
                fill.getElements().add(new LineTo(point.getX(), point.getY()));
            }
            line.getElements().add(new LineTo(endX, last.getY()));
            fill.getElements().addAll(new LineTo(endX, last.getY()), new LineTo(endX, baseY), new ClosePath());
        }
    }

}
