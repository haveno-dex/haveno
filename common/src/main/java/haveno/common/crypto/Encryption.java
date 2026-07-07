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

import haveno.common.util.Utilities;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

@Slf4j
public class Encryption {
    public static final String ASYM_KEY_ALGO = "RSA";
    private static final String ASYM_CIPHER = "RSA/ECB/OAEPWithSHA-256AndMGF1PADDING";

    public static final String SYM_KEY_ALGO = "AES";
    private static final String SYM_CIPHER = "AES";

    private static final String HMAC = "HmacSHA256";

    public static final String HMAC_ERROR_MSG = "Hmac does not match.";

    public static KeyPair generateKeyPair() {
        long ts = System.currentTimeMillis();
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(ASYM_KEY_ALGO);
            keyPairGenerator.initialize(2048);
            return keyPairGenerator.genKeyPair();
        } catch (Throwable e) {
            log.error("Could not create key.", e);
            throw new RuntimeException("Could not create key.");
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Symmetric (legacy v1: AES-ECB, no IV)
    ///////////////////////////////////////////////////////////////////////////////////////////

    // v1 is retained to read pre-v2 data and for network messages to peers without v2 support;
    // new at-rest data uses the v2 methods below.
    public static byte[] encrypt(byte[] payload, SecretKey secretKey) throws CryptoException {
        try {
            Cipher cipher = Cipher.getInstance(SYM_CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            return cipher.doFinal(payload);
        } catch (Throwable e) {
            log.error("error in encrypt", e);
            throw new CryptoException(e);
        }
    }

    public static byte[] decrypt(byte[] encryptedPayload, SecretKey secretKey) throws CryptoException {
        try {
            Cipher cipher = Cipher.getInstance(SYM_CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            return cipher.doFinal(encryptedPayload);
        } catch (Throwable e) {
            throw new CryptoException(e);
        }
    }

    public static SecretKey getSecretKeyFromBytes(byte[] secretKeyBytes) {
        return new SecretKeySpec(secretKeyBytes, 0, secretKeyBytes.length, SYM_KEY_ALGO);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Hmac
    ///////////////////////////////////////////////////////////////////////////////////////////

    private static byte[] getPayloadWithHmac(byte[] payload, SecretKey secretKey) {
        try {
            // Single-allocation concat (no ByteArrayOutputStream copy of the full payload).
            return Utilities.concatenateByteArrays(payload, getHmac(payload, secretKey));
        } catch (Throwable e) {
            log.error("Could not create hmac", e);
            throw new RuntimeException("Could not create hmac", e);
        }
    }

    private static boolean verifyHmac(byte[] message, byte[] hmac, SecretKey secretKey) {
        try {
            byte[] hmacTest = getHmac(message, secretKey);
            return MessageDigest.isEqual(hmacTest, hmac);
        } catch (Throwable e) {
            log.error("Could not create cipher", e);
            throw new RuntimeException("Could not create cipher");
        }
    }

    private static byte[] getHmac(byte[] payload, SecretKey secretKey) throws NoSuchAlgorithmException, InvalidKeyException {
        return createHmac(secretKey).doFinal(payload);
    }

    /** Returns an initialized {@code HmacSHA256} Mac, so streamed-payload verifiers match the writers in this class. */
    public static Mac createHmac(SecretKey secretKey) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(HMAC);
        mac.init(secretKey);
        return mac;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Symmetric with Hmac (legacy v1: AES-ECB, hmac keyed with the encryption key)
    ///////////////////////////////////////////////////////////////////////////////////////////


    public static byte[] encryptPayloadWithHmac(byte[] payload, SecretKey secretKey) throws CryptoException {
        return encrypt(getPayloadWithHmac(payload, secretKey), secretKey);
    }

    /**
     * A payload writable to a stream repeatedly with identical bytes (e.g. protobuf {@code writeTo}),
     * so hmac and encryption can run as separate passes without materializing a byte[].
     */
    public interface PayloadWriter {
        void writeTo(OutputStream outputStream) throws IOException;
    }

    /**
     * Streams {@code encrypt(payload || hmac(payload))} to {@code outputStream} with constant memory:
     * pass 1 hmacs, pass 2 encrypts, so no full copy of the payload or ciphertext is ever held.
     * Byte-identical to {@link #encryptPayloadWithHmac(byte[], SecretKey)} so persisted files stay
     * compatible, but cannot throw OutOfMemoryError. Does not close {@code outputStream}.
     */
    public static void encryptPayloadWithHmacToStream(PayloadWriter payloadWriter, SecretKey secretKey, OutputStream outputStream) throws CryptoException {
        try {
            Mac mac = createHmac(secretKey);
            payloadWriter.writeTo(new MacUpdatingOutputStream(mac));
            byte[] hmac = mac.doFinal();

            Cipher cipher = Cipher.getInstance(SYM_CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            // Closing the CipherOutputStream flushes the final padded block; the shield keeps the
            // caller's stream open.
            try (CipherOutputStream cipherOutputStream = new CipherOutputStream(new CloseShieldOutputStream(outputStream), cipher)) {
                payloadWriter.writeTo(cipherOutputStream);
                cipherOutputStream.write(hmac);
            }
        } catch (Throwable e) {
            log.error("error in encryptPayloadWithHmacToStream", e);
            throw new CryptoException(e);
        }
    }

    /**
     * Convenience overload for payloads already held in memory; writes in chunks so the cipher
     * never allocates a full second copy.
     */
    public static void encryptPayloadWithHmacToStream(byte[] payload, SecretKey secretKey, OutputStream outputStream) throws CryptoException {
        int chunkSize = 64 * 1024;
        encryptPayloadWithHmacToStream(out -> {
            for (int off = 0; off < payload.length; off += chunkSize) {
                out.write(payload, off, Math.min(chunkSize, payload.length - off));
            }
        }, secretKey, outputStream);
    }

    private static class MacUpdatingOutputStream extends OutputStream {
        private final Mac mac;

        private MacUpdatingOutputStream(Mac mac) {
            this.mac = mac;
        }

        @Override
        public void write(int b) {
            mac.update((byte) b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            mac.update(b, off, len);
        }
    }

    private static class CloseShieldOutputStream extends FilterOutputStream {
        private CloseShieldOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len); // FilterOutputStream would write byte-by-byte
        }

        @Override
        public void close() throws IOException {
            flush(); // keep the underlying stream open
        }
    }

    public static byte[] decryptPayloadWithHmac(byte[] encryptedPayloadWithHmac, SecretKey secretKey) throws CryptoException {
        byte[] payloadWithHmac = decrypt(encryptedPayloadWithHmac, secretKey);
        // HMAC-SHA256 is always 32 bytes; split directly to avoid hex-encoding memory amplification
        int payloadLen = payloadWithHmac.length - 32;
        byte[] payload = Arrays.copyOfRange(payloadWithHmac, 0, payloadLen);
        byte[] hmac = Arrays.copyOfRange(payloadWithHmac, payloadLen, payloadWithHmac.length);
        if (verifyHmac(payload, hmac, secretKey)) {
            return payload;
        } else {
            throw new CryptoException(HMAC_ERROR_MSG);
        }
    }

    /**
     * Pass 1 of a two-pass streaming read: decrypts in chunks, verifies the trailing hmac and returns
     * the payload length, never holding the full payload in memory. The caller re-reads the file with
     * {@link #decryptStream(InputStream, SecretKey)} limited to that length. Throws {@link CryptoException}
     * on an invalid stream (so callers can fall back to an unencrypted read); OutOfMemoryError is rethrown
     * so a heap-constrained read is never mistaken for a corrupt file.
     */
    public static long verifyPayloadWithHmacStream(InputStream encryptedInput, SecretKey secretKey) throws CryptoException {
        try {
            Cipher cipher = Cipher.getInstance(SYM_CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            Mac mac = createHmac(secretKey);

            // We feed every decrypted byte except the final 32 (the hmac) into the mac and count it as
            // payload; the final 32 bytes are kept in 'hold' and compared against the computed hmac at EOF.
            byte[] hold = new byte[32];
            int holdLen = 0;
            long payloadLen = 0;
            byte[] readBuf = new byte[64 * 1024];
            try (CipherInputStream cipherInputStream = new CipherInputStream(encryptedInput, cipher)) {
                int read;
                while ((read = cipherInputStream.read(readBuf)) != -1) {
                    int emit = holdLen + read - 32; // decrypted bytes that can no longer be part of the hmac
                    if (emit <= 0) {
                        System.arraycopy(readBuf, 0, hold, holdLen, read);
                        holdLen += read;
                        continue;
                    }
                    int fromHold = Math.min(emit, holdLen);
                    if (fromHold > 0) {
                        mac.update(hold, 0, fromHold);
                        payloadLen += fromHold;
                    }
                    int fromBuf = emit - fromHold;
                    if (fromBuf > 0) {
                        mac.update(readBuf, 0, fromBuf);
                        payloadLen += fromBuf;
                    }
                    // The new trailing 32 bytes = leftover of hold followed by the tail of readBuf.
                    int remHold = holdLen - fromHold;
                    if (remHold > 0) System.arraycopy(hold, fromHold, hold, 0, remHold);
                    int tail = read - fromBuf;
                    System.arraycopy(readBuf, fromBuf, hold, remHold, tail);
                    holdLen = remHold + tail; // == 32
                }
            }
            if (holdLen != 32) throw new CryptoException(HMAC_ERROR_MSG);
            if (!MessageDigest.isEqual(mac.doFinal(), hold)) {
                throw new CryptoException(HMAC_ERROR_MSG);
            }
            return payloadLen;
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (CryptoException e) {
            throw e;
        } catch (Throwable e) {
            throw new CryptoException(e);
        }
    }

    /**
     * Pass 2: returns a stream decrypting on the fly, yielding {@code payload || hmac}; callers limit
     * it to the length from {@link #verifyPayloadWithHmacStream(InputStream, SecretKey)}. Closing the
     * returned stream closes {@code encryptedInput}.
     */
    public static InputStream decryptStream(InputStream encryptedInput, SecretKey secretKey) throws CryptoException {
        try {
            Cipher cipher = Cipher.getInstance(SYM_CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            return new CipherInputStream(encryptedInput, cipher);
        } catch (OutOfMemoryError e) {
            throw e; // never mistake a heap-constrained read for a corrupt file
        } catch (Throwable e) {
            throw new CryptoException(e);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // V2 authenticated encryption (AES-256-CTR + HMAC-SHA256 encrypt-then-MAC)
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Blob layout: magic "HVN2" (4) || random IV (16) || AES-CTR ciphertext || HMAC-SHA256 tag (32).
    // The tag covers magic || IV || ciphertext. Enc and MAC keys are derived from the master key via
    // HKDF-SHA256, so the master key is never used directly for either primitive (unlike v1, which
    // used one key for AES-ECB and HMAC). CTR+HMAC is used instead of GCM so large payloads can be
    // streamed with constant memory in two passes (JCE GCM buffers the whole payload on decrypt).
    public static final byte[] V2_MAGIC = {'H', 'V', 'N', '2'};
    private static final String V2_CIPHER = "AES/CTR/NoPadding";
    private static final int V2_IV_LENGTH = 16;
    private static final int V2_TAG_LENGTH = 32;
    private static final int V2_MIN_LENGTH = V2_MAGIC.length + V2_IV_LENGTH + V2_TAG_LENGTH;
    private static final byte[] V2_HKDF_INFO_ENC = "haveno.crypto.v2.enc".getBytes(StandardCharsets.UTF_8);
    private static final byte[] V2_HKDF_INFO_MAC = "haveno.crypto.v2.mac".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    public static boolean isV2Format(byte[] blob) {
        if (blob == null || blob.length < V2_MIN_LENGTH) return false;
        for (int i = 0; i < V2_MAGIC.length; i++) {
            if (blob[i] != V2_MAGIC[i]) return false;
        }
        return true;
    }

    // Peeks the magic without consuming; the stream must support mark/reset.
    public static boolean isV2Format(InputStream markSupportedStream) throws IOException {
        markSupportedStream.mark(V2_MAGIC.length);
        byte[] head = markSupportedStream.readNBytes(V2_MAGIC.length);
        markSupportedStream.reset();
        return head.length == V2_MAGIC.length && Arrays.equals(head, V2_MAGIC);
    }

    // At-rest blobs are versioned by the last magic byte ("HVN" + version). To add a v3: add the
    // HVN3 cipher pair, detect it here and in the auto/dispatch paths, and bump this constant -
    // consumers re-encrypt anything older than the current version on first read (like v1 -> v2).
    public static final int CURRENT_BLOB_VERSION = 2;

    /** Returns the at-rest format version of the blob: 0 for legacy/unversioned (AES-ECB era), 2 for v2. */
    public static int blobVersion(byte[] blob) {
        return isV2Format(blob) ? 2 : 0;
    }

    /** Stream variant of {@link #blobVersion(byte[])}; peeks without consuming (stream must support mark/reset). */
    public static int blobVersion(InputStream markSupportedStream) throws IOException {
        return isV2Format(markSupportedStream) ? 2 : 0;
    }

    private static byte[] hkdf(byte[] masterKeyBytes, byte[] info) {
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(masterKeyBytes, null, info));
        byte[] out = new byte[32];
        hkdf.generateBytes(out, 0, 32);
        return out;
    }

    private static SecretKey deriveV2EncKey(SecretKey masterKey) {
        return new SecretKeySpec(hkdf(masterKey.getEncoded(), V2_HKDF_INFO_ENC), SYM_KEY_ALGO);
    }

    private static SecretKey deriveV2MacKey(SecretKey masterKey) {
        return new SecretKeySpec(hkdf(masterKey.getEncoded(), V2_HKDF_INFO_MAC), HMAC);
    }

    public static byte[] encryptV2(byte[] payload, SecretKey masterKey) throws CryptoException {
        try {
            byte[] iv = new byte[V2_IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(V2_CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, deriveV2EncKey(masterKey), new IvParameterSpec(iv));
            byte[] ciphertext = cipher.doFinal(payload);

            Mac mac = createHmac(deriveV2MacKey(masterKey));
            mac.update(V2_MAGIC);
            mac.update(iv);
            mac.update(ciphertext);
            byte[] tag = mac.doFinal();

            byte[] blob = new byte[V2_MAGIC.length + V2_IV_LENGTH + ciphertext.length + V2_TAG_LENGTH];
            System.arraycopy(V2_MAGIC, 0, blob, 0, V2_MAGIC.length);
            System.arraycopy(iv, 0, blob, V2_MAGIC.length, V2_IV_LENGTH);
            System.arraycopy(ciphertext, 0, blob, V2_MAGIC.length + V2_IV_LENGTH, ciphertext.length);
            System.arraycopy(tag, 0, blob, blob.length - V2_TAG_LENGTH, V2_TAG_LENGTH);
            return blob;
        } catch (Throwable e) {
            log.error("error in encryptV2", e);
            throw new CryptoException(e);
        }
    }

    public static byte[] decryptV2(byte[] blob, SecretKey masterKey) throws CryptoException {
        try {
            if (!isV2Format(blob)) throw new CryptoException("Not a v2 encrypted blob");
            int ciphertextLen = blob.length - V2_MIN_LENGTH;

            Mac mac = createHmac(deriveV2MacKey(masterKey));
            mac.update(blob, 0, blob.length - V2_TAG_LENGTH);
            byte[] tag = Arrays.copyOfRange(blob, blob.length - V2_TAG_LENGTH, blob.length);
            if (!MessageDigest.isEqual(mac.doFinal(), tag)) throw new CryptoException(HMAC_ERROR_MSG);

            byte[] iv = Arrays.copyOfRange(blob, V2_MAGIC.length, V2_MAGIC.length + V2_IV_LENGTH);
            Cipher cipher = Cipher.getInstance(V2_CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, deriveV2EncKey(masterKey), new IvParameterSpec(iv));
            return cipher.doFinal(blob, V2_MAGIC.length + V2_IV_LENGTH, ciphertextLen);
        } catch (CryptoException e) {
            throw e;
        } catch (OutOfMemoryError e) {
            throw e; // never mistake a heap-constrained decrypt for a corrupt blob
        } catch (Throwable e) {
            throw new CryptoException(e);
        }
    }

    // Decrypts v2 or legacy v1 (AES-ECB) blobs, detected by the magic prefix. A magic-colliding
    // legacy blob (p = 2^-32) falls back to v1 after failing the tag check. The v1 path carries
    // no MAC, so callers must authenticate the result themselves (all current callers do).
    public static byte[] decryptAuto(byte[] blob, SecretKey masterKey) throws CryptoException {
        if (isV2Format(blob)) {
            try {
                return decryptV2(blob, masterKey);
            } catch (CryptoException e) {
                // log before falling back so a real v2 tag failure is not misdiagnosed as a v1 error
                log.warn("v2 decrypt failed ({}); attempting legacy v1 decrypt", e.getMessage());
                return decrypt(blob, masterKey);
            }
        }
        return decrypt(blob, masterKey);
    }

    // Decrypts v2 or legacy v1 (AES-ECB with trailing HMAC) blobs, detected by the magic prefix,
    // with the same magic-collision fallback as decryptAuto (the v1 path verifies its own hmac).
    public static byte[] decryptPayloadWithHmacAuto(byte[] blob, SecretKey masterKey) throws CryptoException {
        if (isV2Format(blob)) {
            try {
                return decryptV2(blob, masterKey);
            } catch (CryptoException e) {
                // log before falling back so a real v2 tag failure is not misdiagnosed as a v1 error
                log.warn("v2 decrypt failed ({}); attempting legacy v1 decrypt", e.getMessage());
                return decryptPayloadWithHmac(blob, masterKey);
            }
        }
        return decryptPayloadWithHmac(blob, masterKey);
    }

    /**
     * Streams a v2 blob to {@code outputStream} with constant memory in a single payload pass
     * (ciphertext teed into the hmac). Does not close {@code outputStream}.
     */
    public static void encryptV2ToStream(PayloadWriter payloadWriter, SecretKey masterKey, OutputStream outputStream) throws CryptoException {
        try {
            byte[] iv = new byte[V2_IV_LENGTH];
            RANDOM.nextBytes(iv);
            Mac mac = createHmac(deriveV2MacKey(masterKey));
            mac.update(V2_MAGIC);
            mac.update(iv);
            outputStream.write(V2_MAGIC);
            outputStream.write(iv);

            Cipher cipher = Cipher.getInstance(V2_CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, deriveV2EncKey(masterKey), new IvParameterSpec(iv));
            try (CipherOutputStream cipherOutputStream = new CipherOutputStream(
                    new MacTeeOutputStream(new CloseShieldOutputStream(outputStream), mac), cipher)) {
                payloadWriter.writeTo(cipherOutputStream);
            }
            outputStream.write(mac.doFinal());
        } catch (Throwable e) {
            log.error("error in encryptV2ToStream", e);
            throw new CryptoException(e);
        }
    }

    /**
     * Pass 1 of a two-pass v2 read: verifies the tag over the raw stream (no decryption needed with
     * encrypt-then-MAC) and returns the payload length. Throws {@link CryptoException} if the stream
     * is not a valid v2 blob for this key.
     */
    public static long verifyV2Stream(InputStream encryptedInput, SecretKey masterKey) throws CryptoException {
        try {
            byte[] head = encryptedInput.readNBytes(V2_MAGIC.length);
            if (head.length != V2_MAGIC.length || !Arrays.equals(head, V2_MAGIC)) throw new CryptoException("Not a v2 encrypted stream");
            byte[] iv = encryptedInput.readNBytes(V2_IV_LENGTH);
            if (iv.length != V2_IV_LENGTH) throw new CryptoException("Truncated v2 stream");

            Mac mac = createHmac(deriveV2MacKey(masterKey));
            mac.update(head);
            mac.update(iv);

            // Feed all bytes except the trailing 32 (the tag) into the mac; CTR has no padding, so
            // ciphertext length == payload length.
            byte[] hold = new byte[V2_TAG_LENGTH];
            int holdLen = 0;
            long payloadLen = 0;
            byte[] readBuf = new byte[64 * 1024];
            int read;
            while ((read = encryptedInput.read(readBuf)) != -1) {
                int emit = holdLen + read - V2_TAG_LENGTH;
                if (emit <= 0) {
                    System.arraycopy(readBuf, 0, hold, holdLen, read);
                    holdLen += read;
                    continue;
                }
                int fromHold = Math.min(emit, holdLen);
                if (fromHold > 0) {
                    mac.update(hold, 0, fromHold);
                    payloadLen += fromHold;
                }
                int fromBuf = emit - fromHold;
                if (fromBuf > 0) {
                    mac.update(readBuf, 0, fromBuf);
                    payloadLen += fromBuf;
                }
                int remHold = holdLen - fromHold;
                if (remHold > 0) System.arraycopy(hold, fromHold, hold, 0, remHold);
                int tail = read - fromBuf;
                System.arraycopy(readBuf, fromBuf, hold, remHold, tail);
                holdLen = remHold + tail; // == 32
            }
            if (holdLen != V2_TAG_LENGTH) throw new CryptoException(HMAC_ERROR_MSG);
            if (!MessageDigest.isEqual(mac.doFinal(), hold)) throw new CryptoException(HMAC_ERROR_MSG);
            return payloadLen;
        } catch (OutOfMemoryError | CryptoException e) {
            throw e;
        } catch (Throwable e) {
            throw new CryptoException(e);
        }
    }

    /**
     * Pass 2 of a two-pass v2 read: returns a plaintext stream that re-verifies the tag over the
     * bytes actually read, throwing {@link IOException} on mismatch before signaling EOF, so fully
     * consumed data is always authenticated. Closing it closes {@code encryptedInput}.
     */
    public static InputStream decryptV2Stream(InputStream encryptedInput, SecretKey masterKey) throws CryptoException {
        try {
            byte[] head = encryptedInput.readNBytes(V2_MAGIC.length);
            if (head.length != V2_MAGIC.length || !Arrays.equals(head, V2_MAGIC)) throw new CryptoException("Not a v2 encrypted stream");
            byte[] iv = encryptedInput.readNBytes(V2_IV_LENGTH);
            if (iv.length != V2_IV_LENGTH) throw new CryptoException("Truncated v2 stream");

            Mac mac = createHmac(deriveV2MacKey(masterKey));
            mac.update(head);
            mac.update(iv);

            Cipher cipher = Cipher.getInstance(V2_CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, deriveV2EncKey(masterKey), new IvParameterSpec(iv));
            return new V2DecryptingInputStream(encryptedInput, cipher, mac);
        } catch (OutOfMemoryError | CryptoException e) {
            throw e; // never mistake a heap-constrained read for a corrupt file
        } catch (Throwable e) {
            throw new CryptoException(e);
        }
    }

    private static class MacTeeOutputStream extends FilterOutputStream {
        private final Mac mac;

        private MacTeeOutputStream(OutputStream out, Mac mac) {
            super(out);
            this.mac = mac;
        }

        @Override
        public void write(int b) throws IOException {
            mac.update((byte) b);
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            mac.update(b, off, len);
            out.write(b, off, len);
        }
    }

    // Decrypts ciphertext while holding back the trailing 32 bytes as the candidate tag; at EOF the
    // tag is compared against the mac over everything decrypted (header and IV are fed by the caller).
    private static class V2DecryptingInputStream extends InputStream {
        private final InputStream in;
        private final Cipher cipher;
        private final Mac mac;
        private final byte[] hold = new byte[V2_TAG_LENGTH];
        private int holdLen = 0;
        private final byte[] readBuf = new byte[64 * 1024];
        private byte[] outBuf = new byte[0];
        private int outPos = 0;
        private boolean finished = false;

        private V2DecryptingInputStream(InputStream in, Cipher cipher, Mac mac) {
            this.in = in;
            this.cipher = cipher;
            this.mac = mac;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n == -1 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (outPos >= outBuf.length) {
                if (finished) return -1;
                fill();
            }
            int n = Math.min(len, outBuf.length - outPos);
            System.arraycopy(outBuf, outPos, b, off, n);
            outPos += n;
            return n;
        }

        private void fill() throws IOException {
            int read = in.read(readBuf);
            if (read == -1) {
                if (holdLen != V2_TAG_LENGTH || !MessageDigest.isEqual(mac.doFinal(), Arrays.copyOf(hold, holdLen))) {
                    throw new IOException(HMAC_ERROR_MSG);
                }
                try {
                    byte[] last = cipher.doFinal();
                    outBuf = last != null ? last : new byte[0];
                } catch (Exception e) {
                    throw new IOException(e);
                }
                outPos = 0;
                finished = true;
                return;
            }
            int emit = holdLen + read - V2_TAG_LENGTH;
            if (emit <= 0) {
                System.arraycopy(readBuf, 0, hold, holdLen, read);
                holdLen += read;
                outBuf = new byte[0];
                outPos = 0;
                return;
            }
            byte[] ciphertext = new byte[emit];
            int fromHold = Math.min(emit, holdLen);
            if (fromHold > 0) System.arraycopy(hold, 0, ciphertext, 0, fromHold);
            int fromBuf = emit - fromHold;
            if (fromBuf > 0) System.arraycopy(readBuf, 0, ciphertext, fromHold, fromBuf);
            int remHold = holdLen - fromHold;
            if (remHold > 0) System.arraycopy(hold, fromHold, hold, 0, remHold);
            int tail = read - fromBuf;
            System.arraycopy(readBuf, fromBuf, hold, remHold, tail);
            holdLen = remHold + tail; // == 32
            mac.update(ciphertext);
            byte[] plaintext = cipher.update(ciphertext);
            outBuf = plaintext != null ? plaintext : new byte[0];
            outPos = 0;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Asymmetric
    ///////////////////////////////////////////////////////////////////////////////////////////

    public static byte[] encryptSecretKey(SecretKey secretKey, PublicKey publicKey) throws CryptoException {
        try {
            Cipher cipher = Cipher.getInstance(ASYM_CIPHER);
            OAEPParameterSpec oaepParameterSpec = new OAEPParameterSpec("SHA-256", "MGF1",
                    MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
            cipher.init(Cipher.WRAP_MODE, publicKey, oaepParameterSpec);
            return cipher.wrap(secretKey);
        } catch (Throwable e) {
            log.error("Couldn't encrypt payload", e);
            throw new CryptoException("Couldn't encrypt payload");
        }
    }

    public static SecretKey decryptSecretKey(byte[] encryptedSecretKey, PrivateKey privateKey) throws CryptoException {
        try {
            Cipher cipher = Cipher.getInstance(ASYM_CIPHER);
            OAEPParameterSpec oaepParameterSpec = new OAEPParameterSpec("SHA-256", "MGF1",
                    MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
            cipher.init(Cipher.UNWRAP_MODE, privateKey, oaepParameterSpec);
            return (SecretKey) cipher.unwrap(encryptedSecretKey, "AES", Cipher.SECRET_KEY);
        } catch (Throwable e) {
            // errors when trying to decrypt foreign network_messages are normal
            throw new CryptoException(e);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Hybrid with signature of asymmetric key
    ///////////////////////////////////////////////////////////////////////////////////////////

    public static SecretKey generateSecretKey(int bits) {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(SYM_KEY_ALGO);
            keyGenerator.init(bits);
            return keyGenerator.generateKey();
        } catch (Throwable e) {
            log.error("Couldn't generate key", e);
            throw new RuntimeException("Couldn't generate key");
        }
    }

    public static byte[] getPublicKeyBytes(PublicKey encryptionPubKey) {
        return new X509EncodedKeySpec(encryptionPubKey.getEncoded()).getEncoded();
    }

    /**
     * @param encryptionPubKeyBytes
     * @return
     */
    public static PublicKey getPublicKeyFromBytes(byte[] encryptionPubKeyBytes) {
        try {
            return KeyFactory.getInstance(Encryption.ASYM_KEY_ALGO).generatePublic(new X509EncodedKeySpec(encryptionPubKeyBytes));
        } catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
            log.error("Error creating sigPublicKey from bytes. sigPublicKeyBytes as hex={}, error={}", Utilities.bytesAsHexString(encryptionPubKeyBytes), e);
            throw new KeyConversionException(e);
        }
    }
}

