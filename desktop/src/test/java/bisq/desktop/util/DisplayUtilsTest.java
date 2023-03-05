package bisq.desktop.util;

import bisq.core.locale.GlobalSettings;
import bisq.core.locale.Res;
import bisq.core.monetary.Volume;
import bisq.core.offer.Offer;
import bisq.core.offer.OfferPayload;
import bisq.core.util.VolumeUtil;
import bisq.core.util.coin.CoinFormatter;
import bisq.core.util.coin.ImmutableCoinFormatter;

import bisq.common.config.Config;

import java.math.BigInteger;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;

import static bisq.desktop.maker.OfferMaker.xmrUsdOffer;
import static bisq.desktop.maker.VolumeMaker.usdVolume;
import static bisq.desktop.maker.VolumeMaker.volumeString;
import static com.natpryce.makeiteasy.MakeItEasy.make;
import static com.natpryce.makeiteasy.MakeItEasy.with;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DisplayUtilsTest {
    private final CoinFormatter formatter = new ImmutableCoinFormatter(Config.baseCurrencyNetworkParameters().getMonetaryFormat());

    @Before
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
}
