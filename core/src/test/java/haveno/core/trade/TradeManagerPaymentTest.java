/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.core.trade;

import haveno.common.crypto.PubKeyRing;
import haveno.common.persistence.PersistenceManager;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.monetary.Price;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OpenOffer;
import haveno.core.offer.OpenOfferManager;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.trade.failed.FailedTradesManager;
import haveno.core.trade.messages.InitTradeRequest;
import haveno.core.trade.messages.InitMultisigRequest;
import haveno.core.trade.protocol.ProcessModel;
import haveno.core.trade.protocol.ProcessModelServiceProvider;
import haveno.core.trade.protocol.SellerAsMakerProtocol;
import haveno.core.trade.protocol.tasks.ProcessInitMultisigRequest;
import haveno.core.util.PriceUtil;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.DecryptedMessageWithPubKey;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TradeManagerPaymentTest {

    private TradeManager manager;
    private XmrWalletService walletService;
    private OpenOfferManager openOfferManager;
    private P2PService p2pService;
    private ProcessModelServiceProvider provider;
    private List<Trade> closedTrades;
    private ObservableList<Trade> failedTrades;
    private TradeManager previousTradeManager;

    @BeforeEach
    @SuppressWarnings("unchecked")
    public void setUp() {
        previousTradeManager = HavenoUtils.tradeManager;
        walletService = mock(XmrWalletService.class);
        openOfferManager = mock(OpenOfferManager.class);
        p2pService = mock(P2PService.class, RETURNS_DEEP_STUBS);
        provider = mock(ProcessModelServiceProvider.class, RETURNS_DEEP_STUBS);
        ClosedTradableManager closedManager = mock(ClosedTradableManager.class);
        FailedTradesManager failedManager = mock(FailedTradesManager.class);
        closedTrades = new ArrayList<>();
        failedTrades = FXCollections.observableArrayList();
        when(closedManager.getClosedTrades()).thenAnswer(invocation -> List.copyOf(closedTrades));
        when(failedManager.getObservableList()).thenReturn(failedTrades);
        when(failedManager.getTradesById(anyString())).thenReturn(List.of());
        manager = spy(new TradeManager(null, null, null, walletService, null, null,
                openOfferManager, closedManager, failedManager, p2pService, null, null,
                null, null, null, null, provider, null, mock(PersistenceManager.class), null));
    }

    @AfterEach
    public void tearDown() {
        HavenoUtils.tradeManager = previousTradeManager;
    }

    @Test
    public void rejectsSamePaymentDespiteDifferentTakerIdentitiesAndXmrAmounts() {
        Trade first = trade("first", "USD", "account", OfferDirection.SELL, "1", "100");
        Trade second = trade("second", "USD", "account", OfferDirection.SELL, "2", "50");
        first.getTaker().setPaymentAccountId("first-taker-account");
        second.getTaker().setPaymentAccountId("second-taker-account");
        first.getTaker().setPubKeyRing(mock(PubKeyRing.class));
        second.getTaker().setPubKeyRing(mock(PubKeyRing.class));
        manager.addMakerTrade(first);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> manager.addMakerTrade(second));

        assertEquals(List.of(first), manager.getOpenTrades());
        assertFalse(error.getMessage().contains(first.getId()));
    }

    @Test
    public void comparesRoundedPaymentAmounts() {
        Trade first = trade("first", "EUR", "account", OfferDirection.SELL, "1", "99.6");
        Trade second = trade("second", "EUR", "account", OfferDirection.SELL, "1", "100.4");
        assertEquals("100", first.getVolume().toPlainString());
        manager.addMakerTrade(first);
        assertThrows(IllegalArgumentException.class, () -> manager.addMakerTrade(second));
    }

    @Test
    public void comparesCashAtmRounding() {
        Trade first = trade("first", "EUR", "account", OfferDirection.SELL, "1", "96");
        Trade second = trade("second", "EUR", "account", OfferDirection.SELL, "1", "104");
        when(first.getOffer().getPaymentMethod().getId()).thenReturn(PaymentMethod.CASH_AT_ATM_ID);
        when(second.getOffer().getPaymentMethod().getId()).thenReturn(PaymentMethod.CASH_AT_ATM_ID);
        manager.addMakerTrade(first);
        assertThrows(IllegalArgumentException.class, () -> manager.addMakerTrade(second));
    }

    @Test
    public void allowsDifferentCurrencyAccountDirectionOrPaymentAmount() {
        manager.addMakerTrade(trade("first", "USD", "account", OfferDirection.SELL, "1", "100"));
        manager.addMakerTrade(trade("currency", "EUR", "account", OfferDirection.SELL, "1", "100"));
        manager.addMakerTrade(trade("account", "USD", "another-account", OfferDirection.SELL, "1", "100"));
        manager.addMakerTrade(trade("direction", "USD", "account", OfferDirection.BUY, "1", "100"));
        manager.addMakerTrade(trade("amount", "USD", "account", OfferDirection.SELL, "1", "101"));
        assertEquals(5, manager.getOpenTrades().size());
    }

    @Test
    public void rejectsDuplicateBuyOffersToo() {
        manager.addMakerTrade(trade("first", "USD", "account", OfferDirection.BUY, "1", "100"));
        assertThrows(IllegalArgumentException.class,
                () -> manager.addMakerTrade(trade("second", "USD", "account", OfferDirection.BUY, "1", "100")));
    }

    @Test
    public void arbitratorPriceCannotCollideAndFailedRepricePreservesPreviousPrice() {
        Trade first = trade("first", "USD", "account", OfferDirection.SELL, "1", "100");
        Trade second = trade("second", "USD", "account", OfferDirection.SELL, "1", "101");
        manager.addMakerTrade(first);
        manager.addMakerTrade(second);
        manager.setTradePrice(first, first.getRawPrice().getValue());
        long oldPrice = second.getRawPrice().getValue();

        assertThrows(IllegalArgumentException.class,
                () -> manager.setTradePrice(second, Price.parse("USD", "100.4").getValue()));

        assertEquals(oldPrice, second.getRawPrice().getValue());
        manager.setTradePrice(second, Price.parse("USD", "102").getValue());
        assertEquals("102", second.getVolume().toPlainString());
    }

    @Test
    public void concurrentInitializationsAcceptExactlyOne() throws Exception {
        Trade first = trade("first", "USD", "account", OfferDirection.SELL, "1", "100");
        Trade second = trade("second", "USD", "account", OfferDirection.SELL, "1", "100");
        assertEquals(1, race(() -> manager.addMakerTrade(first), () -> manager.addMakerTrade(second)));
        assertEquals(1, manager.getOpenTrades().size());
    }

    @Test
    public void multisigTaskRejectsFinalPriceCollisionBeforeWalletWork() {
        Trade first = trade("first", "USD", "account", OfferDirection.SELL, "1", "100");
        Trade second = spy(trade("second", "USD", "account", OfferDirection.SELL, "1", "101"));
        manager.addMakerTrade(first);
        manager.addMakerTrade(second);
        NodeAddress arbitrator = new NodeAddress("arbitrator:9999");
        second.getArbitrator().setNodeAddress(arbitrator);
        ProcessModel processModel = second.getProcessModel();
        processModel.applyTransient(provider, manager, second.getOffer());
        processModel.setTempTradePeerNodeAddress(arbitrator);
        InitMultisigRequest request = mock(InitMultisigRequest.class);
        doReturn(second.getId()).when(request).getOfferId();
        doReturn(first.getRawPrice().getValue()).when(request).getTradePrice();
        processModel.setTradeMessage(request);
        AtomicReference<String> failure = new AtomicReference<>();
        TaskRunner<Trade> runner = new TaskRunner<>(second, Trade.class,
                () -> { throw new AssertionError("Conflicting multisig request succeeded"); }, failure::set);
        runner.addTasks(ProcessInitMultisigRequest.class);

        runner.run();

        assertTrue(failure.get().contains("unresolved trade"));
        assertEquals("101", second.getVolume().toPlainString());
        verify(second, never()).createWallet();
        verify(second, never()).getWallet();
    }

    @Test
    public void concurrentRepriceAndInitializationAcceptExactlyOne() throws Exception {
        Trade first = trade("first", "USD", "account", OfferDirection.SELL, "1", "101");
        Trade second = trade("second", "USD", "account", OfferDirection.SELL, "1", "100");
        manager.addMakerTrade(first);
        assertEquals(1, race(() -> manager.setTradePrice(first, second.getRawPrice().getValue()),
                () -> manager.addMakerTrade(second)));
    }

    @Test
    public void publishedPayoutReleasesPaymentAmount() {
        Trade first = spy(trade("first", "USD", "account", OfferDirection.SELL, "1", "100"));
        manager.addMakerTrade(first);
        doReturn(true).when(first).isPayoutPublished();
        manager.addMakerTrade(trade("second", "USD", "account", OfferDirection.SELL, "1", "100"));
        assertEquals(2, manager.getOpenTrades().size());
    }

    @Test
    public void blocksUnresolvedClosedTrades() {
        closedTrades.add(trade("closed", "USD", "account", OfferDirection.SELL, "1", "100"));
        assertThrows(IllegalArgumentException.class,
                () -> manager.addMakerTrade(trade("second", "USD", "account", OfferDirection.SELL, "1", "100")));
    }

    @Test
    public void blocksFailedDepositRequestsIncludingPriorAttemptWithSameOfferId() throws Exception {
        Trade failed = trade("same-offer", "USD", "account", OfferDirection.SELL, "1", "100");
        var state = Trade.class.getDeclaredField("state");
        state.setAccessible(true);
        state.set(failed, Trade.State.PUBLISH_DEPOSIT_TX_REQUEST_FAILED);
        assertTrue(failed.isDepositRequested());
        assertTrue(failed.isDepositRequestFailed());
        failedTrades.add(failed);
        assertThrows(IllegalArgumentException.class,
                () -> manager.addMakerTrade(trade("same-offer", "USD", "account", OfferDirection.SELL, "1", "100")));
        failedTrades.remove(failed);
        manager.addMakerTrade(trade("same-offer", "USD", "account", OfferDirection.SELL, "1", "100"));
    }

    @Test
    public void allowsUnfundedFailedTradesAndIgnoresOtherLocalRoles() {
        failedTrades.add(trade("failed", "USD", "account", OfferDirection.SELL, "1", "100"));
        Trade taker = mock(SellerAsTakerTrade.class);
        Trade arbitrator = mock(ArbitratorTrade.class);
        manager.getObservableList().addAll(taker, arbitrator);
        manager.addMakerTrade(trade("second", "USD", "account", OfferDirection.SELL, "1", "100"));
    }

    @Test
    public void usesLegacyInvertedCryptoPriceAndDoesNotMutateItToCalculateVolume() {
        Trade first = trade("first", "BTC", "account", OfferDirection.SELL, "1", "100");
        when(first.getOffer().isInverted()).thenReturn(true);
        long price = first.getRawPrice().getValue();
        Trade second = trade("second", "BTC", "account", OfferDirection.SELL, "1", "0.01");
        assertEquals(PriceUtil.invertLongPrice(price, "BTC"), first.getPrice().getValue());
        assertEquals(first.getVolume().getValue(), second.getVolume().getValue());
        first.getVolume(Price.parse("BTC", "200").getValue());
        assertEquals(price, first.getRawPrice().getValue());
        manager.addMakerTrade(first);
        assertThrows(IllegalArgumentException.class, () -> manager.addMakerTrade(second));
    }

    @Test
    public void rejectsCollisionBeforeReservingOfferOrCreatingProtocol() throws Exception {
        manager.addMakerTrade(trade("first", "USD", "account", OfferDirection.SELL, "1", "100"));
        Trade incoming = trade("second", "USD", "account", OfferDirection.SELL, "1", "100");
        InitTradeRequest request = request(incoming);
        OpenOffer openOffer = mock(OpenOffer.class);
        when(openOffer.getState()).thenReturn(OpenOffer.State.AVAILABLE);
        when(openOffer.getOffer()).thenReturn(incoming.getOffer());
        when(openOfferManager.getOpenOffer(incoming.getId())).thenReturn(Optional.of(openOffer));
        doNothing().when(manager).sendAckMessage(any(), any(), any(), anyBoolean(), any(), any());

        handleRequest(request);

        verify(openOfferManager, never()).reserveOpenOffer(any());
        verify(manager, never()).createTradeProtocol(any());
        NodeAddress takerAddress = request.getTakerNodeAddress();
        PubKeyRing takerKey = request.getTakerPubKeyRing();
        verify(manager).sendAckMessage(eq(takerAddress), eq(takerKey),
                eq(request), eq(false), anyString(), isNull());
        assertEquals(1, manager.getOpenTrades().size());
    }

    @Test
    public void marketOfferClaimsRequestedPriceBeforeProtocolRuns() throws Exception {
        Trade incoming = trade("market", "USD", "account", OfferDirection.SELL, "1", "100");
        when(incoming.getOffer().getOfferPayload().getPrice()).thenReturn(0L);
        InitTradeRequest request = request(incoming);
        OpenOffer openOffer = mock(OpenOffer.class);
        when(openOffer.getState()).thenReturn(OpenOffer.State.AVAILABLE);
        when(openOffer.getOffer()).thenReturn(incoming.getOffer());
        when(openOfferManager.getOpenOffer(incoming.getId())).thenReturn(Optional.of(openOffer));
        SellerAsMakerProtocol protocol = mock(SellerAsMakerProtocol.class);
        doReturn(protocol).when(manager).createTradeProtocol(any());
        doReturn(protocol).when(manager).getTradeProtocol(any());

        handleRequest(request);

        assertEquals("100", manager.getOpenTrades().get(0).getVolume().toPlainString());
        assertThrows(IllegalArgumentException.class,
                () -> manager.addMakerTrade(trade("second", "USD", "account", OfferDirection.SELL, "1", "100")));
        verify(openOfferManager).reserveOpenOffer(openOffer);
    }

    private Trade trade(String id, String currency, String account, OfferDirection direction, String amount, String price) {
        Offer offer = mock(Offer.class, RETURNS_DEEP_STUBS);
        when(offer.getId()).thenReturn(id);
        when(offer.getDirection()).thenReturn(direction);
        when(offer.isBuyOffer()).thenReturn(direction == OfferDirection.BUY);
        when(offer.getCounterCurrencyCode()).thenReturn(currency);
        when(offer.getOfferPayload().getMakerPaymentAccountId()).thenReturn(account);
        when(offer.getPaymentMethod().getId()).thenReturn(PaymentMethod.SEPA_ID);
        when(offer.getAmount()).thenReturn(HavenoUtils.xmrToAtomicUnits(10));
        when(offer.getMinAmount()).thenReturn(HavenoUtils.xmrToAtomicUnits(0.1));
        BigInteger atomicAmount = HavenoUtils.xmrToAtomicUnits(Double.parseDouble(amount));
        long rawPrice = Price.parse(currency, price).getValue();
        ProcessModel processModel = new ProcessModel(id, "maker", mock(PubKeyRing.class));
        return direction == OfferDirection.BUY
                ? new BuyerAsMakerTrade(offer, atomicAmount, rawPrice, walletService, processModel, UUID.randomUUID().toString(), null, null, null, null)
                : new SellerAsMakerTrade(offer, atomicAmount, rawPrice, walletService, processModel, UUID.randomUUID().toString(), null, null, null, null);
    }

    private InitTradeRequest request(Trade trade) {
        InitTradeRequest request = mock(InitTradeRequest.class);
        doReturn(trade.getId()).when(request).getOfferId();
        when(request.getUid()).thenReturn(UUID.randomUUID().toString());
        when(request.getTradeAmount()).thenReturn(trade.getAmount().longValueExact());
        doReturn(trade.getRawPrice().getValue()).when(request).getTradePrice();
        when(request.getTakerPubKeyRing()).thenReturn(mock(PubKeyRing.class));
        when(request.getMakerNodeAddress()).thenReturn(new NodeAddress("maker:9999"));
        when(request.getTakerNodeAddress()).thenReturn(new NodeAddress("taker:9999"));
        when(request.getArbitratorNodeAddress()).thenReturn(new NodeAddress("arbitrator:9999"));
        NodeAddress makerAddress = request.getMakerNodeAddress();
        when(p2pService.getNetworkNode().getNodeAddress()).thenReturn(makerAddress);
        return request;
    }

    private void handleRequest(InitTradeRequest request) throws Exception {
        Method method = TradeManager.class.getDeclaredMethod("handleInitTradeRequest", DecryptedMessageWithPubKey.class,
                InitTradeRequest.class, NodeAddress.class);
        method.setAccessible(true);
        method.invoke(manager, mock(DecryptedMessageWithPubKey.class), request, request.getTakerNodeAddress());
    }

    private int race(Runnable first, Runnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (Runnable action : List.of(first, second)) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        action.run();
                        return true;
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return (results.get(0).get(5, TimeUnit.SECONDS) ? 1 : 0) + (results.get(1).get(5, TimeUnit.SECONDS) ? 1 : 0);
        } finally {
            executor.shutdownNow();
        }
    }
}
