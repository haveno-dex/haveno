package haveno.desktop.main.portfolio.editoffer;

import haveno.desktop.util.validation.SecurityDepositValidator;

import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.btc.model.XmrAddressEntry;
import haveno.core.btc.wallet.XmrWalletService;
import haveno.core.locale.Country;
import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.offer.CreateOfferService;
import haveno.core.offer.OfferPayload;
import haveno.core.offer.OfferUtil;
import haveno.core.offer.OpenOffer;
import haveno.core.payment.CryptoCurrencyAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.provider.fee.FeeService;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.util.validation.InputValidator;

import org.bitcoinj.core.Coin;

import javafx.beans.property.SimpleIntegerProperty;

import javafx.collections.FXCollections;

import java.time.Instant;

import java.util.UUID;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import static haveno.desktop.maker.OfferMaker.btcBCHCOffer;
import static haveno.desktop.maker.PreferenceMakers.empty;
import static com.natpryce.makeiteasy.MakeItEasy.make;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class EditOfferDataModelTest {

    private EditOfferDataModel model;
    private User user;

    @Rule
    public final ExpectedException exception = ExpectedException.none();

    @Before
    public void setUp() {

        final CryptoCurrency xmr = new CryptoCurrency("XMR", "monero");
        GlobalSettings.setDefaultTradeCurrency(xmr);
        Res.setup();

        FeeService feeService = mock(FeeService.class);
        XmrAddressEntry addressEntry = mock(XmrAddressEntry.class);
        XmrWalletService xmrWalletService = mock(XmrWalletService.class);
        PriceFeedService priceFeedService = mock(PriceFeedService.class);
        user = mock(User.class);
        PaymentAccount paymentAccount = mock(PaymentAccount.class);
        Preferences preferences = mock(Preferences.class);
        SecurityDepositValidator securityDepositValidator = mock(SecurityDepositValidator.class);
        AccountAgeWitnessService accountAgeWitnessService = mock(AccountAgeWitnessService.class);
        CreateOfferService createOfferService = mock(CreateOfferService.class);
        OfferUtil offerUtil = mock(OfferUtil.class);

        when(xmrWalletService.getOrCreateAddressEntry(anyString(), any())).thenReturn(addressEntry);
        when(xmrWalletService.getBalanceForSubaddress(any(Integer.class))).thenReturn(Coin.valueOf(1000L));
        when(priceFeedService.updateCounterProperty()).thenReturn(new SimpleIntegerProperty());
        when(priceFeedService.getMarketPrice(anyString())).thenReturn(
                new MarketPrice("USD",
                        12684.0450,
                        Instant.now().getEpochSecond(),
                        true));
        when(feeService.getTxFee(anyInt())).thenReturn(Coin.valueOf(1000L));
        when(user.findFirstPaymentAccountWithCurrency(any())).thenReturn(paymentAccount);
        when(user.getPaymentAccountsAsObservable()).thenReturn(FXCollections.observableSet());
        when(securityDepositValidator.validate(any())).thenReturn(new InputValidator.ValidationResult(false));
        when(accountAgeWitnessService.getMyTradeLimit(any(), any(), any())).thenReturn(100000000L);
        when(preferences.getUserCountry()).thenReturn(new Country("US", "United States", null));
        when(createOfferService.getRandomOfferId()).thenReturn(UUID.randomUUID().toString());

        model = new EditOfferDataModel(createOfferService,
            null,
            offerUtil,
            xmrWalletService,
            empty,
            user,
            null,
            priceFeedService,
            accountAgeWitnessService,
            feeService,
            null,
            null,
            mock(TradeStatisticsManager.class),
            null);
    }

    @Test
    public void testEditOfferOfRemovedAsset() {

        final CryptoCurrencyAccount bitcoinClashicAccount = new CryptoCurrencyAccount();
        bitcoinClashicAccount.setId("BCHC");

        when(user.getPaymentAccount(anyString())).thenReturn(bitcoinClashicAccount);

        model.applyOpenOffer(new OpenOffer(make(btcBCHCOffer)));
        assertNull(model.getPreselectedPaymentAccount());
    }

    @Test
    public void testInitializeEditOfferWithRemovedAsset() {
        exception.expect(IllegalArgumentException.class);
        model.initWithData(OfferPayload.Direction.BUY, null);
    }
}
