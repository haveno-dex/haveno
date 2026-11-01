package haveno.desktop.main.portfolio.duplicateoffer;

import haveno.common.UserThread;
import haveno.common.config.Config;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.Country;
import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.monetary.Price;
import haveno.core.offer.CreateOfferService;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferPayload;
import haveno.core.offer.OfferUtil;
import haveno.core.offer.OpenOfferManager;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.validation.FiatVolumeValidator;
import haveno.core.payment.validation.SecurityDepositValidator;
import haveno.core.payment.validation.XmrValidator;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.coin.ImmutableCoinFormatter;
import haveno.core.util.validation.AmountValidator4Decimals;
import haveno.core.util.validation.AmountValidator8Decimals;
import haveno.core.xmr.wallet.Restrictions;
import haveno.core.xmr.wallet.XmrWalletService;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DuplicateOfferViewModelTest {

    private final CoinFormatter coinFormatter = new ImmutableCoinFormatter(Config.baseCurrencyNetworkParameters().getMonetaryFormat());
    private DuplicateOfferViewModel model;
    private PriceFeedService priceFeedService;
    private PaymentAccount paymentAccount;

    @BeforeEach
    public void setUp() {
        setUp(true);
    }

    private void setUp(boolean usePercentageBasedPrice) {
        final CryptoCurrency xmr = new CryptoCurrency("XMR", "monero");
        final TraditionalCurrency usd = new TraditionalCurrency("USD");
        GlobalSettings.setDefaultTradeCurrency(xmr);
        Res.setup();

        final XmrValidator xmrValidator = new XmrValidator();
        xmrValidator.setMinValue(Restrictions.getMinTradeAmount());

        XmrWalletService xmrWalletService = mock(XmrWalletService.class);
        priceFeedService = mock(PriceFeedService.class);
        User user = mock(User.class);
        paymentAccount = mock(PaymentAccount.class);
        Preferences preferences = mock(Preferences.class);
        AccountAgeWitnessService accountAgeWitnessService = mock(AccountAgeWitnessService.class);
        CreateOfferService createOfferService = mock(CreateOfferService.class);
        OfferUtil offerUtil = mock(OfferUtil.class);

        when(priceFeedService.updateCounterProperty()).thenReturn(new SimpleIntegerProperty());
        when(priceFeedService.getMarketPrice(anyString())).thenReturn(new MarketPrice("USD", 300.0, Instant.now().toEpochMilli(), true));
        when(user.findFirstPaymentAccountWithCurrency(any())).thenReturn(paymentAccount);
        when(paymentAccount.getPaymentMethod()).thenReturn(PaymentMethod.ZELLE);
        when(user.getPaymentAccountsAsObservable()).thenReturn(FXCollections.observableArrayList());
        when(accountAgeWitnessService.getMyTradeLimit(any(), any(), any(), anyBoolean())).thenReturn(100000000L);
        when(preferences.getUserCountry()).thenReturn(new Country("ES", "Spain", null));
        when(preferences.getMaxPriceDistanceInPercent()).thenReturn(1.0);
        when(preferences.isUsePercentageBasedPrice()).thenReturn(usePercentageBasedPrice);
        when(offerUtil.getMaxTradeLimitForRelease(any(), anyString(), any(), anyBoolean())).thenReturn(HavenoUtils.xmrToAtomicUnits(10));
        when(createOfferService.getRandomOfferId()).thenReturn(UUID.randomUUID().toString());

        DuplicateOfferDataModel dataModel = new DuplicateOfferDataModel(createOfferService,
                mock(OpenOfferManager.class),
                offerUtil,
                xmrWalletService,
                preferences,
                user,
                null,
                priceFeedService,
                accountAgeWitnessService,
                coinFormatter,
                null);
        dataModel.initWithData(OfferDirection.BUY, usd, false);
        dataModel.activate();

        model = new DuplicateOfferViewModel(dataModel,
                new FiatVolumeValidator(),
                new AmountValidator4Decimals(),
                new AmountValidator8Decimals(),
                xmrValidator,
                new SecurityDepositValidator(),
                priceFeedService,
                null,
                null,
                preferences,
                coinFormatter,
                offerUtil);
    }

    @Test
    public void testActivateKeepsSecurityDepositPercentWhileMinDepositLocksField() {
        // the 0.1 XMR minimum deposit is 50% of 0.2 XMR, so the field is locked while the copied percent is set
        OfferPayload payload = mock(OfferPayload.class);
        when(payload.getDirection()).thenReturn(OfferDirection.BUY);
        when(payload.getBaseCurrencyCode()).thenReturn("XMR");
        when(payload.getCounterCurrencyCode()).thenReturn("USD");
        when(payload.getPaymentMethodId()).thenReturn("SEPA");
        when(payload.getPrice()).thenReturn(Price.parse("USD", "300").getValue());
        when(payload.getAmount()).thenReturn(HavenoUtils.xmrToAtomicUnits(0.2).longValueExact());
        when(payload.getMinAmount()).thenReturn(HavenoUtils.xmrToAtomicUnits(0.2).longValueExact());
        when(payload.getSellerSecurityDepositPct()).thenReturn(0.3);
        when(payload.getMaxSellerSecurityDeposit()).thenReturn(Restrictions.getMinSecurityDeposit());

        model.initWithData(payload);
        model.activate();
        assertTrue(model.isMinSecurityDeposit.get());
        assertEquals(0.3, model.getDataModel().getSecurityDepositPct().get());

        // raising the amount unlocks the field with the copied percent
        model.amount.set("1.0");
        assertFalse(model.isMinSecurityDeposit.get());
        assertEquals(0.3, model.getDataModel().getSecurityDepositPct().get());
    }

    private OfferPayload fixedAmountPayload() {
        OfferPayload payload = mock(OfferPayload.class);
        when(payload.getDirection()).thenReturn(OfferDirection.BUY);
        when(payload.getBaseCurrencyCode()).thenReturn("XMR");
        when(payload.getCounterCurrencyCode()).thenReturn("USD");
        when(payload.getPaymentMethodId()).thenReturn("SEPA");
        when(payload.getAmount()).thenReturn(HavenoUtils.xmrToAtomicUnits(0.111).longValueExact());
        when(payload.getMinAmount()).thenReturn(HavenoUtils.xmrToAtomicUnits(0.111).longValueExact());
        when(payload.getSellerSecurityDepositPct()).thenReturn(0.15);
        when(payload.getMaxSellerSecurityDeposit()).thenReturn(Restrictions.getMinSecurityDeposit());
        return payload;
    }

    private void activateDuplicate(OfferPayload payload) {
        Executor executor = UserThread.getExecutor();
        Queue<Runnable> callbacks = new ArrayDeque<>();
        try {
            // defer focus-out callbacks until after activation, as in the JavaFX event loop
            UserThread.setExecutor(callbacks::add);
            UserThread.execute(() -> {
                model.initWithData(payload);
                model.activate();
                model.onInvalidatePrice();
            });
            while (!callbacks.isEmpty()) callbacks.remove().run();
        } finally {
            UserThread.setExecutor(executor);
        }
    }

    @Test
    public void testActivateKeepsMinAmountSyncedWhenMarketPriceMoved() {
        // 0.111 XMR rounds differently at the raw market price than at the offer price with margin
        when(priceFeedService.getMarketPrice(anyString())).thenReturn(new MarketPrice("USD", 600.0, Instant.now().toEpochMilli(), true));
        OfferPayload payload = fixedAmountPayload();
        when(payload.isUseMarketBasedPrice()).thenReturn(true);
        when(payload.getMarketPriceMarginPct()).thenReturn(-0.01);

        activateDuplicate(payload);
        assertEquals("0.1106", model.amount.get()); // 67 USD at 606
        assertEquals(model.amount.get(), model.minAmount.get());
    }

    @Test
    public void testNoDepositDuplicateKeepsFixedAmountWhenVolumeChanges() {
        when(paymentAccount.getPaymentMethod()).thenReturn(PaymentMethod.SEPA_INSTANT);
        model.getDataModel().initWithData(OfferDirection.SELL, new TraditionalCurrency("EUR"), false);
        when(priceFeedService.getMarketPrice(anyString())).thenReturn(new MarketPrice("EUR", 599.0, Instant.now().toEpochMilli(), true));
        OfferPayload payload = fixedAmountPayload();
        when(payload.getDirection()).thenReturn(OfferDirection.SELL);
        when(payload.getCounterCurrencyCode()).thenReturn("EUR");
        when(payload.getPaymentMethodId()).thenReturn(PaymentMethod.SEPA_INSTANT_ID);
        when(payload.getBuyerSecurityDepositPct()).thenReturn(0.0);
        when(payload.isUseMarketBasedPrice()).thenReturn(true);
        when(payload.getMarketPriceMarginPct()).thenReturn(0.0678);

        activateDuplicate(payload);
        assertEquals("71", model.volume.get());
        assertEquals("0.111", model.amount.get());
        assertEquals(model.amount.get(), model.minAmount.get());

        model.volume.set("72");
        assertEquals("0.1126", model.amount.get());
        assertEquals(model.amount.get(), model.minAmount.get());

        model.volume.set("70");
        assertEquals("0.1094", model.amount.get());
        assertEquals(model.amount.get(), model.minAmount.get());

        model.volume.set("71");
        assertEquals("0.111", model.amount.get());
        assertEquals(model.amount.get(), model.minAmount.get());
    }

    @Test
    public void testActivateKeepsFixedPriceWhenMarketPriceMoved() {
        for (boolean usePercentageBasedPrice : new boolean[]{false, true}) {
            setUp(usePercentageBasedPrice);
            when(priceFeedService.getMarketPrice(anyString())).thenReturn(new MarketPrice("USD", 600.0, Instant.now().toEpochMilli(), true));
            OfferPayload payload = fixedAmountPayload();
            when(payload.getPrice()).thenReturn(Price.parse("USD", "300").getValue());

            activateDuplicate(payload);
            assertEquals(Price.parse("USD", "300"), Price.parse("USD", model.price.get()));
            assertEquals(model.amount.get(), model.minAmount.get());
            assertEquals(FormattingUtils.formatToPercent(0.5), model.marketPriceMargin.get()); // 300 is 50% below the 600 market price
            assertEquals(0.5, model.getDataModel().getMarketPriceMarginPct());
        }
    }

    @Test
    public void testDuplicateKeepsRangeWhenVolumeChanges() {
        for (boolean useMarketBasedPrice : new boolean[]{false, true}) {
            setUp();
            when(priceFeedService.getMarketPrice(anyString())).thenReturn(new MarketPrice("USD", 600.0, Instant.now().toEpochMilli(), true));
            OfferPayload payload = fixedAmountPayload();
            when(payload.getMinAmount()).thenReturn(HavenoUtils.xmrToAtomicUnits(0.08).longValueExact());
            when(payload.getPrice()).thenReturn(Price.parse("USD", "300").getValue());
            when(payload.isUseMarketBasedPrice()).thenReturn(useMarketBasedPrice);
            when(payload.getMarketPriceMarginPct()).thenReturn(-0.01);

            activateDuplicate(payload);
            String expectedMinAmount = useMarketBasedPrice ? "0.0792" : "0.08";
            assertEquals(expectedMinAmount, model.minAmount.get());

            model.volume.set("100");
            assertEquals(useMarketBasedPrice ? "0.165" : "0.3333", model.amount.get());
            assertEquals(expectedMinAmount, model.minAmount.get());
        }
    }
}
