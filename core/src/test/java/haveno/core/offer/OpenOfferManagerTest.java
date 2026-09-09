package haveno.core.offer;

import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.file.CorruptedStorageFileHandler;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import haveno.common.persistence.PersistenceManager;
import haveno.core.api.CoreContext;
import haveno.core.api.XmrConnectionService;
import haveno.core.api.XmrKeyImagePoller;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.trade.BuyerAsMakerTrade;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.TradableList;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.trade.protocol.ProcessModel;
import haveno.core.trade.protocol.ProcessModelServiceProvider;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.NetworkNotReadyException;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.peers.PeerManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.natpryce.makeiteasy.MakeItEasy.make;
import static com.natpryce.makeiteasy.MakeItEasy.with;
import static haveno.core.offer.OfferMaker.btcUsdOffer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OpenOfferManagerTest {
    private PersistenceManager<TradableList<OpenOffer>> persistenceManager;
    private PersistenceManager<SignedOfferList> signedOfferPersistenceManager;
    private CoreContext coreContext;

    @BeforeEach
    public void setUp() throws Exception {
        var corruptedStorageFileHandler = mock(CorruptedStorageFileHandler.class);
        var storageDir = Files.createTempDirectory("storage").toFile();
        var keyRing = new KeyRing(new KeyStorage(storageDir));
        persistenceManager = new PersistenceManager<>(storageDir, null, corruptedStorageFileHandler, keyRing);
        signedOfferPersistenceManager = new PersistenceManager<>(storageDir, null, corruptedStorageFileHandler, keyRing);
        coreContext = new CoreContext();
    }

    @AfterEach
    public void tearDown() {
        persistenceManager.shutdown();
        signedOfferPersistenceManager.shutdown();
    }

    @Test
    public void testStartEditOfferForActiveOffer() {
        P2PService p2PService = mock(P2PService.class);
        OfferBookService offerBookService = mock(OfferBookService.class);
        XmrConnectionService xmrConnectionService = mock(XmrConnectionService.class);

        when(p2PService.getPeerManager()).thenReturn(mock(PeerManager.class));

        final OpenOfferManager manager = new OpenOfferManager(coreContext,
                null,
                null,
                p2PService,
                xmrConnectionService,
                null,
                null,
                null,
                offerBookService,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                persistenceManager,
                signedOfferPersistenceManager,
                null);

        AtomicBoolean startEditOfferSuccessful = new AtomicBoolean(false);


        doAnswer(invocation -> {
            ((ResultHandler) invocation.getArgument(1)).handleResult();
            return null;
        }).when(offerBookService).deactivateOffer(any(OfferPayload.class), any(ResultHandler.class), any(ErrorMessageHandler.class));

        final OpenOffer openOffer = new OpenOffer(make(btcUsdOffer));
        openOffer.setState(OpenOffer.State.AVAILABLE);

        ResultHandler resultHandler = () -> startEditOfferSuccessful.set(true);

        manager.editOpenOfferStart(openOffer, resultHandler, null);

        verify(offerBookService, times(1)).deactivateOffer(any(OfferPayload.class), any(ResultHandler.class), any(ErrorMessageHandler.class));

        assertTrue(startEditOfferSuccessful.get());
    }

    @Test
    public void testStartEditOfferForDeactivatedOffer() {
        P2PService p2PService = mock(P2PService.class);
        OfferBookService offerBookService = mock(OfferBookService.class);
        XmrConnectionService xmrConnectionService = mock(XmrConnectionService.class);
        when(p2PService.getPeerManager()).thenReturn(mock(PeerManager.class));

        final OpenOfferManager manager = new OpenOfferManager(coreContext,
                null,
                null,
                p2PService,
                xmrConnectionService,
                null,
                null,
                null,
                offerBookService,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                persistenceManager,
                signedOfferPersistenceManager,
                null);

        AtomicBoolean startEditOfferSuccessful = new AtomicBoolean(false);

        ResultHandler resultHandler = () -> startEditOfferSuccessful.set(true);

        final OpenOffer openOffer = new OpenOffer(make(btcUsdOffer));
        openOffer.setState(OpenOffer.State.DEACTIVATED);

        manager.editOpenOfferStart(openOffer, resultHandler, null);
        assertTrue(startEditOfferSuccessful.get());

    }

    @Test
    public void testStartEditOfferForOfferThatIsCurrentlyEdited() {
        P2PService p2PService = mock(P2PService.class);
        OfferBookService offerBookService = mock(OfferBookService.class);
        XmrConnectionService xmrConnectionService = mock(XmrConnectionService.class);

        when(p2PService.getPeerManager()).thenReturn(mock(PeerManager.class));


        final OpenOfferManager manager = new OpenOfferManager(coreContext,
                null,
                null,
                p2PService,
                xmrConnectionService,
                null,
                null,
                null,
                offerBookService,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                persistenceManager,
                signedOfferPersistenceManager,
                null);

        AtomicBoolean startEditOfferSuccessful = new AtomicBoolean(false);

        ResultHandler resultHandler = () -> startEditOfferSuccessful.set(true);

        final OpenOffer openOffer = new OpenOffer(make(btcUsdOffer));
        openOffer.setState(OpenOffer.State.DEACTIVATED);

        manager.editOpenOfferStart(openOffer, resultHandler, null);
        assertTrue(startEditOfferSuccessful.get());

        startEditOfferSuccessful.set(false);

        manager.editOpenOfferStart(openOffer, resultHandler, null);
        assertTrue(startEditOfferSuccessful.get());
    }

    @Test
    public void testStartEditOfferClearsEditStateOnSynchronousDeactivateException() {
        P2PService p2PService = mock(P2PService.class);
        OfferBookService offerBookService = mock(OfferBookService.class);
        XmrConnectionService xmrConnectionService = mock(XmrConnectionService.class);

        when(p2PService.getPeerManager()).thenReturn(mock(PeerManager.class));

        final OpenOfferManager manager = new OpenOfferManager(coreContext,
                null,
                null,
                p2PService,
                xmrConnectionService,
                null,
                null,
                null,
                offerBookService,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                persistenceManager,
                signedOfferPersistenceManager,
                null);

        final OpenOffer openOffer = new OpenOffer(make(btcUsdOffer));
        openOffer.setState(OpenOffer.State.AVAILABLE);

        doThrow(new NetworkNotReadyException())
                .when(offerBookService).deactivateOffer(any(OfferPayload.class), any(ResultHandler.class), any(ErrorMessageHandler.class));

        AtomicBoolean firstEditErrorHandled = new AtomicBoolean(false);
        manager.editOpenOfferStart(openOffer, () -> {
        }, errorMessage -> firstEditErrorHandled.set(true));
        assertTrue(firstEditErrorHandled.get());

        doAnswer(invocation -> {
            ((ResultHandler) invocation.getArgument(1)).handleResult();
            return null;
        }).when(offerBookService).deactivateOffer(any(OfferPayload.class), any(ResultHandler.class), any(ErrorMessageHandler.class));

        AtomicBoolean secondEditSuccessful = new AtomicBoolean(false);
        manager.editOpenOfferStart(openOffer, () -> secondEditSuccessful.set(true), null);
        assertTrue(secondEditSuccessful.get());
        verify(offerBookService, times(2)).deactivateOffer(any(OfferPayload.class), any(ResultHandler.class), any(ErrorMessageHandler.class));
    }

    @Test
    public void testRemoveAllOpenOffersCancelsReservedOffer() {
        assertRemoveAllOpenOffersCancelsTradeOwnedOffer(OpenOffer.State.RESERVED);
    }

    @Test
    public void testRemoveAllOpenOffersCancelsRestoredTradeOwnedOffer() {
        assertRemoveAllOpenOffersCancelsTradeOwnedOffer(OpenOffer.State.AVAILABLE);
    }

    private void assertRemoveAllOpenOffersCancelsTradeOwnedOffer(OpenOffer.State initialState) {
        P2PService p2PService = mock(P2PService.class);
        OfferBookService offerBookService = mock(OfferBookService.class);
        XmrConnectionService xmrConnectionService = mock(XmrConnectionService.class);
        TradeManager tradeManager = mock(TradeManager.class);
        Trade trade = mock(Trade.class);
        TradeManager originalTradeManager = HavenoUtils.tradeManager;
        OpenOfferManager originalOpenOfferManager = HavenoUtils.openOfferManager;

        when(p2PService.isBootstrapped()).thenReturn(true);
        when(trade.isMaker()).thenReturn(true);
        doAnswer(invocation -> {
            ((ErrorMessageHandler) invocation.getArgument(2)).handleErrorMessage("Network unavailable");
            return null;
        }).when(offerBookService).removeOffer(any(OfferPayload.class), any(ResultHandler.class), any(ErrorMessageHandler.class));

        try {
            HavenoUtils.tradeManager = tradeManager;
            OpenOfferManager manager = new OpenOfferManager(coreContext,
                    null,
                    null,
                    p2PService,
                    xmrConnectionService,
                    null,
                    null,
                    null,
                    offerBookService,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    persistenceManager,
                    signedOfferPersistenceManager,
                    null);
            OpenOffer openOffer = new OpenOffer(make(btcUsdOffer));
            openOffer.setState(initialState);
            manager.getObservableList().add(openOffer);
            when(tradeManager.getOpenTrade(openOffer.getId())).thenReturn(Optional.of(trade));

            AtomicBoolean cancelRejected = new AtomicBoolean(false);
            manager.removeOpenOffer(openOffer, null, errorMessage -> cancelRejected.set(true));
            assertTrue(cancelRejected.get());
            assertEquals(initialState, openOffer.getState());

            manager.removeAllOpenOffers(null);
            assertEquals(OpenOffer.State.CANCELED, openOffer.getState());
            verify(offerBookService).removeOffer(any(OfferPayload.class), any(ResultHandler.class), any(ErrorMessageHandler.class));

            manager.unreserveOpenOffer(openOffer);
            assertEquals(OpenOffer.State.CANCELED, openOffer.getState());
            assertFalse(manager.reserveOpenOffer(openOffer));
        } finally {
            HavenoUtils.tradeManager = originalTradeManager;
            HavenoUtils.openOfferManager = originalOpenOfferManager;
        }
    }

    @Test
    public void testUnreserveCancelsOfferWithSpentInputs() {
        assertUnreserveOpenOfferState(OpenOffer.State.RESERVED, true, OpenOffer.State.CANCELED);
    }

    @Test
    public void testUnreserveCancelsRestoredOfferWithSpentInputs() {
        assertUnreserveOpenOfferState(OpenOffer.State.AVAILABLE, true, OpenOffer.State.CANCELED);
    }

    @Test
    public void testUnreserveMakesUnspentOfferAvailable() {
        assertUnreserveOpenOfferState(OpenOffer.State.RESERVED, false, OpenOffer.State.AVAILABLE);
    }

    @Test
    public void testUnreserveMakesOfferWithUnknownSpentStatusAvailable() {
        assertUnreserveOpenOfferState(OpenOffer.State.RESERVED, null, OpenOffer.State.AVAILABLE);
    }

    private void assertUnreserveOpenOfferState(OpenOffer.State initialState, Boolean spent, OpenOffer.State expectedState) {
        P2PService p2PService = mock(P2PService.class);
        OfferBookService offerBookService = mock(OfferBookService.class);
        XmrConnectionService xmrConnectionService = mock(XmrConnectionService.class);
        XmrKeyImagePoller keyImagePoller = mock(XmrKeyImagePoller.class);
        TradeManager originalTradeManager = HavenoUtils.tradeManager;
        OpenOfferManager originalOpenOfferManager = HavenoUtils.openOfferManager;

        when(p2PService.isBootstrapped()).thenReturn(true);
        when(xmrConnectionService.getKeyImagePoller()).thenReturn(keyImagePoller);
        when(keyImagePoller.isSpent("unspent")).thenReturn(false);
        when(keyImagePoller.isSpent("reserve")).thenReturn(spent);
        doAnswer(invocation -> {
            ErrorMessageHandler errorMessageHandler = invocation.getArgument(2);
            if (errorMessageHandler != null) errorMessageHandler.handleErrorMessage("Network unavailable");
            return null;
        }).when(offerBookService).removeOffer(any(OfferPayload.class), any(), any());

        try {
            HavenoUtils.tradeManager = null;
            OpenOfferManager manager = createOfferManager(p2PService, offerBookService, xmrConnectionService);
            OpenOffer openOffer = new OpenOffer(make(btcUsdOffer));
            openOffer.getOffer().getOfferPayload().setReserveTxKeyImages(List.of("unspent", "reserve"));
            openOffer.setState(initialState);
            manager.getObservableList().add(openOffer);

            manager.unreserveOpenOffer(openOffer);

            assertEquals(expectedState, openOffer.getState());
            if (expectedState == OpenOffer.State.CANCELED) {
                verify(offerBookService).removeOffer(any(OfferPayload.class), any(), any());
                assertFalse(manager.reserveOpenOffer(openOffer));
                manager.unreserveOpenOffer(openOffer);
                assertEquals(OpenOffer.State.CANCELED, openOffer.getState());
            }
        } finally {
            HavenoUtils.tradeManager = originalTradeManager;
            HavenoUtils.openOfferManager = originalOpenOfferManager;
        }
    }

    @Test
    public void testSpentRecheckCancelsOfferAfterTradeUnregistered() {
        assertSpentRecheckAfterOwnershipRelease(false);
    }

    @Test
    public void testSpentRecheckPreservesNewTradeReservation() {
        assertSpentRecheckAfterOwnershipRelease(true);
    }

    private void assertSpentRecheckAfterOwnershipRelease(boolean reserveAgain) {
        P2PService p2PService = mock(P2PService.class);
        OfferBookService offerBookService = mock(OfferBookService.class);
        XmrConnectionService xmrConnectionService = mock(XmrConnectionService.class);
        XmrKeyImagePoller keyImagePoller = mock(XmrKeyImagePoller.class);
        TradeManager tradeManager = mock(TradeManager.class);
        Trade trade = mock(Trade.class);
        TradeManager originalTradeManager = HavenoUtils.tradeManager;
        OpenOfferManager originalOpenOfferManager = HavenoUtils.openOfferManager;

        when(p2PService.isBootstrapped()).thenReturn(true);
        when(xmrConnectionService.getKeyImagePoller()).thenReturn(keyImagePoller);
        when(keyImagePoller.isSpent("reserve")).thenReturn(false);
        when(trade.isMaker()).thenReturn(true);

        try {
            HavenoUtils.tradeManager = tradeManager;
            OpenOfferManager manager = createOfferManager(p2PService, offerBookService, xmrConnectionService);
            OpenOffer openOffer = new OpenOffer(make(btcUsdOffer));
            openOffer.getOffer().getOfferPayload().setReserveTxKeyImages(List.of("reserve"));
            openOffer.setState(OpenOffer.State.RESERVED);
            manager.getObservableList().add(openOffer);
            when(tradeManager.getOpenTrade(openOffer.getId())).thenReturn(Optional.of(trade));

            manager.unreserveOpenOffer(openOffer);
            assertEquals(OpenOffer.State.AVAILABLE, openOffer.getState());

            when(keyImagePoller.isSpent("reserve")).thenReturn(true);
            manager.removeOpenOfferIfSpent(openOffer);
            assertEquals(OpenOffer.State.AVAILABLE, openOffer.getState());
            verify(offerBookService, never()).removeOffer(any(), any(), any());

            when(tradeManager.getOpenTrade(openOffer.getId())).thenReturn(Optional.empty());
            if (reserveAgain) assertTrue(manager.reserveOpenOffer(openOffer));
            manager.removeOpenOfferIfSpent(openOffer);
            if (reserveAgain) {
                assertEquals(OpenOffer.State.RESERVED, openOffer.getState());
                verify(offerBookService, never()).removeOffer(any(), any(), any());
            } else {
                assertEquals(OpenOffer.State.CANCELED, openOffer.getState());
                verify(offerBookService).removeOffer(any(), any(), any());
            }
        } finally {
            HavenoUtils.tradeManager = originalTradeManager;
            HavenoUtils.openOfferManager = originalOpenOfferManager;
        }
    }

    @Test
    public void testInitializePublishedMakerTradeClosesRestoredOffersWithoutWallet() {
        assertInitializeRestoredMakerOffer(Trade.State.DEPOSIT_TXS_SEEN_IN_NETWORK, false, true, OpenOffer.State.CLOSED);
    }

    @Test
    public void testInitializePublishedMakerTradeClosesRestoredOffersWhenNetworkNotReady() {
        assertInitializeRestoredMakerOffer(Trade.State.DEPOSIT_TXS_SEEN_IN_NETWORK, false, false, OpenOffer.State.CLOSED);
    }

    @Test
    public void testInitializeCompletedMakerTradeClosesRestoredOffers() {
        assertInitializeRestoredMakerOffer(Trade.State.DEPOSIT_TXS_SEEN_IN_NETWORK, true, true, OpenOffer.State.CLOSED);
    }

    @Test
    public void testInitializeUnfundedMakerTradePreservesRestoredOffers() {
        assertInitializeRestoredMakerOffer(Trade.State.MULTISIG_COMPLETED, false, true, OpenOffer.State.AVAILABLE);
    }

    @Test
    public void testInitializeFailedDepositMakerTradePreservesRestoredOffers() {
        assertInitializeRestoredMakerOffer(Trade.State.PUBLISH_DEPOSIT_TX_REQUEST_FAILED, false, true, OpenOffer.State.AVAILABLE);
    }

    private void assertInitializeRestoredMakerOffer(Trade.State tradeState, boolean completed, boolean networkReady, OpenOffer.State expectedState) {
        P2PService p2PService = mock(P2PService.class);
        OfferBookService offerBookService = mock(OfferBookService.class);
        XmrConnectionService xmrConnectionService = mock(XmrConnectionService.class);
        XmrWalletService xmrWalletService = mock(XmrWalletService.class);
        TradeManager tradeManager = mock(TradeManager.class);
        ProcessModelServiceProvider serviceProvider = mock(ProcessModelServiceProvider.class);
        ArbitratorManager arbitratorManager = mock(ArbitratorManager.class);
        TradeManager originalTradeManager = HavenoUtils.tradeManager;
        OpenOfferManager originalOpenOfferManager = HavenoUtils.openOfferManager;

        when(p2PService.isBootstrapped()).thenReturn(networkReady);
        when(xmrWalletService.getXmrConnectionService()).thenReturn(xmrConnectionService);
        when(xmrConnectionService.getKeyImagePoller()).thenReturn(mock(XmrKeyImagePoller.class));
        when(serviceProvider.getArbitratorManager()).thenReturn(arbitratorManager);
        if (!networkReady) {
            doThrow(new NetworkNotReadyException()).when(offerBookService).removeOffer(any(), any(), any());
        }

        try {
            HavenoUtils.tradeManager = tradeManager;
            OpenOfferManager manager = createOfferManager(p2PService, offerBookService, xmrConnectionService, xmrWalletService);
            when(serviceProvider.getOpenOfferManager()).thenReturn(manager);

            OpenOffer openOffer = new OpenOffer(make(btcUsdOffer));
            openOffer.getOffer().getOfferPayload().setReserveTxKeyImages(List.of("reserve"));
            openOffer.setState(OpenOffer.State.AVAILABLE);
            OpenOffer clonedOffer = new OpenOffer(make(btcUsdOffer.but(with(OfferMaker.id, "clone"))), 0, openOffer);
            clonedOffer.getOffer().getOfferPayload().setReserveTxKeyImages(List.of("reserve"));
            OpenOffer unrelatedOffer = new OpenOffer(make(btcUsdOffer.but(with(OfferMaker.id, "unrelated"))));
            unrelatedOffer.setState(OpenOffer.State.AVAILABLE);
            manager.getObservableList().addAll(openOffer, clonedOffer, unrelatedOffer);

            Offer offer = openOffer.getOffer();
            ProcessModel processModel = new ProcessModel(offer.getId(), "account", null);
            processModel.applyTransient(serviceProvider, tradeManager, offer);
            Trade trade = new BuyerAsMakerTrade(offer, offer.getAmount(), offer.getPrice().getValue(),
                    xmrWalletService, processModel, "restored-maker", null, null, null, null);
            trade.getMaker().setDepositTxHash("maker-deposit");
            trade.getTaker().setDepositTxHash("taker-deposit");
            trade.setState(tradeState);
            if (completed) {
                trade.setPayoutState(Trade.PayoutState.PAYOUT_FINALIZED);
                trade.setCompleted(true);
            }
            when(tradeManager.getOpenTrade(offer.getId())).thenReturn(completed ? Optional.empty() : Optional.of(trade));

            if (trade.isDepositRequested() && !completed) {
                RuntimeException error = assertThrows(RuntimeException.class, () -> trade.initialize(serviceProvider));
                assertTrue(error.getMessage().startsWith("Missing trade wallet"));
            } else {
                trade.initialize(serviceProvider);
            }

            assertEquals(expectedState, openOffer.getState());
            assertEquals(expectedState, clonedOffer.getState());
            assertEquals(OpenOffer.State.AVAILABLE, unrelatedOffer.getState());
            assertTrue(manager.getOpenOffer(unrelatedOffer.getId()).isPresent());
            if (expectedState == OpenOffer.State.CLOSED) {
                assertTrue(manager.getOpenOffer(offer.getId()).isEmpty());
                assertTrue(manager.getOpenOffer(clonedOffer.getId()).isEmpty());
                assertFalse(manager.reserveOpenOffer(openOffer));
                verify(offerBookService, times(2)).removeOffer(any(), any(), any());
                verify(xmrWalletService).resetAddressEntriesForOpenOffer(offer.getId());
                verify(xmrWalletService).resetAddressEntriesForOpenOffer(clonedOffer.getId());
            } else {
                assertTrue(manager.getOpenOffer(offer.getId()).isPresent());
                assertTrue(manager.getOpenOffer(clonedOffer.getId()).isPresent());
                verify(offerBookService, never()).removeOffer(any(), any(), any());
            }
            verify(xmrWalletService, never()).thawOutputs(any());
        } finally {
            HavenoUtils.tradeManager = originalTradeManager;
            HavenoUtils.openOfferManager = originalOpenOfferManager;
        }
    }

    @Test
    public void testSpentNotificationDuringUnreserveIsNotLost() throws Exception {
        P2PService p2PService = mock(P2PService.class);
        OfferBookService offerBookService = mock(OfferBookService.class);
        XmrConnectionService xmrConnectionService = mock(XmrConnectionService.class);
        XmrKeyImagePoller keyImagePoller = mock(XmrKeyImagePoller.class);
        TradeManager originalTradeManager = HavenoUtils.tradeManager;
        OpenOfferManager originalOpenOfferManager = HavenoUtils.openOfferManager;
        CountDownLatch statusRead = new CountDownLatch(1);
        CountDownLatch finishStatusRead = new CountDownLatch(1);
        CountDownLatch notificationStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        when(p2PService.isBootstrapped()).thenReturn(true);
        when(xmrConnectionService.getKeyImagePoller()).thenReturn(keyImagePoller);
        when(keyImagePoller.isSpent("reserve")).thenAnswer(invocation -> {
            statusRead.countDown();
            assertTrue(finishStatusRead.await(5, TimeUnit.SECONDS));
            return false;
        });

        try {
            HavenoUtils.tradeManager = null;
            OpenOfferManager manager = createOfferManager(p2PService, offerBookService, xmrConnectionService);
            OpenOffer openOffer = new OpenOffer(make(btcUsdOffer));
            openOffer.getOffer().getOfferPayload().setReserveTxKeyImages(List.of("reserve"));
            openOffer.setState(OpenOffer.State.RESERVED);
            manager.getObservableList().add(openOffer);
            var removeOnSpent = OpenOfferManager.class.getDeclaredMethod("removeOpenOffersOnSpent", String.class);
            removeOnSpent.setAccessible(true);

            var unreserve = executor.submit(() -> manager.unreserveOpenOffer(openOffer));
            assertTrue(statusRead.await(5, TimeUnit.SECONDS));
            var notification = executor.submit(() -> {
                notificationStarted.countDown();
                removeOnSpent.invoke(manager, "reserve");
                return null;
            });
            assertTrue(notificationStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> notification.get(100, TimeUnit.MILLISECONDS));
            finishStatusRead.countDown();

            unreserve.get(5, TimeUnit.SECONDS);
            notification.get(5, TimeUnit.SECONDS);
            assertEquals(OpenOffer.State.CANCELED, openOffer.getState());
            verify(offerBookService).removeOffer(any(), any(), any());
        } finally {
            finishStatusRead.countDown();
            executor.shutdownNow();
            try {
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
                HavenoUtils.tradeManager = originalTradeManager;
                HavenoUtils.openOfferManager = originalOpenOfferManager;
            }
        }
    }

    private OpenOfferManager createOfferManager(P2PService p2PService,
                                                OfferBookService offerBookService,
                                                XmrConnectionService xmrConnectionService) {
        return createOfferManager(p2PService, offerBookService, xmrConnectionService, null);
    }

    private OpenOfferManager createOfferManager(P2PService p2PService,
                                                OfferBookService offerBookService,
                                                XmrConnectionService xmrConnectionService,
                                                XmrWalletService xmrWalletService) {
        return new OpenOfferManager(coreContext,
                null,
                null,
                p2PService,
                xmrConnectionService,
                null,
                xmrWalletService,
                null,
                offerBookService,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                persistenceManager,
                signedOfferPersistenceManager,
                null);
    }

}
