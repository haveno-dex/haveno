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

package bisq.common.crypto;

import bisq.common.config.Config;
import bisq.common.file.FileUtil;

import org.bitcoinj.crypto.KeyCrypterScrypt;

import com.google.inject.Inject;

import javax.inject.Named;
import javax.inject.Singleton;

import org.bouncycastle.crypto.params.KeyParameter;

import javax.crypto.SecretKey;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.DSAParams;
import java.security.interfaces.DSAPrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.DSAPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import java.math.BigInteger;

import java.util.Arrays;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;

import static bisq.common.util.Preconditions.checkDir;

@Singleton
public class KeyStorage {
    private static final Logger log = LoggerFactory.getLogger(KeyStorage.class);
    private static final int SALT_LENGTH = 20;
    private static final int SECRET_ITERATIONS = 65536;
    private static final int SECRET_LENGTH = 256;

    private static final byte[] ENCRYPTED_FORMAT_MAGIC = "HVNENC".getBytes(StandardCharsets.UTF_8);
    private static final int ENCRYPTED_FORMAT_VERSION = 1;
    private static final int ENCRYPTED_FORMAT_LENGTH = 4*4; // version,salt,iterations,length

    public enum KeyEntry {
        MSG_SIGNATURE("sig", Sig.KEY_ALGO),
        MSG_ENCRYPTION("enc", Encryption.ASYM_KEY_ALGO);

        private final String fileName;
        private final String algorithm;

        KeyEntry(String fileName, String algorithm) {
            this.fileName = fileName;
            this.algorithm = algorithm;
        }

        public String getFileName() {
            return fileName;
        }

        public String getAlgorithm() {
            return algorithm;
        }

        @NotNull
        @Override
        public String toString() {
            return "Key{" +
                    "fileName='" + fileName + '\'' +
                    ", algorithm='" + algorithm + '\'' +
                    '}';
        }
    }

    private final File storageDir;

    @Inject
    public KeyStorage(@Named(Config.KEY_STORAGE_DIR) File storageDir) {
        this.storageDir = checkDir(storageDir);
    }

    public boolean allKeyFilesExist() {
        return fileExists(KeyEntry.MSG_SIGNATURE) && fileExists(KeyEntry.MSG_ENCRYPTION);
    }

    private boolean fileExists(KeyEntry keyEntry) {
        return new File(storageDir + "/" + keyEntry.getFileName() + ".key").exists();
    }

    public KeyPair loadKeyPair(KeyEntry keyEntry, String password) throws IncorrectPasswordException {
        FileUtil.rollingBackup(storageDir, keyEntry.getFileName() + ".key", 20);
        // long now = System.currentTimeMillis();
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(keyEntry.getAlgorithm());
            PublicKey publicKey;
            PrivateKey privateKey;

            File filePrivateKey = new File(storageDir + "/" + keyEntry.getFileName() + ".key");
            try (FileInputStream fis = new FileInputStream(filePrivateKey.getPath())) {
                byte[] encodedPrivateKey = new byte[(int) filePrivateKey.length()];
                //noinspection ResultOfMethodCallIgnored
                fis.read(encodedPrivateKey);

                // Read magic bytes
                byte[] magicBytes = Arrays.copyOfRange(encodedPrivateKey, 0, ENCRYPTED_FORMAT_MAGIC.length);
                boolean isEncryptedPassword = Arrays.compare(magicBytes, ENCRYPTED_FORMAT_MAGIC) == 0;
                if (isEncryptedPassword && password == null) {
                    throw new IncorrectPasswordException("Cannot load encrypted keys, user must open account with password " + filePrivateKey);
                } else if (password != null && !isEncryptedPassword) {
                    log.warn("Password not needed for unencrypted key " + filePrivateKey);
                }

                // Decrypt using password
                if (password != null) {
                    int position = ENCRYPTED_FORMAT_MAGIC.length;

                    // Read remaining header
                    ByteBuffer buf = ByteBuffer.wrap(encodedPrivateKey, position, ENCRYPTED_FORMAT_LENGTH);
                    position += ENCRYPTED_FORMAT_LENGTH;
                    int version = buf.getInt();
                    if (version != 1) throw new RuntimeException("Unable to parse encrypted keys");
                    int saltLength = buf.getInt();
                    int iterations = buf.getInt();
                    int secretLength = buf.getInt();

                    byte[] salt = Arrays.copyOfRange(encodedPrivateKey, position, position + saltLength);
                    position += saltLength;
                    KeyCrypterScrypt crypter = ScryptUtil.getKeyCrypterScrypt(salt);
                    KeyParameter pwKey = ScryptUtil.deriveKeyWithScrypt(crypter, password);
                    byte[] pwEncrypted = Arrays.copyOfRange(encodedPrivateKey, position, position + pwKey.getKey().length);
                    if (Arrays.compare(pwEncrypted, pwKey.getKey()) != 0) {
                        throw new IncorrectPasswordException("Incorrect password");
                    }
                    position += pwEncrypted.length;

                    // Payload salt
                    salt = Arrays.copyOfRange(encodedPrivateKey, position, position + saltLength);
                    position += saltLength;

                    // Decrypt payload
                    SecretKey secretKey = Encryption.generateSecretKey(password, salt, iterations, secretLength);
                    byte[] encryptedPayload = Arrays.copyOfRange(encodedPrivateKey, position, encodedPrivateKey.length);
                    encodedPrivateKey = Encryption.decryptPayloadWithHmac(encryptedPayload, secretKey);
                }

                PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(encodedPrivateKey);
                privateKey = keyFactory.generatePrivate(privateKeySpec);
            } catch (InvalidKeySpecException | IOException | CryptoException e) {
                log.error("Could not load key " + keyEntry.toString(), e.getMessage());
                throw new RuntimeException("Could not load key " + keyEntry.toString(), e);
            }

            if (privateKey instanceof RSAPrivateCrtKey) {
                RSAPrivateCrtKey rsaPrivateKey = (RSAPrivateCrtKey) privateKey;
                RSAPublicKeySpec publicKeySpec = new RSAPublicKeySpec(rsaPrivateKey.getModulus(), rsaPrivateKey.getPublicExponent());
                publicKey = keyFactory.generatePublic(publicKeySpec);
            } else if (privateKey instanceof DSAPrivateKey) {
                DSAPrivateKey dsaPrivateKey = (DSAPrivateKey) privateKey;
                DSAParams dsaParams = dsaPrivateKey.getParams();
                BigInteger p = dsaParams.getP();
                BigInteger q = dsaParams.getQ();
                BigInteger g = dsaParams.getG();
                BigInteger y = g.modPow(dsaPrivateKey.getX(), p);
                KeySpec publicKeySpec = new DSAPublicKeySpec(y, p, q, g);
                publicKey = keyFactory.generatePublic(publicKeySpec);
            } else {
                throw new RuntimeException("Unsupported key algo" + keyEntry.getAlgorithm());
            }

            log.debug("load completed in {} msec", System.currentTimeMillis() - new Date().getTime());
            return new KeyPair(publicKey, privateKey);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            log.error("Could not load key " + keyEntry.toString(), e);
            throw new RuntimeException("Could not load key " + keyEntry.toString(), e);
        }
    }

    public void saveKeyRing(KeyRing keyRing, String password) {
        savePrivateKey(keyRing.getSignatureKeyPair().getPrivate(), KeyEntry.MSG_SIGNATURE.getFileName(), password);
        savePrivateKey(keyRing.getEncryptionKeyPair().getPrivate(), KeyEntry.MSG_ENCRYPTION.getFileName(), password);
    }

    private void savePrivateKey(PrivateKey privateKey, String name, String password) {
        if (!storageDir.exists())
            //noinspection ResultOfMethodCallIgnored
            storageDir.mkdirs();

        PKCS8EncodedKeySpec pkcs8EncodedKeySpec = new PKCS8EncodedKeySpec(privateKey.getEncoded());
        try (FileOutputStream fos = new FileOutputStream(storageDir + "/" + name + ".key")) {
            byte[] keyBytes = pkcs8EncodedKeySpec.getEncoded();
            // Encrypt
            if (password != null) {
                // Magic
                fos.write(ENCRYPTED_FORMAT_MAGIC);

                // Version, salt length, iterations, and secret length.
                ByteBuffer header = ByteBuffer.allocate(ENCRYPTED_FORMAT_LENGTH);
                header.putInt(ENCRYPTED_FORMAT_VERSION);
                header.putInt(SALT_LENGTH);
                header.putInt(SECRET_ITERATIONS);
                header.putInt(SECRET_LENGTH);
                fos.write(header.array());

                // Write pw salt and pw encrypted
                byte[] salt = CryptoUtils.getRandomBytes(SALT_LENGTH);
                fos.write(salt);
                KeyCrypterScrypt crypter = ScryptUtil.getKeyCrypterScrypt(salt);
                KeyParameter pwKey = ScryptUtil.deriveKeyWithScrypt(crypter, password);
                fos.write(pwKey.getKey());

                // Write new salt and generate SecretKey
                salt = CryptoUtils.getRandomBytes(SALT_LENGTH);
                fos.write(salt);
                SecretKey secretKey = Encryption.generateSecretKey(password, salt, SECRET_ITERATIONS, SECRET_LENGTH);

                // Encrypt payload
                keyBytes = Encryption.encryptPayloadWithHmac(keyBytes, secretKey);
            }
            fos.write(keyBytes);
        } catch (Exception e) {
            log.error("Could not save key " + name, e);
            throw new RuntimeException("Could not save key " + name, e);
        }
    }
}
