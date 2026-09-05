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

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

/**
 * Versioned encrypt-then-MAC: header || AES-256-CTR ciphertext || HMAC-SHA256.
 * The header contains an eight-byte format identifier, a 32-byte random HKDF salt and a
 * 16-byte random IV. HKDF separates encryption/MAC keys and application contexts. The MAC
 * covers the entire header and ciphertext. No decryption failure ever selects another format.
 *
 * Large files are authenticated before parsing, then authenticated again over the same open
 * file descriptor while parsing. A parsed object is returned only after the second MAC and
 * exact EOF check succeed. This avoids both unbounded JCE GCM buffering and plaintext temps.
 */
public final class AuthenticatedEncryption {
    private static final byte[] MAGIC = {'H', 'V', 'N', 'E', (byte) 0xff, 0, 0, 2};
    static final int HEADER_LENGTH = 56;
    static final int TAG_LENGTH = 32;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AuthenticatedEncryption() {}

    /** Includes unknown versions of this format; these must fail closed, never fall back. */
    public static boolean hasEnvelope(byte[] bytes) {
        return bytes.length >= 7 && Arrays.equals(bytes, 0, 7, MAGIC, 0, 7);
    }

    public static boolean hasEnvelope(Path path) throws IOException {
        try (InputStream in = java.nio.file.Files.newInputStream(path)) {
            return hasEnvelope(in.readNBytes(MAGIC.length));
        }
    }

    public static byte[] encrypt(byte[] plaintext, SecretKey key, String context) throws CryptoException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encryptToStream(stream -> stream.write(plaintext), key, context, out);
        return out.toByteArray();
    }

    /** Writes once, in bounded chunks, without closing the caller's stream. */
    public static void encryptToStream(Encryption.PayloadWriter writer, SecretKey key, String context,
                                       OutputStream out) throws CryptoException {
        byte[] header = new byte[HEADER_LENGTH];
        RANDOM.nextBytes(header);
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        try {
            Cipher cipher = cipher(key, context, header, Cipher.ENCRYPT_MODE);
            Mac mac = mac(key, context, header);
            out.write(header);
            writer.writeTo(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    write(new byte[]{(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    java.util.Objects.checkFromIndexSize(off, len, b.length);
                    while (len > 0) {
                        int count = Math.min(len, BUFFER_SIZE);
                        byte[] encrypted = cipher.update(b, off, count);
                        if (encrypted != null) {
                            mac.update(encrypted);
                            out.write(encrypted);
                        }
                        off += count;
                        len -= count;
                    }
                }
            });
            // Explicit doFinal: CipherOutputStream.close can hide cryptographic failures.
            byte[] last = cipher.doFinal();
            mac.update(last);
            out.write(last);
            out.write(mac.doFinal());
        } catch (GeneralSecurityException | IOException e) {
            throw new CryptoException("Could not encrypt authenticated envelope", e);
        }
    }

    public static byte[] decrypt(byte[] envelope, SecretKey key, String context) throws CryptoException {
        try {
            verify(new ByteArrayInputStream(envelope), envelope.length, key, context);
            byte[] header = Arrays.copyOf(envelope, HEADER_LENGTH);
            return cipher(key, context, header, Cipher.DECRYPT_MODE)
                    .doFinal(envelope, HEADER_LENGTH, envelope.length - HEADER_LENGTH - TAG_LENGTH);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            throw new CryptoException("Invalid authenticated envelope", e);
        }
    }

    @FunctionalInterface
    public interface Reader<T> {
        T read(InputStream plaintext) throws IOException;
    }

    /** The callback must not publish data or perform side effects; its result is untrusted until return. */
    public static <T> T readFile(Path path, SecretKey key, String context, Reader<T> reader)
            throws IOException, CryptoException {
        try (FileInputStream file = new FileInputStream(path.toFile())) {
            long length = file.getChannel().size();
            verify(new BufferedInputStream(file, BUFFER_SIZE), length, key, context);
            file.getChannel().position(0);
            InputStream in = new BufferedInputStream(file, BUFFER_SIZE);
            byte[] header = readHeader(in, length);
            Mac mac = mac(key, context, header);
            InputStream ciphertext = new AuthenticatedInput(in, length - HEADER_LENGTH - TAG_LENGTH, mac);
            InputStream plaintext = new CipherInputStream(ciphertext, cipher(key, context, header, Cipher.DECRYPT_MODE));
            T value = reader.read(plaintext);
            // Exhaust even when a parser stops early. No parsed object escapes before authentication.
            plaintext.transferTo(OutputStream.nullOutputStream());
            verifyTag(in, mac);
            return value;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new CryptoException("Invalid authenticated envelope", e);
        }
    }

    public static void verifyFile(Path path, SecretKey key, String context) throws IOException, CryptoException {
        try (FileInputStream file = new FileInputStream(path.toFile())) {
            verify(new BufferedInputStream(file, BUFFER_SIZE), file.getChannel().size(), key, context);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new CryptoException("Invalid authenticated envelope", e);
        }
    }

    private static void verify(InputStream in, long length, SecretKey key, String context)
            throws IOException, GeneralSecurityException {
        byte[] header = readHeader(in, length);
        Mac mac = mac(key, context, header);
        new AuthenticatedInput(in, length - HEADER_LENGTH - TAG_LENGTH, mac)
                .transferTo(OutputStream.nullOutputStream());
        verifyTag(in, mac);
    }

    private static byte[] readHeader(InputStream in, long length) throws IOException {
        if (length < HEADER_LENGTH + TAG_LENGTH) throw new IOException("Truncated authenticated envelope");
        byte[] header = in.readNBytes(HEADER_LENGTH);
        if (header.length != HEADER_LENGTH || !Arrays.equals(MAGIC, Arrays.copyOf(header, MAGIC.length))) {
            throw new IOException("Unsupported authenticated envelope");
        }
        return header;
    }

    private static void verifyTag(InputStream in, Mac mac) throws IOException {
        byte[] tag = in.readNBytes(TAG_LENGTH);
        if (tag.length != TAG_LENGTH || !MessageDigest.isEqual(mac.doFinal(), tag) || in.read() != -1) {
            throw new IOException("Authenticated envelope verification failed");
        }
    }

    private static Cipher cipher(SecretKey key, String context, byte[] header, int mode) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        byte[] derived = derive(key, header, "encryption", context);
        try {
            cipher.init(mode, new SecretKeySpec(derived, "AES"), new IvParameterSpec(header, 40, 16));
        } finally {
            Arrays.fill(derived, (byte) 0);
        }
        return cipher;
    }

    private static Mac mac(SecretKey key, String context, byte[] header) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        byte[] derived = derive(key, header, "authentication", context);
        try {
            mac.init(new SecretKeySpec(derived, "HmacSHA256"));
        } finally {
            Arrays.fill(derived, (byte) 0);
        }
        mac.update(header);
        return mac;
    }

    private static byte[] derive(SecretKey key, byte[] header, String purpose, String context) {
        if (context == null || context.isEmpty() || context.length() > 1024) {
            throw new IllegalArgumentException("An explicit encryption context is required");
        }
        byte[] encoded = key.getEncoded();
        try {
            if (encoded == null || encoded.length != 32) throw new IllegalArgumentException("A 256-bit key is required");
            HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
            hkdf.init(new HKDFParameters(encoded, Arrays.copyOfRange(header, 8, 40),
                    ("haveno/envelope/2/" + purpose + "/" + context).getBytes(StandardCharsets.UTF_8)));
            byte[] derived = new byte[32];
            hkdf.generateBytes(derived, 0, derived.length);
            return derived;
        } finally {
            if (encoded != null) Arrays.fill(encoded, (byte) 0);
        }
    }

    /** Limits reads to the exact ciphertext length and authenticates every byte actually read. */
    private static final class AuthenticatedInput extends InputStream {
        private final InputStream in;
        private final Mac mac;
        private long remaining;

        private AuthenticatedInput(InputStream in, long remaining, Mac mac) {
            this.in = in;
            this.remaining = remaining;
            this.mac = mac;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) == -1 ? -1 : b[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            java.util.Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) return 0;
            if (remaining == 0) return -1;
            int count = in.read(b, off, (int) Math.min(remaining, len));
            if (count < 0) throw new IOException("Truncated authenticated envelope");
            mac.update(b, off, count);
            remaining -= count;
            return count;
        }
    }
}
