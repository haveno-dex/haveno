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
import java.io.OutputStream;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import javax.crypto.Cipher;
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
            byte[] hmac = getHmac(payload, secretKey);
            // Append the hmac with a single copy (HMAC-SHA256 is always 32 bytes) to avoid the
            // extra full-payload copy a ByteArrayOutputStream would make.
            byte[] payloadWithHmac = Arrays.copyOf(payload, payload.length + hmac.length);
            System.arraycopy(hmac, 0, payloadWithHmac, payload.length, hmac.length);
            return payloadWithHmac;
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

    private static byte[] getHmac(byte[] payload, SecretKey secretKey) throws NoSuchAlgorithmException, InvalidKeyException, NoSuchProviderException {
        Mac mac = Mac.getInstance(HMAC);
        mac.init(secretKey);
        return mac.doFinal(payload);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Symmetric with Hmac
    ///////////////////////////////////////////////////////////////////////////////////////////


    public static byte[] encryptPayloadWithHmac(byte[] payload, SecretKey secretKey) throws CryptoException {
        return encrypt(getPayloadWithHmac(payload, secretKey), secretKey);
    }

    /**
     * Streams {@code encrypt(payload || hmac(payload))} directly to {@code outputStream} without
     * ever holding the concatenated payload-with-hmac or the encrypted result fully in memory.
     * This produces byte-identical output to {@link #encryptPayloadWithHmac(byte[], SecretKey)} but
     * avoids the ~3x peak-memory amplification of the array based variant, which can throw
     * OutOfMemoryError for large persisted stores (e.g. ClosedTrades for arbitrators with many trades).
     * The provided {@code outputStream} is not closed by this method.
     */
    public static void encryptPayloadWithHmacToStream(byte[] payload, SecretKey secretKey, OutputStream outputStream) throws CryptoException {
        try {
            byte[] hmac = getHmac(payload, secretKey);
            Cipher cipher = Cipher.getInstance(SYM_CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);

            // Encrypt the payload in chunks so we never allocate a full second copy of it.
            int chunkSize = 64 * 1024;
            byte[] outBuf = new byte[cipher.getOutputSize(chunkSize)];
            int n;
            for (int off = 0; off < payload.length; off += chunkSize) {
                int len = Math.min(chunkSize, payload.length - off);
                n = cipher.update(payload, off, len, outBuf, 0);
                if (n > 0) outputStream.write(outBuf, 0, n);
            }
            // Append the hmac (also encrypted) and flush the final padded block.
            n = cipher.update(hmac, 0, hmac.length, outBuf, 0);
            if (n > 0) outputStream.write(outBuf, 0, n);
            n = cipher.doFinal(outBuf, 0);
            if (n > 0) outputStream.write(outBuf, 0, n);
        } catch (Throwable e) {
            log.error("error in encryptPayloadWithHmacToStream", e);
            throw new CryptoException(e);
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

