package haveno.desktop.main.offer.createoffer;

import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.monetary.Price;
import haveno.core.offer.CreateOfferService;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferPayload;
import haveno.core.offer.OpenOffer;
import haveno.core.offer.OfferUtil;
import haveno.core.offer.OpenOfferManager;
import haveno.core.payment.ZelleAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.RevolutAccount;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.TradeManager;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.desktop.main.offer.MutableOfferDataModel.PaymentAmountConflict;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.core.xmr.wallet.XmrWalletService;
import javafx.collections.FXCollections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CreateOfferDataModelTest {

    private CreateOfferDataModel model;
    private User user;
    private Preferences preferences;
    private OfferUtil offerUtil;
    private OpenOfferManager openOfferManager;
    private TradeManager tradeManager;
    private TradeManager originalTradeManager;

    @BeforeEach
    public void setUp() {
        final CryptoCurrency xmr = new CryptoCurrency("XMR", "monero");
        GlobalSettings.setDefaultTradeCurrency(xmr);
        Res.setup();

        openOfferManager = mock(OpenOfferManager.class);
        originalTradeManager = HavenoUtils.tradeManager;
        tradeManager = mock(TradeManager.class);
        HavenoUtils.tradeManager = tradeManager;
        XmrAddressEntry addressEntry = mock(XmrAddressEntry.class);
        XmrWalletService xmrWalletService = mock(XmrWalletService.class);
        PriceFeedService priceFeedService = mock(PriceFeedService.class);
        CreateOfferService createOfferService = mock(CreateOfferService.class);
        preferences = mock(Preferences.class);
        offerUtil = mock(OfferUtil.class);
        user = mock(User.class);
        var tradeStats = mock(TradeStatisticsManager.class);

        when(xmrWalletService.getOrCreateAddressEntry(anyString(), any())).thenReturn(addressEntry);
        when(preferences.isUsePercentageBasedPrice()).thenReturn(true);
        when(preferences.getSecurityDepositAsPercent(null)).thenReturn(0.01);
        when(createOfferService.getRandomOfferId()).thenReturn(UUID.randomUUID().toString());
        when(tradeStats.getObservableTradeStatisticsList()).thenReturn(FXCollections.observableArrayList());

        model = new CreateOfferDataModel(createOfferService,
                openOfferManager,
                offerUtil,
                xmrWalletService,
                preferences,
                user,
                null,
                priceFeedService,
                null,
                null,
                tradeStats,
                null);
    }

    @Test
    public void testUseTradeCurrencySetInOfferViewWhenInPaymentAccountAvailable() {
        final HashSet<PaymentAccount> paymentAccounts = new HashSet<>();
        final ZelleAccount zelleAccount = new ZelleAccount();
        zelleAccount.setId("234");
        zelleAccount.setAccountName("zelleAccount");
        paymentAccounts.add(zelleAccount);
        final RevolutAccount revolutAccount = new RevolutAccount();
        revolutAccount.setId("123");
        revolutAccount.setAccountName("revolutAccount");
        revolutAccount.setSingleTradeCurrency(new TraditionalCurrency("EUR"));
        revolutAccount.addCurrency(new TraditionalCurrency("USD"));
        paymentAccounts.add(revolutAccount);

        when(user.getPaymentAccounts()).thenReturn(paymentAccounts);
        when(preferences.getSelectedPaymentAccountForCreateOffer()).thenReturn(revolutAccount);

        model.initWithData(OfferDirection.BUY, new TraditionalCurrency("USD"), true);
        assertEquals("USD", model.getTradeCurrencyCode().get());
    }

    @Test
    public void testUseTradeAccountThatMatchesTradeCurrencySetInOffer() {
        final HashSet<PaymentAccount> paymentAccounts = new HashSet<>();
        final ZelleAccount zelleAccount = new ZelleAccount();
        zelleAccount.setId("234");
        zelleAccount.setAccountName("zelleAccount");
        paymentAccounts.add(zelleAccount);
        final RevolutAccount revolutAccount = new RevolutAccount();
        revolutAccount.setId("123");
        revolutAccount.setAccountName("revolutAccount");
        revolutAccount.setSingleTradeCurrency(new TraditionalCurrency("EUR"));
        paymentAccounts.add(revolutAccount);

        when(user.getPaymentAccounts()).thenReturn(paymentAccounts);
        when(user.findFirstPaymentAccountWithCurrency(new TraditionalCurrency("USD"))).thenReturn(zelleAccount);
        when(preferences.getSelectedPaymentAccountForCreateOffer()).thenReturn(revolutAccount);

        model.initWithData(OfferDirection.BUY, new TraditionalCurrency("USD"), true);
        assertEquals("USD", model.getTradeCurrencyCode().get());
    }

    @AfterEach
    public void tearDown() {
        HavenoUtils.tradeManager = originalTradeManager;
    }

    @Test
    public void testUnresolvedTradeWarningTakesPrecedenceOverMatchingOffer() {
        Offer offer = paymentOffer("new", OfferDirection.BUY, "USD", "account", 100_000_000_000L);
        Offer other = paymentOffer("existing", OfferDirection.BUY, "USD", "account", 100_000_000_000L);
        when(tradeManager.hasAmbiguousPayment(offer, offer.getVolume())).thenReturn(true);
        when(openOfferManager.getOpenOffers()).thenReturn(List.of(new OpenOffer(other)));

        assertEquals(PaymentAmountConflict.TRADE, model.getPaymentAmountConflict(offer, offer.getVolume()));
        when(tradeManager.hasAmbiguousPayment(offer, offer.getVolume())).thenReturn(false);
        assertEquals(PaymentAmountConflict.OFFER, model.getPaymentAmountConflict(offer, offer.getVolume()));
    }

    @Test
    public void testMatchingOfferUsesRoundedPaymentInsteadOfXmrAmount() {
        Offer offer = paymentOffer("new", OfferDirection.BUY, "USD", "account", 100_000_000_000L);
        Offer other = paymentOffer("existing", OfferDirection.BUY, "USD", "account", 100_100_000_000L);
        assertNotEquals(offer.getAmount(), other.getAmount());
        assertEquals(offer.getVolume(), other.getVolume());
        when(openOfferManager.getOpenOffers()).thenReturn(List.of(new OpenOffer(other)));

        assertEquals(PaymentAmountConflict.OFFER, model.getPaymentAmountConflict(offer, offer.getVolume()));
    }

    @Test
    public void testMatchingOfferRequiresSameAccountCurrencyDirectionAndPaymentAmount() {
        Offer offer = paymentOffer("new", OfferDirection.BUY, "USD", "account", 100_000_000_000L);
        List<Offer> nonMatchingOffers = List.of(
                paymentOffer("account", OfferDirection.BUY, "USD", "other", 100_000_000_000L),
                paymentOffer("currency", OfferDirection.BUY, "EUR", "account", 100_000_000_000L),
                paymentOffer("direction", OfferDirection.SELL, "USD", "account", 100_000_000_000L),
                paymentOffer("amount", OfferDirection.BUY, "USD", "account", 200_000_000_000L));
        for (Offer other : nonMatchingOffers) {
            when(openOfferManager.getOpenOffers()).thenReturn(List.of(new OpenOffer(other)));
            assertEquals(PaymentAmountConflict.NONE, model.getPaymentAmountConflict(offer, offer.getVolume()), other.getId());
        }
    }

    @Test
    public void testMatchingOfferIgnoresSelfAndInactiveOffers() {
        Offer offer = paymentOffer("new", OfferDirection.BUY, "USD", "account", 100_000_000_000L);
        OpenOffer other = new OpenOffer(paymentOffer("existing", OfferDirection.BUY, "USD", "account", 100_000_000_000L));
        when(openOfferManager.getOpenOffers()).thenReturn(List.of(new OpenOffer(offer), other));
        other.setState(OpenOffer.State.DEACTIVATED);
        assertEquals(PaymentAmountConflict.NONE, model.getPaymentAmountConflict(offer, offer.getVolume()));
        other.setState(OpenOffer.State.CANCELED);
        assertEquals(PaymentAmountConflict.NONE, model.getPaymentAmountConflict(offer, offer.getVolume()));
        other.setState(OpenOffer.State.AVAILABLE);
        assertEquals(PaymentAmountConflict.OFFER, model.getPaymentAmountConflict(offer, offer.getVolume()));
    }

    @Test
    public void testUnknownPaymentAmountDoesNotWarn() {
        Offer offer = paymentOffer("new", OfferDirection.BUY, "USD", "account", 100_000_000_000L);
        assertEquals(PaymentAmountConflict.NONE, model.getPaymentAmountConflict(offer, null));
    }

    private Offer paymentOffer(String id, OfferDirection direction, String currencyCode, String accountId, long amount) {
        OfferPayload payload = mock(OfferPayload.class);
        when(payload.getId()).thenReturn(id);
        when(payload.getDirection()).thenReturn(direction);
        when(payload.getBaseCurrencyCode()).thenReturn("XMR");
        when(payload.getCounterCurrencyCode()).thenReturn(currencyCode);
        when(payload.getMakerPaymentAccountId()).thenReturn(accountId);
        when(payload.getPaymentMethodId()).thenReturn("SEPA");
        when(payload.getPrice()).thenReturn(Price.parse(currencyCode, "300").getValue());
        when(payload.getAmount()).thenReturn(amount);
        when(payload.getMinAmount()).thenReturn(amount);
        return new Offer(payload);
    }

}
