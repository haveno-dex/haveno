package haveno.core.offer;

import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.file.CorruptedStorageFileHandler;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import haveno.common.persistence.PersistenceManager;
import haveno.core.api.CoreContext;
import haveno.core.api.XmrConnectionService;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.TradableList;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.network.p2p.NetworkNotReadyException;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.peers.PeerManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.natpryce.makeiteasy.MakeItEasy.make;
import static com.natpryce.makeiteasy.MakeItEasy.with;
import static haveno.core.offer.OfferMaker.btcUsdOffer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    public void testReserveOpenOfferRequiresAvailableOpenOffer() {
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

        // cannot reserve an offer which is not in the open offers list
        assertFalse(manager.reserveOpenOffer(openOffer));

        // reserve an available open offer
        manager.getObservableList().add(openOffer);
        assertTrue(manager.reserveOpenOffer(openOffer));
        assertEquals(OpenOffer.State.RESERVED, openOffer.getState());

        // cannot reserve an already reserved offer
        assertFalse(manager.reserveOpenOffer(openOffer));
    }

    @Test
    public void testRemoveAllOpenOffersSkipsReservedOffer() {
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
        manager.getObservableList().add(openOffer);
        assertTrue(manager.reserveOpenOffer(openOffer));

        // removal skips the reserved offer instead of canceling it
        manager.removeAllOpenOffers(null);
        assertEquals(OpenOffer.State.RESERVED, openOffer.getState());
        assertTrue(manager.getObservableList().contains(openOffer));
        verify(offerBookService, never()).removeOffer(any(OfferPayload.class), any(), any());
    }

    @Test
    public void testReserveOpenOfferRejectsReservedClone() {
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

        final OpenOffer openOffer = new OpenOffer(make(btcUsdOffer), 0, false, "group");
        final OpenOffer clonedOffer = new OpenOffer(make(btcUsdOffer.but(with(OfferMaker.id, "5678"))), 0, false, "group");
        openOffer.setState(OpenOffer.State.AVAILABLE);
        clonedOffer.setState(OpenOffer.State.AVAILABLE);
        manager.getObservableList().add(openOffer);
        manager.getObservableList().add(clonedOffer);

        // cannot reserve an offer while a clone sharing its funds is reserved
        assertTrue(manager.reserveOpenOffer(openOffer));
        assertFalse(manager.reserveOpenOffer(clonedOffer));
        assertEquals(OpenOffer.State.AVAILABLE, clonedOffer.getState());
    }

    @Test
    public void testReserveOpenOfferRejectsCloneWithOpenTrade() {
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

        final OpenOffer openOffer = new OpenOffer(make(btcUsdOffer), 0, false, "group");
        final OpenOffer clonedOffer = new OpenOffer(make(btcUsdOffer.but(with(OfferMaker.id, "5678"))), 0, false, "group");
        openOffer.setState(OpenOffer.State.AVAILABLE);
        clonedOffer.setState(OpenOffer.State.AVAILABLE);
        manager.getObservableList().add(openOffer);
        manager.getObservableList().add(clonedOffer);

        // cannot reserve an offer while a clone sharing its funds has an open trade, even if not marked reserved yet
        TradeManager tradeManager = mock(TradeManager.class);
        try {
            HavenoUtils.tradeManager = tradeManager;
            when(tradeManager.getOpenTrade(clonedOffer.getId())).thenReturn(Optional.of(mock(Trade.class)));
            assertFalse(manager.reserveOpenOffer(openOffer));
            assertEquals(OpenOffer.State.AVAILABLE, openOffer.getState());
        } finally {
            HavenoUtils.tradeManager = null;
        }
    }

    @Test
    public void testReserveOpenOfferRejectsOfferBeingEdited() {
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

        doAnswer(invocation -> {
            ((ResultHandler) invocation.getArgument(1)).handleResult();
            return null;
        }).when(offerBookService).deactivateOffer(any(OfferPayload.class), any(ResultHandler.class), any(ErrorMessageHandler.class));

        final OpenOffer openOffer = new OpenOffer(make(btcUsdOffer));
        openOffer.setState(OpenOffer.State.AVAILABLE);
        manager.getObservableList().add(openOffer);
        manager.editOpenOfferStart(openOffer, () -> {
        }, errorMessage -> {
        });

        // cannot reserve an offer being edited, even if available while the edit publishes
        openOffer.setState(OpenOffer.State.AVAILABLE);
        assertFalse(manager.reserveOpenOffer(openOffer));
        assertEquals(OpenOffer.State.AVAILABLE, openOffer.getState());
    }

    @Test
    public void testEditOpenOfferStartRejectsReservedOffer() {
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
        manager.getObservableList().add(openOffer);
        assertTrue(manager.reserveOpenOffer(openOffer));

        // editing is rejected with an error while the offer is reserved
        AtomicBoolean errorHandled = new AtomicBoolean(false);
        manager.editOpenOfferStart(openOffer, () -> {
        }, errorMessage -> errorHandled.set(true));
        assertTrue(errorHandled.get());
        assertEquals(OpenOffer.State.RESERVED, openOffer.getState());
        verify(offerBookService, never()).deactivateOffer(any(OfferPayload.class), any(), any());
    }

    @Test
    public void testRemoveOpenOfferRejectsReservedOffer() {
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
        manager.getObservableList().add(openOffer);
        assertTrue(manager.reserveOpenOffer(openOffer));

        // cancellation is rejected with an error while the offer is reserved
        AtomicBoolean errorHandled = new AtomicBoolean(false);
        manager.removeOpenOffer(openOffer, () -> {
        }, errorMessage -> errorHandled.set(true));
        assertTrue(errorHandled.get());
        assertEquals(OpenOffer.State.RESERVED, openOffer.getState());
        assertTrue(manager.getObservableList().contains(openOffer));
        verify(offerBookService, never()).removeOffer(any(OfferPayload.class), any(), any());
    }

}
