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
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKey;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.io.CipherInputStream;
import org.bouncycastle.crypto.io.CipherOutputStream;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;

/** Versioned AES-256-GCM envelopes. Legacy formats remain in {@link Encryption}. */
public final class AuthenticatedEncryption {
    // The format family is separate from its version, so unknown versions never select legacy decryption.
    private static final byte[] MAGIC = {'H', 'V', 'N', 0, (byte) 0x80, 'E', 'N', 'C'};
    private static final int VERSION = 2;
    private static final int SALT_LENGTH = 32;
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH = 16;
    private static final int HEADER_LENGTH = MAGIC.length + 1 + SALT_LENGTH + NONCE_LENGTH;
    private static final byte[] KEY_INFO = "Haveno encryption v2 AES-256-GCM".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private AuthenticatedEncryption() {
    }

    public static boolean isEnvelope(byte[] bytes) {
        if (bytes.length < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) {
            if (bytes[i] != MAGIC[i]) return false;
        }
        return true;
    }

    /** Detects the format family without consuming a buffered stream. */
    public static boolean isEnvelope(InputStream in) throws IOException {
        if (!in.markSupported()) throw new IllegalArgumentException("Buffered input required");
        in.mark(MAGIC.length);
        byte[] prefix = in.readNBytes(MAGIC.length);
        in.reset();
        return isEnvelope(prefix);
    }

    public static byte[] encrypt(byte[] payload, SecretKey key, String context) throws CryptoException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encryptToStream(stream -> {
            for (int offset = 0; offset < payload.length; offset += 64 * 1024) {
                stream.write(payload, offset, Math.min(64 * 1024, payload.length - offset));
            }
        }, key, context, out);
        return out.toByteArray();
    }

    /** Authenticates the entire envelope before returning any plaintext. */
    public static byte[] decrypt(byte[] envelope, SecretKey key, String context) throws CryptoException {
        if (envelope.length < HEADER_LENGTH + TAG_LENGTH) throw new CryptoException("Truncated encrypted envelope");
        try (InputStream in = decryptStream(new ByteArrayInputStream(envelope), key, context)) {
            return in.readAllBytes();
        } catch (IOException | IllegalArgumentException e) {
            throw new CryptoException("Invalid encrypted envelope", e);
        }
    }

    /** One-pass, bounded-memory encryption; does not close the caller's output. */
    public static void encryptToStream(Encryption.PayloadWriter writer, SecretKey key, String context,
                                       OutputStream out) throws CryptoException {
        byte[] header = new byte[HEADER_LENGTH];
        RANDOM.nextBytes(header);
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        header[MAGIC.length] = VERSION;
        try {
            GCMBlockCipher cipher = createCipher(true, key, header, context);
            out.write(header);
            try (CipherOutputStream encrypted = new CipherOutputStream(new FilterOutputStream(out) {
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    out.write(b, off, len);
                }

                @Override
                public void close() throws IOException {
                    flush();
                }
            }, cipher)) {
                writer.writeTo(encrypted);
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new CryptoException("Could not encrypt envelope", e);
        }
    }

    /**
     * Streaming plaintext is provisional until EOF verifies the tag. Only persistence readers may
     * use this: verify a first pass, then parse and drain a second pass before publishing any result.
     * The BC stream propagates authentication failures; JCE GCM streams can buffer entire stores.
     */
    public static InputStream decryptStream(InputStream in, SecretKey key, String context) throws IOException {
        byte[] header = readHeader(in);
        try {
            return new CipherInputStream(in, createCipher(false, key, header, context));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid encrypted envelope", e);
        }
    }

    private static GCMBlockCipher createCipher(boolean encrypt, SecretKey key, byte[] header, String context) {
        byte[] derivedKey = deriveKey(key, header, context);
        try {
            GCMBlockCipher cipher = new GCMBlockCipher(AESEngine.newInstance());
            cipher.init(encrypt, new AEADParameters(new KeyParameter(derivedKey), TAG_LENGTH * 8,
                    nonce(header), associatedData(header, context)));
            return cipher;
        } finally {
            Arrays.fill(derivedKey, (byte) 0);
        }
    }

    private static byte[] readHeader(InputStream in) throws IOException {
        byte[] header = in.readNBytes(HEADER_LENGTH);
        if (header.length != HEADER_LENGTH || !isEnvelope(header)) throw new IOException("Invalid encrypted header");
        if (header[MAGIC.length] != VERSION) throw new IOException("Unsupported encrypted envelope version");
        return header;
    }

    private static byte[] deriveKey(SecretKey key, byte[] header, String context) {
        byte[] master = key.getEncoded();
        try {
            if (master == null || (master.length != 16 && master.length != 24 && master.length != 32)) {
                throw new IllegalArgumentException("Invalid AES master key");
            }
            byte[] salt = Arrays.copyOfRange(header, MAGIC.length + 1, MAGIC.length + 1 + SALT_LENGTH);
            HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
            hkdf.init(new HKDFParameters(master, salt, associatedData(KEY_INFO, context)));
            byte[] derived = new byte[32];
            hkdf.generateBytes(derived, 0, derived.length);
            return derived;
        } finally {
            if (master != null) Arrays.fill(master, (byte) 0);
        }
    }

    private static byte[] nonce(byte[] header) {
        return Arrays.copyOfRange(header, HEADER_LENGTH - NONCE_LENGTH, HEADER_LENGTH);
    }

    private static byte[] associatedData(byte[] header, String context) {
        byte[] purpose = context.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(header.length + 4 + purpose.length)
                .put(header).putInt(purpose.length).put(purpose).array();
    }
}
