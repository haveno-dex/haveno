package haveno.core.support.dispute;

import haveno.common.crypto.Hash;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.app.Version;
import haveno.common.file.FileUtil;
import haveno.common.util.Utilities;
import haveno.core.support.SupportType;
import haveno.core.support.dispute.messages.DisputeClosedMessage;
import haveno.core.support.messages.ChatMessage;
import haveno.core.trade.HavenoUtils;
import haveno.network.p2p.NodeAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigInteger;
import java.util.Date;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
