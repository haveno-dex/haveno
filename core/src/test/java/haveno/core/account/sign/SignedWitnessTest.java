package haveno.core.account.sign;

import com.google.common.base.Charsets;
import haveno.common.crypto.Sig;
import haveno.common.util.Utilities;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Utils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static haveno.core.account.sign.SignedWitness.VerificationMethod.ARBITRATOR;
import static haveno.core.account.sign.SignedWitness.VerificationMethod.TRADE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SignedWitnessTest {

    private ECKey arbitrator1Key;
    private byte[] witnessOwner1PubKey;
    private byte[] witnessHash;
    private byte[] witnessHashSignature;

    @BeforeEach
    public void setUp() {
        arbitrator1Key = new ECKey();
        witnessOwner1PubKey = Sig.getPublicKeyBytes(Sig.generateKeyPair().getPublic());
        witnessHash = Utils.sha256hash160(new byte[]{1});
        witnessHashSignature = arbitrator1Key.signMessage(Utilities.encodeToHex(witnessHash)).getBytes(Charsets.UTF_8);
    }

    @Test
    public void testProtoRoundTrip() {
        SignedWitness signedWitness = new SignedWitness(ARBITRATOR, witnessHash, witnessHashSignature, arbitrator1Key.getPubKey(), witnessOwner1PubKey, Instant.now().getEpochSecond(), 100);
        assertEquals(signedWitness, SignedWitness.fromProto(signedWitness.toProtoMessage().getSignedWitness()));
    }

    @Test
    public void isImmutable() {
        byte[] signerPubkey = arbitrator1Key.getPubKey();
        SignedWitness signedWitness = new SignedWitness(TRADE, witnessHash, witnessHashSignature, signerPubkey, witnessOwner1PubKey, Instant.now().getEpochSecond(), 100);
        byte[] originalWitnessHash = signedWitness.getAccountAgeWitnessHash().clone();
        witnessHash[0] += 1;
        assertArrayEquals(originalWitnessHash, signedWitness.getAccountAgeWitnessHash());

        byte[] originalWitnessHashSignature = signedWitness.getSignature().clone();
        witnessHashSignature[0] += 1;
        assertArrayEquals(originalWitnessHashSignature, signedWitness.getSignature());

        byte[] originalSignerPubkey = signedWitness.getSignerPubKey().clone();
        signerPubkey[0] += 1;
        assertArrayEquals(originalSignerPubkey, signedWitness.getSignerPubKey());
        byte[] originalwitnessOwner1PubKey = signedWitness.getWitnessOwnerPubKey().clone();
        witnessOwner1PubKey[0] += 1;
        assertArrayEquals(originalwitnessOwner1PubKey, signedWitness.getWitnessOwnerPubKey());
    }

}
