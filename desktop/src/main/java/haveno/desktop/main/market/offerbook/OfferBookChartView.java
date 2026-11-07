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
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.config.Config;
import haveno.common.util.Tuple3;
import haveno.common.util.Tuple4;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.monetary.Volume;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.payment.payload.PaymentMethod;
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
import haveno.desktop.main.offer.OfferViewUtil;
import haveno.desktop.main.offer.offerbook.OfferBookListItem;
import haveno.desktop.util.CurrencyListItem;
import haveno.desktop.util.DisplayUtils;
import static haveno.desktop.util.FormBuilder.addTopLabelAutocompleteComboBox;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.GlyphsDude;
import haveno.network.p2p.NodeAddress;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.beans.value.WeakChangeListener;
import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.BoundingBox;
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
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.SingleSelectionModel;
import javafx.scene.control.Tab;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.Line;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.text.Text;
import javafx.util.Callback;
import javafx.util.Duration;
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
    private DepthChart areaChart;
    private AnchorPane chartPane;
    private Pane deviationScalePane;
    private Pane gridLinesPane;
    private Label pricesLoadingLabel;
    private boolean hasUnpricedOffers;
    private Pane focusPane;
    private VBox emptyBookBox;
    private Label emptyBookLabel;
    private boolean chartOverlaysRedrawScheduled;
    private AutocompleteComboBox<CurrencyListItem> currencyComboBox;
    private Subscription tradeCurrencySubscriber;
    private final StringProperty volumeSellColumnLabel = new SimpleStringProperty();
    private final StringProperty volumeBuyColumnLabel = new SimpleStringProperty();
    private final StringProperty amountSellColumnLabel = new SimpleStringProperty();
    private final StringProperty amountBuyColumnLabel = new SimpleStringProperty();
    private final StringProperty priceColumnLabel = new SimpleStringProperty();
    private final StringProperty placeholderLabel = new SimpleStringProperty();
    private AutoTooltipButton sellButton;
    private AutoTooltipButton buyButton;
    private ChangeListener<Number> selectedTabIndexListener;
    private SingleSelectionModel<Tab> tabPaneSelectionModel;
    private Label sellHeaderLabel, buyHeaderLabel;
    private ListChangeListener<OfferBookListItem> changeListener;
    private ListChangeListener<CurrencyListItem> currencyListItemsListener;
    private final double initialOfferTableViewHeight = 83; // decrease as MainView's content-pane's top anchor increases
    private ChangeListener<Number> priceFeedUpdateCounterListener;
    private Timer priceRefreshTimer;
    private double chartMarketPrice;

    // The offers plotted on each side, best first, and what points at one of them or at a bare price.
    private List<OfferListItem> plottedAsks = List.of(), plottedBids = List.of();
    private Point2D chartPointer;
    private OfferListItem pointedOffer;
    private TableRow<OfferListItem> hoveredRow;
    private String selectedOfferId;
    private boolean isEnterPressed, isKeyFocus;
    // The hover stays with the offer which the pointer was moved onto while the book changes under it, until the pointer
    // moves again. It follows what comes under a resting pointer only in a panel whose rows the wheel or the keys scrolled.
    private String hoveredOfferId, termsHoverId;
    private TableView<OfferListItem> hoverFollowingTable;
    private final ObjectProperty<OfferListItem> focusedOffer = new SimpleObjectProperty<>();

    // The card with an offer's payment details and its maker's terms, shown while the pointer rests on the cell's terms icon.
    private VBox termsCard;
    private Label termsLabel, termsMoreLabel;
    private TableCell<OfferListItem, OfferListItem> termsCell;
    private String termsOfferId, termsPendingId;
    private final PauseTransition termsTimer = new PauseTransition();

    // The offers of the last pass over the book, to tell those which arrive with the next, and the glow of each of these, fading from 1.
    private Set<String> shownOfferIds;
    private String shownCurrencyCode;
    private final Map<String, DoubleProperty> newOfferGlows = new HashMap<>();

    // Fixed axis scale used while the chart has no data, so the scales don't jump (see updateChartAxisRanging).
    private static final double EMPTY_AXIS_UPPER_BOUND = 4;
    private static final int EMPTY_AXIS_TICK_INTERVALS = 4;

    // Heights (px) of the deviation scale's tick marks above the plot
    private static final int DEVIATION_TICK_HEIGHT = 5;        // labeled ticks
    private static final int DEVIATION_MINOR_TICK_HEIGHT = 4;  // unlabeled midway ticks
    private static final int DEVIATION_LABEL_GAP = 1;          // px between the labels and their ticks (0 = touching)

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
        // the currency list opens over the chart without the pointer leaving it
        this.currencyComboBox.showingProperty().addListener((observable, wasShowing, isShowing) -> setChartPointer(null));

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

        root.getChildren().addAll(currencyComboBoxTuple.first, chartPane, bottomHBox, createTermsCard());
    }

    @Override
    protected void activate() {
        // the book may have changed while the page was away, so its first pass tells no arrivals
        shownOfferIds = null;

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
                    // the rows of another book come under the pointer
                    setHover(null, null);
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
                    emptyBookLabel.setText(Res.get("market.offerBook.chart.noOffers", code));

                    xAxis.setLabel(CurrencyUtil.getPriceWithCurrencyCode(code));

                    seriesBuy.setName(sellHeaderLabel.getText() + "   ");
                    seriesSell.setName(buyHeaderLabel.getText());
                });

        buyOfferTableView.setItems(model.getTopBuyOfferList());
        sellOfferTableView.setItems(model.getTopSellOfferList());

        model.priceFeedService.updateCounterProperty().addListener(priceFeedUpdateCounterListener);

        updateChartData();
    }

    // Shows the currency's offers, as from a row of the offers by currency tab.
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
        if (priceRefreshTimer != null) {
            priceRefreshTimer.stop();
            priceRefreshTimer = null;
        }
        hoveredRow = null;
        termsCell = null;
        hoveredOfferId = null;
        termsHoverId = null;
        hoverFollowingTable = null;
        cancelTermsCard();
        setChartPointer(null);
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
        areaChart.setCreateSymbols(false);
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

        // shown in place of the deviation scale, which needs a market price
        pricesLoadingLabel = new Label(Res.get("market.offerBook.loadingPrices"));
        pricesLoadingLabel.getStyleClass().add("deviation-scale-label");
        pricesLoadingLabel.setAlignment(Pos.CENTER);
        AnchorPane.setTopAnchor(pricesLoadingLabel, 13d);
        AnchorPane.setLeftAnchor(pricesLoadingLabel, 0d);
        AnchorPane.setRightAnchor(pricesLoadingLabel, 0d);

        // Pane for the focus marker, guide, chips and label, above the chart and its scales.
        focusPane = new Pane();
        focusPane.setMouseTransparent(true);
        AnchorPane.setTopAnchor(focusPane, 0d);
        AnchorPane.setBottomAnchor(focusPane, 0d);
        AnchorPane.setLeftAnchor(focusPane, 0d);
        AnchorPane.setRightAnchor(focusPane, 0d);

        chartPane.getChildren().addAll(gridLinesPane, areaChart, deviationScalePane, pricesLoadingLabel, focusPane, createEmptyBookBox());
        chartPane.setOnMouseMoved(this::onChartPointer);
        chartPane.setOnMouseDragged(this::onChartPointer);
        // a scroll of the page brings the chart under a resting pointer without a move
        chartPane.setOnMouseEntered(this::onChartPointer);
        chartPane.setOnMouseExited(event -> setChartPointer(null));
        chartPane.setOnMouseClicked(event -> {
            if (pointedOffer != null && isPlainClick(event)) showOffer(pointedOffer);
        });

        ChangeListener<Number> overlayRedrawTrigger = (observable, oldValue, newValue) -> scheduleChartOverlaysRedraw();
        xAxis.lowerBoundProperty().addListener(overlayRedrawTrigger);
        xAxis.upperBoundProperty().addListener(overlayRedrawTrigger);
        xAxis.widthProperty().addListener(overlayRedrawTrigger);
        yAxis.lowerBoundProperty().addListener(overlayRedrawTrigger);
        yAxis.upperBoundProperty().addListener(overlayRedrawTrigger);
        areaChart.widthProperty().addListener(overlayRedrawTrigger);
        areaChart.heightProperty().addListener(overlayRedrawTrigger);
        // a refresh lays out new tick labels even while the range stays, and those under a chip need hiding again
        xAxis.getTickMarks().addListener((ListChangeListener<Axis.TickMark<Number>>) change -> scheduleChartOverlaysRedraw());
        // a scroll of the page moves the chart under a resting pointer
        focusPane.localToSceneTransformProperty().addListener((observable, oldValue, newValue) -> scheduleChartOverlaysRedraw());
    }

    private synchronized void updateChartData() {
        chartMarketPrice = model.getMarketPriceAsDouble()
                .filter(price -> Double.isFinite(price) && price > 0)
                .orElse(0d);

        // market priced offers are left out until the first market prices arrive
        boolean awaitingPrices = model.isAwaitingPrices();
        hasUnpricedOffers = model.hasUnpricedOffers();
        pricesLoadingLabel.setVisible(awaitingPrices);
        placeholderLabel.set(awaitingPrices ? pricesLoadingLabel.getText() : Res.get("table.placeholder.noItems", Res.get("shared.multipleOffers")));

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

        areaChart.setRangeEnds(getRangeEnd(model.getBuyData(), OfferDirection.BUY, chartMarketPrice),
                getRangeEnd(model.getSellData(), OfferDirection.SELL, chartMarketPrice));
        seriesSell.getData().addAll(filterOutliers(model.getSellData(), OfferDirection.SELL, chartMarketPrice));
        seriesBuy.getData().addAll(filterOutliers(model.getBuyData(), OfferDirection.BUY, chartMarketPrice));
        plottedAsks = getPlottedOffers(model.getTopSellOfferList(), seriesSell);
        plottedBids = getPlottedOffers(model.getTopBuyOfferList(), seriesBuy);
        glowNewOffers();

        areaChart.getData().addAll(List.of(seriesBuy, seriesSell));
        // the table lists dropped the selection when they were refilled
        if (selectedOfferId != null) selectOffer(findOffer(selectedOfferId));

        updateChartAxisRanging();

        scheduleChartOverlaysRedraw();
    }

    private void updateChartAxisRanging() {
        boolean hasData = !seriesBuy.getData().isEmpty() || !seriesSell.getData().isEmpty();
        pinAxisWhenEmpty(xAxis, hasData);
        pinAxisWhenEmpty(yAxis, hasData);
    }

    private void pinAxisWhenEmpty(NumberAxis axis, boolean hasData) {
        // the pinned scale of an empty book means nothing, so hide its numbers in place
        axis.setStyle(hasData ? null : "-fx-tick-label-fill: transparent;");
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
            redrawEmptyNotes();
            redrawNewOffers();
            if (hoveredRow != null && hoveredRow.getTableView() == hoverFollowingTable) syncHover();
            // an offer which left the book takes the hover of its terms icon with it
            if (termsHoverId != null && findOffer(termsHoverId) == null) termsHoverId = null;
            updateFocus();
            updateTermsCard();
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

    // Unsigned, as the position on the scale already says above or below the market price.
    private static String formatDeviation(double deviation, int decimals) {
        DecimalFormat format = new DecimalFormat();
        format.setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.US));
        format.setGroupingUsed(false);
        format.setMinimumFractionDigits(decimals);
        format.setMaximumFractionDigits(decimals);
        return format.format(Math.abs(deviation * 100)) + "%";
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Sparse markets (a note in an empty half of the chart, links to create an offer in an empty one)
    ///////////////////////////////////////////////////////////////////////////////////////////

    private VBox createEmptyBookBox() {
        emptyBookLabel = new Label();
        emptyBookLabel.getStyleClass().add("chart-empty-title");
        Hyperlink buyLink = new Hyperlink(Res.get("shared.buyCurrency", Res.getBaseCurrencyCode()));
        buyLink.getStyleClass().add("buy");
        buyLink.setOnAction(event -> buyButton.fire());
        Hyperlink sellLink = new Hyperlink(Res.get("shared.sellCurrency", Res.getBaseCurrencyCode()));
        sellLink.getStyleClass().add("sell");
        sellLink.setOnAction(event -> sellButton.fire());

        // the two links take the places of the sentence's arguments
        HBox createOfferBox = new HBox();
        createOfferBox.setAlignment(Pos.BASELINE_CENTER);
        String[] sentence = Res.get("market.offerBook.chart.createOffer", "{}", "{}").split("\\{}", -1);
        List<Hyperlink> links = List.of(buyLink, sellLink);
        for (int i = 0; i < sentence.length; i++) {
            createOfferBox.getChildren().add(new Label(sentence[i]));
            if (i < Math.min(links.size(), sentence.length - 1)) createOfferBox.getChildren().add(links.get(i));
        }

        emptyBookBox = new VBox(emptyBookLabel, createOfferBox);
        emptyBookBox.getStyleClass().add("chart-empty-book");
        emptyBookBox.setAlignment(Pos.CENTER);
        emptyBookBox.setManaged(false);
        emptyBookBox.setVisible(false);
        return emptyBookBox;
    }

    private void redrawEmptyNotes() {
        // a side is not known to be empty while offers of the book have no price
        if (hasUnpricedOffers) {
            emptyBookBox.setVisible(false);
            return;
        }
        Bounds plot = getPlotBounds();
        boolean noBids = seriesBuy.getData().isEmpty();
        boolean noAsks = seriesSell.getData().isEmpty();
        emptyBookBox.setVisible(plot != null && noBids && noAsks);
        if (plot == null || !noBids && !noAsks) return;
        if (noBids && noAsks) {
            emptyBookBox.applyCss();
            emptyBookBox.autosize();
            emptyBookBox.relocate(plot.getCenterX() - emptyBookBox.getWidth() / 2, plot.getCenterY() - emptyBookBox.getHeight() / 2);
            return;
        }

        Text note = new Text(Res.get(noBids ? "market.offerBook.chart.noOffersToSellTo" : "market.offerBook.chart.noOffersToBuyFrom", Res.getBaseCurrencyCode()));
        note.getStyleClass().add("chart-empty-note");
        note.setManaged(false);
        deviationScalePane.getChildren().add(note);
        note.applyCss();
        double width = note.getLayoutBounds().getWidth();
        // centered in the empty half, between the plot edge and the best price of the other side
        double centerX = ((noBids ? plot.getMinX() : plot.getMaxX()) + deviationScaleX((noBids ? plottedAsks : plottedBids).get(0).price)) / 2;
        note.relocate(Math.max(plot.getMinX() + 4, Math.min(centerX - width / 2, plot.getMaxX() - 4 - width)), plot.getCenterY() - note.getLayoutBounds().getHeight() / 2);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Terms card (an offer's payment details and its maker's terms, beside the payment cell whose icon is under the pointer)
    ///////////////////////////////////////////////////////////////////////////////////////////

    private static final double TERMS_WIDTH = 380;
    private static final double TERMS_HEIGHT = 280;  // longer terms are cut, and read on in the offer book
    private static final Duration TERMS_DELAY = Duration.millis(250);  // before a card shows
    private static final Duration TERMS_GRACE = Duration.millis(150);  // before a shown card leaves or changes

    private VBox createTermsCard() {
        termsLabel = new Label();
        termsLabel.setWrapText(true);
        termsMoreLabel = new Label(Res.get("market.offerBook.chart.moreTerms"));
        termsMoreLabel.getStyleClass().add("offer-terms-more");
        termsCard = new VBox(termsLabel, termsMoreLabel);
        termsCard.getStyleClass().addAll("tooltip", "offer-terms-card");
        termsCard.setManaged(false);
        termsCard.setMouseTransparent(true);
        termsCard.setVisible(false);
        termsTimer.setOnFinished(event -> {
            Offer offer = getTermsOffer();
            String pendingId = termsPendingId;
            termsPendingId = null;
            if (termsOfferId != null) {
                // the card does not move on to another offer, which waits for its own
                hideTermsCard();
                updateTermsCard();
            } else if (offer != null && offer.getId().equals(pendingId)) {
                termsOfferId = pendingId;
                // runs of blank lines would spend the card's room
                termsLabel.setText(OfferViewUtil.getPaymentMethodToolTip(offer).replaceAll("\\n(\\s*\\n)+", "\n\n"));
                termsCard.setVisible(true);
                placeTermsCard();
            }
        });
        return termsCard;
    }

    // Shows the card of the offer whose terms icon the pointer rests on, a moment after it arrived there, and takes the
    // card away a moment after the pointer left. A refilled table gives the row a new icon, which takes the pointer off
    // the offer for an instant: the wait for a card runs on over that, and a shown card stays.
    private void updateTermsCard() {
        Offer offer = getTermsOffer();
        String offerId = offer == null ? null : offer.getId();
        if (termsOfferId != null && termsOfferId.equals(offerId)) {
            termsTimer.stop();
            placeTermsCard();
        } else if (termsOfferId != null) {
            if (termsTimer.getStatus() != Animation.Status.RUNNING) {
                termsTimer.setDuration(TERMS_GRACE);
                termsTimer.playFromStart();
            }
        } else if (offerId != null && !offerId.equals(termsPendingId)) {
            termsPendingId = offerId;
            termsTimer.setDuration(TERMS_DELAY);
            termsTimer.playFromStart();
        }
    }

    // The offer whose terms icon is hovered, while it is in the book.
    private Offer getTermsOffer() {
        OfferListItem item = termsHoverId == null ? null : findOffer(termsHoverId);
        return item == null || model.isOfferBanned(item.offer) ? null : item.offer;
    }

    // The terms icon in the row which shows the offer, or null while none does.
    private Node findTermsIcon(String offerId) {
        for (TableView<OfferListItem> table : List.of(sellOfferTableView, buyOfferTableView)) {
            for (Node row : table.lookupAll(".table-row-cell")) {
                // the table keeps rows which it does not show
                if (row.isVisible() && ((TableRow<?>) row).getItem() instanceof OfferListItem item && item.offer.getId().equals(offerId)) return row.lookup(".offer-terms-icon");
            }
        }
        return null;
    }

    private void hideTermsCard() {
        termsOfferId = null;
        termsCard.setVisible(false);
    }

    private void cancelTermsCard() {
        termsTimer.stop();
        termsPendingId = null;
        hideTermsCard();
    }

    // Sizes the card to its text and sets it under the payment cell of its offer, or above it where it would leave the window.
    private void placeTermsCard() {
        Node cell = findTermsIcon(termsOfferId);
        while (cell != null && !(cell instanceof TableCell)) cell = cell.getParent();
        if (cell == null || root.getScene() == null) {
            // the book moved its row out of the panel's view
            termsHoverId = null;
            cancelTermsCard();
            return;
        }
        termsCard.applyCss();
        termsLabel.setMaxHeight(Region.USE_COMPUTED_SIZE);
        double width = Math.min(TERMS_WIDTH, termsCard.prefWidth(-1));
        boolean isCut = termsLabel.prefHeight(width - termsCard.getInsets().getLeft() - termsCard.getInsets().getRight()) > TERMS_HEIGHT;
        termsLabel.setMaxHeight(TERMS_HEIGHT);
        termsMoreLabel.setVisible(isCut);
        termsMoreLabel.setManaged(isCut);
        double height = termsCard.prefHeight(width);

        Bounds bounds = root.sceneToLocal(cell.localToScene(cell.getBoundsInLocal()));
        Bounds window = root.sceneToLocal(new BoundingBox(0, 0, root.getScene().getWidth(), root.getScene().getHeight()));
        double minY = Math.max(0, window.getMinY()) + 6;
        double maxY = Math.min(root.getHeight(), window.getMaxY()) - 6;
        double x = Math.max(6, Math.min(bounds.getMinX() + 18, root.getWidth() - width - 6));
        double y = bounds.getMaxY() + 4 + height > maxY ? Math.max(minY, bounds.getMinY() - 4 - height) : bounds.getMaxY() + 4;
        termsCard.resizeRelocate(x, y, width, height);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // New offers (a ring pulses once on the offer's step corner while its row glows)
    ///////////////////////////////////////////////////////////////////////////////////////////

    private static final PseudoClass NEW_OFFER = PseudoClass.getPseudoClass("new-offer");
    private static final int MAX_NEW_OFFERS = 5;  // more at once is a book loading

    // Starts the glow of the offers which arrived since the last pass over the same book.
    private void glowNewOffers() {
        Set<String> offerIds = Stream.concat(model.getTopSellOfferList().stream(), model.getTopBuyOfferList().stream())
                .map(item -> item.offer.getId())
                .collect(Collectors.toSet());
        boolean isSameBook = shownOfferIds != null && model.getCurrencyCode().equals(shownCurrencyCode);
        Set<String> arrived = offerIds.stream().filter(id -> isSameBook && !shownOfferIds.contains(id)).collect(Collectors.toSet());
        shownOfferIds = offerIds;
        shownCurrencyCode = model.getCurrencyCode();
        if (arrived.isEmpty() || arrived.size() > MAX_NEW_OFFERS || !GlobalSettings.getUseAnimations()) return;

        // these fade together, apart from the offers which arrived before them or arrive after
        DoubleProperty glow = new SimpleDoubleProperty(1);
        glow.addListener((observable, oldValue, newValue) -> redrawNewOffers());
        arrived.forEach(id -> newOfferGlows.put(id, glow));
        Timeline timeline = new Timeline(new KeyFrame(Duration.millis(1200), new KeyValue(glow, 0)));
        timeline.setOnFinished(event -> {
            newOfferGlows.values().removeIf(value -> value == glow);
            redrawNewOffers();
        });
        timeline.play();
    }

    // Draws the rings of the new offers, which grow from their dots as the glow fades, and glows their rows.
    private void redrawNewOffers() {
        deviationScalePane.getChildren().removeIf(node -> node.getStyleClass().contains("chart-new-offer"));
        for (TableView<OfferListItem> table : List.of(sellOfferTableView, buyOfferTableView)) table.lookupAll(".table-row-cell").forEach(row -> glowRow((TableRow<?>) row));
        if (getPlotBounds() == null) return;
        Stream.concat(plottedAsks.stream(), plottedBids.stream()).filter(item -> newOfferGlows.containsKey(item.offer.getId())).forEach(item -> {
            double glow = newOfferGlows.get(item.offer.getId()).get();
            Circle ring = new Circle(5 + 12 * (1 - glow));
            ring.getStyleClass().add("chart-new-offer-ring");
            Circle dot = new Circle(4);
            dot.getStyleClass().add("chart-focus-dot");
            Group pulse = new Group(ring, dot);
            pulse.getStyleClass().addAll("chart-new-offer", "chart-focus", item.offer.isBuyOffer() ? "bid" : "ask");
            pulse.setManaged(false);
            pulse.setOpacity(glow);
            pulse.setLayoutX(deviationScaleX(item.price));
            pulse.setLayoutY(gridLineY(item.accumulated));
            deviationScalePane.getChildren().add(pulse);
        });
    }

    private void glowRow(TableRow<?> row) {
        DoubleProperty glow = row.getItem() instanceof OfferListItem item ? newOfferGlows.get(item.offer.getId()) : null;
        long percent = glow == null ? 0 : Math.round(glow.get() * 100);
        row.pseudoClassStateChanged(NEW_OFFER, percent > 0);
        // the stylesheet takes the strength of the row's glow from the brightness of this color
        row.setStyle(percent > 0 ? "-bs-new-offer-glow: hsb(0, 0%, " + percent + "%);" : null);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Focus (the offer or price which the pointer on the chart, a hovered row or the selected row points at)
    ///////////////////////////////////////////////////////////////////////////////////////////

    private static final PseudoClass LINKED = PseudoClass.getPseudoClass("linked");
    private static final PseudoClass LINKED_SIBLING = PseudoClass.getPseudoClass("linked-sibling");
    private static final PseudoClass MARKET = PseudoClass.getPseudoClass("market");
    private static final double CHIP_HEIGHT = 17;
    private static final double CHIP_CLEARANCE = 26;       // axis labels this close to a chip are hidden
    private static final double MARKET_SNAP_DISTANCE = 8;  // the percent scale names the market price this close to its 0% tick

    private void onChartPointer(MouseEvent event) {
        // a drag which began on the chart keeps reporting from outside of it
        boolean isOnChart = !currencyComboBox.isShowing() && chartPane.getLayoutBounds().contains(event.getX(), event.getY());
        isKeyFocus = false;
        setChartPointer(isOnChart ? new Point2D(event.getSceneX(), event.getSceneY()) : null);
    }

    // Keeps the pointer's scene position, to resolve the focus again when the chart changes under it.
    private void setChartPointer(Point2D scenePoint) {
        chartPointer = scenePoint;
        updateFocus();
    }

    // Takes the row and the terms icon under the pointer as the hovered ones.
    private void syncHover() {
        setHover(hoveredRow == null || hoveredRow.getItem() == null ? null : hoveredRow.getItem().offer.getId(),
                termsCell == null || termsCell.getItem() == null ? null : termsCell.getItem().offer.getId());
    }

    private void setHover(String offerId, String termsId) {
        boolean isChanged = !Objects.equals(offerId, hoveredOfferId);
        boolean isTermsChanged = !Objects.equals(termsId, termsHoverId);
        hoveredOfferId = offerId;
        termsHoverId = termsId;
        if (isChanged) updateFocus();
        if (isTermsChanged) updateTermsCard();
    }

    // Resolves the focus from the pointer on the chart, else the hovered row, else the row selected in a panel which has
    // the keys, and draws it. A row which a click left selected stays off the chart, once the pointer is elsewhere.
    private void updateFocus() {
        Bounds plot = getPlotBounds();
        OfferListItem offer = null;
        double price = Double.NaN;
        boolean isMarketPrice = false;
        boolean hasKeys = sellOfferTableView.isFocused() || buyOfferTableView.isFocused();
        // the keys of a panel take the focus over from a pointer resting on the chart as well, until it moves
        Point2D pointer = isKeyFocus && hasKeys || chartPointer == null || plot == null ? null : focusPane.sceneToLocal(chartPointer);
        if (pointer != null && pointer.getX() >= plot.getMinX() && pointer.getX() <= plot.getMaxX() && !(plottedAsks.isEmpty() && plottedBids.isEmpty())) {
            double pointerPrice = xAxis.getValueForDisplay(xAxis.sceneToLocal(chartPointer).getX()).doubleValue();
            if (pointer.getY() >= plot.getMinY() && pointer.getY() <= plot.getMaxY()) {
                double pointerAmount = yAxis.getValueForDisplay(yAxis.sceneToLocal(chartPointer).getY()).doubleValue();
                offer = offerNear(plottedAsks, plottedBids, pointerPrice, pointerAmount, 1 / xAxis.getScale());
                if (offer == null) price = pointerPrice;
            } else if (pointer.getY() < plot.getMinY() && deviationScalePane.lookup(".deviation-scale-label") != null) {
                // the percent scale reads as a ruler, which names the market price at its 0% tick
                isMarketPrice = chartMarketPrice >= xAxis.getLowerBound() && chartMarketPrice <= xAxis.getUpperBound()
                        && Math.abs(pointer.getX() - deviationScaleX(chartMarketPrice)) <= MARKET_SNAP_DISTANCE;
                price = isMarketPrice ? chartMarketPrice : pointerPrice;
            }
        }
        pointedOffer = offer;
        chartPane.setCursor(offer != null ? Cursor.HAND : null);

        OfferListItem hovered = isKeyFocus || hoveredOfferId == null ? null : findOffer(hoveredOfferId);
        if (offer == null && Double.isNaN(price)) offer = hovered != null ? hovered : hasKeys ? getSelectedOffer() : null;
        focusedOffer.set(offer);
        redrawFocus(plot, offer, price, isMarketPrice);
    }

    private void redrawFocus(Bounds plot, OfferListItem offer, double price, boolean isMarketPrice) {
        focusPane.getChildren().clear();
        Bounds priceChip = null;
        Bounds percentChip = null;
        Bounds depthChip = null;
        if (plot != null && (offer != null || !Double.isNaN(price))) {
            boolean isAsk = offer != null && !offer.offer.isBuyOffer();
            // a far offer which the plot leaves out pins its chips to the plot edge on its side
            boolean isPlotted = offer == null || (isAsk ? plottedAsks : plottedBids).contains(offer);
            double x = isPlotted ? Math.round(deviationScaleX(offer != null ? offer.price : price)) + 0.5 : isAsk ? plot.getMaxX() : plot.getMinX();
            double y = offer != null ? gridLineY(offer.accumulated) : Double.NaN;
            focusPane.getStyleClass().setAll("chart-focus", offer == null ? "gap" : isAsk ? "ask" : "bid");
            if (isPlotted) {
                Line guide = new Line(x, plot.getMinY(), x, plot.getMaxY());
                guide.getStyleClass().add("chart-focus-guide");
                guide.setManaged(false);
                focusPane.getChildren().add(guide);
            }
            if (offer != null && isPlotted) {
                Circle halo = new Circle(x, y, 9);
                halo.getStyleClass().add("chart-focus-halo");
                Circle dot = new Circle(x, y, 4.3);
                dot.getStyleClass().add("chart-focus-dot");
                halo.setManaged(false);
                dot.setManaged(false);
                focusPane.getChildren().addAll(halo, dot);
            }

            String priceText = offer != null ? DisplayUtils.formatPrice(offer.offer.getPrice(), false, 0) : FormattingUtils.formatMarketPrice(price, model.getCurrencyCode());
            if (isMarketPrice) priceText = Res.get("market.offerBook.chart.market", priceText);
            if (!isPlotted) priceText = isAsk ? priceText + " \u25B8" : "\u25C2 " + priceText;
            priceChip = addChip(priceText, x, plot.getMaxY() + xAxis.getTickLength(), plot).getBoundsInParent();
            String percentText = offer != null ? model.getPriceAsPercentage(offer.offer)
                    : isMarketPrice || chartMarketPrice <= 0 ? "" : FormattingUtils.formatPercentagePrice(Math.abs(price / chartMarketPrice - 1));
            if (!percentText.isEmpty()) percentChip = addChip(percentText, x, plot.getMinY() - DEVIATION_TICK_HEIGHT - CHIP_HEIGHT, plot).getBoundsInParent();
            if (offer != null && isPlotted) {
                // the depth up to the offer reads on the volume axis, level with the marker
                Label chip = addChip(FormattingUtils.formatRoundedDoubleWithPrecision(offer.accumulated, 2), x, y, plot);
                chip.relocate(Math.min(plot.getMaxX() + yAxis.getTickLength(), focusPane.getWidth() - chip.getWidth()),
                        Math.max(plot.getMinY(), Math.min(y - CHIP_HEIGHT / 2, plot.getMaxY() - CHIP_HEIGHT)));
                depthChip = chip.getBoundsInParent();
            }

            if (offer != null) {
                Region label = createFocusLabel(offer);
                label.setManaged(false);
                focusPane.getChildren().add(label);
                label.applyCss();
                double width = label.prefWidth(-1);
                double height = label.prefHeight(width);
                Point2D spot = placeLabel(plot, !isAsk, new Point2D(x, y), width, height,
                        labelX -> getOutlineY(plottedAsks, OfferDirection.SELL, labelX),
                        labelX -> getOutlineY(plottedBids, OfferDirection.BUY, labelX));
                label.resizeRelocate(spot.getX(), spot.getY(), width, height);
            }
        }

        // hide the axis labels which a chip covers, and point out the 0% label while its chip names the market price
        for (Node node : deviationScalePane.getChildren()) {
            if (!(node instanceof Label label) || !label.getStyleClass().contains("deviation-scale-label")) continue;
            label.setVisible(!isCovered(label.getBoundsInParent(), percentChip));
            label.pseudoClassStateChanged(MARKET, isMarketPrice && Math.abs(label.getBoundsInParent().getCenterX() - deviationScaleX(chartMarketPrice)) < 1);
        }
        for (NumberAxis axis : List.of(xAxis, yAxis)) {
            for (Node node : axis.getChildrenUnmodifiable()) {
                if (node instanceof Text tickLabel) tickLabel.setOpacity(isCovered(focusPane.sceneToLocal(tickLabel.localToScene(tickLabel.getBoundsInLocal())), axis == xAxis ? priceChip : depthChip) ? 0 : 1);
            }
        }
    }

    // Adds a chip centered on x and kept within the plot, and returns it.
    private Label addChip(String text, double x, double y, Bounds plot) {
        Label chip = new Label(text);
        chip.getStyleClass().add("chart-focus-chip");
        chip.setManaged(false);
        focusPane.getChildren().add(chip);
        chip.applyCss();
        double width = chip.prefWidth(-1);
        chip.resizeRelocate(Math.max(plot.getMinX(), Math.min(x - width / 2, plot.getMaxX() - width)), y, width, CHIP_HEIGHT);
        return chip;
    }

    private static boolean isCovered(Bounds label, Bounds chip) {
        return chip != null && label.getMaxX() > chip.getMinX() - CHIP_CLEARANCE && label.getMinX() < chip.getMaxX() + CHIP_CLEARANCE
                && label.getMaxY() > chip.getMinY() && label.getMinY() < chip.getMaxY();
    }

    // Two lines without field names: the amount, then the volume, the payment method and other offers at the price.
    private Region createFocusLabel(OfferListItem item) {
        Offer offer = item.offer;
        Label amountLabel = new Label(DisplayUtils.formatAmount(offer, formatter) + " " + Res.getBaseCurrencyCode());
        amountLabel.getStyleClass().add("chart-focus-amount");
        List<String> details = new ArrayList<>(List.of(VolumeUtil.formatVolume(offer, true, 2) + " " + offer.getCounterCurrencyCode()));
        if (offer.isTraditionalOffer()) details.add(OfferViewUtil.getPaymentMethod(offer));
        else if (isInstant(offer)) details.add(Res.get("market.offerBook.chart.instant"));
        long others = (offer.isBuyOffer() ? model.getTopBuyOfferList() : model.getTopSellOfferList()).stream()
                .filter(other -> other.price == item.price)
                .count() - 1;
        if (others > 0) details.add(Res.get("market.offerBook.chart.more", others));
        VBox label = new VBox(amountLabel, new Label(String.join(" \u00B7 ", details)));
        label.getStyleClass().add("chart-focus-label");
        return label;
    }

    private static boolean isInstant(Offer offer) {
        return offer.getPaymentMethod().equals(PaymentMethod.BLOCK_CHAINS_INSTANT);
    }

    // Height in the overlay of a side's outline at x, or NaN where the side draws none.
    private double getOutlineY(List<OfferListItem> offers, OfferDirection direction, double x) {
        if (offers.isEmpty()) return Double.NaN;
        double bestX = deviationScaleX(offers.get(0).price);
        if (direction == OfferDirection.SELL ? x < bestX : x > bestX) return Double.NaN;
        double price = xAxis.getValueForDisplay(xAxis.sceneToLocal(focusPane.localToScene(x, 0)).getX()).doubleValue();
        int index = lastAtOrBetter(offers, direction, price);
        return gridLineY(index < 0 ? 0 : offers.get(index).accumulated);
    }

    // The plot area in the coordinates of the overlay panes, or null before it is laid out.
    private Bounds getPlotBounds() {
        Node plotBackground = areaChart.lookup(".chart-plot-background");
        if (plotBackground == null) return null;
        Bounds plot = focusPane.sceneToLocal(plotBackground.localToScene(plotBackground.getBoundsInLocal()));
        return plot.getWidth() > 0 && plot.getHeight() > 0 ? plot : null;
    }

    // The offers of a table list which the chart plots, best first: those whose point survived the outlier filter.
    private static List<OfferListItem> getPlottedOffers(List<OfferListItem> offers, XYChart.Series<Number, Number> series) {
        Set<Object> plotted = series.getData().stream().map(XYChart.Data::getExtraValue).collect(Collectors.toSet());
        return offers.stream().filter(item -> plotted.contains(item.offer)).collect(Collectors.toList());
    }

    // Index of the last offer priced at or better than the price, in a best first list of one direction, or -1.
    static int lastAtOrBetter(List<OfferListItem> offers, OfferDirection direction, double price) {
        int low = 0;
        int high = offers.size();
        while (low < high) {
            int middle = (low + high) / 2;
            double offerPrice = offers.get(middle).price;
            if (direction == OfferDirection.SELL ? offerPrice <= price : offerPrice >= price) low = middle + 1;
            else high = middle;
        }
        return low - 1;
    }

    // The offer nearest the pointer's price, on a side which reaches that price. Offers within a pixel of its price
    // count as stacked there, where the nearest to the pointer's height takes it.
    static OfferListItem offerNear(List<OfferListItem> asks, List<OfferListItem> bids, double price, double amount, double pricePerPixel) {
        List<OfferListItem> offers = new ArrayList<>();
        if (!asks.isEmpty() && price >= asks.get(0).price) offers.addAll(asks);
        if (!bids.isEmpty() && price <= bids.get(0).price) offers.addAll(bids);
        OfferListItem nearest = offers.stream().min(Comparator.comparingDouble(offer -> Math.abs(offer.price - price))).orElse(null);
        return offers.stream()
                .filter(offer -> Math.abs(offer.price - nearest.price) <= pricePerPixel)
                .min(Comparator.comparingDouble(offer -> Math.abs(offer.accumulated - amount)))
                .orElse(nearest);
    }

    // Top left of a label for a marker: above it toward the spread, then away from the spread, then at the top of
    // the guide, then below the marker. Takes the first spot which at most crosses an outline briefly, else the one
    // lying along them least.
    static Point2D placeLabel(Bounds plot, boolean isSpreadToRight, Point2D marker, double width, double height, DoubleUnaryOperator... outlines) {
        double toward = isSpreadToRight ? marker.getX() + 8 : marker.getX() - 8 - width;
        double away = isSpreadToRight ? marker.getX() - 8 - width : marker.getX() + 8;
        double above = marker.getY() - height - 12;
        double below = marker.getY() + 12;
        double[][] spots = {{toward, above, 0}, {away, above, 0}, {marker.getX() - width / 2, plot.getMinY() + 4, 4}, {toward, below, 4}, {away, below, 4}};
        Point2D best = null;
        double bestScore = Double.MAX_VALUE;
        for (double[] spot : spots) {
            double left = Math.max(plot.getMinX() + 4, Math.min(spot[0], plot.getMaxX() - 4 - width));
            double top = Math.max(plot.getMinY() + 4, Math.min(spot[1], plot.getMaxY() - height - 4));
            double score = spot[2];
            for (DoubleUnaryOperator outline : outlines) {
                double previousY = Double.NaN;
                for (double x = left; x <= left + width; x += 3) {
                    // count the samples where the outline, coming from the previous one, runs through the label
                    double outlineY = outline.applyAsDouble(x);
                    double fromY = Double.isNaN(previousY) ? outlineY : previousY;
                    if (Math.min(outlineY, fromY) <= top + height && Math.max(outlineY, fromY) >= top) score++;
                    previousY = outlineY;
                }
            }
            boolean isOnMarker = marker.getX() >= left - 6 && marker.getX() <= left + width + 6 && marker.getY() >= top - 6 && marker.getY() <= top + height + 6;
            if (isOnMarker) score += 100;
            if (score < bestScore) {
                best = new Point2D(left, top);
                bestScore = score;
            }
            if (score <= 3) break;
        }
        return best;
    }

    private static boolean isPlainClick(MouseEvent event) {
        return event.getButton() == MouseButton.PRIMARY && event.isStillSincePress()
                && !event.isShiftDown() && !event.isControlDown() && !event.isAltDown() && !event.isMetaDown();
    }

    // Opens the offer in the offer book, and keeps it selected here for the way back.
    private void showOffer(OfferListItem offer) {
        selectOffer(offer);
        model.showOffer(offer.offer);
    }

    private OfferListItem getSelectedOffer() {
        OfferListItem selected = sellOfferTableView.getSelectionModel().getSelectedItem();
        return selected != null ? selected : buyOfferTableView.getSelectionModel().getSelectedItem();
    }

    private OfferListItem findOffer(String offerId) {
        return Stream.concat(model.getTopSellOfferList().stream(), model.getTopBuyOfferList().stream())
                .filter(item -> item.offer.getId().equals(offerId))
                .findAny()
                .orElse(null);
    }

    // Selects the offer's row as the one selection of both panels, or clears it. The offer id restores it in refilled lists.
    private void selectOffer(OfferListItem offer) {
        selectedOfferId = offer != null ? offer.offer.getId() : null;
        boolean isBuyOffer = offer != null && offer.offer.isBuyOffer();
        if (offer == null || isBuyOffer) sellOfferTableView.getSelectionModel().clearSelection();
        if (offer == null || !isBuyOffer) buyOfferTableView.getSelectionModel().clearSelection();
        if (offer != null) (isBuyOffer ? buyOfferTableView : sellOfferTableView).getSelectionModel().select(offer);
    }

    // Each side shows from its best price out to its range end. Offers earn the range its width: from any nearer
    // offer, the offer count and the depth must both grow by at least 5% of the relative widening of the range.
    private static final double RANGE_GROWTH_RATIO = 0.05;
    // Distances count from the market price, or the best price without one, and as at least 5% of that price.
    private static final double RANGE_NEAR_PRICE_RATIO = 0.05;
    // The range runs on past the farthest earning offer to show its surroundings: by twice that offer's distance
    // from the market price, or from the best price if that is nearer the other side, at most 7% of its price.
    private static final double RANGE_CONTEXT_RATIO = 2;
    private static final double RANGE_CONTEXT_PRICE_RATIO = 0.07;
    // The range reaches at least the tenth offer of the side, so thin books show in full.
    private static final int RANGE_MIN_OFFERS = 10;
    // The range ends short of 3x the farther of the market price and the best price.
    private static final double RANGE_MAX_PRICE_RATIO = 3;

    static List<XYChart.Data<Number, Number>> filterOutliers(List<XYChart.Data<Number, Number>> data, OfferDirection direction, double marketPrice) {
        synchronized (data) {
            double rangeEnd = getRangeEnd(data, direction, marketPrice);
            return data.stream()
                    .filter(o -> direction == OfferDirection.SELL ? o.getXValue().doubleValue() <= rangeEnd : o.getXValue().doubleValue() >= rangeEnd)
                    .collect(Collectors.toList());
        }
    }

    // The price out to which a side shows, or NaN without offers.
    static double getRangeEnd(List<XYChart.Data<Number, Number>> data, OfferDirection direction, double marketPrice) {
        boolean farSideHigh = direction == OfferDirection.SELL;
        synchronized (data) {
            if (data.isEmpty()) return Double.NaN;
            // The y values accumulate amounts from the best price, so price then depth orders the offers outward.
            Comparator<XYChart.Data<Number, Number>> byPrice = Comparator.comparingDouble(o -> o.getXValue().doubleValue());
            List<XYChart.Data<Number, Number>> outward = new ArrayList<>(data);
            outward.sort((farSideHigh ? byPrice : byPrice.reversed()).thenComparingDouble(o -> o.getYValue().doubleValue()));
            double bestPrice = outward.get(0).getXValue().doubleValue();
            double anchorPrice = Double.isFinite(marketPrice) && marketPrice > 0 ? marketPrice : bestPrice;
            double outerLimit = farSideHigh ? Math.max(bestPrice, anchorPrice) * RANGE_MAX_PRICE_RATIO : Math.min(bestPrice, anchorPrice) / RANGE_MAX_PRICE_RATIO;
            // Offer prices round to eight decimal places; allow half a unit plus floating-point error.
            double tolerance = 0.5e-8 / anchorPrice + 1e-12;
            double[] reach = new double[outward.size()];
            double[] depth = new double[outward.size()];
            double earnedPrice = bestPrice;
            double minOffersPrice = bestPrice;
            for (int k = 0; k < reach.length; k++) {
                double price = outward.get(k).getXValue().doubleValue();
                if (farSideHigh ? price >= outerLimit : price <= outerLimit) break;
                reach[k] = Math.max((farSideHigh ? price - anchorPrice : anchorPrice - price) / anchorPrice, RANGE_NEAR_PRICE_RATIO);
                depth[k] = outward.get(k).getYValue().doubleValue();
                if (earnsWidth(reach, depth, k, tolerance)) earnedPrice = price;
                if (k < RANGE_MIN_OFFERS) minOffersPrice = price;
            }
            double nearPrice = farSideHigh ? Math.min(bestPrice, anchorPrice) : Math.max(bestPrice, anchorPrice);
            double context = Math.min(RANGE_CONTEXT_RATIO * Math.abs(earnedPrice - nearPrice), earnedPrice * RANGE_CONTEXT_PRICE_RATIO);
            double rangeEnd = farSideHigh ? earnedPrice + context : earnedPrice - context;
            return farSideHigh
                    ? Math.min(Math.max(rangeEnd, minOffersPrice), Math.nextDown(outerLimit))
                    : Math.max(Math.min(rangeEnd, minOffersPrice), Math.nextUp(outerLimit));
        }
    }

    // Whether the range out to offer k earns its width from every nearer offer, by offer count and by depth.
    private static boolean earnsWidth(double[] reach, double[] depth, int k, double tolerance) {
        for (int j = 0; j < k; j++) {
            double growth = 1 + RANGE_GROWTH_RATIO * ((reach[k] - tolerance) / (reach[j] + tolerance) - 1);
            if (k + 1 < (j + 1) * growth || depth[k] < depth[j] * growth) return false;
        }
        return true;
    }

    // The price followed by the offer's percent from the market price, as on the offer book.
    private HBox getPriceBox(Offer offer, boolean showPercent) {
        HBox priceBox = new ColoredDecimalPlacesWithZerosText(model.getPrice(offer), model.getZeroDecimalsForPrice(offer));
        String percent = model.getPriceAsPercentage(offer);
        if (showPercent && !percent.isEmpty()) {
            Label percentLabel = new Label("(" + percent + ")");
            percentLabel.getStyleClass().add("offer-percent");
            percentLabel.setOpacity(offer.isUseMarketBasedPrice() ? 1 : 0.6);
            priceBox.getChildren().add(percentLabel);
        }
        return priceBox;
    }

    // Sets an Instant tag after the amount, where the cell has room for both. The amount takes the room of the
    // panel's longest one, so that the tags of all rows line up, where the cell has that room as well.
    private static HBox getInstantBox(HBox amountBox, StringProperty longestAmount) {
        Label longestLabel = new Label();
        longestLabel.textProperty().bind(longestAmount);
        longestLabel.setVisible(false);
        // an amount is drawn in parts, which round up to a few pixels more than the one label
        longestLabel.setPadding(new Insets(0, 4, 0, 0));
        StackPane amountPane = new StackPane(longestLabel, amountBox);
        amountPane.setAlignment(Pos.CENTER_LEFT);
        Label instantTag = new Label(Res.get("market.offerBook.chart.instant"));
        instantTag.getStyleClass().add("offer-instant-tag");
        instantTag.setMinWidth(Region.USE_PREF_SIZE);
        Insets margin = new Insets(0, 0, 0, 8);
        HBox.setMargin(instantTag, margin);
        HBox instantBox = new HBox(amountPane, instantTag) {
            @Override
            protected void layoutChildren() {
                double tagWidth = instantTag.prefWidth(-1) + margin.getLeft();
                longestLabel.setManaged(getWidth() >= longestLabel.prefWidth(-1) + tagWidth);
                boolean fits = getWidth() >= amountPane.prefWidth(-1) + tagWidth;
                instantTag.setVisible(fits);
                instantTag.setManaged(fits);
                super.layoutChildren();
            }
        };
        instantBox.setAlignment(Pos.CENTER_LEFT);
        return instantBox;
    }

    // Sets the column's share of the table width, and its width anew, so that the columns stay in proportion to their shares.
    private static void setWeight(TableColumn<?, ?> column, double weight) {
        column.setPrefWidth(0);
        column.setPrefWidth(weight);
    }

    private Tuple4<TableView<OfferListItem>, VBox, Button, Label> getOfferTable(OfferDirection direction) {
        TableView<OfferListItem> tableView = new TableView<>();
        GUIUtil.applyTableStyle(tableView, false);
        GUIUtil.applyRoundedBottomArc(tableView); // the card's title bar above keeps the header corners square
        tableView.setMinHeight(initialOfferTableViewHeight);
        tableView.setPrefHeight(initialOfferTableViewHeight);
        tableView.setMinWidth(480);
        tableView.setPrefWidth(480); // keeps both tables equally wide, whatever their columns weigh
        tableView.getStyleClass().addAll("offer-table", "non-interactive-table");
        tableView.setRowFactory(table -> {
            TableRow<OfferListItem> row = new TableRow<>();
            ChangeListener<OfferListItem> linkListener = (observable, oldValue, newValue) -> {
                OfferListItem item = row.getItem();
                OfferListItem focused = focusedOffer.get();
                boolean isSameSide = item != null && focused != null && item.offer.getDirection() == focused.offer.getDirection();
                boolean isFocused = isSameSide && item.offer.getId().equals(focused.offer.getId());
                row.pseudoClassStateChanged(LINKED, isFocused);
                row.pseudoClassStateChanged(LINKED_SIBLING, isSameSide && !isFocused && item.price == focused.price);
            };
            // weak, as a refresh drops the table's rows, while the row's own property keeps the listener for its lifetime
            focusedOffer.addListener(new WeakChangeListener<>(linkListener));
            row.itemProperty().addListener(linkListener);
            // after the layout pass which moves the rows, the focus and the terms card follow their offers to the new places
            row.itemProperty().addListener((observable, oldValue, newValue) -> {
                if (row == hoveredRow || termsOfferId != null) scheduleChartOverlaysRedraw();
            });
            row.hoverProperty().addListener((observable, wasHovered, isHovered) -> {
                if (isHovered) hoveredRow = row;
                else if (row == hoveredRow) hoveredRow = null;
                if (tableView == hoverFollowingTable) syncHover();
            });
            row.setOnMouseClicked(event -> {
                if (!isPlainClick(event)) return;
                // the hovered offer, also where the book has since slid another row or none under the resting pointer,
                // and no offer where the hovered one left the book
                OfferListItem offer = hoveredOfferId == null ? row.getItem() : findOffer(hoveredOfferId);
                if (offer != null) showOffer(offer);
            });
            row.itemProperty().addListener((observable, oldValue, newValue) -> glowRow(row));
            return row;
        });
        // Enter opens the selected offer. A filter, as the table's own handling of the key selects the row with its key focus.
        tableView.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() != KeyCode.ENTER) return;
            // opened on release, or the repeats of a held key would fire the default button of the opened offer book
            event.consume();
            isEnterPressed = tableView.getSelectionModel().getSelectedItem() != null;
        });
        tableView.setOnKeyReleased(event -> {
            if (event.getCode() != KeyCode.ENTER) return;
            OfferListItem selected = tableView.getSelectionModel().getSelectedItem();
            if (isEnterPressed && selected != null) showOffer(selected);
            isEnterPressed = false;
        });
        tableView.focusedProperty().addListener((observable, wasFocused, isFocused) -> {
            isEnterPressed = false;
            updateFocus();
        });
        // the other keys take the focus over from a pointer resting on a row, until it moves, and may scroll the rows under it
        tableView.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) selectOffer(null);
            isKeyFocus = true;
            hoverFollowingTable = tableView;
            updateFocus();
        });
        // only these events come from the pointer itself: the hover of a row also changes when rows move under it
        tableView.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            hoverFollowingTable = null;
            if (isKeyFocus) {
                isKeyFocus = false;
                updateFocus();
            }
            syncHover();
        });
        // the table exits before its rows do, which still read as hovered then
        tableView.addEventHandler(MouseEvent.MOUSE_EXITED, event -> setHover(null, null));
        // a drag took the pointer elsewhere without a move
        tableView.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (event.isStillSincePress()) return;
            hoverFollowingTable = null;
            setHover(null, null);
        });
        // a scroll moves the rows from under the terms card, and the hover on to what it brings under the pointer
        tableView.addEventFilter(ScrollEvent.SCROLL, event -> {
            hoverFollowingTable = tableView;
            termsHoverId = null;
            cancelTermsCard();
        });
        // a refilled list moves the rows without a scroll, so the hover stays with its offer again
        (model.isSellOffer(direction) ? model.getTopSellOfferList() : model.getTopBuyOfferList())
                .addListener((ListChangeListener<OfferListItem>) change -> hoverFollowingTable = null);
        tableView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) selectOffer(newValue);
            // a row deselected by hand is still in the list, unlike that of a refilled list, whose selection returns by offer id
            else if (oldValue.offer.getId().equals(selectedOfferId) && tableView.getItems().stream().anyMatch(item -> item == oldValue)) selectedOfferId = null;
            updateFocus();
        });

        // narrow tables drop the percents after the prices, before the volumes or the payment methods truncate
        BooleanBinding showPercents = tableView.widthProperty().greaterThanOrEqualTo(530);

        // price
        TableColumn<OfferListItem, OfferListItem> priceColumn = new TableColumn<>();
        priceColumn.textProperty().bind(priceColumnLabel);
        priceColumn.setMinWidth(60);
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
                                        setGraphic(getPriceBox(offer, showPercents.get()));
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
                                        setGraphic(getPriceBox(offer, showPercents.get()));
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
        volumeColumn.setMinWidth(80);
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
        StringProperty longestAmount = new SimpleStringProperty("");
        (isSellTable ? model.getTopSellOfferList() : model.getTopBuyOfferList()).addListener((ListChangeListener<OfferListItem>) change ->
                longestAmount.set(change.getList().stream()
                        .map(item -> DisplayUtils.formatAmount(item.offer, formatter))
                        .max(Comparator.comparingInt(String::length))
                        .orElse("")));
        TableColumn<OfferListItem, OfferListItem> amountColumn = new TableColumn<>();
        amountColumn.textProperty().bind(isSellTable ? amountSellColumnLabel : amountBuyColumnLabel);
        amountColumn.setMinWidth(70);
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
                                    HBox amountBox = new ColoredDecimalPlacesWithZerosText(amountRange, GUIUtil.AMOUNT_DECIMALS_WITH_ZEROS);
                                    setGraphic(isInstant(offerListItem.offer) ? getInstantBox(amountBox, longestAmount) : amountBox);
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });

        // trader avatar
        TableColumn<OfferListItem, OfferListItem> avatarColumn = new AutoTooltipTableColumn<>(isSellTable ?
                Res.get("shared.sellerUpperCase") : Res.get("shared.buyerUpperCase")) {
            {
                setMinWidth(80);
                setMaxWidth(80);
                setSortable(false);
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

        // payment method, on traditional currency markets
        TableColumn<OfferListItem, OfferListItem> paymentColumn = new AutoTooltipTableColumn<>(Res.get("shared.paymentMethod"));
        paymentColumn.setMinWidth(120);
        paymentColumn.setSortable(false);
        paymentColumn.getStyleClass().add("number-column");
        paymentColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper<>(offer.getValue()));
        paymentColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            public void updateItem(final OfferListItem offerListItem, boolean empty) {
                super.updateItem(offerListItem, empty);
                if (offerListItem != null && !empty) {
                    Offer offer = offerListItem.offer;
                    if (model.isOfferBanned(offer)) {
                        setGraphic(new AutoTooltipLabel(OfferViewUtil.getPaymentMethod(offer)));
                    } else {
                        Label label = new Label(OfferViewUtil.getPaymentMethod(offer));
                        if (!offer.getCombinedExtraInfo().isEmpty()) {
                            // mark the offers whose maker left terms to read, which the icon shows under the pointer
                            Text termsIcon = GlyphsDude.createIcon(MaterialDesignIcon.INFORMATION_OUTLINE, "1em");
                            termsIcon.getStyleClass().add("offer-terms-icon");
                            termsIcon.setPickOnBounds(true);
                            termsIcon.hoverProperty().addListener((observable, wasHovered, isHovered) -> {
                                if (isHovered) termsCell = this;
                                else if (termsCell == this) termsCell = null;
                                if (tableView == hoverFollowingTable) syncHover();
                            });
                            label.setGraphic(termsIcon);
                            label.setContentDisplay(ContentDisplay.RIGHT);
                        }
                        setGraphic(label);
                    }
                } else {
                    setGraphic(null);
                }
            }
        });

        // Share the width among the columns by weights for the market. Crypto volumes are long, while traditional
        // currencies leave room for the payment method.
        BooleanBinding isTraditional = Bindings.createBooleanBinding(
                () -> CurrencyUtil.isTraditionalCurrency(model.getCurrencyCode()), model.selectedTradeCurrencyProperty);
        ChangeListener<Boolean> columnWeigher = (observable, oldValue, newValue) -> {
            setWeight(volumeColumn, isTraditional.get() ? 105 : 190);
            setWeight(amountColumn, isTraditional.get() ? 92 : 130);
            setWeight(priceColumn, showPercents.get() ? 140 : 85);
            setWeight(paymentColumn, 140);
            paymentColumn.setVisible(isTraditional.get());
            tableView.refresh();
        };
        isTraditional.addListener(columnWeigher);
        showPercents.addListener(columnWeigher);
        columnWeigher.changed(null, null, null);

        tableView.getColumns().add(volumeColumn);
        tableView.getColumns().add(amountColumn);
        tableView.getColumns().add(priceColumn);
        tableView.getColumns().add(paymentColumn);
        tableView.getColumns().add(avatarColumn);

        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        Label placeholder = new AutoTooltipLabel();
        placeholder.textProperty().bind(placeholderLabel);
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
        private double bidRangeEnd = Double.NaN, askRangeEnd = Double.NaN;

        DepthChart(NumberAxis xAxis, NumberAxis yAxis, XYChart.Series<Number, Number> bids, XYChart.Series<Number, Number> asks) {
            super(xAxis, yAxis);
            this.bids = bids;
            this.asks = asks;
        }

        // The prices out to which the bids and the asks show, NaN without offers, for the price axis to keep in view.
        void setRangeEnds(double bidRangeEnd, double askRangeEnd) {
            this.bidRangeEnd = bidRangeEnd;
            this.askRangeEnd = askRangeEnd;
        }

        @Override
        protected void updateAxisRange() {
            super.updateAxisRange();
            if (!getXAxis().isAutoRanging()) return;
            List<Number> prices = new ArrayList<>();
            getData().forEach(series -> series.getData().forEach(item -> prices.add(item.getXValue())));
            if (!Double.isNaN(bidRangeEnd)) prices.add(bidRangeEnd);
            if (!Double.isNaN(askRangeEnd)) prices.add(askRangeEnd);
            getXAxis().invalidateRange(prices);
        }

        @Override
        protected void layoutPlotChildren() {
            super.layoutPlotChildren();
            drawDepth(bids, true);
            drawDepth(asks, false);
        }

        // Redraw the series as steps from the plot edge on its open side to the baseline at its best price.
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
            line.getElements().setAll(new MoveTo(startX, openLeft ? first.getY() : baseY));
            fill.getElements().setAll(new MoveTo(startX, baseY));
            Point2D previous = new Point2D(startX, first.getY());
            for (Point2D point : points) {
                // depth only changes at an offer's price, so reach each point through the corner before it
                Point2D corner = openLeft ? new Point2D(previous.getX(), point.getY()) : new Point2D(point.getX(), previous.getY());
                for (Point2D vertex : List.of(corner, point)) {
                    line.getElements().add(new LineTo(vertex.getX(), vertex.getY()));
                    fill.getElements().add(new LineTo(vertex.getX(), vertex.getY()));
                }
                previous = point;
            }
            line.getElements().add(new LineTo(endX, openLeft ? baseY : last.getY()));
            fill.getElements().addAll(new LineTo(endX, last.getY()), new LineTo(endX, baseY), new ClosePath());
        }
    }

}
