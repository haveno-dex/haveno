package haveno.core.offer;

import haveno.common.ThreadUtils;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.app.Version;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.crypto.PubKeyRing;
import haveno.common.file.CorruptedStorageFileHandler;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import haveno.common.persistence.PersistenceManager;
import haveno.core.api.CoreContext;
import haveno.core.api.CoreNotificationService;
import haveno.core.api.XmrConnectionService;
import haveno.core.api.XmrKeyImagePoller;
import haveno.core.filter.FilterManager;
import haveno.core.offer.messages.SignOfferRequest;
import haveno.core.offer.messages.SignOfferResponse;
import haveno.core.offer.messages.OfferAvailabilityRequest;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.support.dispute.arbitration.arbitrator.Arbitrator;
import haveno.core.trade.ArbitratorTrade;
import haveno.core.trade.BuyerAsMakerTrade;
import haveno.core.trade.BuyerAsTakerTrade;
import haveno.core.trade.ClosedTradableManager;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.TradableList;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.trade.failed.FailedTradesManager;
import haveno.core.monetary.Volume;
import haveno.core.trade.protocol.ProcessModel;
import haveno.core.trade.protocol.ProcessModelServiceProvider;
import haveno.core.trade.protocol.TradePeer;
import haveno.core.trade.messages.InitTradeRequest;
import haveno.core.trade.messages.TradeProtocolVersion;
import haveno.core.user.User;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.core.xmr.wallet.Restrictions;
import haveno.network.p2p.NetworkNotReadyException;
import haveno.network.p2p.AckMessage;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.DecryptedMessageWithPubKey;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.network.NetworkNode;
import haveno.network.p2p.mailbox.MailboxMessageService;
import haveno.network.p2p.peers.PeerManager;
import haveno.network.p2p.peers.Broadcaster;
import monero.daemon.model.MoneroTx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javafx.collections.FXCollections;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.natpryce.makeiteasy.MakeItEasy.make;
import static com.natpryce.makeiteasy.MakeItEasy.with;
import static haveno.core.offer.OfferMaker.btcUsdOffer;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeastOnce;

public class OpenOfferManagerTest {
    private PersistenceManager<TradableList<OpenOffer>> persistenceManager;
    private PersistenceManager<SignedOfferList> signedOfferPersistenceManager;
    private CoreContext coreContext;
    private KeyRing keyRing;

    @BeforeEach
    public void setUp() throws Exception {
        var corruptedStorageFileHandler = mock(CorruptedStorageFileHandler.class);
        var storageDir = Files.createTempDirectory("storage").toFile();
        keyRing = new KeyRing(new KeyStorage(storageDir));
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
    public void testBulkRemovalReportsFailureAndRetriesCanceledOffer() {
        P2PService p2PService = mock(P2PService.class);
        when(p2PService.isBootstrapped()).thenReturn(true);
        OfferBookService offerBookService = mock(OfferBookService.class);
        OpenOfferManager originalManager = HavenoUtils.openOfferManager;
        try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class)) {
            OpenOfferManager manager = createOfferManager(p2PService, offerBookService, mock(XmrConnectionService.class));
            OpenOffer offer = new OpenOffer(make(btcUsdOffer));
            offer.setState(OpenOffer.State.AVAILABLE);
            manager.getObservableList().add(offer);
            Runnable success = mock(Runnable.class);
            ErrorMessageHandler failure = mock(ErrorMessageHandler.class);
            ArgumentCaptor<ErrorMessageHandler> removalFailure = ArgumentCaptor.forClass(ErrorMessageHandler.class);
            ArgumentCaptor<Runnable> drain = ArgumentCaptor.forClass(Runnable.class);
            userThread.when(() -> UserThread.runAfter(drain.capture(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                    .thenReturn(mock(Timer.class));

            manager.removeAllOpenOffers(success, failure);
            verify(offerBookService).removeOffer(eq(offer.getOffer().getOfferPayload()), any(), removalFailure.capture());
            assertTrue(drain.getAllValues().isEmpty());
            removalFailure.getValue().handleErrorMessage("Network unavailable");
            drain.getValue().run();
            verify(success, never()).run();
            verify(failure).handleErrorMessage("Offers that could not be removed: " + offer.getId());
            assertEquals(OpenOffer.State.CANCELED, offer.getState());
            assertTrue(manager.getObservableList().contains(offer));

            manager.removeAllOpenOffers(success, failure);
            verify(offerBookService, times(2)).removeOffer(eq(offer.getOffer().getOfferPayload()), any(), any());
            verify(success, never()).run();
        } finally {
            HavenoUtils.openOfferManager = originalManager;
        }
    }

    @Test
    public void testBulkRemovalWaitsForLocalCancellation() {
        P2PService p2PService = mock(P2PService.class);
        when(p2PService.isBootstrapped()).thenReturn(true);
        OfferBookService offerBookService = mock(OfferBookService.class);
        XmrWalletService walletService = mock(XmrWalletService.class);
        when(walletService.getWalletLock()).thenReturn(new Object());
        OpenOfferManager originalManager = HavenoUtils.openOfferManager;
        ArrayDeque<Runnable> cancellations = new ArrayDeque<>();
        try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class);
             MockedStatic<ThreadUtils> threadUtils = mockStatic(ThreadUtils.class)) {
            OpenOfferManager manager = createOfferManager(p2PService, offerBookService,
                    mock(XmrConnectionService.class), walletService);
            OpenOffer offer = new OpenOffer(make(btcUsdOffer));
            offer.setState(OpenOffer.State.AVAILABLE);
            manager.getObservableList().add(offer);
            Runnable success = mock(Runnable.class);
            ErrorMessageHandler failure = mock(ErrorMessageHandler.class);
            ArgumentCaptor<ResultHandler> removed = ArgumentCaptor.forClass(ResultHandler.class);
            ArgumentCaptor<Runnable> drain = ArgumentCaptor.forClass(Runnable.class);
            userThread.when(() -> UserThread.runAfter(drain.capture(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                    .thenReturn(mock(Timer.class));
            threadUtils.when(() -> ThreadUtils.submitToPool(any(Runnable.class))).thenAnswer(invocation -> {
                cancellations.add(invocation.getArgument(0));
                return CompletableFuture.completedFuture(null);
            });

            manager.removeAllOpenOffers(success, failure);
            verify(offerBookService).removeOffer(any(), removed.capture(), any());
            removed.getValue().handleResult();
            assertTrue(drain.getAllValues().isEmpty());
            verify(success, never()).run();
            cancellations.remove().run();
            assertTrue(manager.getObservableList().isEmpty());
            drain.getValue().run();
            verify(success).run();
            verify(failure, never()).handleErrorMessage(anyString());
        } finally {
            HavenoUtils.openOfferManager = originalManager;
        }
    }

    @Test
    public void testBulkRemovalRequiresNetworkOnlyBeforeWalletReplacement() {
        OfferBookService offerBookService = mock(OfferBookService.class);
        XmrWalletService walletService = mock(XmrWalletService.class);
        when(walletService.getWalletLock()).thenReturn(new Object());
        OpenOfferManager originalManager = HavenoUtils.openOfferManager;
        ArrayDeque<Runnable> cancellations = new ArrayDeque<>();
        try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class);
             MockedStatic<ThreadUtils> threadUtils = mockStatic(ThreadUtils.class)) {
            OpenOfferManager manager = createOfferManager(mock(P2PService.class), offerBookService,
                    mock(XmrConnectionService.class), walletService);
            OpenOffer offer = new OpenOffer(make(btcUsdOffer));
            offer.setState(OpenOffer.State.AVAILABLE);
            manager.getObservableList().add(offer);
            Runnable success = mock(Runnable.class);
            ErrorMessageHandler failure = mock(ErrorMessageHandler.class);
            ArgumentCaptor<Runnable> drain = ArgumentCaptor.forClass(Runnable.class);
            userThread.when(() -> UserThread.runAfter(drain.capture(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                    .thenReturn(mock(Timer.class));
            threadUtils.when(() -> ThreadUtils.submitToPool(any(Runnable.class))).thenAnswer(invocation -> {
                cancellations.add(invocation.getArgument(0));
                return CompletableFuture.completedFuture(null);
            });

            manager.removeAllOpenOffers(success, failure);
            verify(failure).handleErrorMessage("Cannot remove published offers before the P2P network is ready");
            verify(success, never()).run();
            assertEquals(OpenOffer.State.AVAILABLE, offer.getState());
            assertTrue(cancellations.isEmpty());

            manager.removeAllOpenOffers(success);
            cancellations.remove().run();
            drain.getValue().run();
            verify(success).run();
            assertTrue(manager.getObservableList().isEmpty());
            verify(offerBookService, never()).removeOffer(any(), any(), any());
        } finally {
            HavenoUtils.openOfferManager = originalManager;
        }
    }

    @Test
    public void testOfferBookReportsNetworkNotReadyThroughHandlers(@TempDir Path storageDir) {
        P2PService p2PService = mock(P2PService.class);
        XmrConnectionService connectionService = mock(XmrConnectionService.class);
        when(connectionService.getKeyImagePoller()).thenReturn(mock(XmrKeyImagePoller.class));
        OfferBookService service = new OfferBookService(p2PService, null, mock(FilterManager.class), connectionService,
                storageDir.toFile(), false);
        Offer offer = make(btcUsdOffer);
        ResultHandler success = mock(ResultHandler.class);
        ErrorMessageHandler failure = mock(ErrorMessageHandler.class);
        when(p2PService.addProtectedStorageEntry(any())).thenThrow(new NetworkNotReadyException());
        when(p2PService.refreshTTL(any())).thenThrow(new NetworkNotReadyException());
        when(p2PService.removeData(any())).thenThrow(new NetworkNotReadyException());

        service.addOffer(offer, success, failure);
        service.refreshTTL(offer.getOfferPayload(), success, failure);
        service.removeOffer(offer.getOfferPayload(), success, failure);
        service.removeOfferAtShutDown(offer.getOfferPayload());
        verify(success, never()).handleResult();
        verify(failure, times(3)).handleErrorMessage(anyString());
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

        try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class)) {
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

            ErrorMessageHandler failure = mock(ErrorMessageHandler.class);
            ArgumentCaptor<Runnable> drain = ArgumentCaptor.forClass(Runnable.class);
            userThread.when(() -> UserThread.runAfter(drain.capture(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                    .thenReturn(mock(Timer.class));

            manager.removeAllOpenOffers(null, failure);
            drain.getValue().run();
            verify(failure).handleErrorMessage("Offers that could not be removed: " + openOffer.getId());
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

    @Test
    public void testPaymentWarningMatchesTradeAdmissionAndIgnoresRepricingSelf() {
        ClosedTradableManager closedTrades = mock(ClosedTradableManager.class);
        FailedTradesManager failedTrades = mock(FailedTradesManager.class);
        when(failedTrades.getObservableList()).thenReturn(FXCollections.observableArrayList());
        TradeManager originalTradeManager = HavenoUtils.tradeManager;
        var originalNotificationService = HavenoUtils.notificationService;
        try {
            TradeManager manager = new TradeManager(null, null, null, null, null, null, null,
                    closedTrades, failedTrades, mock(P2PService.class), null, null, null, null, null, null, null, null,
                    mock(PersistenceManager.class), null);
            Offer offer = mock(Offer.class);
            when(offer.getDirection()).thenReturn(OfferDirection.BUY);
            when(offer.getCounterCurrencyCode()).thenReturn("USD");
            when(offer.getMakerPaymentAccountId()).thenReturn("account");
            Volume volume = Volume.parse("30", "USD");
            Trade other = mock(Trade.class);
            when(other.getOffer()).thenReturn(offer);
            when(other.isMaker()).thenReturn(true);
            when(other.getVolume()).thenReturn(volume);
            manager.getObservableList().add(other);
            Trade candidate = mock(Trade.class);
            when(candidate.isMaker()).thenReturn(true);
            when(candidate.getOffer()).thenReturn(offer);
            when(candidate.getVolume(1L)).thenReturn(volume);

            assertTrue(manager.hasAmbiguousPayment(offer, volume));
            assertThrows(IllegalArgumentException.class, () -> manager.setTradePrice(candidate, 1L));
            verify(candidate, never()).setPrice(anyLong());
            assertFalse(manager.hasAmbiguousPayment(offer, Volume.parse("31", "USD")));

            when(other.isPayoutPublished()).thenReturn(true);
            assertFalse(manager.hasAmbiguousPayment(offer, volume));
            assertDoesNotThrow(() -> manager.setTradePrice(candidate, 1L));
            when(other.isPayoutPublished()).thenReturn(false);
            when(other.isMaker()).thenReturn(false);
            assertFalse(manager.hasAmbiguousPayment(offer, volume));
            when(other.isMaker()).thenReturn(true);
            when(other.getVolume()).thenReturn(null);
            assertTrue(manager.hasAmbiguousPayment(offer, volume));

            manager.getObservableList().clear();
            manager.getObservableList().add(candidate);
            assertDoesNotThrow(() -> manager.setTradePrice(candidate, 1L));
        } finally {
            HavenoUtils.tradeManager = originalTradeManager;
            HavenoUtils.notificationService = originalNotificationService;
        }
    }

    @Test
    public void testPaymentWarningRetainsUnresolvedClosedAndFailedTrades() {
        ClosedTradableManager closedTrades = mock(ClosedTradableManager.class);
        FailedTradesManager failedTrades = mock(FailedTradesManager.class);
        when(failedTrades.getObservableList()).thenReturn(FXCollections.observableArrayList());
        TradeManager originalTradeManager = HavenoUtils.tradeManager;
        var originalNotificationService = HavenoUtils.notificationService;
        try {
            TradeManager manager = new TradeManager(null, null, null, null, null, null, null,
                    closedTrades, failedTrades, mock(P2PService.class), null, null, null, null, null, null, null, null,
                    mock(PersistenceManager.class), null);
            Offer offer = mock(Offer.class);
            when(offer.getDirection()).thenReturn(OfferDirection.BUY);
            when(offer.getCounterCurrencyCode()).thenReturn("USD");
            when(offer.getMakerPaymentAccountId()).thenReturn("account");
            Volume volume = Volume.parse("30", "USD");
            Trade other = mock(Trade.class);
            when(other.getOffer()).thenReturn(offer);
            when(other.isMaker()).thenReturn(true);
            when(other.getVolume()).thenReturn(volume);
            when(closedTrades.getClosedTrades()).thenReturn(List.of(other));
            assertTrue(manager.hasAmbiguousPayment(offer, volume));

            when(closedTrades.getClosedTrades()).thenReturn(List.of());
            failedTrades.getObservableList().add(other);
            assertFalse(manager.hasAmbiguousPayment(offer, volume));
            when(other.isDepositRequested()).thenReturn(true);
            assertFalse(manager.hasAmbiguousPayment(offer, volume));
            when(other.isProtocolErrorHandlingScheduled()).thenReturn(true);
            assertTrue(manager.hasAmbiguousPayment(offer, volume));
            when(other.isProtocolErrorHandlingScheduled()).thenReturn(false);
            when(other.isDepositsPublished()).thenReturn(true);
            assertTrue(manager.hasAmbiguousPayment(offer, volume));
            when(other.isPayoutPublished()).thenReturn(true);
            assertFalse(manager.hasAmbiguousPayment(offer, volume));
        } finally {
            HavenoUtils.tradeManager = originalTradeManager;
            HavenoUtils.notificationService = originalNotificationService;
        }
    }

    @Test
    public void testUnknownInitTradeRequestsDoNotAllocateTradeThreads() throws Exception {
        List<String> ids = new ArrayList<>();
        try (TradeRequestContext context = new TradeRequestContext()) {
            for (int i = 0; i < 256; i++) {
                String id = "unknown-init-request-" + i;
                ids.add(id);
                context.manager.onDirectMessage(context.message(context.request(id), context.takerKey), context.taker);
            }
            context.executor.shutdown();
            assertTrue(context.executor.awaitTermination(10, TimeUnit.SECONDS));
            verify(context.openOffers, atLeastOnce()).getOpenOffer(anyString());
            assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(thread -> ids.contains(thread.getName())));
        } finally {
            ids.forEach(ThreadUtils::shutDown);
        }
    }

    @Test
    public void testInitTradeAdmissionIsBoundedAndStopsAtShutdown() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (TradeRequestContext context = new TradeRequestContext()) {
            CountDownLatch started = new CountDownLatch(1);
            context.executor.execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            InitTradeRequest request = context.request("unknown");
            DecryptedMessageWithPubKey message = context.message(request, context.takerKey);
            for (int i = 0; i < 256; i++) context.manager.onDirectMessage(message, context.taker);
            assertEquals(64, context.executor.getQueue().size());
            assertEquals(1, context.executor.getLargestPoolSize());
            context.manager.onShutDownStarted();
            assertTrue(context.executor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(context.executor.getQueue().isEmpty());
            assertDoesNotThrow(() -> context.manager.onDirectMessage(message, context.taker));
            verify(context.openOffers, never()).getOpenOffer(anyString());
        } finally {
            release.countDown();
        }
    }

    @Test
    public void testInitTradeAdmissionAuthenticatesMakerTakerAndArbitrator() throws Exception {
        try (TradeRequestContext context = new TradeRequestContext()) {
            InitTradeRequest request = context.request("offer", null);
            Offer offer = mock(Offer.class);
            when(offer.getId()).thenReturn("offer");
            when(offer.getOwnerNodeAddress()).thenReturn(context.maker);
            PubKeyRing makerKeys = mock(PubKeyRing.class);
            when(makerKeys.getSignaturePubKey()).thenReturn(context.makerKey);
            when(offer.getPubKeyRing()).thenReturn(makerKeys);
            OpenOffer openOffer = new OpenOffer(offer);
            openOffer.setState(OpenOffer.State.AVAILABLE);
            when(context.openOffers.getOpenOffer("offer")).thenReturn(Optional.of(openOffer));

            assertTrue(context.expects(request, context.takerKey, context.taker));
            assertFalse(context.expects(request, context.makerKey, context.taker));
            assertFalse(context.expects(request, context.takerKey, context.arbitrator));
            openOffer.setState(OpenOffer.State.RESERVED);
            assertFalse(context.expects(request, context.takerKey, context.taker));
            ArgumentCaptor<AckMessage> unavailable = ArgumentCaptor.forClass(AckMessage.class);
            verify(context.mailbox).sendEncryptedMailboxMessage(eq(context.taker), eq(context.takerKeys), unavailable.capture(), any());
            assertFalse(unavailable.getValue().isSuccess());
            assertEquals("offer", unavailable.getValue().getSourceId());

            when(offer.getChallenge()).thenReturn("secret");
            OpenOffer privateOffer = new OpenOffer(offer);
            privateOffer.setState(OpenOffer.State.AVAILABLE);
            when(context.openOffers.getOpenOffer("offer")).thenReturn(Optional.of(privateOffer));
            assertFalse(context.expects(request, context.takerKey, context.taker));

            when(context.networkNode.getNodeAddress()).thenReturn(context.arbitrator);
            when(context.offerBook.getOffers()).thenReturn(List.of(offer));
            request = context.request("offer");
            OfferPayload payload = mock(OfferPayload.class);
            when(offer.getOfferPayload()).thenReturn(payload);
            NodeAddress signerAddress = new NodeAddress("signer.onion", 9999);
            when(payload.getArbitratorSigner()).thenReturn(signerAddress);
            when(payload.getSignatureHash()).thenReturn(new byte[]{1, 2, 3});
            KeyRing signerKeys = new KeyRing(new KeyStorage(Files.createTempDirectory("admission-signing").toFile()), null, true);
            Arbitrator signer = mock(Arbitrator.class);
            when(signer.getPubKeyRing()).thenReturn(signerKeys.getPubKeyRing());
            when(context.user.getAcceptedArbitratorByAddress(signerAddress)).thenReturn(signer);
            assertFalse(context.expects(request, context.makerKey, context.maker));
            when(payload.getArbitratorSignature()).thenReturn(new byte[]{1, 2, 3});
            assertFalse(context.expects(request, context.makerKey, context.maker));
            byte[] signature = HavenoUtils.signOffer(payload, signerKeys);
            when(payload.getArbitratorSignature()).thenReturn(signature);
            when(context.user.getAcceptedArbitratorByAddress(signerAddress)).thenReturn(null);
            assertFalse(context.expects(request, context.makerKey, context.maker));
            when(context.user.getAcceptedArbitratorByAddress(signerAddress)).thenReturn(signer);
            assertTrue(context.expects(request, context.makerKey, context.maker));
            assertFalse(context.expects(request, context.takerKey, context.maker));
            assertFalse(context.expects(request, context.takerKey, context.taker));
            assertFalse(context.expects(context.request("unknown"), context.makerKey, context.maker));

            ArbitratorTrade arbitratorTrade = mock(ArbitratorTrade.class);
            TradePeer taker = mock(TradePeer.class);
            when(arbitratorTrade.getId()).thenReturn("offer");
            when(arbitratorTrade.getTaker()).thenReturn(taker);
            when(taker.getNodeAddress()).thenReturn(context.taker);
            when(taker.getPubKeyRing()).thenReturn(context.takerKeys);
            context.manager.getObservableList().add(arbitratorTrade);
            assertTrue(context.expects(request, context.takerKey, context.taker));
            assertFalse(context.expects(request, context.makerKey, context.taker));

            context.manager.getObservableList().clear();
            when(context.networkNode.getNodeAddress()).thenReturn(context.taker);
            assertFalse(context.expects(request, context.arbitratorKey, context.arbitrator));
            BuyerAsTakerTrade takerTrade = mock(BuyerAsTakerTrade.class);
            when(takerTrade.getId()).thenReturn("offer");
            context.manager.getObservableList().add(takerTrade);
            assertTrue(context.expects(request, context.arbitratorKey, context.arbitrator));
            assertFalse(context.expects(request, context.takerKey, context.arbitrator));

            context.manager.getObservableList().clear();
            when(context.networkNode.getNodeAddress()).thenReturn(context.arbitrator);
            when(context.user.getRegisteredArbitrator()).thenReturn(signer);
            when(signer.getNodeAddress()).thenReturn(context.arbitrator);
            when(payload.getArbitratorSigner()).thenReturn(context.arbitrator);
            assertTrue(context.expects(request, context.makerKey, context.maker));
        }
    }

    @Test
    public void testSigningDoesNotBlockDispatchAndCoalescesAuthenticatedSender() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (SigningRequestContext context = new SigningRequestContext()) {
            CountDownLatch verifying = new CountDownLatch(1);
            doAnswer(invocation -> {
                verifying.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                throw new IllegalStateException("verification failed");
            }).when(context.walletService).verifyReserveTx(anyString(), any(), any(), any(), any(), anyString(), anyString(), anyString(), anyString(), any());
            SignOfferRequest request = context.request("offer", context.makerKeys);
            ExecutorService dispatcher = Executors.newSingleThreadExecutor();
            try {
                var arrival = dispatcher.submit(() -> context.receive(request));
                assertTrue(verifying.await(5, TimeUnit.SECONDS));
                arrival.get(5, TimeUnit.SECONDS);
                for (int i = 0; i < 100; i++) context.receive(request);
                PubKeyRing changedEncryptionKey = mock(PubKeyRing.class);
                PublicKey signatureKey = context.makerKeys.getSignaturePubKey();
                when(changedEncryptionKey.getSignaturePubKey()).thenReturn(signatureKey);
                context.receive(context.request("same-signer", changedEncryptionKey));
                assertTrue(context.executor.getQueue().isEmpty());
                verify(context.p2p, never()).sendEncryptedDirectMessage(any(), any(), any(AckMessage.class), any());

                OfferAvailabilityRequest availability = mock(OfferAvailabilityRequest.class);
                when(availability.getOfferId()).thenReturn("unrelated");
                when(availability.getPubKeyRing()).thenReturn(context.makerKeys);
                DecryptedMessageWithPubKey message = mock(DecryptedMessageWithPubKey.class);
                when(message.getNetworkEnvelope()).thenReturn(availability);
                dispatcher.submit(() -> context.manager.onDirectMessage(message, context.maker)).get(5, TimeUnit.SECONDS);
                verify(context.p2p).sendEncryptedDirectMessage(eq(context.maker), eq(context.makerKeys),
                        argThat(envelope -> envelope instanceof AckMessage && "unrelated".equals(((AckMessage) envelope).getSourceId())), any());
                verify(context.walletService).verifyReserveTx(anyString(), any(), any(), any(), any(), anyString(), anyString(), anyString(), anyString(), any());
            } finally {
                release.countDown();
                dispatcher.shutdownNow();
                assertTrue(dispatcher.awaitTermination(5, TimeUnit.SECONDS));
            }
            context.executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            context.receive(request);
            context.executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            verify(context.walletService, times(2)).verifyReserveTx(anyString(), any(), any(), any(), any(), anyString(), anyString(), anyString(), anyString(), any());
        } finally {
            release.countDown();
        }
    }

    @Test
    public void testSigningQueueIsBoundedAndShutdownSkipsQueuedRequests() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (SigningRequestContext context = new SigningRequestContext()) {
            CountDownLatch verifying = new CountDownLatch(1);
            AtomicBoolean interrupted = new AtomicBoolean();
            doAnswer(invocation -> {
                verifying.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    throw e;
                }
                return new MoneroTx().setFee(BigInteger.ONE);
            }).when(context.walletService).verifyReserveTx(anyString(), any(), any(), any(), any(), anyString(), anyString(), anyString(), anyString(), any());
            try {
                context.receive(context.request("active", context.makerKeys));
                assertTrue(verifying.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 32; i++) context.receive(context.request("queued-" + i, context.keys()));
                assertEquals(16, context.executor.getQueue().size());
                assertEquals(1, context.executor.getLargestPoolSize());
                verify(context.p2p, never()).sendEncryptedDirectMessage(any(), any(), any(AckMessage.class), any());
                CountDownLatch shutdown = new CountDownLatch(1);
                context.manager.shutDown(shutdown::countDown);
                assertTrue(context.executor.isShutdown());
                context.receive(context.request("after-shutdown", context.keys()));
                release.countDown();
                assertTrue(shutdown.await(5, TimeUnit.SECONDS));
                assertFalse(interrupted.get());
                verify(context.walletService).verifyReserveTx(anyString(), any(), any(), any(), any(), anyString(), anyString(), anyString(), anyString(), any());
                verify(context.p2p, never()).sendEncryptedDirectMessage(any(), any(), any(), any());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    public void testSigningAuthenticatesRequestsAndPreservesSuccessfulVerification() throws Exception {
        keyRing.generateKeys(null);
        try (SigningRequestContext context = new SigningRequestContext()) {
            SignOfferRequest request = context.request("offer", context.makerKeys);
            DecryptedMessageWithPubKey impostor = mock(DecryptedMessageWithPubKey.class);
            when(impostor.getNetworkEnvelope()).thenReturn(request);
            when(impostor.getSignaturePubKey()).thenReturn(mock(PublicKey.class));
            context.manager.onDirectMessage(impostor, context.maker);
            when(request.getOfferPayload().getOwnerNodeAddress()).thenReturn(context.arbitrator);
            context.receive(request);
            verify(context.p2p).sendEncryptedDirectMessage(eq(context.maker), eq(context.makerKeys),
                    argThat(envelope -> envelope instanceof AckMessage && !((AckMessage) envelope).isSuccess()), any());
            when(request.getOfferPayload().getOwnerNodeAddress()).thenReturn(context.maker);
            verify(context.walletService, never()).verifyReserveTx(anyString(), any(), any(), any(), any(), anyString(), anyString(), anyString(), anyString(), any());

            when(context.walletService.verifyReserveTx(anyString(), any(), any(), any(), any(), anyString(), anyString(), anyString(), anyString(), any()))
                    .thenReturn(new MoneroTx().setFee(BigInteger.ONE));
            try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class)) {
                context.manager.onAllConnectionsLost();
            }
            context.receive(request);
            context.executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            ArgumentCaptor<byte[]> signature = ArgumentCaptor.forClass(byte[].class);
            verify(request.getOfferPayload()).setArbitratorSignature(signature.capture());
            assertTrue(HavenoUtils.isSignatureValid(keyRing.getPubKeyRing(), request.getOfferPayload().getSignatureHash(), signature.getValue()));
            verify(context.p2p).sendEncryptedDirectMessage(eq(context.maker), eq(context.makerKeys), any(SignOfferResponse.class), any());
            verify(context.p2p).sendEncryptedDirectMessage(eq(context.maker), eq(context.makerKeys),
                    argThat(envelope -> envelope instanceof AckMessage && ((AckMessage) envelope).isSuccess()), any());
        }
    }

    private class SigningRequestContext implements AutoCloseable {
        private final NodeAddress maker = new NodeAddress("maker.onion", 9999);
        private final NodeAddress arbitrator = new NodeAddress("arbitrator.onion", 9999);
        private final PubKeyRing makerKeys = keys();
        private final P2PService p2p = mock(P2PService.class);
        private final XmrWalletService walletService = mock(XmrWalletService.class);
        private final OpenOfferManager originalManager = HavenoUtils.openOfferManager;
        private final OpenOfferManager manager;
        private final ThreadPoolExecutor executor;

        private SigningRequestContext() throws Exception {
            User user = mock(User.class);
            Arbitrator registeredArbitrator = mock(Arbitrator.class);
            when(registeredArbitrator.getNodeAddress()).thenReturn(arbitrator);
            when(user.getRegisteredArbitrator()).thenReturn(registeredArbitrator);
            NetworkNode networkNode = mock(NetworkNode.class);
            when(networkNode.getNodeAddress()).thenReturn(arbitrator);
            when(p2p.getNetworkNode()).thenReturn(networkNode);
            when(p2p.getPeerManager()).thenReturn(mock(PeerManager.class));
            XmrConnectionService connectionService = mock(XmrConnectionService.class);
            when(connectionService.getKeyImagePoller()).thenReturn(mock(XmrKeyImagePoller.class));
            manager = new OpenOfferManager(coreContext, keyRing, user, p2p, connectionService, null, walletService,
                    null, mock(OfferBookService.class), mock(ClosedTradableManager.class), null, null, null,
                    null, null, mock(FilterManager.class), mock(Broadcaster.class), persistenceManager,
                    signedOfferPersistenceManager, null);
            var field = OpenOfferManager.class.getDeclaredField("signOfferRequestExecutor");
            field.setAccessible(true);
            executor = (ThreadPoolExecutor) field.get(manager);
        }

        private PubKeyRing keys() {
            PubKeyRing keys = mock(PubKeyRing.class);
            when(keys.getSignaturePubKey()).thenReturn(mock(PublicKey.class));
            return keys;
        }

        private SignOfferRequest request(String id, PubKeyRing keys) {
            OfferPayload payload = mock(OfferPayload.class);
            when(payload.getId()).thenReturn(id);
            when(payload.getOwnerNodeAddress()).thenReturn(maker);
            when(payload.getPubKeyRing()).thenReturn(keys);
            when(payload.getArbitratorSigner()).thenReturn(arbitrator);
            when(payload.getProtocolVersion()).thenReturn(Version.TRADE_PROTOCOL_VERSION);
            when(payload.getDirection()).thenReturn(OfferDirection.BUY);
            when(payload.getPrice()).thenReturn(100000L);
            when(payload.getAmount()).thenReturn(1000000000000L);
            when(payload.getCounterCurrencyCode()).thenReturn("USD");
            when(payload.getMakerFeePct()).thenReturn(HavenoUtils.getMakerFeePct("USD", false));
            when(payload.getTakerFeePct()).thenReturn(HavenoUtils.getTakerFeePct("USD", false));
            when(payload.getPenaltyFeePct()).thenReturn(HavenoUtils.PENALTY_FEE_PCT);
            when(payload.getBuyerSecurityDepositPct()).thenReturn(Restrictions.getMinSecurityDepositPct());
            when(payload.getSellerSecurityDepositPct()).thenReturn(Restrictions.getMinSecurityDepositPct());
            when(payload.getMaxBuyerSecurityDeposit()).thenReturn(BigInteger.valueOf(150000000000L));
            when(payload.getSignatureHash()).thenReturn(new byte[]{1, 2, 3});
            return new SignOfferRequest(id, maker, keys, "account", payload, id, "1", 1,
                    "1".repeat(64), "0102", "2".repeat(64), List.of("3".repeat(64)), "payout");
        }

        private void receive(SignOfferRequest request) {
            DecryptedMessageWithPubKey message = mock(DecryptedMessageWithPubKey.class);
            when(message.getNetworkEnvelope()).thenReturn(request);
            PublicKey signatureKey = request.getPubKeyRing().getSignaturePubKey();
            when(message.getSignaturePubKey()).thenReturn(signatureKey);
            manager.onDirectMessage(message, maker);
        }

        @Override
        public void close() throws Exception {
            CountDownLatch shutdown = new CountDownLatch(1);
            manager.shutDown(shutdown::countDown);
            try {
                assertTrue(shutdown.await(5, TimeUnit.SECONDS));
            } finally {
                HavenoUtils.openOfferManager = originalManager;
            }
        }
    }

    private static class TradeRequestContext implements AutoCloseable {
        private final NodeAddress maker = new NodeAddress("maker.onion", 9999);
        private final NodeAddress taker = new NodeAddress("taker.onion", 9999);
        private final NodeAddress arbitrator = new NodeAddress("arbitrator.onion", 9999);
        private final PublicKey makerKey = mock(PublicKey.class);
        private final PublicKey takerKey = mock(PublicKey.class);
        private final PublicKey arbitratorKey = mock(PublicKey.class);
        private final PubKeyRing takerKeys = mock(PubKeyRing.class);
        private final User user = mock(User.class);
        private final MailboxMessageService mailbox = mock(MailboxMessageService.class);
        private final OpenOfferManager openOffers = mock(OpenOfferManager.class);
        private final OfferBookService offerBook = mock(OfferBookService.class);
        private final NetworkNode networkNode = mock(NetworkNode.class);
        private final TradeManager originalManager = HavenoUtils.tradeManager;
        private final CoreNotificationService originalNotifications = HavenoUtils.notificationService;
        private final TradeManager manager;
        private final ThreadPoolExecutor executor;

        private TradeRequestContext() throws Exception {
            Arbitrator registeredArbitrator = mock(Arbitrator.class);
            PubKeyRing arbitratorKeys = mock(PubKeyRing.class);
            when(arbitratorKeys.getSignaturePubKey()).thenReturn(arbitratorKey);
            when(registeredArbitrator.getNodeAddress()).thenReturn(arbitrator);
            when(registeredArbitrator.getPubKeyRing()).thenReturn(arbitratorKeys);
            when(user.getRegisteredArbitrator()).thenReturn(registeredArbitrator);
            when(user.getAcceptedArbitratorByAddress(arbitrator)).thenReturn(registeredArbitrator);
            when(takerKeys.getSignaturePubKey()).thenReturn(takerKey);
            P2PService p2p = mock(P2PService.class);
            when(p2p.getNetworkNode()).thenReturn(networkNode);
            when(p2p.getMailboxMessageService()).thenReturn(mailbox);
            when(networkNode.getNodeAddress()).thenReturn(maker);
            FailedTradesManager failedTrades = mock(FailedTradesManager.class);
            when(failedTrades.getObservableList()).thenReturn(FXCollections.observableArrayList());
            manager = new TradeManager(user, null, null, null, null, offerBook, openOffers,
                    mock(ClosedTradableManager.class), failedTrades, p2p, null, null, null, null, null, null, null, null,
                    mock(PersistenceManager.class), null);
            var field = TradeManager.class.getDeclaredField("initTradeRequestExecutor");
            field.setAccessible(true);
            executor = (ThreadPoolExecutor) field.get(manager);
        }

        private InitTradeRequest request(String id) {
            return request(id, arbitrator);
        }

        private InitTradeRequest request(String id, NodeAddress arbitratorAddress) {
            return new InitTradeRequest(TradeProtocolVersion.MULTISIG_2_3, id, 1, 1, "SEPA", null,
                    "taker", "maker-payment", "taker-payment", takerKeys, "uid", "1", null, 1,
                    maker, taker, arbitratorAddress, null, null, null, null, null);
        }

        private DecryptedMessageWithPubKey message(InitTradeRequest request, PublicKey signatureKey) {
            DecryptedMessageWithPubKey message = mock(DecryptedMessageWithPubKey.class);
            when(message.getNetworkEnvelope()).thenReturn(request);
            when(message.getSignaturePubKey()).thenReturn(signatureKey);
            return message;
        }

        private boolean expects(InitTradeRequest request, PublicKey signatureKey, NodeAddress sender) throws Exception {
            var method = TradeManager.class.getDeclaredMethod("isInitTradeRequestExpected", DecryptedMessageWithPubKey.class, InitTradeRequest.class, NodeAddress.class);
            method.setAccessible(true);
            return (boolean) method.invoke(manager, message(request, signatureKey), request, sender);
        }

        @Override
        public void close() throws Exception {
            executor.shutdownNow();
            try {
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
                HavenoUtils.tradeManager = originalManager;
                HavenoUtils.notificationService = originalNotifications;
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
                mock(ClosedTradableManager.class),
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
