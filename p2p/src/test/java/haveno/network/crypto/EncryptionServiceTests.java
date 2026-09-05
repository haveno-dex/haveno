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

import haveno.common.crypto.AuthenticatedEncryption;
import haveno.common.crypto.CryptoException;
import haveno.common.crypto.Encryption;
import haveno.common.crypto.Hash;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.crypto.SealedAndSigned;
import haveno.common.crypto.Sig;
import haveno.common.proto.network.NetworkProtoResolver;
import haveno.network.p2p.peers.keepalive.messages.Ping;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Base64;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EncryptionServiceTests {
    @TempDir Path directory;

    private EncryptionService service(KeyRing ring) throws Exception {
        NetworkProtoResolver resolver = mock(NetworkProtoResolver.class);
        when(resolver.fromProto(any(protobuf.NetworkEnvelope.class))).thenAnswer(call -> {
            protobuf.NetworkEnvelope proto = call.getArgument(0);
            return Ping.fromProto(proto.getPing(), proto.getMessageVersion());
        });
        return new EncryptionService(ring, resolver);
    }

    @Test
    void legacySignedMessagesRoundTripThroughProductionServiceAndProto() throws Exception {
        KeyRing ring = new KeyRing(new KeyStorage(directory.toFile()), null, true);
        EncryptionService service = service(ring);
        Ping payload = Ping.fromProto(protobuf.Ping.newBuilder().setNonce(42).setLastRoundTripTime(10).build(), "test");
        SealedAndSigned sealed = service.encryptAndSign(ring.getPubKeyRing(), payload);
        assertFalse(AuthenticatedEncryption.hasEnvelope(sealed.getEncryptedPayloadWithHmac()));
        var read = service.decryptAndVerify(SealedAndSigned.fromProto(sealed.toProtoMessage()));
        assertEquals(payload, read.getNetworkEnvelope());
        assertEquals(ring.getPubKeyRing().getSignaturePubKey(), read.getSignaturePubKey());
    }

    @Test
    void modernSignedMessagesAreReadableAndBindSenderIdentity() throws Exception {
        KeyRing ring = new KeyRing(new KeyStorage(directory.toFile()), null, true);
        EncryptionService service = service(ring);
        Ping payload = Ping.fromProto(protobuf.Ping.newBuilder().setNonce(7).setLastRoundTripTime(9).build(), "test");
        SecretKey key = Encryption.generateSecretKey(256);
        byte[] wrapped = Encryption.encryptSecretKey(key, ring.getPubKeyRing().getEncryptionPubKey());
        byte[] sender = Sig.getPublicKeyBytes(ring.getPubKeyRing().getSignaturePubKey());
        byte[] bound = ByteBuffer.allocate(8 + wrapped.length + sender.length).putInt(wrapped.length).put(wrapped)
                .putInt(sender.length).put(sender).array();
        String context = "network-message/" + Base64.getEncoder().encodeToString(Hash.getSha256Hash(bound));
        byte[] encrypted = AuthenticatedEncryption.encrypt(payload.toProtoNetworkEnvelope().toByteArray(), key, context);
        byte[] signature = Sig.sign(ring.getSignatureKeyPair().getPrivate(), Hash.getSha256Hash(encrypted));
        SealedAndSigned sealed = new SealedAndSigned(wrapped, encrypted, signature, ring.getPubKeyRing().getSignaturePubKey());
        assertEquals(payload, service.decryptAndVerify(SealedAndSigned.fromProto(sealed.toProtoMessage())).getNetworkEnvelope());

        var other = Sig.generateKeyPair();
        SealedAndSigned resigned = new SealedAndSigned(wrapped, encrypted,
                Sig.sign(other.getPrivate(), Hash.getSha256Hash(encrypted)), other.getPublic());
        assertThrows(CryptoException.class, () -> service.decryptAndVerify(resigned));
        encrypted[encrypted.length - 1] ^= 1;
        assertThrows(CryptoException.class, () -> service.decryptAndVerify(sealed));
    }
}
