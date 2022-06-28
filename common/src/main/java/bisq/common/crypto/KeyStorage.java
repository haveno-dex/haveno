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
import com.google.inject.Inject;

import javax.inject.Named;
import javax.inject.Singleton;

import javax.crypto.SecretKey;

import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.UnrecoverableKeyException;
import java.security.interfaces.DSAParams;
import java.security.interfaces.DSAPrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.DSAPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import java.math.BigInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;

import static bisq.common.util.Preconditions.checkDir;

/**
 * KeyStorage uses password protection to save a symmetric key in PKCS#12 format.
 * The symmetric key is used to encrypt and decrypt other keys in the key ring and other types of persistence.
 */
@Singleton
public class KeyStorage {

    private static final Logger log = LoggerFactory.getLogger(KeyStorage.class);

    public enum KeyEntry {
        SYM_ENCRYPTION("sym.p12", Encryption.SYM_KEY_ALGO, "sym"), // symmetric encryption for persistence
        MSG_SIGNATURE("sig.key", Sig.KEY_ALGO, "sig"),
        MSG_ENCRYPTION("enc.key", Encryption.ASYM_KEY_ALGO, "enc");

        private final String fileName;
        private final String algorithm;
        private final String alias;

        KeyEntry(String fileName, String algorithm, String alias) {
            this.fileName = fileName;
            this.algorithm = algorithm;
            this.alias = alias;
        }

        public String getFileName() {
            return fileName;
        }

        public String getAlgorithm() {
            return algorithm;
        }

        public String getAlias() {
             return alias;
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
        return fileExists(KeyEntry.MSG_SIGNATURE) && fileExists(KeyEntry.MSG_ENCRYPTION) && fileExists(KeyEntry.SYM_ENCRYPTION);
    }

    private boolean fileExists(KeyEntry keyEntry) {
        return new File(storageDir + "/" + keyEntry.getFileName()).exists();
    }

    private byte[] loadKeyBytes(KeyEntry keyEntry, SecretKey secretKey) {
        File keyFile = new File(storageDir + "/" + keyEntry.getFileName());
        try (FileInputStream fis = new FileInputStream(keyFile.getPath())) {
            byte[] encodedKey = new byte[(int) keyFile.length()];
            //noinspection ResultOfMethodCallIgnored
            fis.read(encodedKey);
            encodedKey = Encryption.decryptPayloadWithHmac(encodedKey, secretKey);
            return encodedKey;
        } catch (IOException | CryptoException e) {
            log.error("Could not load key " + keyEntry.toString(), e.getMessage());
            throw new RuntimeException("Could not load key " + keyEntry.toString(), e);
        }
    }

    /**
     * Loads the public private KeyPair from a key file.
     *
     * @param keyEntry   The key entry that defines the public private key
     * @param secretKey  The symmetric key that protects the key entry file
     */
    public KeyPair loadKeyPair(KeyEntry keyEntry, SecretKey secretKey) {
        FileUtil.rollingBackup(storageDir, keyEntry.getFileName() + ".key", 20);
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(keyEntry.getAlgorithm());
            byte[] encodedPrivateKey = loadKeyBytes(keyEntry, secretKey);
            PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(encodedPrivateKey);
            PrivateKey privateKey = keyFactory.generatePrivate(privateKeySpec);
            PublicKey publicKey;
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
            return new KeyPair(publicKey, privateKey);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            log.error("Could not load key " + keyEntry.toString(), e);
            throw new RuntimeException("Could not load key " + keyEntry.toString(), e);
        }
    }

    /**
     * Loads the password protected symmetric secret key for this key ring.
     *
     * @param keyEntry The key entry that defines the symmetric key
     * @param password Optional password that protects the key
     */
    public SecretKey loadSecretKey(KeyEntry keyEntry, String password) throws IncorrectPasswordException {
        char[] passwordChars = password == null ? new char[0] : password.toCharArray();
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(new FileInputStream(storageDir + "/" + keyEntry.getFileName()), passwordChars);
            Key key = keyStore.getKey(keyEntry.getAlias(), passwordChars);
            return (SecretKey) key;
        } catch (UnrecoverableKeyException e) { // null password when password is required
            throw new IncorrectPasswordException("Incorrect password");
        } catch (IOException e) { // incorrect password
            if (e.getCause() instanceof UnrecoverableKeyException) {
                throw new IncorrectPasswordException("Incorrect password");
            } else {
                log.error("Could not load key " + keyEntry.toString(), e);
                throw new RuntimeException("Could not load key " + keyEntry.toString(), e);
            }
        } catch (Exception e) {
            log.error("Could not load key " + keyEntry.toString(), e);
            throw new RuntimeException("Could not load key " + keyEntry.toString(), e);
        }
    }

    /**
     * Saves the key ring to the key storage directory.
     *
     * @param keyRing  The key ring
     * @param password Optional password
     */
    public void saveKeyRing(KeyRing keyRing, String oldPassword, String password) {
        SecretKey symmetric = keyRing.getSymmetricKey();

        // password protect the symmetric key
        saveKey(symmetric, KeyEntry.SYM_ENCRYPTION.getAlias(), KeyEntry.SYM_ENCRYPTION.getFileName(), oldPassword, password);

        // use symmetric encryption to encrypt the key pairs
        saveKey(keyRing.getSignatureKeyPair().getPrivate(), KeyEntry.MSG_SIGNATURE.getFileName(), symmetric);
        saveKey(keyRing.getEncryptionKeyPair().getPrivate(), KeyEntry.MSG_ENCRYPTION.getFileName(), symmetric);
    }

    /**
     * Saves private key in PKCS#8 to a file and encrypts using the symmetric key.
     *
     * @param key       The key pair
     * @param fileName  File name to save
     * @param secretKey Secret key to encrypt the key pair
     */
    private void saveKey(PrivateKey key, String fileName, SecretKey secretKey) {
        if (!storageDir.exists())
            //noinspection ResultOfMethodCallIgnored
            storageDir.mkdirs();

        PKCS8EncodedKeySpec pkcs8EncodedKeySpec = new PKCS8EncodedKeySpec(key.getEncoded());
        byte[] keyBytes = pkcs8EncodedKeySpec.getEncoded();
        try (FileOutputStream fos = new FileOutputStream(storageDir + "/" + fileName)) {
            keyBytes = Encryption.encryptPayloadWithHmac(keyBytes, secretKey);
            fos.write(keyBytes);
        } catch (Exception e) {
            log.error("Could not save key " + fileName, e);
            throw new RuntimeException("Could not save key " + fileName, e);
        }
    }

    /**
     * Saves a SecretKey to a PKCS12 file.
     *
     * @param key         The symmetric key
     * @param alias       Alias of the key entry in the key store
     * @param fileName    Filename of the key store
     * @param oldPassword Optional password to decrypt existing key store
     * @param password    Optional password to encrypt the key store
     */
    private void saveKey(SecretKey key, String alias, String fileName, String oldPassword, String password) {
        if (!storageDir.exists())
            //noinspection ResultOfMethodCallIgnored
            storageDir.mkdirs();

        var oldPasswordChars = oldPassword == null ? new char[0] : oldPassword.toCharArray();
        var passwordChars = password == null ? new char[0] : password.toCharArray();
        try {
            var path = storageDir + "/" + fileName;
            KeyStore keyStore = KeyStore.getInstance("PKCS12");

            // load from existing file or initialize new
            try {
                keyStore.load(new FileInputStream(path), oldPasswordChars);
            } catch (Exception e) {
                keyStore.load(null, null);
            }

            // store in the keystore
            keyStore.setKeyEntry(alias, key, passwordChars, null);

            // save the keystore
            keyStore.store(new FileOutputStream(path), passwordChars);
        } catch (Exception e) {
            throw new RuntimeException("Could not save key " + alias, e);
        }
    }
}
