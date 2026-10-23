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

package haveno.core.offer;

import haveno.common.UserThread;
import haveno.common.crypto.PubKeyRing;
import haveno.common.crypto.Sig;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.offer.availability.OfferAvailabilityModel;
import haveno.core.offer.availability.OfferAvailabilityProtocol;
import haveno.core.offer.messages.OfferAvailabilityResponse;
import haveno.network.p2p.DecryptedDirectMessageListener;
import haveno.network.p2p.DecryptedMessageWithPubKey;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import java.lang.reflect.Field;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.offer.placeoffer.tasks.ValidateOffer;
import haveno.core.payment.PaymentAccount;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.TradeManager;
import haveno.core.user.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

public class OfferTest {

    @Test
    public void availabilityReplyCanArriveWhenListenerIsRegistered() {
        Offer offer = mock(Offer.class);
        PubKeyRing maker = mock(PubKeyRing.class);
        when(maker.getSignaturePubKey()).thenReturn(Sig.generateKeyPair().getPublic());
        when(offer.getPubKeyRing()).thenReturn(maker);
        when(offer.getId()).thenReturn("offer");
        NodeAddress address = new NodeAddress("maker:9999");
        when(offer.getMakerNodeAddress()).thenReturn(address);
        P2PService p2p = mock(P2PService.class);
        OfferAvailabilityModel model = new OfferAvailabilityModel(offer, null, null, p2p,
                null, null, null, false, null, null, null);
        OfferAvailabilityResponse response = new OfferAvailabilityResponse("offer", AvailabilityResult.OFFER_TAKEN, null);
        doAnswer(invocation -> {
            DecryptedDirectMessageListener listener = invocation.getArgument(0);
            listener.onDirectMessage(new DecryptedMessageWithPubKey(response, maker.getSignaturePubKey()), address);
            return null;
        }).when(p2p).addDecryptedDirectMessageListener(any());
        try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class);
             MockedConstruction<TaskRunner> runners = mockConstruction(TaskRunner.class)) {
            new OfferAvailabilityProtocol(model, () -> {}, error -> {}).sendOfferAvailabilityRequest();
            assertEquals(response, model.getMessage());
        }
    }

    @Test
    public void availabilityRepliesMustComeFromExpectedMaker() throws Exception {
        OfferAvailabilityModel model = mock(OfferAvailabilityModel.class);
        Offer offer = mock(Offer.class);
        PubKeyRing maker = mock(PubKeyRing.class);
        when(maker.getSignaturePubKey()).thenReturn(Sig.generateKeyPair().getPublic());
        when(offer.getPubKeyRing()).thenReturn(maker);
        when(offer.getId()).thenReturn("offer");
        when(model.getOffer()).thenReturn(offer);
        NodeAddress address = new NodeAddress("maker:9999");
        when(model.getPeerNodeAddress()).thenReturn(address);
        OfferAvailabilityProtocol protocol = new OfferAvailabilityProtocol(model, () -> {}, error -> {});
        Field field = OfferAvailabilityProtocol.class.getDeclaredField("decryptedDirectMessageListener");
        field.setAccessible(true);
        DecryptedDirectMessageListener listener = (DecryptedDirectMessageListener) field.get(protocol);
        OfferAvailabilityResponse response = new OfferAvailabilityResponse("offer", AvailabilityResult.OFFER_TAKEN, null);
        listener.onDirectMessage(new DecryptedMessageWithPubKey(response, Sig.generateKeyPair().getPublic()), address);
        listener.onDirectMessage(new DecryptedMessageWithPubKey(response, maker.getSignaturePubKey()), new NodeAddress("attacker:9999"));
        verify(model, never()).setMessage(any());
        try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class);
             MockedConstruction<TaskRunner> runners = mockConstruction(TaskRunner.class)) {
            listener.onDirectMessage(new DecryptedMessageWithPubKey(response, maker.getSignaturePubKey()), address);
            verify(model).setMessage(response);
            assertEquals(1, runners.constructed().size());
        }
    }

    @Test
    public void statisticsPricesCannotActivateOrDeactivateOffers() {
        OpenOfferManager manager = mock(OpenOfferManager.class);
        TriggerPriceService service = new TriggerPriceService(null, manager, null);
        OpenOffer offer = mock(OpenOffer.class);
        for (OpenOffer.State state : new OpenOffer.State[]{OpenOffer.State.AVAILABLE, OpenOffer.State.DEACTIVATED}) {
            when(offer.getState()).thenReturn(state);
            when(offer.isDeactivatedByTrigger()).thenReturn(true);
            service.checkPriceThreshold(new MarketPrice("USD", 100, System.currentTimeMillis(), false), offer);
            service.checkPriceThreshold(new MarketPrice("USD", 100, 0, true), offer);
        }
        verifyNoInteractions(manager);
    }


    @Test
    public void testMarketPriceMarginBoundsRequirePositivePrice() {
        assertTrue(OfferRestrictions.isValidMarketPriceMargin(OfferDirection.BUY, -1));
        assertFalse(OfferRestrictions.isValidMarketPriceMargin(OfferDirection.BUY, 1));
        assertTrue(OfferRestrictions.isValidMarketPriceMargin(OfferDirection.SELL, 1));
        assertFalse(OfferRestrictions.isValidMarketPriceMargin(OfferDirection.SELL, -1));
        assertFalse(OfferRestrictions.isValidMarketPriceMargin(null, 0));

        for (OfferDirection direction : OfferDirection.values()) {
            for (double margin : new double[]{-0.9999, -0.5, 0, 0.5, 0.9999}) {
                assertTrue(OfferRestrictions.isValidMarketPriceMargin(direction, margin));
            }
            for (double margin : new double[]{-1.0001, 1.0001, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
                assertFalse(OfferRestrictions.isValidMarketPriceMargin(direction, margin));
            }
        }
    }

    @Test
    public void testFullMarketPriceDeviationDoublesPrice() {
        assertEquals(200, marketOffer(OfferDirection.BUY, -1, 100).getPrice().getDoubleValue());
        assertEquals(200, marketOffer(OfferDirection.SELL, 1, 100).getPrice().getDoubleValue());
    }

    @Test
    public void testDiscountedPriceRoundingToZeroIsUnavailable() {
        assertNull(marketOffer(OfferDirection.BUY, 0.9999, 0.00000001).getPrice());
        assertNull(marketOffer(OfferDirection.SELL, -0.9999, 0.00000001).getPrice());
        assertEquals(0.00000001, marketOffer(OfferDirection.BUY, 0.9999, 0.0001).getPrice().getDoubleValue());
    }

    private Offer marketOffer(OfferDirection direction, double margin, double marketPrice) {
        OfferPayload payload = mock(OfferPayload.class);
        when(payload.getBaseCurrencyCode()).thenReturn("XMR");
        when(payload.getCounterCurrencyCode()).thenReturn("USD");
        when(payload.getDirection()).thenReturn(direction);
        when(payload.isUseMarketBasedPrice()).thenReturn(true);
        when(payload.getMarketPriceMarginPct()).thenReturn(margin);
        PriceFeedService priceFeedService = mock(PriceFeedService.class);
        when(priceFeedService.getMarketPrice("USD")).thenReturn(new MarketPrice("USD", marketPrice, System.currentTimeMillis(), true));
        Offer offer = new Offer(payload);
        offer.setPriceFeedService(priceFeedService);
        return offer;
    }

    @Test
    public void testPlacementRequiresPositiveMinimumPaymentOnlyWhenPriceRequired() {
        OfferPayload payload = mock(OfferPayload.class);
        when(payload.getId()).thenReturn("offer");
        when(payload.getBaseCurrencyCode()).thenReturn("XMR");
        when(payload.getCounterCurrencyCode()).thenReturn("BTC");
        when(payload.getPaymentMethodId()).thenReturn("BLOCK_CHAINS");
        when(payload.getDirection()).thenReturn(OfferDirection.SELL);
        when(payload.getMakerPaymentAccountId()).thenReturn("account");
        when(payload.getMinAmount()).thenReturn(100_000_000_000L);
        when(payload.getAmount()).thenReturn(1_000_000_000_000L);
        when(payload.getMaxTradeLimit()).thenReturn(1_000_000_000_000L);
        when(payload.getPrice()).thenReturn(1L);
        when(payload.getBuyerSecurityDepositPct()).thenReturn(0.15);
        when(payload.getSellerSecurityDepositPct()).thenReturn(0.15);
        when(payload.getDate()).thenReturn(System.currentTimeMillis());
        when(payload.getPubKeyRing()).thenReturn(mock(PubKeyRing.class));
        when(payload.getVersionNr()).thenReturn("1.0.0");
        when(payload.getMaxTradePeriod()).thenReturn(1L);
        Offer offer = new Offer(payload);
        PaymentAccount paymentAccount = mock(PaymentAccount.class);
        User user = mock(User.class);
        when(user.getPaymentAccount("account")).thenReturn(paymentAccount);
        AccountAgeWitnessService accountAgeWitnessService = mock(AccountAgeWitnessService.class);
        when(accountAgeWitnessService.getMyTradeLimit(paymentAccount, "BTC", OfferDirection.SELL, false)).thenReturn(1_000_000_000_000L);
        TradeManager originalTradeManager = HavenoUtils.tradeManager;
        HavenoUtils.tradeManager = mock(TradeManager.class);
        try {
            for (long invalidPrice : new long[]{0, -1}) {
                when(payload.getPrice()).thenReturn(invalidPrice);
                for (boolean requirePrice : new boolean[]{true, false}) {
                    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                            () -> ValidateOffer.validateOffer(offer, accountAgeWitnessService, user, requirePrice));
                    assertEquals("Price must be positive unless using market based price. price=" + offer.getPrice().toFriendlyString(), error.getMessage());
                }
            }
            when(payload.getPrice()).thenReturn(1L);

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> ValidateOffer.validateOffer(offer, accountAgeWitnessService, user));
            assertEquals("Minimum payment amount must be positive at the current price", error.getMessage());
            assertDoesNotThrow(() -> ValidateOffer.validateOffer(offer, accountAgeWitnessService, user, false));

            when(payload.getMinAmount()).thenReturn(999_950_000_000L);
            assertEquals(1, offer.getMinVolume().getValue());
            assertThrows(IllegalArgumentException.class, () -> offer.verifyTradePrice(1L));
            assertThrows(IllegalArgumentException.class,
                    () -> ValidateOffer.validateOffer(offer, accountAgeWitnessService, user));

            when(payload.getMinAmount()).thenReturn(1_000_000_000_000L);
            assertDoesNotThrow(() -> ValidateOffer.validateOffer(offer, accountAgeWitnessService, user));

            when(payload.isUseMarketBasedPrice()).thenReturn(true);
            offer.setPriceFeedService(mock(PriceFeedService.class));
            assertNull(offer.getPrice());
            assertDoesNotThrow(() -> ValidateOffer.validateOffer(offer, accountAgeWitnessService, user, false));
        } finally {
            HavenoUtils.tradeManager = originalTradeManager;
        }
    }

    @Test
    public void testRejectsTradePriceWhoseMinimumPaymentRoundsToZero() {
        OfferPayload payload = mock(OfferPayload.class);
        when(payload.getBaseCurrencyCode()).thenReturn("XMR");
        when(payload.getCounterCurrencyCode()).thenReturn("BTC");
        when(payload.getPaymentMethodId()).thenReturn("BLOCK_CHAINS");
        when(payload.getMinAmount()).thenReturn(100_000_000_000L);
        when(payload.getPrice()).thenReturn(1L);
        Offer offer = new Offer(payload);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> offer.verifyTradePrice(1L));
        assertEquals("Trade payment amount must not round to zero", error.getMessage());
    }

    @Test
    public void testAcceptsTradePricePayingOneSmallestUnit() {
        OfferPayload payload = mock(OfferPayload.class);
        when(payload.getBaseCurrencyCode()).thenReturn("XMR");
        when(payload.getCounterCurrencyCode()).thenReturn("BTC");
        when(payload.getPaymentMethodId()).thenReturn("BLOCK_CHAINS");
        when(payload.getMinAmount()).thenReturn(1_000_000_000_000L);
        when(payload.getPrice()).thenReturn(1L);

        assertDoesNotThrow(() -> new Offer(payload).verifyTradePrice(1L));
    }

    @Test
    public void testHasNoRange() {
        OfferPayload payload = mock(OfferPayload.class);
        when(payload.getMinAmount()).thenReturn(1000L);
        when(payload.getAmount()).thenReturn(1000L);

        Offer offer = new Offer(payload);
        assertFalse(offer.isRange());
    }

    @Test
    public void testHasRange() {
        OfferPayload payload = mock(OfferPayload.class);
        when(payload.getMinAmount()).thenReturn(1000L);
        when(payload.getAmount()).thenReturn(2000L);

        Offer offer = new Offer(payload);
        assertTrue(offer.isRange());
    }
}
