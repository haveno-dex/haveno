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

package haveno.network.crypto;

import haveno.common.Payload;
import haveno.common.crypto.CryptoException;
import haveno.common.crypto.Encryption;
import haveno.common.crypto.Hash;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.crypto.SealedAndSigned;
import haveno.common.crypto.Sig;
import haveno.common.file.FileUtil;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.common.proto.network.NetworkPayload;
import haveno.common.proto.network.NetworkProtoResolver;
import haveno.common.proto.persistable.PersistablePayload;
import haveno.network.p2p.DecryptedMessageWithPubKey;
import java.io.File;
import java.io.IOException;
import java.time.Clock;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class EncryptionServiceTests {

    private KeyRing keyRing;
    private File dir;

    @BeforeEach
    public void setup() throws IOException {
        dir = File.createTempFile("temp_tests", "");
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdir();
        KeyStorage keyStorage = new KeyStorage(dir);
        keyRing = new KeyRing(keyStorage, null, true);
    }

    @AfterEach
    public void tearDown() throws IOException {
        FileUtil.deleteDirectory(dir);
    }

    @Test
    public void testSealRoundTrip() throws Exception {
        EncryptionService service = new EncryptionService(keyRing, resolver());
        SealedAndSigned sealed = service.encryptAndSign(keyRing.getPubKeyRing(), new MockMessage(12345));
        DecryptedMessageWithPubKey decrypted = service.decryptAndVerify(sealed);
        assertEquals(12345, ((MockMessage) decrypted.getNetworkEnvelope()).nonce);
    }

    @Test
    public void testV2SealIsAccepted() throws Exception {
        // a received seal may carry a v2-encrypted payload regardless of what this node sends
        SealedAndSigned sealed = sealWithPayloadCiphertext(Encryption::encryptV2, 54321);
        EncryptionService service = new EncryptionService(keyRing, resolver());
        DecryptedMessageWithPubKey decrypted = service.decryptAndVerify(sealed);
        assertEquals(54321, ((MockMessage) decrypted.getNetworkEnvelope()).nonce);
    }

    @Test
    public void testTamperedPayloadIsRejected() throws Exception {
        SecretKey secretKey = Encryption.generateSecretKey(256);
        byte[] encryptedSecretKey = Encryption.encryptSecretKey(secretKey, keyRing.getPubKeyRing().getEncryptionPubKey());
        byte[] payload = new MockMessage(999).toProtoNetworkEnvelope().toByteArray();
        byte[] tampered = Encryption.encryptV2(payload, secretKey);
        tampered[tampered.length / 2] ^= 0x01;
        // re-sign the tampered ciphertext so the failure is attributable to the payload authentication
        byte[] signature = Sig.sign(keyRing.getSignatureKeyPair().getPrivate(), Hash.getSha256Hash(tampered));
        SealedAndSigned sealed = new SealedAndSigned(encryptedSecretKey, tampered, signature, keyRing.getSignatureKeyPair().getPublic());

        EncryptionService service = new EncryptionService(keyRing, resolver());
        assertThrows(CryptoException.class, () -> service.decryptAndVerify(sealed));
    }

    // Builds a seal like EncryptionService.encryptHybridWithSignature but with a caller-chosen
    // payload encryption, to simulate senders using other supported formats.
    private interface PayloadEncryptor {
        byte[] encrypt(byte[] payload, SecretKey secretKey) throws CryptoException;
    }

    private SealedAndSigned sealWithPayloadCiphertext(PayloadEncryptor encryptor, int nonce) throws CryptoException {
        SecretKey sessionKey = Encryption.generateSecretKey(256);
        byte[] encryptedSecretKey = Encryption.encryptSecretKey(sessionKey, keyRing.getPubKeyRing().getEncryptionPubKey());
        byte[] payload = new MockMessage(nonce).toProtoNetworkEnvelope().toByteArray();
        byte[] encryptedPayload = encryptor.encrypt(payload, sessionKey);
        byte[] signature = Sig.sign(keyRing.getSignatureKeyPair().getPrivate(), Hash.getSha256Hash(encryptedPayload));
        return new SealedAndSigned(encryptedSecretKey, encryptedPayload, signature, keyRing.getSignatureKeyPair().getPublic());
    }

    private static NetworkProtoResolver resolver() {
        return new NetworkProtoResolver() {
            @Override
            public NetworkEnvelope fromProto(protobuf.NetworkEnvelope envelope) {
                return new MockMessage(envelope.getPing().getNonce());
            }

            @Override
            public NetworkPayload fromProto(protobuf.StoragePayload proto) {
                return null;
            }

            @Override
            public NetworkPayload fromProto(protobuf.StorageEntryWrapper proto) {
                return null;
            }

            @Override
            public Payload fromProto(protobuf.PaymentAccountPayload proto) {
                return null;
            }

            @Override
            public PersistablePayload fromProto(protobuf.PersistableNetworkPayload persistable) {
                return null;
            }

            @Override
            public Clock getClock() {
                return null;
            }
        };
    }

    private static class MockMessage extends NetworkEnvelope {
        public final int nonce;

        public MockMessage(int nonce) {
            super("0");
            this.nonce = nonce;
        }

        @Override
        public String getMessageVersion() {
            return "0";
        }

        @Override
        public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
            return protobuf.NetworkEnvelope.newBuilder().setPing(protobuf.Ping.newBuilder().setNonce(nonce)).build();
        }
    }
}
