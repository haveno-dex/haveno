package haveno.core.api;

import haveno.common.crypto.KeyRing;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferBookService;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferFilterService;
import haveno.core.offer.OfferPayload;
import haveno.core.offer.OfferUtil;
import haveno.core.offer.OpenOffer;
import haveno.core.offer.OpenOfferManager;
import haveno.core.offer.CreateOfferService;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.proto.persistable.CorePersistenceProtoResolver;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

public class CoreOffersServiceTest {

    @Test
    public void testEditedOfferUsesNewPaymentMethodTradePeriod() {
        OpenOffer openOffer = new OpenOffer(new Offer(createPayload(PaymentMethod.SEPA_ID, PaymentMethod.SEPA.getMaxTradePeriod())));
        OfferPayload newPayload = createPayload(PaymentMethod.ZELLE_ID, PaymentMethod.ZELLE.getMaxTradePeriod());

        OfferPayload editedPayload = createService().getEditedOffer(openOffer, newPayload).getOfferPayload();
        assertEquals(PaymentMethod.ZELLE_ID, editedPayload.getPaymentMethodId());
        assertEquals(PaymentMethod.ZELLE.getMaxTradePeriod(), editedPayload.getMaxTradePeriod());
    }

    @Test
    public void testEditedOfferRefreshesTradePeriodForSamePaymentMethod() {
        // editing republishes the offer with the payment method's current period
        long grandfatheredPeriod = PaymentMethod.ZELLE.getMaxTradePeriod() * 4;
        OpenOffer openOffer = new OpenOffer(new Offer(createPayload(PaymentMethod.ZELLE_ID, grandfatheredPeriod)));
        OfferPayload newPayload = createPayload(PaymentMethod.ZELLE_ID, PaymentMethod.ZELLE.getMaxTradePeriod());

        OfferPayload editedPayload = createService().getEditedOffer(openOffer, newPayload).getOfferPayload();
        assertEquals(PaymentMethod.ZELLE.getMaxTradePeriod(), editedPayload.getMaxTradePeriod());
    }

    private static CoreOffersService createService() {
        return new CoreOffersService(new CoreContext(),
                mock(KeyRing.class),
                mock(CoreWalletsService.class),
                mock(CreateOfferService.class),
                mock(OfferBookService.class),
                mock(OfferFilterService.class),
                mock(OpenOfferManager.class),
                mock(OfferUtil.class),
                mock(User.class),
                mock(PriceFeedService.class),
                mock(CorePersistenceProtoResolver.class));
    }

    private static OfferPayload createPayload(String paymentMethodId, long maxTradePeriod) {
        return new OfferPayload("offer-id",
                0L,
                null,
                null,
                OfferDirection.BUY,
                100000L,
                0.0,
                false,
                100000L,
                100000L,
                HavenoUtils.getMakerFeePct("USD", false),
                HavenoUtils.getTakerFeePct("USD", false),
                0.0,
                0.0,
                0.0,
                "XMR",
                "USD",
                paymentMethodId,
                "account-id",
                null,
                null,
                null,
                null,
                "",
                0L,
                0L,
                maxTradePeriod,
                false,
                false,
                0L,
                0L,
                false,
                null,
                null,
                0,
                null,
                null,
                null,
                null);
    }
}
