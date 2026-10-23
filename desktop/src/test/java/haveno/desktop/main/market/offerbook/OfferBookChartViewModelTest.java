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

import haveno.core.locale.GlobalSettings;
import haveno.core.offer.OfferDirection;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.desktop.main.offer.offerbook.OfferBook;
import haveno.desktop.main.offer.offerbook.OfferBookListItem;
import haveno.desktop.main.offer.offerbook.OfferBookListItemMaker;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.chart.XYChart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;

import static com.natpryce.makeiteasy.MakeItEasy.make;
import static com.natpryce.makeiteasy.MakeItEasy.with;
import static haveno.desktop.main.offer.offerbook.OfferBookListItemMaker.xmrBuyItem;
import static haveno.desktop.main.offer.offerbook.OfferBookListItemMaker.xmrSellItem;
import static haveno.desktop.maker.PreferenceMakers.empty;
import static haveno.desktop.maker.TradeCurrencyMakers.usd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class OfferBookChartViewModelTest {

    @BeforeEach
    public void setUp() {
        GlobalSettings.setDefaultTradeCurrency(usd);
    }

    @Test
    public void testMaxCharactersForBuyPriceWithNoOffers() {
        OfferBook offerBook = mock(OfferBook.class);
        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();

        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, null, null, null);
        assertEquals(0, model.maxPlacesForBuyPrice.intValue());
    }

    @Test
    public void testMaxCharactersForBuyPriceWithOfflinePriceFeedService() {
        OfferBook offerBook = mock(OfferBook.class);
        PriceFeedService priceFeedService = mock(PriceFeedService.class);


        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();
        final OfferBookListItem item = make(OfferBookListItemMaker.xmrBuyItem.but(with(OfferBookListItemMaker.useMarketBasedPrice, true)));
        item.getOffer().setPriceFeedService(priceFeedService);
        offerBookListItems.addAll(item);

        when(priceFeedService.getMarketPrice(anyString())).thenReturn(null);
        when(priceFeedService.updateCounterProperty()).thenReturn(new SimpleIntegerProperty());
        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, priceFeedService, null, null);
        model.activate();
        assertEquals(0, model.maxPlacesForBuyPrice.intValue());
    }

    @Test
    public void testMaxCharactersForFiatBuyPrice() {
        OfferBook offerBook = mock(OfferBook.class);
        PriceFeedService service = mock(PriceFeedService.class);
        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();
        offerBookListItems.addAll(make(OfferBookListItemMaker.xmrBuyItem));

        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, service, null, null);
        model.activate();
        assertEquals(9, model.maxPlacesForBuyPrice.intValue());
        offerBookListItems.addAll(make(xmrBuyItem.but(with(OfferBookListItemMaker.price, 940164750000L))));
        assertEquals(9, model.maxPlacesForBuyPrice.intValue()); // 9401.6475
        offerBookListItems.addAll(make(xmrBuyItem.but(with(OfferBookListItemMaker.price, 1010164750000L))));
        assertEquals(10, model.maxPlacesForBuyPrice.intValue()); //10101.6475
    }

    @Test
    public void testMaxCharactersForBuyVolumeWithNoOffers() {
        OfferBook offerBook = mock(OfferBook.class);
        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();

        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, null, null, null);
        assertEquals(0, model.maxPlacesForBuyVolume.intValue());
    }

    @Test
    public void testMaxCharactersForFiatBuyVolume() {
        OfferBook offerBook = mock(OfferBook.class);
        PriceFeedService service = mock(PriceFeedService.class);
        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();
        offerBookListItems.addAll(make(OfferBookListItemMaker.xmrBuyItem));

        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, service, null, null);
        model.activate();
        assertEquals(3, model.maxPlacesForBuyVolume.intValue()); //0
        offerBookListItems.addAll(make(xmrBuyItem.but(with(OfferBookListItemMaker.amount, 1000000000000L))));
        assertEquals(4, model.maxPlacesForBuyVolume.intValue()); //10
        offerBookListItems.addAll(make(xmrBuyItem.but(with(OfferBookListItemMaker.amount, 221286000000000L))));
        assertEquals(6, model.maxPlacesForBuyVolume.intValue()); //2213
    }

    @Test
    public void testMaxCharactersForSellPriceWithNoOffers() {
        OfferBook offerBook = mock(OfferBook.class);
        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();

        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, null, null, null);
        assertEquals(0, model.maxPlacesForSellPrice.intValue());
    }

    @Test
    public void testMaxCharactersForSellPriceWithOfflinePriceFeedService() {
        OfferBook offerBook = mock(OfferBook.class);
        PriceFeedService priceFeedService = mock(PriceFeedService.class);


        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();
        final OfferBookListItem item = make(OfferBookListItemMaker.xmrSellItem.but(with(OfferBookListItemMaker.useMarketBasedPrice, true)));
        item.getOffer().setPriceFeedService(priceFeedService);
        offerBookListItems.addAll(item);

        when(priceFeedService.getMarketPrice(anyString())).thenReturn(null);
        when(priceFeedService.updateCounterProperty()).thenReturn(new SimpleIntegerProperty());
        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, priceFeedService, null, null);
        model.activate();
        assertEquals(0, model.maxPlacesForSellPrice.intValue());
    }

    @Test
    public void testMaxCharactersForFiatSellPrice() {
        OfferBook offerBook = mock(OfferBook.class);
        PriceFeedService service = mock(PriceFeedService.class);
        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();
        offerBookListItems.addAll(make(OfferBookListItemMaker.xmrSellItem));

        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, service, null, null);
        model.activate();
        assertEquals(9, model.maxPlacesForSellPrice.intValue()); // 10.0000 default price
        offerBookListItems.addAll(make(xmrSellItem.but(with(OfferBookListItemMaker.price, 940164750000L))));
        assertEquals(9, model.maxPlacesForSellPrice.intValue()); // 9401.6475
        offerBookListItems.addAll(make(xmrSellItem.but(with(OfferBookListItemMaker.price, 1010164750000L))));
        assertEquals(10, model.maxPlacesForSellPrice.intValue()); // 10101.6475
    }

    @Test
    public void testMaxCharactersForSellVolumeWithNoOffers() {
        OfferBook offerBook = mock(OfferBook.class);
        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();

        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, null, null, null);
        assertEquals(0, model.maxPlacesForSellVolume.intValue());
    }

    @Test
    public void testMaxCharactersForFiatSellVolume() {
        OfferBook offerBook = mock(OfferBook.class);
        PriceFeedService service = mock(PriceFeedService.class);
        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();
        offerBookListItems.addAll(make(OfferBookListItemMaker.xmrSellItem));

        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, service, null, null);
        model.activate();
        assertEquals(3, model.maxPlacesForSellVolume.intValue()); //0
        offerBookListItems.addAll(make(xmrSellItem.but(with(OfferBookListItemMaker.amount, 1000000000000L))));
        assertEquals(4, model.maxPlacesForSellVolume.intValue()); //10
        offerBookListItems.addAll(make(xmrSellItem.but(with(OfferBookListItemMaker.amount, 221286000000000L))));
        assertEquals(6, model.maxPlacesForSellVolume.intValue()); //2213
    }

    @Test
    public void testFarSellOffersHidden() {
        List<Double> shown = filter(OfferDirection.SELL, 101, 102, 103, 104, 105, 106, 107, 108, 150, 200);
        assertEquals(List.of(101d, 102d, 103d, 104d, 105d, 106d, 107d, 108d), shown);
    }

    @Test
    public void testFarBuyOffersHidden() {
        List<Double> shown = filter(OfferDirection.BUY, 10, 50, 92, 93, 94, 95, 96, 97, 98, 99);
        assertEquals(List.of(92d, 93d, 94d, 95d, 96d, 97d, 98d, 99d), shown);
    }

    @Test
    public void testThinBookRespectsOuterLimit() {
        assertEquals(List.of(103d, 120d, 180d), filter(OfferDirection.SELL, 103, 120, 180));
        assertEquals(List.of(97d), filter(OfferDirection.BUY, 20, 97));
        assertEquals(List.of(100d, 101d, 102d, 299d), filter(OfferDirection.SELL, 100, 101, 102, 299, 300));
        assertEquals(List.of(101d, 298d, 299d, 300d), filter(OfferDirection.BUY, 100, 101, 298, 299, 300));
    }

    @Test
    public void testThinBooksKeepModeratelyDistantOffers() {
        for (double marketPrice : new double[]{0, 100}) {
            assertEquals(List.of(100d, 101d, 102d, 125d), filterAtMarket(OfferDirection.SELL, marketPrice, 100, 101, 102, 125));
            assertEquals(List.of(75d, 98d, 99d, 100d), filterAtMarket(OfferDirection.BUY, marketPrice, 75, 98, 99, 100));
            for (int size = 4; size < 8; size++) {
                double[] sell = new double[size];
                double[] buy = new double[size];
                for (int i = 0; i < size - 1; i++) {
                    sell[i] = 100 + i;
                    buy[i + 1] = 100 - (size - 2) + i;
                }
                sell[size - 1] = 150;
                buy[0] = 50;
                assertEquals(DoubleStream.of(sell).boxed().collect(Collectors.toList()), filterAtMarket(OfferDirection.SELL, marketPrice, sell));
                assertEquals(DoubleStream.of(buy).boxed().collect(Collectors.toList()), filterAtMarket(OfferDirection.BUY, marketPrice, buy));
            }
        }
    }

    @Test
    public void testStatisticalTrimmingStartsWithEightOffers() {
        for (double marketPrice : new double[]{0, 100}) {
            assertEquals(List.of(100d, 100d, 100d, 100d, 100d, 100d, 150d),
                    filterAtMarket(OfferDirection.SELL, marketPrice, 100, 100, 100, 100, 100, 100, 150));
            assertEquals(List.of(50d, 100d, 100d, 100d, 100d, 100d, 100d),
                    filterAtMarket(OfferDirection.BUY, marketPrice, 50, 100, 100, 100, 100, 100, 100));
            assertEquals(List.of(100d, 100d, 100d, 100d, 100d, 100d, 100d),
                    filterAtMarket(OfferDirection.SELL, marketPrice, 100, 100, 100, 100, 100, 100, 100, 150));
            assertEquals(List.of(100d, 100d, 100d, 100d, 100d, 100d, 100d),
                    filterAtMarket(OfferDirection.BUY, marketPrice, 50, 100, 100, 100, 100, 100, 100, 100));
        }
    }

    @Test
    public void testEvenSpreadShownInFull() {
        assertEquals(List.of(100d, 120d, 140d, 160d, 180d, 200d), filter(OfferDirection.SELL, 100, 120, 140, 160, 180, 200));
    }

    @Test
    public void testTightClusterKeepsNearbyOffers() {
        assertEquals(List.of(102d, 102d, 102d, 102d, 102d, 102d, 106d, 130d), filter(OfferDirection.SELL, 102, 102, 102, 102, 102, 102, 106, 130, 140));
    }

    @Test
    public void testEmptyAndSingle() {
        assertEquals(List.of(), filter(OfferDirection.SELL));
        assertEquals(List.of(105d), filter(OfferDirection.SELL, 105));
    }

    @Test
    public void testExtremeOffersHiddenFromThinBooks() {
        assertEquals(List.of(100d, 101d), filter(OfferDirection.SELL, 100, 101, 10000));
        assertEquals(List.of(99d, 100d), filter(OfferDirection.BUY, 1, 99, 100));
        assertEquals(List.of(100d, 101d, 102d), filter(OfferDirection.SELL, 100, 101, 102, 10000));
        assertEquals(List.of(98d, 99d, 100d), filter(OfferDirection.BUY, 1, 98, 99, 100));
    }

    @Test
    public void testRepeatedExtremeOffersHidden() {
        assertEquals(List.of(100d, 101d, 102d), filter(OfferDirection.SELL, 100, 101, 102, 10000, 10000));
        assertEquals(List.of(98d, 99d, 100d), filter(OfferDirection.BUY, 1, 1, 98, 99, 100));
    }

    @Test
    public void testOuterLimitIsExclusive() {
        assertEquals(List.of(100d, 299d), filter(OfferDirection.SELL, 100, 299, 300));
        assertEquals(List.of(101d, 300d), filter(OfferDirection.BUY, 100, 101, 300));
        assertEquals(List.of(100d, 299d), filterAtMarket(OfferDirection.SELL, 120, 100, 299, 300));
        assertEquals(List.of(101d, 300d), filterAtMarket(OfferDirection.BUY, 250, 100, 101, 300));
    }

    @Test
    public void testOuterLimitBindsWhenFenceIsWider() {
        assertEquals(List.of(100d, 150d, 200d, 250d), filter(OfferDirection.SELL, 100, 150, 200, 250, 300, 350, 400, 450));
        assertEquals(List.of(200d, 250d, 300d, 350d, 400d, 450d), filter(OfferDirection.BUY, 100, 150, 200, 250, 300, 350, 400, 450));
    }

    @Test
    public void testIqrFenceHidesOffersInsideOuterLimit() {
        assertEquals(List.of(100d, 110d, 120d, 130d, 140d, 150d, 160d, 170d),
                filter(OfferDirection.SELL, 100, 110, 120, 130, 140, 150, 160, 170, 250));
        assertEquals(List.of(80d, 84d, 88d, 92d, 96d, 100d, 104d, 108d),
                filter(OfferDirection.BUY, 50, 80, 84, 88, 92, 96, 100, 104, 108));
    }

    @Test
    public void testMarketNeighborhoodKeepsNearbyOffers() {
        assertEquals(List.of(100d, 100d, 100d, 100d, 100d, 100d, 130d),
                filterAtMarket(OfferDirection.SELL, 100, 100, 100, 100, 100, 100, 100, 130, 200));
        assertEquals(List.of(70d, 100d, 100d, 100d, 100d, 100d, 100d),
                filterAtMarket(OfferDirection.BUY, 100, 1, 70, 100, 100, 100, 100, 100, 100));
        assertEquals(List.of(100d, 100d, 100d, 100d, 100d, 100d),
                filterAtMarket(OfferDirection.SELL, 100, 100, 100, 100, 100, 100, 100, 131, 200));
        assertEquals(List.of(100d, 100d, 100d, 100d, 100d, 100d),
                filterAtMarket(OfferDirection.BUY, 100, 1, 69, 100, 100, 100, 100, 100, 100));
    }

    @Test
    public void testMarketNeighborhoodOverridesMisleadingBestPrice() {
        assertEquals(List.of(30d, 100d, 110d, 120d, 125d, 130d),
                filterAtMarket(OfferDirection.SELL, 100, 30, 100, 110, 120, 125, 130));
        assertEquals(List.of(70d, 75d, 80d, 90d, 100d, 400d),
                filterAtMarket(OfferDirection.BUY, 100, 70, 75, 80, 90, 100, 400));
        assertEquals(List.of(10d, 60d, 100d, 110d, 120d, 125d),
                filterAtMarket(OfferDirection.SELL, 100, 10, 60, 100, 110, 120, 125));
        assertEquals(List.of(75d, 80d, 90d, 100d, 200d, 1000d),
                filterAtMarket(OfferDirection.BUY, 100, 75, 80, 90, 100, 200, 1000));
        assertEquals(List.of(30d, 100d, 130d), filterAtMarket(OfferDirection.SELL, 100, 30, 100, 130, 131));
        assertEquals(List.of(70d, 100d, 400d), filterAtMarket(OfferDirection.BUY, 100, 69, 70, 100, 400));
    }

    @Test
    public void testMarketNeighborhoodStillUsesIqrFence() {
        assertEquals(List.of(30d, 100d, 105d, 110d, 115d, 120d, 125d),
                filterAtMarket(OfferDirection.SELL, 100, 30, 100, 105, 110, 115, 120, 125, 250));
        assertEquals(List.of(75d, 80d, 85d, 90d, 95d, 100d, 400d),
                filterAtMarket(OfferDirection.BUY, 100, 40, 75, 80, 85, 90, 95, 100, 400));
    }

    @Test
    public void testIncorrectMarketPriceDoesNotHideWholeSide() {
        assertEquals(List.of(100d, 101d, 102d), filterAtMarket(OfferDirection.SELL, 1, 100, 101, 102));
        assertEquals(List.of(98d, 99d, 100d), filterAtMarket(OfferDirection.BUY, 10000, 98, 99, 100));
    }

    @Test
    public void testMarketNeighborhoodIncludesRoundedBoundaries() {
        for (double[] prices : new double[][]{
                {100.10, 130.13, 70.07},
                {100.01, 130.013, 70.007},
                {100.123456789, 130.16049383, 70.08641975},
                {0.003000006, 0.00390001, 0.0021},
                {0.0030000125, 0.00390002, 0.00210001},
                {0.000100004, 0.00013001, 0.00007}}) {
            double market = prices[0];
            double sell = prices[1];
            double buy = prices[2];
            assertEquals(List.of(market, market, market, market, market, market, sell),
                    filterAtMarket(OfferDirection.SELL, market, market, market, market, market, market, market, sell, market * 2));
            assertEquals(List.of(buy, market, market, market, market, market, market),
                    filterAtMarket(OfferDirection.BUY, market, market / 100, buy, market, market, market, market, market, market));
            assertEquals(List.of(market, market, market, market, market, market),
                    filterAtMarket(OfferDirection.SELL, market, market, market, market, market, market, market, sell + 1e-8, market * 2));
            assertEquals(List.of(market, market, market, market, market, market),
                    filterAtMarket(OfferDirection.BUY, market, market / 100, buy - 1e-8, market, market, market, market, market, market));
        }
    }

    @Test
    public void testNoMarketPriceKeepsOffersNearBestPrice() {
        assertEquals(List.of(100d, 100d, 100d, 100d, 100d, 100d, 125d), filter(OfferDirection.SELL, 100, 100, 100, 100, 100, 100, 125, 135));
        assertEquals(List.of(75d, 100d, 100d, 100d, 100d, 100d, 100d), filter(OfferDirection.BUY, 65, 75, 100, 100, 100, 100, 100, 100));
    }

    @Test
    public void testMarketPriceRequiresRecentFeedPrice() {
        OfferBook offerBook = mock(OfferBook.class);
        PriceFeedService priceFeedService = mock(PriceFeedService.class);
        when(offerBook.getOfferBookListItems()).thenReturn(FXCollections.observableArrayList());
        when(priceFeedService.updateCounterProperty()).thenReturn(new SimpleIntegerProperty());
        OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, priceFeedService, null, null);
        long now = System.currentTimeMillis();

        when(priceFeedService.getMarketPrice("USD")).thenReturn(new MarketPrice("USD", 100, now, true));
        assertEquals(Optional.of(100d), model.getMarketPriceAsDouble());
        when(priceFeedService.getMarketPrice("USD")).thenReturn(new MarketPrice("USD", 100, now, false));
        assertEquals(Optional.empty(), model.getMarketPriceAsDouble());
        when(priceFeedService.getMarketPrice("USD")).thenReturn(new MarketPrice("USD", 100, now - MarketPrice.MARKET_PRICE_MAX_AGE_MS - 1000, true));
        assertEquals(Optional.empty(), model.getMarketPriceAsDouble());
    }

    @Test
    public void testInvalidMarketPriceUsesBookFences() {
        for (double marketPrice : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals(List.of(100d, 101d), filterAtMarket(OfferDirection.SELL, marketPrice, 100, 101, 10000));
            assertEquals(List.of(99d, 100d), filterAtMarket(OfferDirection.BUY, marketPrice, 1, 99, 100));
        }
    }

    @Test
    public void testFilteringPreservesPointsAndCumulativeAmounts() {
        List<XYChart.Data<Number, Number>> data = new ArrayList<>(List.of(
                new XYChart.Data<>(101, 3), new XYChart.Data<>(100, 1),
                new XYChart.Data<>(10000, 7), new XYChart.Data<>(102, 4)));
        List<XYChart.Data<Number, Number>> original = new ArrayList<>(data);
        List<XYChart.Data<Number, Number>> shown = OfferBookChartView.filterOutliers(data, OfferDirection.SELL, 100);
        assertEquals(original, data);
        assertEquals(List.of(original.get(0), original.get(1), original.get(3)), shown);
        assertEquals(List.of(3, 1, 4), shown.stream().map(XYChart.Data::getYValue).collect(Collectors.toList()));
    }

    private static List<Double> filter(OfferDirection direction, double... prices) {
        return filterAtMarket(direction, 0, prices);
    }

    private static List<Double> filterAtMarket(OfferDirection direction, double marketPrice, double... prices) {
        List<XYChart.Data<Number, Number>> data = DoubleStream.of(prices)
                .mapToObj(price -> new XYChart.Data<Number, Number>(price, 1))
                .collect(Collectors.toList());
        return OfferBookChartView.filterOutliers(data, direction, marketPrice).stream()
                .map(d -> d.getXValue().doubleValue())
                .collect(Collectors.toList());
    }
}
