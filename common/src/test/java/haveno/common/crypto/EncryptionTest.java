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

import java.io.ByteArrayOutputStream;
import java.util.Random;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class EncryptionTest {

    // Sizes around AES block (16) and stream chunk (64 KiB) boundaries, plus an empty payload.
    private static final int[] SIZES = {0, 1, 15, 16, 17, 1000, 65_535, 65_536, 65_537, 100_000, 5_000_000};

    @Test
    public void testStreamWriteMatchesArrayAndRoundTrips() throws CryptoException {
        SecretKey key = Encryption.generateSecretKey(256);
        Random random = new Random(1234);
        for (int size : SIZES) {
            byte[] payload = new byte[size];
            random.nextBytes(payload);

            byte[] viaArray = Encryption.encryptPayloadWithHmac(payload, key);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Encryption.encryptPayloadWithHmacToStream(payload, key, bos);
            byte[] viaStream = bos.toByteArray();

            // The streaming variant must be byte-identical so existing persisted files stay readable.
            assertArrayEquals(viaArray, viaStream, "ciphertext differs for payload size " + size);

            // And it must decrypt back to the original payload with the existing array decrypt path.
            byte[] decrypted = Encryption.decryptPayloadWithHmac(viaStream, key);
            assertArrayEquals(payload, decrypted, "round-trip failed for payload size " + size);
        }
    }

    @Test
    public void testStreamDoesNotCloseOutputStream() throws CryptoException {
        SecretKey key = Encryption.generateSecretKey(256);
        TrackingOutputStream out = new TrackingOutputStream();
        Encryption.encryptPayloadWithHmacToStream(new byte[1000], key, out);
        assertEquals(false, out.closed, "stream must not be closed by the helper");
    }

    private static class TrackingOutputStream extends ByteArrayOutputStream {
        boolean closed = false;

        @Override
        public void close() {
            closed = true;
        }
    }
}
