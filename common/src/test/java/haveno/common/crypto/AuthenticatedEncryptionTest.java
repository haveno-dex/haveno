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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class AuthenticatedEncryptionTest {
    private static final String CONTEXT = "test-store";
    private final SecretKey key = Encryption.generateSecretKey(256);

    @Test
    void arrayAndStreamReadersAgreeAtBlockAndBufferBoundaries() throws Exception {
        for (int size : new int[]{0, 1, 15, 16, 17, 65535, 65536, 65537, 200000}) {
            byte[] data = new byte[size];
            new Random(size).nextBytes(data);
            byte[] blob = AuthenticatedEncryption.encrypt(data, key, CONTEXT);
            assertArrayEquals(data, AuthenticatedEncryption.decrypt(blob, key, CONTEXT));
            try (InputStream in = AuthenticatedEncryption.decryptStream(new ByteArrayInputStream(blob), key, CONTEXT)) {
                assertArrayEquals(data, in.readAllBytes());
            }
            assertFalse(Arrays.equals(blob, AuthenticatedEncryption.encrypt(data, key, CONTEXT)));
        }
    }

    @Test
    void everyByteIsAuthenticatedAndAllTruncationsFail() throws Exception {
        byte[] blob = AuthenticatedEncryption.encrypt(new byte[32], key, CONTEXT);
        for (int i = 0; i < blob.length; i++) {
            byte[] changed = blob.clone();
            changed[i] ^= 1;
            assertThrows(CryptoException.class, () -> AuthenticatedEncryption.decrypt(changed, key, CONTEXT));
            assertInvalidStream(changed);
            byte[] truncated = Arrays.copyOf(blob, i);
            assertThrows(CryptoException.class, () -> AuthenticatedEncryption.decrypt(truncated, key, CONTEXT));
            assertInvalidStream(truncated);
        }
        byte[] appended = Arrays.copyOf(blob, blob.length + 1);
        assertThrows(CryptoException.class, () -> AuthenticatedEncryption.decrypt(appended, key, CONTEXT));
        assertInvalidStream(appended);
        assertThrows(CryptoException.class, () -> AuthenticatedEncryption.decrypt(blob, key, "other-store"));
        assertThrows(CryptoException.class, () -> AuthenticatedEncryption.decrypt(blob, Encryption.generateSecretKey(256), CONTEXT));
    }

    private void assertInvalidStream(byte[] blob) {
        assertThrows(IOException.class, () -> {
            try (InputStream in = AuthenticatedEncryption.decryptStream(new ByteArrayInputStream(blob), key, CONTEXT)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
        });
    }

    @Test
    void interoperatesWithIndependentJceHkdfAndGcmImplementation() throws Exception {
        byte[] data = "authenticated payment data".getBytes(StandardCharsets.UTF_8);
        byte[] blob = AuthenticatedEncryption.encrypt(data, key, CONTEXT);
        // Frozen wire layout and an independent RFC 5869 extract/expand, without production helpers.
        assertArrayEquals(new byte[]{'H', 'V', 'N', 0, (byte) 0x80, 'E', 'N', 'C'}, Arrays.copyOf(blob, 8));
        assertEquals(2, blob[8]);
        byte[] header = Arrays.copyOf(blob, 53);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Arrays.copyOfRange(blob, 9, 41), "HmacSHA256"));
        byte[] prk = mac.doFinal(key.getEncoded());
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update("Haveno encryption v2 AES-256-GCM".getBytes(StandardCharsets.US_ASCII));
        byte[] purpose = CONTEXT.getBytes(StandardCharsets.UTF_8);
        mac.update(ByteBuffer.allocate(4).putInt(purpose.length).array());
        mac.update(purpose);
        byte[] derived = mac.doFinal(new byte[]{1});
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derived, "AES"),
                new GCMParameterSpec(128, Arrays.copyOfRange(blob, 41, 53)));
        byte[] context = CONTEXT.getBytes(StandardCharsets.UTF_8);
        cipher.updateAAD(ByteBuffer.allocate(53 + 4 + context.length).put(header).putInt(context.length).put(context).array());
        assertArrayEquals(data, cipher.doFinal(blob, 53, blob.length - 53));
    }

    @Test
    void decryptsFrozenJceKnownAnswer() throws Exception {
        // Generated independently with SunJCE AES-GCM and RFC 5869 HMAC extract/expand.
        byte[] master = new byte[32];
        for (int i = 0; i < master.length; i++) master[i] = (byte) i;
        byte[] blob = java.util.HexFormat.of().parseHex(
                "48564e0080454e4302202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
                + "000102030405060708090a0b56ccee9f07568903a635abeb759cd957fbae1b3a1fa7c9d2ca366346a1523c1f764f506147fb6e5d69cf40");
        assertArrayEquals("Haveno v2 known-answer test".getBytes(StandardCharsets.US_ASCII),
                AuthenticatedEncryption.decrypt(blob, Encryption.getSecretKeyFromBytes(master), CONTEXT));
    }

    @Test
    void unknownVersionStillIdentifiesAsEnvelopeAndDoesNotConsumePrefix() throws Exception {
        byte[] blob = AuthenticatedEncryption.encrypt(new byte[0], key, CONTEXT);
        blob[8] = 99;
        assertTrue(AuthenticatedEncryption.isEnvelope(blob));
        ByteArrayInputStream in = new ByteArrayInputStream(blob);
        assertTrue(AuthenticatedEncryption.isEnvelope(in));
        assertEquals('H', in.read());
        assertThrows(CryptoException.class, () -> AuthenticatedEncryption.decrypt(blob, key, CONTEXT));
    }

    @Test
    void writerRunsOnceAndLeavesOutputOpenAndReportsFailure() throws Exception {
        int[] calls = {0};
        ByteArrayOutputStream out = new ByteArrayOutputStream() {
            @Override
            public void close() {
                fail("Caller owns output");
            }
        };
        AuthenticatedEncryption.encryptToStream(stream -> {
            calls[0]++;
            stream.write(7);
        }, key, CONTEXT, out);
        assertEquals(1, calls[0]);
        assertArrayEquals(new byte[]{7}, AuthenticatedEncryption.decrypt(out.toByteArray(), key, CONTEXT));
        assertThrows(CryptoException.class, () -> AuthenticatedEncryption.encryptToStream(stream -> {
            stream.write(7);
            throw new IOException("Disk full");
        }, key, CONTEXT, out));
    }
    @Test
    void streamsNinetySixMiBWithBoundedMemory(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path file = dir.resolve("large.enc");
        byte[] chunk = new byte[64 * 1024];
        Arrays.fill(chunk, (byte) 0x6f);
        try (OutputStream out = java.nio.file.Files.newOutputStream(file)) {
            AuthenticatedEncryption.encryptToStream(stream -> {
                for (int i = 0; i < 1536; i++) stream.write(chunk);
            }, key, CONTEXT, out);
        }
        try (InputStream in = new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(file));
             InputStream decrypted = AuthenticatedEncryption.decryptStream(in, key, CONTEXT)) {
            assertEquals(96L * 1024 * 1024, decrypted.transferTo(OutputStream.nullOutputStream()));
        }
    }

}
