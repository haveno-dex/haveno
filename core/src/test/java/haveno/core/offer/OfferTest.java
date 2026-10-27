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

import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.PubKeyRing;
import haveno.common.crypto.Sig;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.Res;
import haveno.core.offer.availability.OfferAvailabilityModel;
import haveno.core.offer.availability.OfferAvailabilityProtocol;
import haveno.core.offer.messages.OfferAvailabilityRequest;
import haveno.core.offer.messages.OfferAvailabilityResponse;
import haveno.core.offer.placeoffer.tasks.ValidateOffer;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.TradeManager;
import haveno.core.user.User;
import haveno.core.xmr.exceptions.WalletUnavailableException;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.core.xmr.wallet.XmrWalletBase;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.SendDirectMessageListener;
import haveno.network.p2p.DecryptedDirectMessageListener;
import haveno.network.p2p.DecryptedMessageWithPubKey;
import haveno.network.p2p.network.NetworkNode;
import monero.wallet.MoneroWallet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

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
        XmrWalletService wallet = mock(XmrWalletService.class);
        when(wallet.getOrCreateAddressEntry("offer", XmrAddressEntry.Context.TRADE_PAYOUT))
                .thenReturn(new XmrAddressEntry(1, "payout-address", XmrAddressEntry.Context.TRADE_PAYOUT, "offer", null));
        OfferAvailabilityModel model = new OfferAvailabilityModel(offer, null, wallet, p2p,
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
            ArgumentCaptor<Runnable> dispatch = ArgumentCaptor.forClass(Runnable.class);
            userThread.verify(() -> UserThread.execute(dispatch.capture()));
            dispatch.getValue().run();
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
        try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class);
             MockedConstruction<TaskRunner> runners = mockConstruction(TaskRunner.class)) {
            listener.onDirectMessage(new DecryptedMessageWithPubKey(response, Sig.generateKeyPair().getPublic()), address);
            listener.onDirectMessage(new DecryptedMessageWithPubKey(response, maker.getSignaturePubKey()), new NodeAddress("attacker:9999"));
            userThread.verifyNoInteractions();
            listener.onDirectMessage(new DecryptedMessageWithPubKey(response, maker.getSignaturePubKey()), address);
            ArgumentCaptor<Runnable> dispatch = ArgumentCaptor.forClass(Runnable.class);
            userThread.verify(() -> UserThread.execute(dispatch.capture()));
            dispatch.getValue().run();
            verify(model).setMessage(response);
            assertEquals(1, runners.constructed().size());
        }
    }

    @Test
    public void availabilityRecoversWalletBeforeStartingPeerTimeout() {
        AvailabilityFixture fixture = new AvailabilityFixture();
        when(fixture.wallet.getOrCreateAddressEntry("offer", XmrAddressEntry.Context.TRADE_PAYOUT))
                .thenThrow(new IllegalStateException("Connection refused"))
                .thenThrow(new WalletUnavailableException("Main wallet is not available"))
                .thenReturn(fixture.address);
        try (MockedStatic<UserThread> ignored = fixture.mockTimers()) {
            fixture.protocol.sendOfferAvailabilityRequest();

            assertEquals("payout-address", fixture.model.getTradeRequest().getPayoutAddress());
            verify(fixture.wallet, times(3)).getOrCreateAddressEntry("offer", XmrAddressEntry.Context.TRADE_PAYOUT);
            verifyNoMoreInteractions(fixture.wallet);
            ArgumentCaptor<SendDirectMessageListener> sent = ArgumentCaptor.forClass(SendDirectMessageListener.class);
            verify(fixture.p2p).sendEncryptedDirectMessage(any(), any(), any(OfferAvailabilityRequest.class), sent.capture());
            assertEquals(2, fixture.timeouts.size());
            fixture.timeouts.get(210L).run(); // a queued preparation timeout must not cancel the peer phase
            verifyNoInteractions(fixture.failure);
            fixture.timeouts.get(90L).run();
            verify(fixture.failure).handleErrorMessage("Timeout reached: Peer has not responded.");
            assertEquals(Offer.State.MAKER_OFFLINE, fixture.offer.getState());
            sent.getValue().onArrived();
            sent.getValue().onFault("late network failure");
            verifyNoInteractions(fixture.success);
            verifyNoMoreInteractions(fixture.failure);
        }
    }

    @Test
    public void availabilityDoesNotSendAfterCanceledOrTimedOutWalletPreparation() throws Exception {
        for (boolean timeout : new boolean[]{false, true}) {
            AvailabilityFixture fixture = new AvailabilityFixture();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            when(fixture.wallet.getOrCreateAddressEntry("offer", XmrAddressEntry.Context.TRADE_PAYOUT)).thenAnswer(invocation -> {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return fixture.address;
            });
            CompletableFuture<Void> preparing = CompletableFuture.runAsync(() -> {
                try (MockedStatic<UserThread> ignored = fixture.mockTimers()) {
                    fixture.protocol.sendOfferAvailabilityRequest();
                }
            });
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                if (timeout) fixture.timeouts.get(210L).run();
                else fixture.protocol.cancel();
            } finally {
                release.countDown();
                preparing.get(10, TimeUnit.SECONDS);
            }
            assertEquals(Offer.State.UNKNOWN, fixture.offer.getState());
            assertNull(fixture.model.getTradeRequest());
            assertEquals(1, fixture.timeouts.size());
            verify(fixture.p2p, never()).sendEncryptedDirectMessage(any(), any(), any(), any());
            if (timeout) verify(fixture.failure).handleErrorMessage(Res.get("takeOffer.walletNotReady"));
            else verifyNoInteractions(fixture.failure);
        }
    }

    @Test
    public void availabilityReportsPermanentWalletErrorWithoutRetrying() {
        AvailabilityFixture fixture = new AvailabilityFixture();
        when(fixture.wallet.getOrCreateAddressEntry("offer", XmrAddressEntry.Context.TRADE_PAYOUT))
                .thenThrow(new IllegalArgumentException("Invalid address entry"));
        try (MockedStatic<UserThread> ignored = fixture.mockTimers()) {
            fixture.protocol.sendOfferAvailabilityRequest();
            fixture.timeouts.get(210L).run();
            verify(fixture.wallet).getOrCreateAddressEntry("offer", XmrAddressEntry.Context.TRADE_PAYOUT);
            verify(fixture.failure).handleErrorMessage("Invalid address entry");
            verifyNoMoreInteractions(fixture.failure);
            verify(fixture.p2p, never()).sendEncryptedDirectMessage(any(), any(), any(), any());
            assertEquals(Offer.State.UNKNOWN, fixture.offer.getState());
        }
    }

    @Test
    public void availabilityCanBeCanceledBeforePreparationStarts() {
        AvailabilityFixture fixture = new AvailabilityFixture();
        fixture.protocol.cancel();
        fixture.protocol.sendOfferAvailabilityRequest();
        verifyNoInteractions(fixture.wallet, fixture.success, fixture.failure);
        verify(fixture.p2p, never()).sendEncryptedDirectMessage(any(), any(), any(), any());
    }

    @Test
    public void availabilityQueuesResponsesAndIgnoresThemAfterCancellation() {
        for (boolean cancel : new boolean[]{false, true}) {
            AvailabilityFixture fixture = new AvailabilityFixture();
            when(fixture.wallet.getOrCreateAddressEntry("offer", XmrAddressEntry.Context.TRADE_PAYOUT)).thenReturn(fixture.address);
            try (MockedStatic<UserThread> userThread = fixture.mockTimers()) {
                fixture.protocol.sendOfferAvailabilityRequest();
                Runnable peerTimeout = fixture.timeouts.get(90L);
                ArgumentCaptor<DecryptedDirectMessageListener> listener = ArgumentCaptor.forClass(DecryptedDirectMessageListener.class);
                verify(fixture.p2p).addDecryptedDirectMessageListener(listener.capture());
                ArgumentCaptor<Runnable> dispatch = ArgumentCaptor.forClass(Runnable.class);
                userThread.when(() -> UserThread.execute(dispatch.capture())).thenAnswer(invocation -> null);
                DecryptedMessageWithPubKey decrypted = new DecryptedMessageWithPubKey(
                        new OfferAvailabilityResponse("offer", AvailabilityResult.AVAILABLE, new byte[]{1}), fixture.maker.getSignaturePubKey());

                listener.getValue().onDirectMessage(decrypted, new NodeAddress("maker:9999"));
                assertNull(fixture.model.getMessage());
                verifyNoInteractions(fixture.success);
                if (cancel) fixture.protocol.cancel();
                dispatch.getValue().run();
                peerTimeout.run();

                assertEquals(cancel ? Offer.State.UNKNOWN : Offer.State.AVAILABLE, fixture.offer.getState());
                if (cancel) verifyNoInteractions(fixture.success);
                else verify(fixture.success).handleResult();
                verifyNoInteractions(fixture.failure);
                fixture.protocol.cancel();
            }
        }
    }

    @Test
    public void availabilityReportsWalletErrorWithoutMessage() {
        AvailabilityFixture fixture = new AvailabilityFixture();
        when(fixture.wallet.getOrCreateAddressEntry("offer", XmrAddressEntry.Context.TRADE_PAYOUT))
                .thenThrow(new NullPointerException());
        try (MockedStatic<UserThread> ignored = fixture.mockTimers()) {
            fixture.protocol.sendOfferAvailabilityRequest();
            verify(fixture.failure).handleErrorMessage("java.lang.NullPointerException");
            assertEquals("An error occurred.\nError message:\njava.lang.NullPointerException", fixture.offer.getErrorMessage());
            verify(fixture.p2p, never()).sendEncryptedDirectMessage(any(), any(), any(), any());
        }
    }

    @Test
    public void addressCreationRecognizesUnavailableOrReplacedWallet() throws Exception {
        XmrWalletService service = mock(XmrWalletService.class, CALLS_REAL_METHODS);
        setWalletField(XmrWalletBase.class, service, "walletLock", new Object());
        setWalletField(XmrWalletService.class, service, "addressEntryLock", new Object());
        assertThrows(WalletUnavailableException.class, service::getNewAddressEntry);

        MoneroWallet wallet = mock(MoneroWallet.class);
        IllegalStateException reset = new IllegalStateException("Connection reset");
        setWalletField(XmrWalletBase.class, service, "wallet", wallet);
        when(wallet.createSubaddress(0)).thenAnswer(invocation -> {
            setWalletField(XmrWalletBase.class, service, "wallet", null);
            throw reset;
        });
        assertSame(reset, assertThrows(WalletUnavailableException.class, service::getNewAddressEntry).getCause());

        doThrow(reset).when(wallet).createSubaddress(0);
        setWalletField(XmrWalletBase.class, service, "wallet", wallet);
        assertSame(reset, assertThrows(IllegalStateException.class, service::getNewAddressEntry));
    }

    private static void setWalletField(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class AvailabilityFixture {
        final XmrWalletService wallet = mock(XmrWalletService.class);
        final P2PService p2p = mock(P2PService.class);
        final ResultHandler success = mock(ResultHandler.class);
        final ErrorMessageHandler failure = mock(ErrorMessageHandler.class);
        final Map<Long, Runnable> timeouts = new ConcurrentHashMap<>();
        final XmrAddressEntry address = new XmrAddressEntry(1, "payout-address", XmrAddressEntry.Context.TRADE_PAYOUT, "offer", null);
        final PubKeyRing maker = mock(PubKeyRing.class);
        final Offer offer;
        final OfferAvailabilityModel model;
        final OfferAvailabilityProtocol protocol;

        AvailabilityFixture() {
            Res.setBaseCurrencyCode("XMR");
            Res.setBaseCurrencyName("Monero");
            OfferPayload payload = mock(OfferPayload.class);
            when(payload.getId()).thenReturn("offer");
            when(payload.getBaseCurrencyCode()).thenReturn("XMR");
            when(payload.getCounterCurrencyCode()).thenReturn("BTC");
            when(payload.getPrice()).thenReturn(100_000_000L);
            when(payload.getOwnerNodeAddress()).thenReturn(new NodeAddress("maker:9999"));
            when(maker.getSignaturePubKey()).thenReturn(Sig.generateKeyPair().getPublic());
            when(payload.getPubKeyRing()).thenReturn(maker);
            offer = new Offer(payload);
            KeyRing keys = mock(KeyRing.class);
            when(keys.getSignatureKeyPair()).thenReturn(Sig.generateKeyPair());
            when(keys.getPubKeyRing()).thenReturn(mock(PubKeyRing.class));
            when(p2p.getKeyRing()).thenReturn(keys);
            NetworkNode networkNode = mock(NetworkNode.class);
            when(networkNode.getNodeAddress()).thenReturn(new NodeAddress("taker:9999"));
            when(p2p.getNetworkNode()).thenReturn(networkNode);
            User user = mock(User.class);
            PaymentAccount account = mock(PaymentAccount.class);
            PaymentAccountPayload payment = mock(PaymentAccountPayload.class);
            when(payment.getPaymentMethodId()).thenReturn("BLOCK_CHAINS");
            when(account.getPaymentAccountPayload()).thenReturn(payment);
            when(user.getPaymentAccount("taker")).thenReturn(account);
            model = new OfferAvailabilityModel(offer, keys.getPubKeyRing(), wallet, p2p, user,
                    null, null, false, "taker", BigInteger.ONE, null);
            protocol = new OfferAvailabilityProtocol(model, success, failure);
        }

        MockedStatic<UserThread> mockTimers() {
            MockedStatic<UserThread> timers = mockStatic(UserThread.class);
            timers.when(() -> UserThread.runAfter(any(Runnable.class), anyLong())).thenAnswer(invocation -> {
                timeouts.put(invocation.getArgument(1), invocation.getArgument(0));
                return mock(Timer.class);
            });
            return timers;
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
