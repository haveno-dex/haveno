package haveno.desktop.util;

import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.config.Config;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.monetary.Volume;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferPayload;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.user.Preferences;
import haveno.core.util.VolumeUtil;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.coin.ImmutableCoinFormatter;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.main.presentation.MarketPricePresentation;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigInteger;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.natpryce.makeiteasy.MakeItEasy.make;
import static com.natpryce.makeiteasy.MakeItEasy.with;
import static haveno.desktop.maker.OfferMaker.xmrUsdOffer;
import static haveno.desktop.maker.VolumeMaker.usdVolume;
import static haveno.desktop.maker.VolumeMaker.volumeString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DisplayUtilsTest {
    private final CoinFormatter formatter = new ImmutableCoinFormatter(Config.baseCurrencyNetworkParameters().getMonetaryFormat());

    @BeforeEach
    public void setUp() {
        Locale.setDefault(Locale.US);
        GlobalSettings.setLocale(Locale.US);
        Res.setBaseCurrencyCode("XMR");
        Res.setBaseCurrencyName("Monero");
    }

    @Test
    public void testFormatAccountAge() {
        assertEquals("0 days", DisplayUtils.formatAccountAge(TimeUnit.HOURS.toMillis(23)));
        assertEquals("0 days", DisplayUtils.formatAccountAge(0));
        assertEquals("0 days", DisplayUtils.formatAccountAge(-1));
        assertEquals("1 day", DisplayUtils.formatAccountAge(TimeUnit.DAYS.toMillis(1)));
        assertEquals("2 days", DisplayUtils.formatAccountAge(TimeUnit.DAYS.toMillis(2)));
        assertEquals("30 days", DisplayUtils.formatAccountAge(TimeUnit.DAYS.toMillis(30)));
        assertEquals("60 days", DisplayUtils.formatAccountAge(TimeUnit.DAYS.toMillis(60)));
    }

    @Test
    public void testFormatVolume() {
        assertEquals("1", VolumeUtil.formatVolume(make(xmrUsdOffer), true, 4));
        assertEquals("100", VolumeUtil.formatVolume(make(usdVolume)));
        assertEquals("1775", VolumeUtil.formatVolume(make(usdVolume.but(with(volumeString, "1774.62")))));
    }

    @Test
    public void testFormatSameVolume() {
        Offer offer = mock(Offer.class);
        Volume xmr = Volume.parse("0.10", "XMR");
        when(offer.getMinVolume()).thenReturn(xmr);
        when(offer.getVolume()).thenReturn(xmr);

        assertEquals("0.10000000", VolumeUtil.formatVolume(offer.getVolume()));
    }

    @Test
    public void testFormatDifferentVolume() {
        Offer offer = mock(Offer.class);
        Volume xmrMin = Volume.parse("0.10", "XMR");
        Volume xmrMax = Volume.parse("0.25", "XMR");
        when(offer.isRange()).thenReturn(true);
        when(offer.getMinVolume()).thenReturn(xmrMin);
        when(offer.getVolume()).thenReturn(xmrMax);

        assertEquals("0.10000000 - 0.25000000", VolumeUtil.formatVolume(offer, false, 0));
    }

    @Test
    public void testFormatNullVolume() {
        Offer offer = mock(Offer.class);
        when(offer.getMinVolume()).thenReturn(null);
        when(offer.getVolume()).thenReturn(null);

        assertEquals("", VolumeUtil.formatVolume(offer.getVolume()));
    }

    @Test
    public void testFormatSameAmount() {
        Offer offer = mock(Offer.class);
        when(offer.getMinAmount()).thenReturn(BigInteger.valueOf(100000000000L));
        when(offer.getAmount()).thenReturn(BigInteger.valueOf(100000000000L));

        assertEquals("0.10", DisplayUtils.formatAmount(offer, formatter));
    }

    @Test
    public void testFormatDifferentAmount() {
        OfferPayload offerPayload = mock(OfferPayload.class);
        Offer offer = new Offer(offerPayload);
        when(offerPayload.getMinAmount()).thenReturn(100000000000L);
        when(offerPayload.getAmount()).thenReturn(200000000000L);

        assertEquals("0.10 - 0.20", DisplayUtils.formatAmount(offer, formatter));
    }

    @Test
    public void testFormatAmountWithAlignmenWithDecimals() {
        OfferPayload offerPayload = mock(OfferPayload.class);
        Offer offer = new Offer(offerPayload);
        when(offerPayload.getMinAmount()).thenReturn(100000000000L);
        when(offerPayload.getAmount()).thenReturn(200000000000L);

        assertEquals("0.1000 - 0.2000", DisplayUtils.formatAmount(offer, 4, true, 15, formatter));
    }

    @Test
    public void testFormatAmountWithAlignmenWithDecimalsNoRange() {
        OfferPayload offerPayload = mock(OfferPayload.class);
        Offer offer = new Offer(offerPayload);
        when(offerPayload.getMinAmount()).thenReturn(100000000000L);
        when(offerPayload.getAmount()).thenReturn(100000000000L);

        assertEquals("0.1000", DisplayUtils.formatAmount(offer, 4, true, 15, formatter));
    }

    @Test
    public void testFormatNullAmount() {
        Offer offer = mock(Offer.class);
        when(offer.getMinAmount()).thenReturn(null);
        when(offer.getAmount()).thenReturn(null);

        assertEquals("", DisplayUtils.formatAmount(offer, formatter));
    }

    @Test
    public void testBalanceEstimateUsesAtomicUnitsAndRoundsOnlyTheResult() {
        MarketPrice price = new MarketPrice("USD", 2.675, System.currentTimeMillis(), true);
        assertEquals("≈ 2.68 USD", DisplayUtils.formatBalanceEstimate(new BigInteger("1000000000000"), price));
        price = new MarketPrice("USD", 2.665, System.currentTimeMillis(), true);
        assertEquals("≈ 2.67 USD", DisplayUtils.formatBalanceEstimate(new BigInteger("1000000000000"), price));
        price = new MarketPrice("USD", 200.1, System.currentTimeMillis(), true);
        assertEquals("≈ 30.02 USD", DisplayUtils.formatBalanceEstimate(new BigInteger("150000000000"), price)); // 0.15 * 200.1 is 30.0149... in doubles
        price = new MarketPrice("USD", 1000, System.currentTimeMillis(), true);
        assertEquals("≈ 1,234.57 USD", DisplayUtils.formatBalanceEstimate(new BigInteger("1234567890123"), price));
        assertEquals("≈ 0.00 USD", DisplayUtils.formatBalanceEstimate(BigInteger.ZERO, price));
    }

    @Test
    public void testBalanceEstimateUsesLocaleAndCurrencyPrecision() {
        BigInteger amount = new BigInteger("1000000000000");
        MarketPrice price = new MarketPrice("EUR", 1234.567, System.currentTimeMillis(), true);
        GlobalSettings.setLocale(Locale.GERMANY);
        try {
            assertEquals("≈ 1.234,57 EUR", DisplayUtils.formatBalanceEstimate(amount, price));
        } finally {
            GlobalSettings.setLocale(Locale.US);
        }
        price = new MarketPrice("BTC", 0.00123456789, System.currentTimeMillis(), true);
        assertEquals("≈ 0.00123457 BTC", DisplayUtils.formatBalanceEstimate(amount, price));
        price = new MarketPrice("XAU", 0.123456789, System.currentTimeMillis(), true);
        assertEquals("≈ 0.12345679 XAU", DisplayUtils.formatBalanceEstimate(amount, price));
    }

    @Test
    public void testBalanceEstimateOmitsUnavailableOrInvalidPrices() {
        BigInteger amount = new BigInteger("1000000000000");
        assertNull(DisplayUtils.formatBalanceEstimate(amount, null));
        for (double value : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            MarketPrice price = new MarketPrice("USD", value, System.currentTimeMillis(), true);
            assertNull(DisplayUtils.formatBalanceEstimate(amount, price));
        }
        MarketPrice recent = new MarketPrice("USD", 200, System.currentTimeMillis(), true);
        assertNull(DisplayUtils.formatBalanceEstimate(null, recent));
        assertNull(DisplayUtils.formatBalanceEstimate(BigInteger.ONE.negate(), recent));
        MarketPrice stale = new MarketPrice("USD", 200,
                System.currentTimeMillis() - MarketPrice.MARKET_PRICE_MAX_AGE_MS, true);
        assertNull(DisplayUtils.formatBalanceEstimate(amount, stale));
        MarketPrice internal = new MarketPrice("USD", 200, System.currentTimeMillis(), false);
        assertNull(DisplayUtils.formatBalanceEstimate(amount, internal));
    }

    @Test
    public void testBalanceEstimatesFollowPreferredCurrencyAndExpireWithoutFeedUpdates() {
        Preferences preferences = mock(Preferences.class);
        PriceFeedService feed = mock(PriceFeedService.class);
        SimpleObjectProperty<TradeCurrency> preferredCurrency = new SimpleObjectProperty<>(new TraditionalCurrency("USD"));
        SimpleIntegerProperty updates = new SimpleIntegerProperty();
        SimpleStringProperty selectedCurrency = new SimpleStringProperty("EUR");
        when(preferences.getPreferredTradeCurrency()).thenAnswer(invocation -> preferredCurrency.get());
        when(preferences.getTradeCurrenciesAsObservable()).thenReturn(FXCollections.observableArrayList(
                preferredCurrency.get(), new TraditionalCurrency("EUR")));
        when(feed.currencyCodeProperty()).thenReturn(selectedCurrency);
        when(feed.getCurrencyCode()).thenAnswer(invocation -> selectedCurrency.get());
        when(feed.updateCounterProperty()).thenReturn(updates);

        Timer timer = mock(Timer.class);
        AtomicReference<Runnable> expiry = new AtomicReference<>();
        try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class);
             MockedStatic<GlobalSettings> settings = mockStatic(GlobalSettings.class, CALLS_REAL_METHODS)) {
            settings.when(GlobalSettings::defaultTradeCurrencyProperty).thenReturn(preferredCurrency);
            userThread.when(() -> UserThread.execute(any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(0).run();
                return null;
            });
            userThread.when(() -> UserThread.runAfter(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS)))
                    .thenAnswer(invocation -> {
                        long delay = invocation.getArgument(1);
                        assertTrue(delay > 0 && delay <= MarketPrice.MARKET_PRICE_MAX_AGE_MS);
                        expiry.set(invocation.getArgument(0));
                        return timer;
                    });
            MarketPricePresentation presentation = new MarketPricePresentation(mock(XmrWalletService.class), feed, preferences);
            presentation.setup();
            assertNull(presentation.balancePriceProperty().get());

            MarketPrice usd = new MarketPrice("USD", 200, System.currentTimeMillis(), true);
            when(feed.getMarketPrice("USD")).thenReturn(usd);
            updates.set(1);
            assertSame(usd, presentation.balancePriceProperty().get());
            assertEquals("EUR", selectedCurrency.get());

            MarketPrice eur = spy(new MarketPrice("EUR", 180, System.currentTimeMillis(), true));
            when(feed.getMarketPrice("EUR")).thenReturn(eur);
            preferredCurrency.set(new TraditionalCurrency("EUR"));
            assertSame(eur, presentation.balancePriceProperty().get());
            verify(timer).stop();

            // simulate the quote aging out without a balance or feed update
            when(eur.isRecentExternalPriceAvailable()).thenReturn(false);
            expiry.get().run();
            assertNull(presentation.balancePriceProperty().get());

            MarketPrice refreshed = new MarketPrice("EUR", 181, System.currentTimeMillis(), true);
            when(feed.getMarketPrice("EUR")).thenReturn(refreshed);
            updates.set(2);
            assertSame(refreshed, presentation.balancePriceProperty().get());
            when(feed.getMarketPrice("EUR")).thenReturn(new MarketPrice("EUR", Double.POSITIVE_INFINITY, System.currentTimeMillis(), true));
            updates.set(3);
            assertNull(presentation.balancePriceProperty().get());
        }
    }
}
