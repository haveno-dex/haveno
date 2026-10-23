package haveno.core.support.dispute;

import haveno.common.UserThread;
import haveno.common.app.Version;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.crypto.PubKeyRing;
import haveno.common.crypto.PubKeyRingProvider;
import haveno.common.file.FileUtil;
import haveno.core.api.CoreNotificationService;
import haveno.core.api.XmrConnectionService;
import haveno.core.offer.OfferMaker;
import haveno.core.support.SupportType;
import haveno.core.support.messages.ChatMessage;
import haveno.core.support.traderchat.TraderChatManager;
import haveno.core.trade.Contract;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.core.trade.protocol.TradePeer;
import haveno.network.p2p.AckMessage;
import haveno.network.p2p.AckMessageSourceType;
import haveno.network.p2p.DecryptedDirectMessageListener;
import haveno.network.p2p.DecryptedMessageWithPubKey;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.mailbox.MailboxMessageService;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.collections.FXCollections;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Date;

import static com.natpryce.makeiteasy.MakeItEasy.make;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DisputeOpenedSenderValidationTest {
    private static final String TRADE_ID = "trade-sender-validation";

    private File dir;
    private PubKeyRing buyerPubKeyRing;
    private PubKeyRing sellerPubKeyRing;
    private PubKeyRing arbitratorPubKeyRing;
    private Trade trade;

    @BeforeEach
    public void setup() throws Exception {
        Version.setBaseCryptoNetworkId(1);
        dir = File.createTempFile("temp_tests", "");
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdir();
        buyerPubKeyRing = newPubKeyRing("buyer");
        sellerPubKeyRing = newPubKeyRing("seller");
        arbitratorPubKeyRing = newPubKeyRing("arbitrator");

        TradePeer arbitratorPeer = new TradePeer();
        arbitratorPeer.setPubKeyRing(arbitratorPubKeyRing);
        trade = mock(Trade.class);
        when(trade.getId()).thenReturn(TRADE_ID);
        when(trade.isArbitrator()).thenReturn(true);
        when(trade.getArbitrator()).thenReturn(arbitratorPeer);
    }

    @AfterEach
    public void tearDown() throws Exception {
        FileUtil.deleteDirectory(dir);
    }

    @Test
    public void newDisputeFromOpenerPasses() {
        Dispute dispute = buildDispute(buyerPubKeyRing, buyerPubKeyRing.hashCode());
        assertDoesNotThrow(() -> DisputeValidation.validateSenderRole(dispute, trade, peer(buyerPubKeyRing), false));
    }

    @Test
    public void newDisputeWithCounterpartyIdentityRejected() {
        // opener claims the counterparty's trader identity fields
        Dispute dispute = buildDispute(sellerPubKeyRing, sellerPubKeyRing.hashCode());
        assertThrows(DisputeValidation.ValidationException.class,
                () -> DisputeValidation.validateSenderRole(dispute, trade, peer(buyerPubKeyRing), false));
    }

    @Test
    public void reOpenByDisputeOwnerPasses() {
        Dispute stored = buildDispute(sellerPubKeyRing, sellerPubKeyRing.hashCode());
        assertDoesNotThrow(() -> DisputeValidation.validateSenderRole(stored, trade, peer(sellerPubKeyRing), true));
    }

    @Test
    public void reOpenByCounterpartyRejected() {
        // a trader must not re-open the counterparty's stored dispute
        Dispute stored = buildDispute(sellerPubKeyRing, sellerPubKeyRing.hashCode());
        assertThrows(DisputeValidation.ValidationException.class,
                () -> DisputeValidation.validateSenderRole(stored, trade, peer(buyerPubKeyRing), true));
    }

    @Test
    public void arbitratorAsSenderRejected() {
        Dispute dispute = buildDispute(buyerPubKeyRing, buyerPubKeyRing.hashCode());
        assertThrows(DisputeValidation.ValidationException.class,
                () -> DisputeValidation.validateSenderRole(dispute, trade, peer(arbitratorPubKeyRing), false));
    }

    @Test
    public void unknownAndUnauthenticatedAcksDoNotBlockOrMutateChat() {
        P2PService p2p = mock(P2PService.class);
        MailboxMessageService mailbox = mock(MailboxMessageService.class);
        when(p2p.getMailboxMessageService()).thenReturn(mailbox);
        XmrConnectionService connection = mock(XmrConnectionService.class);
        XmrWalletService wallet = mock(XmrWalletService.class);
        when(wallet.downloadPercentageProperty()).thenReturn(new SimpleDoubleProperty());
        when(connection.isDownloadComplete()).thenReturn(true);
        when(connection.hasSufficientPeersForBroadcast()).thenReturn(true);
        when(wallet.wasWalletSynced()).thenReturn(true);
        TradeManager trades = mock(TradeManager.class);
        when(trades.getTrade(TRADE_ID)).thenReturn(trade);
        ChatMessage chat = new ChatMessage(SupportType.TRADE, TRADE_ID, 0, true,
                "hello", new NodeAddress("peer:9999"));
        when(trade.getChatMessages()).thenReturn(FXCollections.observableArrayList(chat));
        TraderChatManager manager = new TraderChatManager(p2p, connection, wallet,
                mock(CoreNotificationService.class), trades, mock(PubKeyRingProvider.class));
        ArgumentCaptor<DecryptedDirectMessageListener> listener = ArgumentCaptor.forClass(DecryptedDirectMessageListener.class);
        verify(p2p).addDecryptedDirectMessageListener(listener.capture());
        manager.onAllServicesInitialized();

        AckMessage unknown = chatAck("unknown-trade", chat, true);
        listener.getValue().onDirectMessage(new DecryptedMessageWithPubKey(unknown,
                sellerPubKeyRing.getSignaturePubKey()), unknown.getSenderNodeAddress());
        when(p2p.isBootstrapped()).thenReturn(true);
        assertDoesNotThrow(manager::tryApplyMessages);
        assertTrue(manager.getAllChatMessages("unknown-trade").isEmpty());

        for (boolean success : new boolean[]{true, false}) {
            AckMessage forged = chatAck(TRADE_ID, chat, success);
            listener.getValue().onDirectMessage(new DecryptedMessageWithPubKey(forged,
                    arbitratorPubKeyRing.getSignaturePubKey()), forged.getSenderNodeAddress());
            assertFalse(chat.acknowledgedProperty().get());
            assertNull(chat.ackErrorProperty().get());
        }

        AckMessage valid = chatAck(TRADE_ID, chat, true);
        DecryptedMessageWithPubKey authenticated = new DecryptedMessageWithPubKey(valid, sellerPubKeyRing.getSignaturePubKey());
        when(trade.getVerifiedTradePeer(authenticated)).thenReturn(peer(sellerPubKeyRing));
        try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class)) {
            userThread.when(() -> UserThread.execute(any(Runnable.class))).thenAnswer(invocation -> {
                ((Runnable) invocation.getArgument(0)).run();
                return null;
            });
            listener.getValue().onDirectMessage(authenticated, valid.getSenderNodeAddress());
        }
        assertTrue(chat.acknowledgedProperty().get());
    }

    private AckMessage chatAck(String tradeId, ChatMessage chat, boolean success) {
        return new AckMessage(new NodeAddress("peer:9999"), AckMessageSourceType.TRADE_CHAT_MESSAGE,
                ChatMessage.class.getSimpleName(), chat.getUid(), tradeId, success, success ? null : "rejected");
    }

    private PubKeyRing newPubKeyRing(String name) {
        File keyDir = new File(dir, name);
        //noinspection ResultOfMethodCallIgnored
        keyDir.mkdir();
        return new KeyRing(new KeyStorage(keyDir), null, true).getPubKeyRing();
    }

    private TradePeer peer(PubKeyRing pubKeyRing) {
        TradePeer peer = new TradePeer();
        peer.setPubKeyRing(pubKeyRing);
        return peer;
    }

    // builds a dispute opened by the buyer with the given trader identity fields
    private Dispute buildDispute(PubKeyRing traderPubKeyRing, int traderId) {
        Contract contract = new Contract(make(OfferMaker.btcUsdOffer).getOfferPayload(),
                100000L,
                100000L,
                new NodeAddress("127.0.0.1", 1000),
                new NodeAddress("127.0.0.1", 1001),
                new NodeAddress("127.0.0.1", 1002),
                true,
                "makerAccountId",
                "takerAccountId",
                "SEPA",
                "SEPA",
                new byte[32],
                new byte[32],
                buyerPubKeyRing,
                sellerPubKeyRing,
                "makerPayoutAddress",
                "takerPayoutAddress",
                "makerDepositTxHash",
                null);
        return new Dispute(new Date().getTime(),
                TRADE_ID,
                traderId,
                true,
                true,
                true,
                traderPubKeyRing,
                new Date().getTime(),
                new Date().getTime(),
                contract,
                new byte[32],
                null,
                null,
                "contractAsJson",
                null,
                null,
                null,
                null,
                arbitratorPubKeyRing,
                false,
                SupportType.ARBITRATION);
    }
}
