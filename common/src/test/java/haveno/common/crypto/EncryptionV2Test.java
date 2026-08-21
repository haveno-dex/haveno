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

package haveno.common.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EncryptionV2Test {

    // Sizes around AES block (16) and stream chunk (64 KiB) boundaries, plus an empty payload.
    private static final int[] SIZES = {0, 1, 15, 16, 17, 1000, 65_535, 65_536, 65_537, 100_000, 5_000_000};

    @Test
    public void testRoundTrip() throws CryptoException {
        SecretKey key = Encryption.generateSecretKey(256);
        Random random = new Random(1234);
        for (int size : SIZES) {
            byte[] payload = new byte[size];
            random.nextBytes(payload);
            byte[] blob = Encryption.encryptV2(payload, key);
            assertTrue(Encryption.isV2Format(blob));
            assertArrayEquals(payload, Encryption.decryptV2(blob, key), "round-trip failed for size " + size);
            assertArrayEquals(payload, Encryption.decryptAuto(blob, key));
            assertArrayEquals(payload, Encryption.decryptPayloadWithHmacAuto(blob, key));
        }
    }

    @Test
    public void testBlobVersionDetection() throws CryptoException {
        SecretKey key = Encryption.generateSecretKey(256);
        byte[] v2 = Encryption.encryptV2("hello".getBytes(), key);
        assertEquals(Encryption.CURRENT_BLOB_VERSION, Encryption.blobVersion(v2), "fresh blobs must detect as the current format");
        assertEquals(0, Encryption.blobVersion(Encryption.encrypt(new byte[16], key)), "legacy blobs must detect as version 0");
        assertEquals(0, Encryption.blobVersion(new byte[0]));
        assertEquals(0, Encryption.blobVersion((byte[]) null));
    }

    @Test
    public void testCiphertextIsNonDeterministic() throws CryptoException {
        // Same payload and key must never produce the same blob (random IV), unlike legacy ECB.
        SecretKey key = Encryption.generateSecretKey(256);
        byte[] payload = new byte[1000];
        assertFalse(Arrays.equals(Encryption.encryptV2(payload, key), Encryption.encryptV2(payload, key)));
    }

    @Test
    public void testNoEcbBlockPatterns() throws CryptoException {
        // A payload of identical 16-byte blocks must not produce identical ciphertext blocks.
        SecretKey key = Encryption.generateSecretKey(256);
        byte[] payload = new byte[64];
        Arrays.fill(payload, (byte) 0x42);
        byte[] blob = Encryption.encryptV2(payload, key);
        byte[] block0 = Arrays.copyOfRange(blob, 20, 36);
        byte[] block1 = Arrays.copyOfRange(blob, 36, 52);
        assertFalse(Arrays.equals(block0, block1));
    }

    @Test
    public void testTamperDetection() throws CryptoException {
        SecretKey key = Encryption.generateSecretKey(256);
        byte[] payload = new byte[1000];
        new Random(42).nextBytes(payload);
        byte[] blob = Encryption.encryptV2(payload, key);
        // Flip one bit in the IV, ciphertext and tag regions.
        for (int pos : new int[]{5, blob.length / 2, blob.length - 1}) {
            byte[] tampered = blob.clone();
            tampered[pos] ^= 0x01;
            assertThrows(CryptoException.class, () -> Encryption.decryptV2(tampered, key), "tamper at " + pos + " not detected");
        }
        // Truncation.
        byte[] truncated = Arrays.copyOf(blob, blob.length - 1);
        assertThrows(CryptoException.class, () -> Encryption.decryptV2(truncated, key));
    }

    @Test
    public void testWrongKeyFails() throws CryptoException {
        byte[] blob = Encryption.encryptV2(new byte[100], Encryption.generateSecretKey(256));
        assertThrows(CryptoException.class, () -> Encryption.decryptV2(blob, Encryption.generateSecretKey(256)));
    }

    @Test
    public void testLegacyBlobsStillDecryptViaAutoDetect() throws CryptoException {
        SecretKey key = Encryption.generateSecretKey(256);
        byte[] payload = new byte[1000];
        new Random(7).nextBytes(payload);

        byte[] legacyRaw = Encryption.encrypt(payload, key);
        assertFalse(Encryption.isV2Format(legacyRaw));
        assertArrayEquals(payload, Encryption.decryptAuto(legacyRaw, key));

        byte[] legacyWithHmac = Encryption.encryptPayloadWithHmac(payload, key);
        assertFalse(Encryption.isV2Format(legacyWithHmac));
        assertArrayEquals(payload, Encryption.decryptPayloadWithHmacAuto(legacyWithHmac, key));
    }

    @Test
    public void testStreamWriteReadRoundTrip() throws CryptoException, IOException {
        SecretKey key = Encryption.generateSecretKey(256);
        Random random = new Random(4321);
        for (int size : SIZES) {
            byte[] payload = new byte[size];
            random.nextBytes(payload);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Encryption.encryptV2ToStream(out -> {
                for (int off = 0; off < payload.length; off += 4096) {
                    out.write(payload, off, Math.min(4096, payload.length - off));
                }
            }, key, bos);
            byte[] blob = bos.toByteArray();

            // Stream-written blobs are one-shot decryptable and vice versa.
            assertArrayEquals(payload, Encryption.decryptV2(blob, key), "stream blob one-shot decrypt failed for size " + size);
            long payloadLen = Encryption.verifyV2Stream(new ByteArrayInputStream(blob), key);
            assertEquals(size, payloadLen);
            try (InputStream in = Encryption.decryptV2Stream(new ByteArrayInputStream(blob), key)) {
                assertArrayEquals(payload, in.readAllBytes(), "streamed decrypt failed for size " + size);
            }

            byte[] oneShot = Encryption.encryptV2(payload, key);
            assertEquals(size, Encryption.verifyV2Stream(new ByteArrayInputStream(oneShot), key));
            try (InputStream in = Encryption.decryptV2Stream(new ByteArrayInputStream(oneShot), key)) {
                assertArrayEquals(payload, in.readAllBytes());
            }
        }
    }

    @Test
    public void testStreamTamperDetection() throws CryptoException {
        SecretKey key = Encryption.generateSecretKey(256);
        byte[] payload = new byte[100_000];
        new Random(9).nextBytes(payload);
        byte[] blob = Encryption.encryptV2(payload, key);
        byte[] tampered = blob.clone();
        tampered[tampered.length / 2] ^= 0x01;

        assertThrows(CryptoException.class, () -> Encryption.verifyV2Stream(new ByteArrayInputStream(tampered), key));
        // Pass-2 stream must also fail on its own before signaling EOF.
        assertThrows(IOException.class, () -> {
            try (InputStream in = Encryption.decryptV2Stream(new ByteArrayInputStream(tampered), key)) {
                in.readAllBytes();
            }
        });
    }

    @Test
    public void testStreamDoesNotCloseOutputStream() throws CryptoException {
        SecretKey key = Encryption.generateSecretKey(256);
        TrackingOutputStream out = new TrackingOutputStream();
        Encryption.encryptV2ToStream(o -> o.write(new byte[1000]), key, out);
        assertFalse(out.closed, "stream must not be closed by the helper");
    }

    private static class TrackingOutputStream extends ByteArrayOutputStream {
        boolean closed = false;

        @Override
        public void close() {
            closed = true;
        }
    }
}
