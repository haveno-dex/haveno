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

package haveno.desktop.main.market.trades;

import haveno.core.locale.TraditionalCurrency;
import haveno.core.monetary.Price;
import haveno.core.monetary.TraditionalMoney;
import haveno.core.offer.OfferPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.statistics.TradeStatistics3;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.user.Preferences;
import haveno.desktop.Navigation;
import haveno.desktop.main.market.trades.charts.CandleData;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;
import javafx.util.Pair;
import org.bitcoinj.core.Coin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

public class TradesChartsViewModelTest {
    TradesChartsViewModel model;
    TradeStatisticsManager tradeStatisticsManager;

    DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
    private File dir;
    OfferPayload offer = new OfferPayload(null,
            0,
            null,
            null,
            null,
            0,
            0,
            false,
            0,
            0,
            "XMR",
            "EUR",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0,
            0,
            0,
            0,
            0,
            0,
            false,
            false,
            0,
            0,
            false,
            null,
            null,
            0,
            null,
            null,
            null);

    @BeforeEach
    public void setup() throws IOException {
        tradeStatisticsManager = mock(TradeStatisticsManager.class);
        model = new TradesChartsViewModel(tradeStatisticsManager, mock(Preferences.class), mock(PriceFeedService.class),
                mock(Navigation.class));
        dir = File.createTempFile("temp_tests1", "");
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdir();
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    public void testGetCandleData() {
        String currencyCode = "EUR";
        model.selectedTradeCurrencyProperty.setValue(new TraditionalCurrency(currencyCode));

        long low = TraditionalMoney.parseTraditionalMoney("EUR", "500").value;
        long open = TraditionalMoney.parseTraditionalMoney("EUR", "520").value;
        long close = TraditionalMoney.parseTraditionalMoney("EUR", "580").value;
        long high = TraditionalMoney.parseTraditionalMoney("EUR", "600").value;
        long average = TraditionalMoney.parseTraditionalMoney("EUR", "550").value;
        long median = TraditionalMoney.parseTraditionalMoney("EUR", "550").value;
        long amount = HavenoUtils.xmrToAtomicUnits(4).longValue();
        long volume = TraditionalMoney.parseTraditionalMoney("EUR", "2200").value;
        boolean isBullish = true;

        Set<TradeStatistics3> set = new HashSet<>();
        final Date now = new Date();

        set.add(new TradeStatistics3(offer.getCurrencyCode(),
                Price.parse("EUR", "520").getValue(),
                HavenoUtils.xmrToAtomicUnits(1).longValue(),
                PaymentMethod.BLOCK_CHAINS_ID,
                now.getTime(),
                null,
                null,
                null));
        set.add(new TradeStatistics3(offer.getCurrencyCode(),
                Price.parse("EUR", "500").getValue(),
                HavenoUtils.xmrToAtomicUnits(1).longValue(),
                PaymentMethod.BLOCK_CHAINS_ID,
                now.getTime() + 100,
                null,
                null,
                null));
        set.add(new TradeStatistics3(offer.getCurrencyCode(),
                Price.parse("EUR", "600").getValue(),
                HavenoUtils.xmrToAtomicUnits(1).longValue(),
                PaymentMethod.BLOCK_CHAINS_ID,
                now.getTime() + 200,
                null,
                null,
                null));
        set.add(new TradeStatistics3(offer.getCurrencyCode(),
                Price.parse("EUR", "580").getValue(),
                HavenoUtils.xmrToAtomicUnits(1).longValue(),
                PaymentMethod.BLOCK_CHAINS_ID,
                now.getTime() + 300,
                null,
                null,
                null));

        Map<Long, Pair<Date, Set<TradeStatistics3>>> itemsPerInterval = null;
        long tick = ChartCalculations.roundToTick(now, TradesChartsViewModel.TickUnit.DAY).getTime();
        CandleData candleData = ChartCalculations.getCandleData(tick,
                set,
                0,
                TradesChartsViewModel.TickUnit.DAY, currencyCode,
                itemsPerInterval);
        assertEquals(open, candleData.open);
        assertEquals(close, candleData.close);
        assertEquals(high, candleData.high);
        assertEquals(low, candleData.low);
        assertEquals(average, candleData.average);
        assertEquals(median, candleData.median);
        assertEquals(amount, candleData.accumulatedAmount);
        assertEquals(volume, candleData.accumulatedVolume);
        assertEquals(isBullish, candleData.isBullish);
    }

    // TODO JMOCKIT
    @Disabled
    @Test
    public void testItemLists() throws ParseException {
        // Helper class to add historic trades
        class Trade {
            Trade(String date, String size, String price, String cc) {
                try {
                    this.date = dateFormat.parse(date);
                } catch (ParseException p) {
                    this.date = new Date();
                }
                this.size = size;
                this.price = price;
                this.cc = cc;
            }

            Date date;
            String size;
            String price;
            String cc;
        }

        // Trade EUR
        model.selectedTradeCurrencyProperty.setValue(new TraditionalCurrency("EUR"));

        ArrayList<Trade> trades = new ArrayList<>();

        // Set predetermined time to use as "now" during test
/*        new MockUp<System>() {
            @Mock
            long currentTimeMillis() {
                return test_time.getTime();
            }
        };*/

        // Two trades 10 seconds apart, different YEAR, MONTH, WEEK, DAY, HOUR, MINUTE_10
        trades.add(new Trade("2017-12-31T23:59:52", "1", "100", "EUR"));
        trades.add(new Trade("2018-01-01T00:00:02", "1", "110", "EUR"));
        Set<TradeStatistics3> set = new HashSet<>();
        trades.forEach(t ->
                set.add(new TradeStatistics3(offer.getCurrencyCode(),
                        Price.parse(t.cc, t.price).getValue(),
                        Coin.parseCoin(t.size).getValue(),
                        PaymentMethod.BLOCK_CHAINS_ID,
                        t.date.getTime(),
                        null,
                        null,
                        null))
        );
        ObservableSet<TradeStatistics3> tradeStats = FXCollections.observableSet(set);

        // Run test for each tick type
        for (TradesChartsViewModel.TickUnit tick : TradesChartsViewModel.TickUnit.values()) {
/*            new Expectations() {{
                tradeStatisticsManager.getObservableTradeStatisticsSet();
                result = tradeStats;
            }};*/

            // Trigger chart update
            model.setTickUnit(tick);
            assertEquals(model.selectedTradeCurrencyProperty.get().getCode(), tradeStats.iterator().next().getCurrency());
            assertEquals(2, model.priceItems.size());
            assertEquals(2, model.volumeItems.size());
        }
    }
}
