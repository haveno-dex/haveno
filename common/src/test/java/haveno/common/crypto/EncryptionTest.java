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

package haveno.common.crypto;

import com.google.common.io.ByteStreams;
import haveno.common.proto.persistable.NavigationPath;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class EncryptionTest {

    // Sizes around AES block (16) and stream chunk (64 KiB) boundaries, plus an empty payload.
    private static final int[] SIZES = {0, 1, 15, 16, 17, 1000, 65_535, 65_536, 65_537, 100_000, 5_000_000};

    // The streaming read (verify + limited decrypt) must produce exactly the same payload as the array
    // path for every boundary size.
    @Test
    public void testStreamingReadMatchesPayload() throws Exception {
        SecretKey key = Encryption.generateSecretKey(256);
        Random random = new Random(5678);
        for (int size : SIZES) {
            byte[] payload = new byte[size];
            random.nextBytes(payload);

            byte[] encrypted = Encryption.encryptPayloadWithHmac(payload, key);
            assertEquals(size, Encryption.verifyPayloadWithHmacStream(new ByteArrayInputStream(encrypted), key),
                    "payload length mismatch for size " + size);
            assertArrayEquals(payload, streamReadPayload(encrypted, key),
                    "streaming read differs from payload for size " + size);
        }
    }

    // Full read of a real protobuf message large enough to span several 64 KiB decrypt chunks, proving the
    // verify/limit split feeds parseFrom exactly the payload (and nothing of the trailing hmac).
    @Test
    public void testProtoRoundTripAcrossChunks() throws Exception {
        SecretKey key = Encryption.generateSecretKey(256);
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < 5000; i++) entries.add("navigation/path/segment/number/" + i + "/with/some/padding");
        NavigationPath navigationPath = new NavigationPath(entries);
        protobuf.PersistableEnvelope envelope = (protobuf.PersistableEnvelope) navigationPath.toProtoMessage();
        byte[] encrypted = Encryption.encryptPayloadWithHmac(envelope.toByteArray(), key);

        long payloadLength = Encryption.verifyPayloadWithHmacStream(new ByteArrayInputStream(encrypted), key);
        try (InputStream decryptStream = Encryption.decryptStream(new ByteArrayInputStream(encrypted), key);
             InputStream payloadStream = ByteStreams.limit(decryptStream, payloadLength)) {
            protobuf.PersistableEnvelope parsed = protobuf.PersistableEnvelope.parseFrom(payloadStream);
            assertEquals(envelope, parsed, "proto round-trip through streaming read failed");
            assertEquals(navigationPath, NavigationPath.fromProto(parsed.getNavigationPath()));
        }
    }

    // Drives the read with a stream that returns a single byte per read() call, exercising the rolling
    // 32-byte holdback under the smallest possible chunks (where off-by-one errors would surface).
    @Test
    public void testStreamingReadWithOneByteReads() throws Exception {
        SecretKey key = Encryption.generateSecretKey(256);
        Random random = new Random(9012);
        for (int size : new int[]{0, 1, 31, 32, 33, 100, 70_000}) {
            byte[] payload = new byte[size];
            random.nextBytes(payload);
            byte[] encrypted = Encryption.encryptPayloadWithHmac(payload, key);

            long payloadLength = Encryption.verifyPayloadWithHmacStream(new DripInputStream(encrypted), key);
            assertEquals(size, payloadLength, "payload length mismatch (drip) for size " + size);
            try (InputStream decryptStream = Encryption.decryptStream(new DripInputStream(encrypted), key);
                 InputStream payloadStream = ByteStreams.limit(decryptStream, payloadLength)) {
                assertArrayEquals(payload, payloadStream.readAllBytes(), "drip read mismatch for size " + size);
            }
        }
    }

    @Test
    public void testCorruptCiphertextIsRejected() throws CryptoException {
        SecretKey key = Encryption.generateSecretKey(256);
        byte[] encrypted = Encryption.encryptPayloadWithHmac("hello world payload".getBytes(), key);
        encrypted[encrypted.length / 2] ^= 0x01; // flip a bit inside the ciphertext
        assertThrows(CryptoException.class,
                () -> Encryption.verifyPayloadWithHmacStream(new ByteArrayInputStream(encrypted), key));
    }

    @Test
    public void testTruncatedCiphertextIsRejected() throws CryptoException {
        SecretKey key = Encryption.generateSecretKey(256);
        byte[] encrypted = Encryption.encryptPayloadWithHmac(new byte[10_000], key);
        byte[] truncated = new byte[encrypted.length - 16];
        System.arraycopy(encrypted, 0, truncated, 0, truncated.length);
        assertThrows(CryptoException.class,
                () -> Encryption.verifyPayloadWithHmacStream(new ByteArrayInputStream(truncated), key));
    }

    @Test
    public void testWrongKeyIsRejected() throws CryptoException {
        SecretKey key = Encryption.generateSecretKey(256);
        SecretKey otherKey = Encryption.generateSecretKey(256);
        byte[] encrypted = Encryption.encryptPayloadWithHmac(new byte[10_000], key);
        assertThrows(CryptoException.class,
                () -> Encryption.verifyPayloadWithHmacStream(new ByteArrayInputStream(encrypted), otherKey));
    }

    private static byte[] streamReadPayload(byte[] encrypted, SecretKey key) throws Exception {
        long payloadLength = Encryption.verifyPayloadWithHmacStream(new ByteArrayInputStream(encrypted), key);
        try (InputStream decryptStream = Encryption.decryptStream(new ByteArrayInputStream(encrypted), key);
             InputStream payloadStream = ByteStreams.limit(decryptStream, payloadLength)) {
            return payloadStream.readAllBytes();
        }
    }

    // An InputStream that hands out at most one byte per read() call.
    private static class DripInputStream extends ByteArrayInputStream {
        DripInputStream(byte[] buf) {
            super(buf);
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            return super.read(b, off, len == 0 ? 0 : 1);
        }
    }
}
