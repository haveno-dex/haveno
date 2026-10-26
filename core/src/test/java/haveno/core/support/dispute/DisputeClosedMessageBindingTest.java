package haveno.core.support.dispute;

import haveno.common.ThreadUtils;
import haveno.common.crypto.Hash;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.app.Version;
import haveno.common.file.FileUtil;
import haveno.common.util.Utilities;
import haveno.core.api.CoreDisputesService;
import haveno.core.support.SupportType;
import haveno.core.support.dispute.arbitration.ArbitrationDisputeListService;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.support.dispute.messages.DisputeClosedMessage;
import haveno.core.support.messages.ChatMessage;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.trade.protocol.TradePeer;
import haveno.core.trade.protocol.TradeProtocol;
import haveno.network.p2p.AckMessage;
import haveno.network.p2p.AckMessageSourceType;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.mailbox.MailboxMessageService;
import javafx.beans.property.SimpleObjectProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigInteger;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DisputeClosedMessageBindingTest {
    private static final String SIG_BEGIN = "\n-----BEGIN SIGNATURE-----\n";
    private static final String SIG_END = "\n-----END SIGNATURE-----\n";

    private File dir;
    private KeyRing arbitratorKeyRing;

    @BeforeEach
    public void setup() throws Exception {
        Version.setBaseCryptoNetworkId(1);
        dir = File.createTempFile("temp_tests", "");
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdir();
        arbitratorKeyRing = new KeyRing(new KeyStorage(dir), null, true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        FileUtil.deleteDirectory(dir);
    }

    /**
     * The arbitrator's summary-text signature only proves the human-readable summary. It does not
     * bind the structured payout fields the wallet relies on, which is why {@link DisputeResult}
     * carries a separate arbitrator signature over {@link DisputeResult#getPayoutSignaturePayload()}.
     */
    @Test
    public void summaryTextSignatureDoesNotBindStructuredPayoutFields() {
        String tradeId = "trade-binding-poc";
        int traderId = 7;
        NodeAddress arbitratorAddress = new NodeAddress("127.0.0.1", 9998);

        BigInteger signedBuyerPayout = BigInteger.valueOf(100_000_000_000L);
        BigInteger signedSellerPayout = BigInteger.valueOf(200_000_000_000L);
        BigInteger injectedBuyerPayout = BigInteger.valueOf(299_000_000_000L);

        String signedText = "Ticket closed on " + new Date() + "\n" +
                "arbitrator node address: " + arbitratorAddress.getFullAddress() + "\n\n" +
                "Summary:\n" +
                "Trade ID: " + tradeId + "\n" +
                "Payout amount for XMR buyer: " + signedBuyerPayout + "\n" +
                "Payout amount for XMR seller: " + signedSellerPayout + "\n";
        byte[] textSignature = HavenoUtils.sign(arbitratorKeyRing.getSignatureKeyPair().getPrivate(), Hash.getSha256Hash(signedText));
        String signedSummary = signedText + SIG_BEGIN + Utilities.encodeToHex(textSignature) + SIG_END;

        // mutate the structured buyer payout; the summary-text signature still verifies
        DisputeResult mutated = sign(tradeId, traderId, arbitratorAddress, signedSummary, signedBuyerPayout, signedSellerPayout);
        mutated.setBuyerPayoutAmountBeforeCost(injectedBuyerPayout);
        assertDoesNotThrow(() -> DisputeSummaryVerification.verifySignature(
                mutated.getChatMessage().getMessage(), arbitratorKeyRing.getPubKeyRing()));
    }

    @Test
    public void validPayoutSignatureVerifies() {
        DisputeResult received = roundTrip(result -> {});
        assertTrue(isPayoutSignatureValid(received));
    }

    @Test
    public void mutatedBuyerPayoutBreaksPayoutSignature() {
        DisputeResult received = roundTrip(proto -> proto.setBuyerPayoutAmountBeforeCost(299_000_000_000L));
        assertFalse(isPayoutSignatureValid(received));
    }

    @Test
    public void mutatedSellerPayoutBreaksPayoutSignature() {
        DisputeResult received = roundTrip(proto -> proto.setSellerPayoutAmountBeforeCost(1_000_000_000L));
        assertFalse(isPayoutSignatureValid(received));
    }

    @Test
    public void mutatedWinnerBreaksPayoutSignature() {
        DisputeResult received = roundTrip(proto -> proto.setWinner(protobuf.DisputeResult.Winner.BUYER));
        assertFalse(isPayoutSignatureValid(received));
    }

    @Test
    public void mutatedSubtractFeeFromBreaksPayoutSignature() {
        DisputeResult received = roundTrip(proto -> proto.setSubtractFeeFrom(protobuf.DisputeResult.SubtractFeeFrom.BUYER_ONLY));
        assertFalse(isPayoutSignatureValid(received));
    }

    @Test
    public void customAwardIncludesExtraDepositInLoserRemainder() {
        DisputeResult result = customAward(2200, false, DisputeResult.Winner.BUYER, 1300);
        assertEquals(BigInteger.valueOf(1300), result.getBuyerPayoutAmountBeforeCost());
        assertEquals(BigInteger.valueOf(900), result.getSellerPayoutAmountBeforeCost());
        assertEquals(DisputeResult.SubtractFeeFrom.SELLER_ONLY, result.getSubtractFeeFrom());
    }

    @Test
    public void customAwardCanAllocateSurplusToWinner() {
        DisputeResult result = customAward(2200, false, DisputeResult.Winner.SELLER, 2100);
        assertEquals(BigInteger.valueOf(100), result.getBuyerPayoutAmountBeforeCost());
        assertEquals(BigInteger.valueOf(2100), result.getSellerPayoutAmountBeforeCost());
        assertEquals(DisputeResult.SubtractFeeFrom.BUYER_ONLY, result.getSubtractFeeFrom());
    }

    @Test
    public void customAwardHandlesMissingDepositAndUnchangedBalance() {
        assertEquals(BigInteger.valueOf(200), customAward(1500, false, DisputeResult.Winner.BUYER, 1300).getSellerPayoutAmountBeforeCost());
        assertEquals(BigInteger.valueOf(700), customAward(2000, false, DisputeResult.Winner.BUYER, 1300).getSellerPayoutAmountBeforeCost());
        assertEquals(BigInteger.ZERO, customAward(1500, false, DisputeResult.Winner.BUYER, 1500).getSellerPayoutAmountBeforeCost());
    }

    @Test
    public void customAwardRejectsNegativeOrExcessiveAmounts() {
        assertThrows(IllegalArgumentException.class, () -> customAward(2200, false, DisputeResult.Winner.BUYER, -1));
        assertThrows(RuntimeException.class, () -> customAward(2200, false, DisputeResult.Winner.BUYER, 2201));
        assertThrows(RuntimeException.class, () -> customAward(1500, false, DisputeResult.Winner.SELLER, 1700));
    }

    @Test
    public void customAwardAfterPublicationDoesNotUseSpentWalletBalance() {
        DisputeResult result = customAward(0, true, DisputeResult.Winner.BUYER, 1300);
        assertEquals(BigInteger.valueOf(1300), result.getBuyerPayoutAmountBeforeCost());
        assertEquals(BigInteger.valueOf(700), result.getSellerPayoutAmountBeforeCost());
    }

    @Test
    public void changedHexClearsStalePayoutTx() {
        TradePeer peer = new TradePeer();
        peer.setUpdatedMultisigHex("export1");
        peer.setUnsignedPayoutTxHex("payout");
        peer.setUpdatedMultisigHex("export2", true);
        assertEquals("export2", peer.getUpdatedMultisigHex());
        assertNull(peer.getUnsignedPayoutTxHex());
    }

    @Test
    public void sameHexKeepsPayoutTx() {
        TradePeer peer = new TradePeer();
        peer.setUpdatedMultisigHex("export1");
        peer.setUnsignedPayoutTxHex("payout");
        peer.setUpdatedMultisigHex("export1", true);
        assertEquals("payout", peer.getUnsignedPayoutTxHex());
    }

    @Test
    public void hexAfterClearedExportClearsStalePayoutTx() {
        TradePeer peer = new TradePeer();
        peer.setUnsignedPayoutTxHex("payout");
        peer.setUpdatedMultisigHex("export1", true);
        assertNull(peer.getUnsignedPayoutTxHex());
    }

    @Test
    public void nullHexOrPlainSetterKeepsPayoutTx() {
        TradePeer peer = new TradePeer();
        peer.setUpdatedMultisigHex("export1");
        peer.setUnsignedPayoutTxHex("payout");
        peer.setUpdatedMultisigHex(null, true);
        assertNull(peer.getUpdatedMultisigHex());
        assertEquals("payout", peer.getUnsignedPayoutTxHex());
        peer.setUpdatedMultisigHex("export2");
        assertEquals("payout", peer.getUnsignedPayoutTxHex());
    }

    @Test
    public void disputePayoutNackReplacesExportBeforeResending() throws Exception {
        try (NackFixture fixture = new NackFixture()) {
            fixture.handle(fixture.nack("fresh-export"));
            assertEquals("fresh-export", fixture.peer.getUpdatedMultisigHex());
            assertNull(fixture.peer.getUnsignedPayoutTxHex());
            fixture.assertResends(1);
            verify(fixture.trade).requestPersistence();
        }
    }

    @Test
    public void duplicateDisputePayoutNacksDoNotUseRetriesOrRestoreOldExport() throws Exception {
        try (NackFixture fixture = new NackFixture()) {
            AckMessage first = fixture.nack("first-export");
            fixture.handle(first);
            for (int i = 0; i < TradeProtocol.MAX_ATTEMPTS; i++) fixture.handle(first);
            fixture.handle(fixture.nack("latest-export"));
            fixture.peer.setUnsignedPayoutTxHex("rebuilt-payout");
            fixture.handle(first);
            assertEquals("latest-export", fixture.peer.getUpdatedMultisigHex());
            assertEquals("rebuilt-payout", fixture.peer.getUnsignedPayoutTxHex());
            fixture.assertResends(2);
        }
    }

    @Test
    public void nackForPriorRulingDoesNotReplaceCurrentExport() throws Exception {
        try (NackFixture fixture = new NackFixture()) {
            AckMessage oldNack = fixture.nack("old-export");
            fixture.newRuling();
            fixture.handle(oldNack);
            assertEquals("initial-export", fixture.peer.getUpdatedMultisigHex());
            assertEquals("initial-payout", fixture.peer.getUnsignedPayoutTxHex());
            fixture.assertResends(0);
        }
    }

    @Test
    public void reopenedOrPublishedDisputeIgnoresPayoutNack() throws Exception {
        try (NackFixture fixture = new NackFixture()) {
            when(fixture.dispute.isClosed()).thenReturn(false);
            fixture.handle(fixture.nack("reopened-export"));
            assertEquals("initial-export", fixture.peer.getUpdatedMultisigHex());
            when(fixture.dispute.isClosed()).thenReturn(true);
            when(fixture.trade.isPayoutPublished()).thenReturn(true);
            fixture.handle(fixture.nack("published-export"));
            assertEquals("initial-export", fixture.peer.getUpdatedMultisigHex());
            fixture.assertResends(0);
        }
    }

    @Test
    public void disputePayoutNackRequiresOwnersMultisigExport() throws Exception {
        try (NackFixture fixture = new NackFixture()) {
            fixture.handle(fixture.nack(null));
            when(fixture.dispute.getTraderPubKeyRing()).thenReturn(null);
            fixture.handle(fixture.nack("another-traders-export"));
            assertEquals("initial-export", fixture.peer.getUpdatedMultisigHex());
            fixture.assertResends(0);
        }
    }

    @Test
    public void newRulingHasItsOwnBoundedNackRetries() throws Exception {
        try (NackFixture fixture = new NackFixture()) {
            for (int i = 0; i < TradeProtocol.MAX_ATTEMPTS * 3; i++) fixture.handle(fixture.nack("export-" + i));
            fixture.assertResends(TradeProtocol.MAX_ATTEMPTS);
            assertEquals("export-" + TradeProtocol.MAX_ATTEMPTS, fixture.peer.getUpdatedMultisigHex());
            assertNull(fixture.peer.getUnsignedPayoutTxHex());
            var field = ArbitrationManager.class.getDeclaredField("disputeClosedMessageNackUids");
            field.setAccessible(true);
            Map<?, ?> nackUids = (Map<?, ?>) field.get(fixture.manager);
            Set<?> rulingNackUids = (Set<?>) nackUids.get(fixture.result.get().getChatMessage().getUid());
            assertEquals(TradeProtocol.MAX_ATTEMPTS + 1, rulingNackUids.size());
            fixture.newRuling();
            fixture.handle(fixture.nack("new-ruling-export"));
            fixture.assertResends(TradeProtocol.MAX_ATTEMPTS + 1);
        }
    }

    private class NackFixture implements AutoCloseable {
        private final String tradeId = "dispute-nack";
        private final Trade trade = mock(Trade.class);
        private final Dispute dispute = mock(Dispute.class);
        private final TradePeer peer = new TradePeer();
        private final SimpleObjectProperty<DisputeResult> result = new SimpleObjectProperty<>();
        private final ArbitrationManager previousManager = HavenoUtils.arbitrationManager;
        private final ArbitrationManager manager;

        private NackFixture() {
            P2PService p2p = mock(P2PService.class);
            when(p2p.getMailboxMessageService()).thenReturn(mock(MailboxMessageService.class));
            manager = spy(new ArbitrationManager(p2p, null, null, null, null, null, null, null, null,
                    mock(ArbitrationDisputeListService.class), null, null));
            doNothing().when(manager).closeDisputeTicket(any(), any(), any(), any(), any());
            when(trade.getId()).thenReturn(tradeId);
            when(trade.getLock()).thenReturn(new Object());
            peer.setPubKeyRing(arbitratorKeyRing.getPubKeyRing());
            peer.setUpdatedMultisigHex("initial-export");
            peer.setUnsignedPayoutTxHex("initial-payout");
            when(dispute.getTraderPubKeyRing()).thenReturn(peer.getPubKeyRing());
            when(dispute.getDisputeResultProperty()).thenReturn(result);
            when(dispute.isClosed()).thenReturn(true);
            newRuling();
        }

        private void newRuling() {
            DisputeResult ruling = new DisputeResult(tradeId, 1);
            ruling.setChatMessage(new ChatMessage(SupportType.ARBITRATION, tradeId, 1, false, "summary",
                    new NodeAddress("127.0.0.1", 9998)));
            result.set(ruling);
        }

        private AckMessage nack(String export) {
            return new AckMessage(new NodeAddress("127.0.0.1", 9999), AckMessageSourceType.ARBITRATION_MESSAGE,
                    ChatMessage.class.getSimpleName(), result.get().getChatMessage().getUid(), tradeId,
                    false, "stale data", export);
        }

        private void handle(AckMessage nack) throws Exception {
            var handler = ArbitrationManager.class.getDeclaredMethod("onDisputeClosedMessageNack",
                    Trade.class, Dispute.class, TradePeer.class, AckMessage.class);
            handler.setAccessible(true);
            handler.invoke(manager, trade, dispute, peer, nack);
            ThreadUtils.execute(() -> {}, tradeId).get(5, TimeUnit.SECONDS);
        }

        private void assertResends(int count) {
            verify(manager, times(count)).closeDisputeTicket(any(), any(), any(), any(), any());
        }

        @Override
        public void close() {
            ThreadUtils.shutDown(tradeId, 5000L);
            HavenoUtils.arbitrationManager = previousManager;
        }
    }

    private DisputeResult customAward(long walletBalance, boolean published, DisputeResult.Winner winner, long winnerAmount) {
        Trade trade = mock(Trade.class, RETURNS_DEEP_STUBS);
        when(trade.getBuyer().getSecurityDeposit()).thenReturn(BigInteger.valueOf(300));
        when(trade.getSeller().getSecurityDeposit()).thenReturn(BigInteger.valueOf(700));
        when(trade.isPayoutPublished()).thenReturn(published);
        when(trade.getWalletBalance()).thenReturn(BigInteger.valueOf(walletBalance));
        Dispute dispute = mock(Dispute.class, RETURNS_DEEP_STUBS);
        when(dispute.getTradeId()).thenReturn("custom-award");
        when(dispute.getContract().getTradeAmount()).thenReturn(BigInteger.valueOf(1000));
        TradeManager tradeManager = mock(TradeManager.class);
        when(tradeManager.getTrade("custom-award")).thenReturn(trade);
        CoreDisputesService service = new CoreDisputesService(null, null, null, tradeManager, null);
        DisputeResult result = new DisputeResult("custom-award", 1);
        result.setWinner(winner);
        service.applyPayoutAmountsToDisputeResult(CoreDisputesService.PayoutSuggestion.CUSTOM, dispute, result, winnerAmount);
        return result;
    }

    // builds a signed dispute result, applies the given mutation to its protobuf, then deserializes as the receiver would
    private DisputeResult roundTrip(Consumer<protobuf.DisputeResult.Builder> mutator) {
        String tradeId = "trade-binding-poc";
        int traderId = 7;
        NodeAddress arbitratorAddress = new NodeAddress("127.0.0.1", 9998);
        DisputeResult disputeResult = sign(tradeId, traderId, arbitratorAddress, "summary",
                BigInteger.valueOf(100_000_000_000L), BigInteger.valueOf(200_000_000_000L));

        DisputeClosedMessage message = new DisputeClosedMessage(disputeResult, arbitratorAddress, "uid",
                SupportType.ARBITRATION, null, "unsigned-payout-tx-placeholder", false);

        protobuf.DisputeClosedMessage proto = message.toProtoNetworkEnvelope().getDisputeClosedMessage();
        protobuf.DisputeResult.Builder resultBuilder = proto.getDisputeResult().toBuilder();
        mutator.accept(resultBuilder);
        proto = proto.toBuilder().setDisputeResult(resultBuilder).build();

        return DisputeClosedMessage.fromProto(proto, "test").getDisputeResult();
    }

    private DisputeResult sign(String tradeId, int traderId, NodeAddress arbitratorAddress, String summary,
                              BigInteger buyerPayout, BigInteger sellerPayout) {
        DisputeResult disputeResult = new DisputeResult(tradeId, traderId);
        disputeResult.setWinner(DisputeResult.Winner.SELLER);
        disputeResult.setReason(DisputeResult.Reason.OTHER);
        disputeResult.setSubtractFeeFrom(DisputeResult.SubtractFeeFrom.SELLER_ONLY);
        disputeResult.setBuyerPayoutAmountBeforeCost(buyerPayout);
        disputeResult.setSellerPayoutAmountBeforeCost(sellerPayout);
        disputeResult.setSummaryNotes("normal arbitrator result");
        disputeResult.setCloseDate(new Date());
        disputeResult.setChatMessage(new ChatMessage(SupportType.ARBITRATION, tradeId, traderId, false, summary, arbitratorAddress));
        disputeResult.setArbitratorSignature(HavenoUtils.sign(arbitratorKeyRing.getSignatureKeyPair().getPrivate(),
                Hash.getSha256Hash(disputeResult.getPayoutSignaturePayload())));
        return disputeResult;
    }

    private boolean isPayoutSignatureValid(DisputeResult disputeResult) {
        return HavenoUtils.isSignatureValid(arbitratorKeyRing.getPubKeyRing(),
                Hash.getSha256Hash(disputeResult.getPayoutSignaturePayload()), disputeResult.getArbitratorSignature());
    }
}
