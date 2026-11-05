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
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.util.FormattingUtils;
import haveno.core.util.PriceUtil;
import haveno.desktop.main.offer.offerbook.OfferBook;
import haveno.desktop.main.offer.offerbook.OfferBookListItem;
import haveno.desktop.main.offer.offerbook.OfferBookListItemMaker;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.chart.XYChart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;

import static com.natpryce.makeiteasy.MakeItEasy.make;
import static com.natpryce.makeiteasy.MakeItEasy.with;
import static haveno.desktop.main.offer.offerbook.OfferBookListItemMaker.xmrBuyItem;
import static haveno.desktop.main.offer.offerbook.OfferBookListItemMaker.xmrSellItem;
import static haveno.desktop.maker.PreferenceMakers.empty;
import static haveno.desktop.maker.TradeCurrencyMakers.usd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

        final OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, null, null, null, null, null);
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

        final OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, priceFeedService, null, null, null, null);
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

        final OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, service, null, null, null, null);
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

        final OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, null, null, null, null, null);
        assertEquals(0, model.maxPlacesForBuyVolume.intValue());
    }

    @Test
    public void testMaxCharactersForFiatBuyVolume() {
        OfferBook offerBook = mock(OfferBook.class);
        PriceFeedService service = mock(PriceFeedService.class);
        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();
        offerBookListItems.addAll(make(OfferBookListItemMaker.xmrBuyItem));

        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, service, null, null, null, null);
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

        final OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, null, null, null, null, null);
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

        final OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, priceFeedService, null, null, null, null);
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

        final OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, service, null, null, null, null);
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

        final OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, null, null, null, null, null);
        assertEquals(0, model.maxPlacesForSellVolume.intValue());
    }

    @Test
    public void testMaxCharactersForFiatSellVolume() {
        OfferBook offerBook = mock(OfferBook.class);
        PriceFeedService service = mock(PriceFeedService.class);
        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList();
        offerBookListItems.addAll(make(OfferBookListItemMaker.xmrSellItem));

        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, service, null, null, null, null);
        model.activate();
        assertEquals(3, model.maxPlacesForSellVolume.intValue()); //0
        offerBookListItems.addAll(make(xmrSellItem.but(with(OfferBookListItemMaker.amount, 1000000000000L))));
        assertEquals(4, model.maxPlacesForSellVolume.intValue()); //10
        offerBookListItems.addAll(make(xmrSellItem.but(with(OfferBookListItemMaker.amount, 221286000000000L))));
        assertEquals(6, model.maxPlacesForSellVolume.intValue()); //2213
    }

    @Test
    public void testThinBooksShownInFull() {
        double[] sell = {2.30713985, 2.55905096, 2.55905096, 2.55905096, 2.56596791, 2.56596791, 3.3246019, 3.3246019};
        double[] sellAmounts = {0.2, 3.975, 1.491, 1.122, 1.5, 1.5, 0.45, 0.45};
        double[] buy = {1.338989, 1.78524428, 2.00837193, 2.14224852, 2.15318177, 2.22904517};
        double[] buyAmounts = {0.1999, 0.4999, 0.9999, 9.9999, 130.9999, 100};
        for (double marketPrice : new double[]{0, 2.23127645}) {
            assertEquals(DoubleStream.of(sell).boxed().collect(Collectors.toList()), filterAtMarket(OfferDirection.SELL, marketPrice, sell, sellAmounts));
            assertEquals(DoubleStream.of(buy).boxed().collect(Collectors.toList()), filterAtMarket(OfferDirection.BUY, marketPrice, buy, buyAmounts));
        }
    }

    @Test
    public void testNearestTenOffersAlwaysShown() {
        double[] sell = {100, 100, 100, 100, 100, 100, 100, 100, 100, 250};
        double[] sellAmounts = {5, 5, 5, 5, 5, 5, 5, 5, 5, 0.001};
        double[] buy = {40, 100, 100, 100, 100, 100, 100, 100, 100, 100};
        double[] buyAmounts = {0.001, 5, 5, 5, 5, 5, 5, 5, 5, 5};
        assertEquals(DoubleStream.of(sell).boxed().collect(Collectors.toList()), filterAtMarket(OfferDirection.SELL, 100, sell, sellAmounts));
        assertEquals(DoubleStream.of(buy).boxed().collect(Collectors.toList()), filterAtMarket(OfferDirection.BUY, 100, buy, buyAmounts));
        assertEquals(shown(OfferDirection.SELL), filterTail(OfferDirection.SELL, 100, 5, new double[]{250}, new double[]{0.001}));
        assertEquals(shown(OfferDirection.BUY), filterTail(OfferDirection.BUY, 100, 5, new double[]{40}, new double[]{0.001}));
    }

    @Test
    public void testLightTailHidden() {
        for (double marketPrice : new double[]{0, 100}) {
            assertEquals(shown(OfferDirection.SELL), filterTail(OfferDirection.SELL, marketPrice, 5, new double[]{108, 120}, new double[]{0.1, 0.1}));
            assertEquals(shown(OfferDirection.BUY), filterTail(OfferDirection.BUY, marketPrice, 5, new double[]{92, 80}, new double[]{0.1, 0.1}));
        }
    }

    @Test
    public void testTailWithDepthShown() {
        for (double marketPrice : new double[]{0, 100}) {
            assertEquals(shown(OfferDirection.SELL, 108, 112, 120),
                    filterTail(OfferDirection.SELL, marketPrice, 5, new double[]{108, 112, 120}, new double[]{4, 4, 8}));
            assertEquals(shown(OfferDirection.BUY, 92, 88, 80),
                    filterTail(OfferDirection.BUY, marketPrice, 5, new double[]{92, 88, 80}, new double[]{4, 4, 8}));
        }
    }

    @Test
    public void testRangeEndShowsSurroundings() {
        for (OfferDirection direction : OfferDirection.values()) {
            boolean sell = direction == OfferDirection.SELL;
            // offers earning the range out to 20% of the market price add 6% of their price, which shows light offers
            double[] earning = sell ? new double[]{108, 112, 120} : new double[]{92, 88, 80};
            double[] light = sell ? new double[]{108, 112, 120, 126, 128} : new double[]{92, 88, 80, 76, 75};
            double[] lightAmounts = {4, 4, 8, 0.001, 0.001};
            double[] shownLight = sell ? new double[]{108, 112, 120, 126} : new double[]{92, 88, 80, 76};
            double rangeEnd = sell ? 127.2 : 75.2;
            assertEquals(shown(direction, earning), filterTail(direction, 100, 5, earning, new double[]{4, 4, 8}));
            assertEquals(shown(direction, shownLight), filterTail(direction, 100, 5, light, lightAmounts));
            assertEquals(rangeEnd, rangeEnd(direction, 100, withCore(direction, 100, earning), withCore(direction, 5, 4, 4, 8)), 1e-9);
            assertEquals(rangeEnd, rangeEnd(direction, 100, withCore(direction, 100, light), withCore(direction, 5, lightAmounts)), 1e-9);
            // offers earning the range out to 2% add twice as much
            double[] tight = sell ? new double[]{102, 107} : new double[]{98, 93};
            assertEquals(shown(direction, tight[0]), filterTail(direction, 100, 5, tight, new double[]{5, 0.001}));
            assertEquals(sell ? 106 : 94, rangeEnd(direction, 100, withCore(direction, 100, tight), withCore(direction, 5, 5, 0.001)), 1e-9);
        }
    }

    @Test
    public void testRangeEndMeasuresFromMarketPrice() {
        // the range past the earning offers does not depend on how near the market the best price is
        double[] amounts = {5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5};
        assertEquals(106, rangeEnd(OfferDirection.SELL, 100, new double[]{100, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102}, amounts), 1e-9);
        assertEquals(106, rangeEnd(OfferDirection.SELL, 100, new double[]{101, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102}, amounts), 1e-9);
        assertEquals(94, rangeEnd(OfferDirection.BUY, 100, new double[]{98, 98, 98, 98, 98, 98, 98, 98, 98, 98, 100}, amounts), 1e-9);
        assertEquals(94, rangeEnd(OfferDirection.BUY, 100, new double[]{98, 98, 98, 98, 98, 98, 98, 98, 98, 98, 99}, amounts), 1e-9);
        // without a market price, or with offers on its other side, it measures from the best price
        assertEquals(104, rangeEnd(OfferDirection.SELL, 0, new double[]{101, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102}, amounts), 1e-9);
        assertEquals(104, rangeEnd(OfferDirection.SELL, 101.5, new double[]{101, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102}, amounts), 1e-9);
    }

    @Test
    public void testRangeEndWithoutOffers() {
        assertEquals(Double.NaN, rangeEnd(OfferDirection.SELL, 100, new double[0], new double[0]));
        assertEquals(Double.NaN, rangeEnd(OfferDirection.BUY, 100, new double[0], new double[0]));
        assertEquals(105, rangeEnd(OfferDirection.SELL, 0, new double[]{105}, new double[]{1}));
        assertEquals(95, rangeEnd(OfferDirection.BUY, 0, new double[]{95}, new double[]{1}));
    }

    @Test
    public void testFarOffersNeedOfferCountAndDepth() {
        for (OfferDirection direction : OfferDirection.values()) {
            double price = direction == OfferDirection.SELL ? 120 : 80;
            assertEquals(shown(direction), filterTail(direction, 100, 1, new double[]{price}, new double[]{100}));
            assertEquals(shown(direction), filterTail(direction, 100, 1, new double[]{price, price}, new double[]{0.1, 0.1}));
            assertEquals(shown(direction, price, price), filterTail(direction, 100, 1, new double[]{price, price}, new double[]{1, 1}));
        }
    }

    @Test
    public void testEvenSpreadShownInFull() {
        double[] sell = DoubleStream.iterate(100, price -> price + 5).limit(21).toArray();
        double[] buy = DoubleStream.iterate(50, price -> price + 2.5).limit(21).toArray();
        assertEquals(DoubleStream.of(sell).boxed().collect(Collectors.toList()), filter(OfferDirection.SELL, sell));
        assertEquals(DoubleStream.of(buy).boxed().collect(Collectors.toList()), filter(OfferDirection.BUY, buy));
    }

    @Test
    public void testOffersNearMarketPriceEarnTheRange() {
        // a light offer within 5% of the market price earns the range, the same offer at 7% from a lower one does not
        assertEquals(110.24, rangeEnd(OfferDirection.SELL, 100, withCore(OfferDirection.SELL, 100, 104), withCore(OfferDirection.SELL, 5, 0.001)), 1e-9);
        assertEquals(106, rangeEnd(OfferDirection.SELL, 97, withCore(OfferDirection.SELL, 100, 104), withCore(OfferDirection.SELL, 5, 0.001)), 1e-9);
        assertEquals(90.24, rangeEnd(OfferDirection.BUY, 100, withCore(OfferDirection.BUY, 100, 96), withCore(OfferDirection.BUY, 5, 0.001)), 1e-9);
        assertEquals(94, rangeEnd(OfferDirection.BUY, 103, withCore(OfferDirection.BUY, 100, 96), withCore(OfferDirection.BUY, 5, 0.001)), 1e-9);
    }

    @Test
    public void testNoMarketPriceMeasuresFromBestPrice() {
        assertEquals(shown(OfferDirection.SELL, 104), filterTail(OfferDirection.SELL, 0, 5, new double[]{104, 113}, new double[]{0.001, 0.001}));
        assertEquals(shown(OfferDirection.BUY, 96), filterTail(OfferDirection.BUY, 0, 5, new double[]{96, 88}, new double[]{0.001, 0.001}));
    }

    @Test
    public void testEqualPricesStayTogether() {
        assertEquals(shown(OfferDirection.SELL), filterTail(OfferDirection.SELL, 100, 1, new double[]{110}, new double[]{0.3}));
        assertEquals(shown(OfferDirection.SELL, 110, 110), filterTail(OfferDirection.SELL, 100, 1, new double[]{110, 110}, new double[]{0.3, 0.3}));
        assertEquals(shown(OfferDirection.BUY, 90, 90), filterTail(OfferDirection.BUY, 100, 1, new double[]{90, 90}, new double[]{0.3, 0.3}));
    }

    @Test
    public void testGrowthThresholdIncludesRoundedPrices() {
        for (double market : new double[]{100, 100.1, 100.123456789, 0.00793225, 0.0030000125, 0.000100004, 4000000.5}) {
            double[] sell = new double[12];
            double[] buy = new double[12];
            Arrays.fill(sell, market);
            Arrays.fill(buy, market);
            sell[10] = sell[11] = Math.round(market * 1.25 * 1e8) / 1e8;
            buy[0] = buy[1] = Math.round(market * 0.75 * 1e8) / 1e8;
            assertEquals(12, filterAtMarket(OfferDirection.SELL, market, sell).size());
            assertEquals(12, filterAtMarket(OfferDirection.BUY, market, buy).size());
            sell[10] = sell[11] = sell[11] * 1.001;
            buy[0] = buy[1] = buy[1] * 0.999;
            assertEquals(10, filterAtMarket(OfferDirection.SELL, market, sell).size());
            assertEquals(10, filterAtMarket(OfferDirection.BUY, market, buy).size());
        }
    }

    @Test
    public void testOfferBeyondRangeLeavesChartUnchanged() {
        Random random = new Random(1);
        int hidden = 0;
        for (int i = 0; i < 2000; i++) {
            OfferDirection direction = random.nextBoolean() ? OfferDirection.SELL : OfferDirection.BUY;
            boolean sell = direction == OfferDirection.SELL;
            double marketPrice = random.nextBoolean() ? 100 : 0;
            int size = 1 + random.nextInt(40);
            double[] prices = random.doubles(size, 0, 0.3).map(distance -> 100 * (sell ? 1 + distance : 1 - distance)).sorted().toArray();
            double[] amounts = random.doubles(size).map(amount -> Math.exp(3 * amount)).toArray();
            List<Double> before = filterAtMarket(direction, marketPrice, prices, amounts);
            double rangeEnd = rangeEnd(direction, marketPrice, prices, amounts);
            double stretch = 1.01 + random.nextDouble();
            double newPrice = sell ? rangeEnd * stretch : rangeEnd / stretch;
            double[] newPrices = DoubleStream.concat(DoubleStream.of(prices), DoubleStream.of(newPrice)).sorted().toArray();
            int newIndex = Arrays.binarySearch(newPrices, newPrice);
            double[] newAmounts = new double[size + 1];
            for (int j = 0; j <= size; j++) newAmounts[j] = j == newIndex ? Math.exp(3 * random.nextDouble()) : amounts[j < newIndex ? j : j - 1];
            List<Double> after = filterAtMarket(direction, marketPrice, newPrices, newAmounts);
            if (after.contains(newPrice)) continue;
            hidden++;
            assertEquals(before, after);
            assertEquals(rangeEnd, rangeEnd(direction, marketPrice, newPrices, newAmounts));
        }
        assertTrue(hidden > 200);
    }

    @Test
    public void testEmptyAndSingle() {
        assertEquals(List.of(), filter(OfferDirection.SELL));
        assertEquals(List.of(105d), filter(OfferDirection.SELL, 105));
        assertEquals(List.of(105d), filterAtMarket(OfferDirection.SELL, 100, 105));
        assertEquals(List.of(95d), filterAtMarket(OfferDirection.BUY, 100, 95));
    }

    @Test
    public void testExtremeOffersHidden() {
        assertEquals(List.of(100d, 101d), filter(OfferDirection.SELL, 100, 101, 10000));
        assertEquals(List.of(99d, 100d), filter(OfferDirection.BUY, 1, 99, 100));
        assertEquals(List.of(100d, 101d, 102d), filter(OfferDirection.SELL, 100, 101, 102, 10000, 10000));
        assertEquals(List.of(98d, 99d, 100d), filter(OfferDirection.BUY, 1, 1, 98, 99, 100));
    }

    @Test
    public void testOuterLimitIsExclusive() {
        assertEquals(List.of(100d, 299d), filter(OfferDirection.SELL, 100, 299, 300));
        assertEquals(List.of(101d, 300d), filter(OfferDirection.BUY, 100, 101, 300));
        assertEquals(List.of(100d, 150d, 200d, 250d, 299d), filter(OfferDirection.SELL, 100, 150, 200, 250, 299, 300));
        assertEquals(Math.nextDown(300d), rangeEnd(OfferDirection.SELL, 0, new double[]{100, 150, 200, 250, 299}, new double[]{1, 1, 1, 1, 1}));
        assertEquals(Math.nextUp(100d), rangeEnd(OfferDirection.BUY, 0, new double[]{101, 150, 200, 250, 300}, new double[]{1, 1, 1, 1, 1}));
    }

    @Test
    public void testOuterLimitUsesFartherOfMarketAndBestPrice() {
        assertEquals(List.of(100d, 359d), filterAtMarket(OfferDirection.SELL, 120, 100, 359, 360));
        assertEquals(List.of(100d, 299d), filterAtMarket(OfferDirection.SELL, 80, 100, 299, 300));
        assertEquals(List.of(81d, 300d), filterAtMarket(OfferDirection.BUY, 240, 80, 81, 300));
        assertEquals(List.of(101d, 300d), filterAtMarket(OfferDirection.BUY, 330, 100, 101, 300));
    }

    @Test
    public void testMarketPriceOverridesMisleadingBestPrice() {
        assertEquals(List.of(30d, 100d, 110d, 120d, 125d, 130d),
                filterAtMarket(OfferDirection.SELL, 100, 30, 100, 110, 120, 125, 130));
        assertEquals(List.of(70d, 75d, 80d, 90d, 100d, 400d),
                filterAtMarket(OfferDirection.BUY, 100, 70, 75, 80, 90, 100, 400));
    }

    @Test
    public void testIncorrectMarketPriceDoesNotHideWholeSide() {
        assertEquals(List.of(100d, 101d, 102d), filterAtMarket(OfferDirection.SELL, 1, 100, 101, 102));
        assertEquals(List.of(98d, 99d, 100d), filterAtMarket(OfferDirection.BUY, 10000, 98, 99, 100));
    }

    @Test
    public void testMarketPriceRequiresRecentFeedPrice() {
        OfferBook offerBook = mock(OfferBook.class);
        PriceFeedService priceFeedService = mock(PriceFeedService.class);
        when(offerBook.getOfferBookListItems()).thenReturn(FXCollections.observableArrayList());
        when(priceFeedService.updateCounterProperty()).thenReturn(new SimpleIntegerProperty());
        OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, priceFeedService, null, null, null, null);
        long now = System.currentTimeMillis();

        when(priceFeedService.getMarketPrice("USD")).thenReturn(new MarketPrice("USD", 100, now, true));
        assertEquals(Optional.of(100d), model.getMarketPriceAsDouble());
        when(priceFeedService.getMarketPrice("USD")).thenReturn(new MarketPrice("USD", 100, now, false));
        assertEquals(Optional.empty(), model.getMarketPriceAsDouble());
        when(priceFeedService.getMarketPrice("USD")).thenReturn(new MarketPrice("USD", 100, now - MarketPrice.MARKET_PRICE_MAX_AGE_MS - 1000, true));
        assertEquals(Optional.empty(), model.getMarketPriceAsDouble());
    }

    @Test
    public void testAwaitsPricesUntilFirstMarketPrices() {
        OfferBook offerBook = mock(OfferBook.class);
        PriceFeedService priceFeedService = mock(PriceFeedService.class);
        OfferBookListItem marketPricedItem = make(xmrBuyItem.but(with(OfferBookListItemMaker.useMarketBasedPrice, true)));
        marketPricedItem.getOffer().setPriceFeedService(priceFeedService);
        ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList(marketPricedItem, make(xmrSellItem));
        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);
        OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, priceFeedService, null, null, null, null);
        assertTrue(model.isAwaitingPrices());

        // a feed without this currency's price is not awaited, while its offer stays without a price
        when(priceFeedService.hasExternalPrices()).thenReturn(true);
        assertFalse(model.isAwaitingPrices());
        assertTrue(model.hasUnpricedOffers());

        // a book of fixed price offers needs no feed
        when(priceFeedService.hasExternalPrices()).thenReturn(false);
        offerBookListItems.remove(marketPricedItem);
        assertFalse(model.isAwaitingPrices());
        assertFalse(model.hasUnpricedOffers());
    }

    @Test
    public void testInvalidMarketPriceUsesBestPrice() {
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

    @Test
    public void testSeriesPointsCarryTheirOffers() {
        OfferBook offerBook = mock(OfferBook.class);
        PriceFeedService service = mock(PriceFeedService.class);
        final ObservableList<OfferBookListItem> offerBookListItems = FXCollections.observableArrayList(
                make(xmrSellItem.but(with(OfferBookListItemMaker.price, 10300000000L))),
                make(xmrSellItem.but(with(OfferBookListItemMaker.price, 10100000000L))),
                make(xmrBuyItem.but(with(OfferBookListItemMaker.price, 9700000000L))),
                make(xmrBuyItem.but(with(OfferBookListItemMaker.price, 9900000000L))));
        when(offerBook.getOfferBookListItems()).thenReturn(offerBookListItems);

        final OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, service, null, null, null, null);
        model.activate();

        // asks are plotted in the order of their table list, bids from its far end
        List<OfferListItem> asks = model.getTopSellOfferList();
        List<OfferListItem> bids = new ArrayList<>(model.getTopBuyOfferList());
        Collections.reverse(bids);
        assertEquals(List.of(101d, 103d), asks.stream().map(item -> item.price).collect(Collectors.toList()));
        assertEquals(List.of(97d, 99d), bids.stream().map(item -> item.price).collect(Collectors.toList()));
        assertPointsCarryOffers(asks, model.getSellData());
        assertPointsCarryOffers(bids, model.getBuyData());
    }

    private static void assertPointsCarryOffers(List<OfferListItem> items, List<XYChart.Data<Number, Number>> points) {
        assertEquals(items.size(), points.size());
        for (int i = 0; i < items.size(); i++) {
            assertSame(items.get(i).offer, points.get(i).getExtraValue());
            assertEquals(items.get(i).price, points.get(i).getXValue());
            assertEquals(items.get(i).accumulated, points.get(i).getYValue());
        }
    }

    @Test
    public void testPriceAsPercentageMatchesOfferBook() {
        OfferBook offerBook = mock(OfferBook.class);
        PriceFeedService priceFeedService = mock(PriceFeedService.class);
        when(offerBook.getOfferBookListItems()).thenReturn(FXCollections.observableArrayList());
        when(priceFeedService.getMarketPrice("USD")).thenReturn(new MarketPrice("USD", 100, System.currentTimeMillis(), true));
        PriceUtil priceUtil = new PriceUtil(priceFeedService, null, null);
        OfferBookChartViewModel model = new OfferBookChartViewModel(offerBook, null, empty, priceFeedService, null, null, priceUtil, null);

        // a fixed price is measured against the market price for its taker, so asks above and bids below it are positive
        Offer fixedAsk = make(xmrSellItem.but(with(OfferBookListItemMaker.price, 10300000000L))).getOffer();
        Offer fixedBid = make(xmrBuyItem.but(with(OfferBookListItemMaker.price, 9800000000L))).getOffer();
        Offer cheapAsk = make(xmrSellItem.but(with(OfferBookListItemMaker.price, 9800000000L))).getOffer();
        assertEquals("3.00%", model.getPriceAsPercentage(fixedAsk));
        assertEquals("2.00%", model.getPriceAsPercentage(fixedBid));
        assertEquals("-2.00%", model.getPriceAsPercentage(cheapAsk));

        // a market based price shows its margin
        Offer marketAsk = make(xmrSellItem.but(with(OfferBookListItemMaker.useMarketBasedPrice, true), with(OfferBookListItemMaker.marketPriceMargin, 0.05))).getOffer();
        Offer marketBid = make(xmrBuyItem.but(with(OfferBookListItemMaker.useMarketBasedPrice, true), with(OfferBookListItemMaker.marketPriceMargin, -0.01))).getOffer();
        assertEquals("5.00%", model.getPriceAsPercentage(marketAsk));
        assertEquals("-1.00%", model.getPriceAsPercentage(marketBid));

        // the Buy tab lists the sell offers and the Sell tab the buy offers, each with its own direction
        for (Offer ask : List.of(fixedAsk, cheapAsk, marketAsk)) {
            assertEquals(FormattingUtils.formatPercentagePrice(priceUtil.getMarketBasedPrice(ask, OfferDirection.BUY).get()), model.getPriceAsPercentage(ask));
        }
        for (Offer bid : List.of(fixedBid, marketBid)) {
            assertEquals(FormattingUtils.formatPercentagePrice(priceUtil.getMarketBasedPrice(bid, OfferDirection.SELL).get()), model.getPriceAsPercentage(bid));
        }

        // without a market price only the margins remain
        when(priceFeedService.getMarketPrice("USD")).thenReturn(null);
        assertEquals("", model.getPriceAsPercentage(fixedAsk));
        assertEquals("5.00%", model.getPriceAsPercentage(marketAsk));
    }

    @Test
    public void testOfferNearPointerIsNearestInPrice() {
        List<OfferListItem> asks = items(101, 1, 102, 3, 105, 6);
        List<OfferListItem> bids = items(99, 1, 98, 3, 95, 6);

        assertNull(OfferBookChartView.offerNear(asks, bids, 100, 2, 0.01));
        assertNull(OfferBookChartView.offerNear(List.of(), List.of(), 100, 2, 0.01));
        assertSame(asks.get(0), OfferBookChartView.offerNear(asks, bids, 101, 2, 0.01));
        assertSame(asks.get(0), OfferBookChartView.offerNear(asks, bids, 101.4, 6, 0.01));
        assertSame(asks.get(1), OfferBookChartView.offerNear(asks, bids, 101.6, 0, 0.01));
        assertSame(asks.get(1), OfferBookChartView.offerNear(asks, bids, 103.4, 100, 0.01));
        assertSame(asks.get(2), OfferBookChartView.offerNear(asks, bids, 103.6, 0, 0.01));
        assertSame(asks.get(2), OfferBookChartView.offerNear(asks, bids, 500, 0, 0.01));
        assertSame(bids.get(0), OfferBookChartView.offerNear(asks, bids, 99, 2, 0.01));
        assertSame(bids.get(0), OfferBookChartView.offerNear(asks, bids, 98.6, 6, 0.01));
        assertSame(bids.get(1), OfferBookChartView.offerNear(asks, bids, 98.4, 0, 0.01));
        assertSame(bids.get(2), OfferBookChartView.offerNear(asks, bids, 96.4, 0, 0.01));
        assertSame(bids.get(2), OfferBookChartView.offerNear(asks, bids, 1, 0, 0.01));
        // the gap starts right at the best prices
        assertNull(OfferBookChartView.offerNear(asks, bids, 100.995, 2, 0.01));
        assertNull(OfferBookChartView.offerNear(asks, bids, 99.005, 2, 0.01));

        assertEquals(-1, OfferBookChartView.lastAtOrBetter(asks, OfferDirection.SELL, 100.9));
        assertEquals(1, OfferBookChartView.lastAtOrBetter(asks, OfferDirection.SELL, 102));
        assertEquals(-1, OfferBookChartView.lastAtOrBetter(bids, OfferDirection.BUY, 99.1));
        assertEquals(1, OfferBookChartView.lastAtOrBetter(bids, OfferDirection.BUY, 98));
    }

    @Test
    public void testOfferNearPointerInStackIsNearestItsHeight() {
        List<OfferListItem> asks = items(101, 1, 102, 3, 102, 5, 102, 9, 105, 10);
        List<OfferListItem> bids = items(99, 2, 98, 4, 98, 8);

        assertSame(asks.get(1), OfferBookChartView.offerNear(asks, bids, 102.5, 0, 0.01));
        assertSame(asks.get(2), OfferBookChartView.offerNear(asks, bids, 102.5, 4.2, 0.01));
        assertSame(asks.get(3), OfferBookChartView.offerNear(asks, bids, 102.5, 100, 0.01));
        assertSame(asks.get(1), OfferBookChartView.offerNear(asks, bids, 102.5, 4, 0.01)); // a tie goes to the one first in the list
        assertSame(asks.get(0), OfferBookChartView.offerNear(asks, bids, 101.4, 100, 0.01)); // other prices stay out of the stack
        assertSame(bids.get(1), OfferBookChartView.offerNear(asks, bids, 97.5, 5, 0.01));
        assertSame(bids.get(2), OfferBookChartView.offerNear(asks, bids, 97.5, 7, 0.01));
        // not the far offer at the pointer's height, however near the midpoint
        List<OfferListItem> far = items(100, 1, 110, 100);
        assertSame(far.get(0), OfferBookChartView.offerNear(far, List.of(), 104.999, 100, 0.01));
        // offers within a pixel of each other stack as well
        List<OfferListItem> close = items(101, 1, 101.005, 2);
        assertSame(close.get(0), OfferBookChartView.offerNear(close, List.of(), 101, 1.4, 0.01));
        assertSame(close.get(1), OfferBookChartView.offerNear(close, List.of(), 101, 1.6, 0.01));
    }

    @Test
    public void testOfferNearPointerInCrossedBookIsOnEitherSide() {
        List<OfferListItem> asks = items(100, 2, 101, 6);
        List<OfferListItem> bids = items(102, 1, 101, 3, 99, 10);

        // at 101 an ask at 6 and a bid at 3 stack
        assertSame(asks.get(1), OfferBookChartView.offerNear(asks, bids, 101, 6, 0.01));
        assertSame(asks.get(1), OfferBookChartView.offerNear(asks, bids, 101, 4.5, 0.01));
        assertSame(bids.get(1), OfferBookChartView.offerNear(asks, bids, 101, 4.4, 0.01));
        assertSame(bids.get(1), OfferBookChartView.offerNear(asks, bids, 101, 0, 0.01));
        // outside the crossed range only one side reaches the price
        assertSame(asks.get(1), OfferBookChartView.offerNear(asks, bids, 103, 0, 0.01));
        assertSame(bids.get(2), OfferBookChartView.offerNear(asks, bids, 99.4, 0, 0.01));
        assertSame(bids.get(2), OfferBookChartView.offerNear(asks, bids, 99, 100, 0.01));
    }

    @Test
    public void testLabelPlacedAboveMarkerTowardSpread() {
        Bounds plot = new BoundingBox(0, 0, 400, 200);
        Point2D marker = new Point2D(200, 150);

        assertEquals(new Point2D(208, 98), OfferBookChartView.placeLabel(plot, true, marker, 100, 40));
        assertEquals(new Point2D(92, 98), OfferBookChartView.placeLabel(plot, false, marker, 100, 40));
        // kept within the plot
        assertEquals(new Point2D(296, 98), OfferBookChartView.placeLabel(plot, true, new Point2D(390, 150), 100, 40));
        // a spot which the plot edges push onto the marker is passed over
        assertEquals(new Point2D(282, 4), OfferBookChartView.placeLabel(plot, true, new Point2D(390, 30), 100, 40));
        // a riser may cross the label
        assertEquals(new Point2D(208, 98), OfferBookChartView.placeLabel(plot, true, marker, 100, 40, x -> x < 250 ? 190 : 20));
    }

    @Test
    public void testLabelAvoidsLyingAlongOutlines() {
        Bounds plot = new BoundingBox(0, 0, 400, 200);
        Point2D marker = new Point2D(200, 150);

        // a plateau toward the spread sends the label away from it
        assertEquals(new Point2D(92, 98), OfferBookChartView.placeLabel(plot, true, marker, 100, 40, x -> x >= 200 ? 110 : Double.NaN));
        // plateaus on both sides send it to the top of the guide
        assertEquals(new Point2D(150, 4), OfferBookChartView.placeLabel(plot, true, marker, 100, 40, x -> 110));
        assertEquals(new Point2D(150, 4), OfferBookChartView.placeLabel(plot, true, marker, 100, 40, x -> x >= 200 ? 110 : Double.NaN, x -> x <= 200 ? 120 : Double.NaN));
        // a marker on a plateau near the top leaves room only below it
        assertEquals(new Point2D(208, 42), OfferBookChartView.placeLabel(plot, true, new Point2D(200, 30), 100, 40, x -> 30));
        // without a clear spot it lies along the outlines as little as it can
        assertEquals(new Point2D(92, 98), OfferBookChartView.placeLabel(plot, true, marker, 100, 40, x -> x >= 150 ? 110 : Double.NaN, x -> 20, x -> 180));
    }

    // Offer list items of one side from pairs of price and accumulated amount, best price first.
    private static List<OfferListItem> items(double... pricesAndAccumulated) {
        List<OfferListItem> items = new ArrayList<>();
        for (int i = 0; i < pricesAndAccumulated.length; i += 2) items.add(new OfferListItem(null, pricesAndAccumulated[i], pricesAndAccumulated[i + 1]));
        return items;
    }

    private static List<Double> filter(OfferDirection direction, double... prices) {
        return filterAtMarket(direction, 0, prices);
    }

    private static List<Double> filterAtMarket(OfferDirection direction, double marketPrice, double... prices) {
        double[] amounts = new double[prices.length];
        Arrays.fill(amounts, 1);
        return filterAtMarket(direction, marketPrice, prices, amounts);
    }

    private static List<Double> filterAtMarket(OfferDirection direction, double marketPrice, double[] prices, double[] amounts) {
        return OfferBookChartView.filterOutliers(series(direction, prices, amounts), direction, marketPrice).stream()
                .map(d -> d.getXValue().doubleValue())
                .collect(Collectors.toList());
    }

    private static double rangeEnd(OfferDirection direction, double marketPrice, double[] prices, double[] amounts) {
        return OfferBookChartView.getRangeEnd(series(direction, prices, amounts), direction, marketPrice);
    }

    // Build the series like the view model: ascending prices, each depth accumulating amounts from the best price.
    private static List<XYChart.Data<Number, Number>> series(OfferDirection direction, double[] prices, double[] amounts) {
        boolean sell = direction == OfferDirection.SELL;
        List<XYChart.Data<Number, Number>> data = new ArrayList<>();
        double depth = 0;
        for (int i = 0; i < prices.length; i++) {
            int index = sell ? i : prices.length - 1 - i;
            depth += amounts[index];
            data.add(sell ? i : 0, new XYChart.Data<>(prices[index], depth));
        }
        return data;
    }

    // Filter ten offers of the core amount at a price of 100 followed outward by the tail offers.
    private static List<Double> filterTail(OfferDirection direction, double marketPrice, double coreAmount, double[] tailPrices, double[] tailAmounts) {
        return filterAtMarket(direction, marketPrice, withCore(direction, 100, tailPrices), withCore(direction, coreAmount, tailAmounts));
    }

    // Ten times the core value followed outward by the tail values, in ascending price order.
    private static double[] withCore(OfferDirection direction, double core, double... tail) {
        double[] values = new double[10 + tail.length];
        for (int i = 0; i < values.length; i++) {
            int outward = direction == OfferDirection.SELL ? i : values.length - 1 - i;
            values[i] = outward < 10 ? core : tail[outward - 10];
        }
        return values;
    }

    // The ten offers at a price of 100 and the given tail offers in ascending price order.
    private static List<Double> shown(OfferDirection direction, double... tailPrices) {
        List<Double> prices = new ArrayList<>(Collections.nCopies(10, 100d));
        for (double price : tailPrices) prices.add(direction == OfferDirection.SELL ? prices.size() : 0, price);
        return prices;
    }
}
