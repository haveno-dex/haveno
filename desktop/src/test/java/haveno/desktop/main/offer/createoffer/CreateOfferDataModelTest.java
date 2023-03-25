package haveno.desktop.main.offer.createoffer;

import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.FiatCurrency;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.offer.CreateOfferService;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferUtil;
import haveno.core.payment.ZelleAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.RevolutAccount;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.core.xmr.wallet.XmrWalletService;
import javafx.collections.FXCollections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CreateOfferDataModelTest {

    private CreateOfferDataModel model;
    private User user;
    private Preferences preferences;
    private OfferUtil offerUtil;

    @BeforeEach
    public void setUp() {
        final CryptoCurrency xmr = new CryptoCurrency("XMR", "monero");
        GlobalSettings.setDefaultTradeCurrency(xmr);
        Res.setup();

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
        when(preferences.getBuyerSecurityDepositAsPercent(null)).thenReturn(0.01);
        when(createOfferService.getRandomOfferId()).thenReturn(UUID.randomUUID().toString());
        when(tradeStats.getObservableTradeStatisticsSet()).thenReturn(FXCollections.observableSet());

        model = new CreateOfferDataModel(createOfferService,
                null,
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
        revolutAccount.setSingleTradeCurrency(new FiatCurrency("EUR"));
        revolutAccount.addCurrency(new FiatCurrency("USD"));
        paymentAccounts.add(revolutAccount);

        when(user.getPaymentAccounts()).thenReturn(paymentAccounts);
        when(preferences.getSelectedPaymentAccountForCreateOffer()).thenReturn(revolutAccount);

        model.initWithData(OfferDirection.BUY, new FiatCurrency("USD"));
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
        revolutAccount.setSingleTradeCurrency(new FiatCurrency("EUR"));
        paymentAccounts.add(revolutAccount);

        when(user.getPaymentAccounts()).thenReturn(paymentAccounts);
        when(user.findFirstPaymentAccountWithCurrency(new FiatCurrency("USD"))).thenReturn(zelleAccount);
        when(preferences.getSelectedPaymentAccountForCreateOffer()).thenReturn(revolutAccount);

        model.initWithData(OfferDirection.BUY, new FiatCurrency("USD"));
        assertEquals("USD", model.getTradeCurrencyCode().get());
    }
}
