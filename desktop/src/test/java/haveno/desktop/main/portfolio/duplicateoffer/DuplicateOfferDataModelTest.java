package haveno.desktop.main.portfolio.duplicateoffer;

import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.monetary.Price;
import haveno.core.offer.CreateOfferService;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferPayload;
import haveno.core.offer.OfferUtil;
import haveno.core.offer.OpenOfferManager;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.xmr.wallet.Restrictions;
import haveno.core.xmr.wallet.XmrWalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DuplicateOfferDataModelTest {

    private DuplicateOfferDataModel model;

    @BeforeEach
    public void setUp() {
        GlobalSettings.setDefaultTradeCurrency(new CryptoCurrency("XMR", "monero"));
        Res.setup();

        model = new DuplicateOfferDataModel(mock(CreateOfferService.class),
                mock(OpenOfferManager.class),
                mock(OfferUtil.class),
                mock(XmrWalletService.class),
                mock(Preferences.class),
                mock(User.class),
                null,
                mock(PriceFeedService.class),
                null,
                null,
                null);
    }

    @Test
    public void testPopulateDataKeepsSecurityDepositPercentWhenMinDepositApplies() {
        // 15% of 0.3 XMR is below the 0.1 XMR minimum deposit, which reads back as 33% of the amount
        OfferPayload payload = mock(OfferPayload.class);
        when(payload.getDirection()).thenReturn(OfferDirection.BUY);
        when(payload.getBaseCurrencyCode()).thenReturn("XMR");
        when(payload.getCounterCurrencyCode()).thenReturn("USD");
        when(payload.getPaymentMethodId()).thenReturn("SEPA");
        when(payload.getPrice()).thenReturn(Price.parse("USD", "300").getValue());
        when(payload.getAmount()).thenReturn(HavenoUtils.xmrToAtomicUnits(0.3).longValueExact());
        when(payload.getMinAmount()).thenReturn(HavenoUtils.xmrToAtomicUnits(0.3).longValueExact());
        when(payload.getSellerSecurityDepositPct()).thenReturn(0.15);
        when(payload.getMaxSellerSecurityDeposit()).thenReturn(Restrictions.getMinSecurityDeposit());

        model.populateData(new Offer(payload));
        assertEquals(0.15, model.getSecurityDepositPct().get());
    }
}
