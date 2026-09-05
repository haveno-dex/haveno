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

package haveno.network.crypto;

import haveno.common.app.Version;
import haveno.common.crypto.AuthenticatedEncryption;
import haveno.common.crypto.Encryption;
import haveno.common.crypto.Hash;
import haveno.common.crypto.SealedAndSigned;
import haveno.common.crypto.Sig;
import haveno.common.crypto.CryptoException;
import haveno.common.proto.network.NetworkProtoResolver;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.file.FileUtil;
import haveno.common.proto.network.NetworkEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.File;
import java.io.IOException;

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
    public void legacyAndAuthenticatedMessagesRoundTripAndOutgoingStaysLegacy() throws Exception {
        MockMessage message = new MockMessage(42);
        NetworkProtoResolver resolver = mock(NetworkProtoResolver.class);
        when(resolver.fromProto(any(protobuf.NetworkEnvelope.class))).thenReturn(message);
        EncryptionService service = new EncryptionService(keyRing, resolver);
        assertEquals(1, Version.NETWORK_ENCRYPTION_VERSION);
        SealedAndSigned defaultSeal = service.encryptAndSign(keyRing.getPubKeyRing(), message);
        assertFalse(AuthenticatedEncryption.isEnvelope(defaultSeal.getEncryptedPayloadWithHmac()));
        // A baseline client can open the default seal using only its legacy primitives.
        assertArrayEquals(message.toProtoNetworkEnvelope().toByteArray(), Encryption.decryptPayloadWithHmac(
                defaultSeal.getEncryptedPayloadWithHmac(), Encryption.decryptSecretKey(defaultSeal.getEncryptedSecretKey(),
                        keyRing.getEncryptionKeyPair().getPrivate())));
        for (int version : new int[]{1, 2}) {
            SealedAndSigned seal = EncryptionService.encryptHybridWithSignature(message, keyRing.getSignatureKeyPair(),
                    keyRing.getPubKeyRing().getEncryptionPubKey(), version);
            assertEquals(version == 2, AuthenticatedEncryption.isEnvelope(seal.getEncryptedPayloadWithHmac()));
            assertEquals(message, service.decryptHybridWithSignature(seal, keyRing.getEncryptionKeyPair().getPrivate()).getNetworkEnvelope());
        }
    }

    @Test
    public void evenValidlySignedDamagedEnvelopeNeverFallsBackOrReachesResolver() throws Exception {
        NetworkProtoResolver resolver = mock(NetworkProtoResolver.class);
        EncryptionService service = new EncryptionService(keyRing, resolver);
        SealedAndSigned seal = EncryptionService.encryptHybridWithSignature(new MockMessage(7), keyRing.getSignatureKeyPair(),
                keyRing.getPubKeyRing().getEncryptionPubKey(), 2);
        byte[] bytes = seal.getEncryptedPayloadWithHmac().clone();
        bytes[bytes.length - 1] ^= 1;
        byte[] signature = Sig.sign(keyRing.getSignatureKeyPair().getPrivate(), Hash.getSha256Hash(bytes));
        SealedAndSigned damaged = new SealedAndSigned(seal.getEncryptedSecretKey(), bytes, signature, keyRing.getSignatureKeyPair().getPublic());
        assertThrows(CryptoException.class, () -> service.decryptHybridWithSignature(damaged, keyRing.getEncryptionKeyPair().getPrivate()));
        verifyNoInteractions(resolver);
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
