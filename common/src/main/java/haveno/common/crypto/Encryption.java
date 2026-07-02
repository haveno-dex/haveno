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
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
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
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;

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
    // Symmetric
    ///////////////////////////////////////////////////////////////////////////////////////////

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
            return Arrays.equals(hmacTest, hmac);
        } catch (Throwable e) {
            log.error("Could not create cipher", e);
            throw new RuntimeException("Could not create cipher");
        }
    }

    private static byte[] getHmac(byte[] payload, SecretKey secretKey) throws NoSuchAlgorithmException, InvalidKeyException {
        return createHmac(secretKey).doFinal(payload);
    }

    /**
     * Returns an initialized {@code HmacSHA256} Mac for the store hmac, so callers that verify
     * streamed payloads use the same algorithm as the writers in this class.
     */
    public static Mac createHmac(SecretKey secretKey) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(HMAC);
        mac.init(secretKey);
        return mac;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Symmetric with Hmac
    ///////////////////////////////////////////////////////////////////////////////////////////


    public static byte[] encryptPayloadWithHmac(byte[] payload, SecretKey secretKey) throws CryptoException {
        return encrypt(getPayloadWithHmac(payload, secretKey), secretKey);
    }

    /**
     * A payload that can be written to a stream more than once, producing identical bytes each time
     * (e.g. a protobuf {@code Message::writeTo}). Lets hmac computation and encryption run as two
     * write passes without ever materializing the payload as a single byte[].
     */
    public interface PayloadWriter {
        void writeTo(OutputStream outputStream) throws IOException;
    }

    /**
     * Streams {@code encrypt(payload || hmac(payload))} directly to {@code outputStream} with
     * constant memory: pass 1 feeds the payload through the hmac, pass 2 feeds it through the
     * cipher, so neither the payload, the payload-with-hmac nor the encrypted result is ever held
     * fully in memory. This produces byte-identical output to
     * {@link #encryptPayloadWithHmac(byte[], SecretKey)} so existing persisted files stay
     * compatible, but cannot throw OutOfMemoryError for large persisted stores (e.g. ClosedTrades
     * or DisputeLists for arbitrators with many trades).
     * The provided {@code outputStream} is not closed by this method.
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
     * Streaming counterpart of {@link #decryptPayloadWithHmac(byte[], SecretKey)} for large persisted
     * files. This is "pass 1" of a two-pass read: it decrypts {@code encryptedInput} in chunks, verifies
     * the trailing HMAC over the payload, and returns the payload length (decrypted length minus the
     * 32-byte HMAC) without ever holding the full decrypted payload in memory. The caller then re-reads
     * the file with {@link #decryptStream(InputStream, SecretKey)} limited to the returned length to parse
     * the (already integrity-checked) payload.
     *
     * <p>Throws {@link CryptoException} if the input is not a valid hmac-protected encrypted stream (bad
     * padding, fewer than 32 decrypted bytes, or hmac mismatch), so callers can fall back to reading an
     * unencrypted legacy file. {@link OutOfMemoryError} is intentionally not caught so a heap-constrained
     * read is never mistaken for a corrupt file.
     *
     * @return the payload length in bytes (always {@code >= 0})
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
            if (!Arrays.equals(mac.doFinal(), hold)) {
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
     * "Pass 2" of the streaming read: returns a stream that decrypts {@code encryptedInput} on the fly.
     * The returned stream yields {@code payload || hmac}; callers limit it to the payload length returned
     * by {@link #verifyPayloadWithHmacStream(InputStream, SecretKey)} so only the (already verified)
     * payload is consumed. Closing the returned stream closes {@code encryptedInput}.
     */
    public static InputStream decryptStream(InputStream encryptedInput, SecretKey secretKey) throws CryptoException {
        try {
            Cipher cipher = Cipher.getInstance(SYM_CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            return new CipherInputStream(encryptedInput, cipher);
        } catch (Throwable e) {
            throw new CryptoException(e);
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

