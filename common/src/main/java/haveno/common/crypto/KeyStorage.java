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

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import haveno.common.config.Config;
import haveno.common.file.FileUtil;
import static haveno.common.util.Preconditions.checkDir;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
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
import java.util.Arrays;
import javax.crypto.SecretKey;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * KeyStorage saves the symmetric key (sym.key) wrapped with an Argon2id-derived key from the account
 * password, replacing the legacy PKCS#12 store whose fast KDF allowed cheap brute force. Legacy
 * files are read transparently and upgraded on the next save.
 */
@Singleton
public class KeyStorage {

    private static final Logger log = LoggerFactory.getLogger(KeyStorage.class);

    // sym.key layout: magic || format version || kdf id || mem KiB || iterations || parallelism || salt || v2 blob of the key.
    private static final byte[] SYM_FILE_MAGIC = {'H', 'V', 'N', 'K'};
    private static final byte SYM_FILE_VERSION = 1;
    private static final String LEGACY_SYM_FILE_NAME = "sym.p12";
    // Bounds against absurd KDF cost from a corrupted or malicious header.
    private static final int MAX_MEM_KIB = 4 * 1024 * 1024;
    private static final int MAX_ITERATIONS = 64;
    private static final int MAX_PARALLELISM = 16;

    public enum KeyEntry {
        SYM_ENCRYPTION("sym.key", Encryption.SYM_KEY_ALGO, "sym"), // symmetric encryption for persistence
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
    // Set when any key file was read in a pre-v2 format, so callers can trigger a re-save.
    private boolean legacyFormatLoaded = false;

    @Inject
    public KeyStorage(@Named(Config.KEY_STORAGE_DIR) File storageDir) {
        this.storageDir = checkDir(storageDir);
    }

    public boolean allKeyFilesExist() {
        return fileExists(KeyEntry.MSG_SIGNATURE) && fileExists(KeyEntry.MSG_ENCRYPTION)
                && (fileExists(KeyEntry.SYM_ENCRYPTION) || legacySymFile().exists());
    }

    public boolean needsFormatUpgrade() {
        return legacyFormatLoaded;
    }

    private boolean fileExists(KeyEntry keyEntry) {
        return new File(storageDir + "/" + keyEntry.getFileName()).exists();
    }

    private File legacySymFile() {
        return new File(storageDir + "/" + LEGACY_SYM_FILE_NAME);
    }

    private byte[] loadKeyBytes(KeyEntry keyEntry, SecretKey secretKey) {
        File keyFile = new File(storageDir + "/" + keyEntry.getFileName());
        try (FileInputStream fis = new FileInputStream(keyFile.getPath())) {
            byte[] encodedKey = new byte[(int) keyFile.length()];
            //noinspection ResultOfMethodCallIgnored
            fis.read(encodedKey);
            if (Encryption.blobVersion(encodedKey) < Encryption.CURRENT_BLOB_VERSION) legacyFormatLoaded = true;
            encodedKey = Encryption.decryptPayloadWithHmacAuto(encodedKey, secretKey);
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
        FileUtil.rollingBackup(storageDir, keyEntry.getFileName(), 20);
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
        File keyFile = new File(storageDir + "/" + keyEntry.getFileName());
        if (keyFile.exists()) {
            SecretKey key = loadSecretKeyV2(keyFile, password);
            // backup only after a successful load, so retries against a corrupt file cannot rotate out good backups
            FileUtil.rollingBackup(storageDir, keyEntry.getFileName(), 20);
            return key;
        }
        legacyFormatLoaded = true;
        SecretKey key = loadSecretKeyLegacy(keyEntry, password);
        FileUtil.rollingBackup(storageDir, LEGACY_SYM_FILE_NAME, 20);
        return key;
    }

    private SecretKey loadSecretKeyV2(File keyFile, String password) throws IncorrectPasswordException {
        try {
            ByteBuffer buf = ByteBuffer.wrap(Files.readAllBytes(keyFile.toPath()));
            byte[] magic = new byte[SYM_FILE_MAGIC.length];
            buf.get(magic);
            if (!Arrays.equals(magic, SYM_FILE_MAGIC)) throw new IOException("Invalid key file magic");
            byte version = buf.get();
            if (version != SYM_FILE_VERSION) throw new IOException("Unsupported key file version " + version);
            byte kdf = buf.get();
            if (kdf != PasswordKdf.KDF_ARGON2ID) throw new IOException("Unsupported kdf " + kdf);
            int memKib = buf.getInt();
            int iterations = buf.getInt();
            int parallelism = buf.getInt();
            if (memKib < 1 || memKib > MAX_MEM_KIB || iterations < 1 || iterations > MAX_ITERATIONS
                    || parallelism < 1 || parallelism > MAX_PARALLELISM) {
                throw new IOException("KDF parameters out of bounds");
            }
            byte[] salt = new byte[PasswordKdf.SALT_LENGTH];
            buf.get(salt);
            byte[] blob = new byte[buf.remaining()];
            buf.get(blob);
            // a mangled wrapped blob is corruption, not a wrong password
            if (!Encryption.isV2Format(blob)) throw new IOException("Corrupt key file");
            SecretKey kek = Encryption.getSecretKeyFromBytes(PasswordKdf.deriveKey(password, salt, memKib, iterations, parallelism));
            try {
                return Encryption.getSecretKeyFromBytes(Encryption.decryptV2(blob, kek));
            } catch (CryptoException e) {
                throw new IncorrectPasswordException("Incorrect password");
            }
        } catch (IncorrectPasswordException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not load key " + keyFile.getName(), e);
            throw new RuntimeException("Could not load key " + keyFile.getName(), e);
        }
    }

    private SecretKey loadSecretKeyLegacy(KeyEntry keyEntry, String password) throws IncorrectPasswordException {
        char[] passwordChars = password == null ? new char[0] : password.toCharArray();
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");

            try (FileInputStream fileInputStream = new FileInputStream(legacySymFile())) {
                keyStore.load(fileInputStream, passwordChars);
            }

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
    public void saveKeyRing(KeyRing keyRing, String password) {
        SecretKey symmetric = keyRing.getSymmetricKey();

        // password protect the symmetric key
        saveSecretKey(symmetric, KeyEntry.SYM_ENCRYPTION.getFileName(), password);

        // use symmetric encryption to encrypt the key pairs
        saveKey(keyRing.getSignatureKeyPair().getPrivate(), KeyEntry.MSG_SIGNATURE.getFileName(), symmetric);
        saveKey(keyRing.getEncryptionKeyPair().getPrivate(), KeyEntry.MSG_ENCRYPTION.getFileName(), symmetric);
        legacyFormatLoaded = false;
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
            keyBytes = Encryption.encryptV2(keyBytes, secretKey);
            fos.write(keyBytes);
        } catch (Exception e) {
            log.error("Could not save key " + fileName, e);
            throw new RuntimeException("Could not save key " + fileName, e);
        }
    }

    /**
     * Saves the symmetric key wrapped with a key derived from the password, then verifies the
     * write and removes legacy PKCS#12 artifacts and stale password-wrapped backups.
     *
     * @param key      The symmetric key
     * @param fileName Filename of the key file
     * @param password Optional password protecting the key
     */
    private void saveSecretKey(SecretKey key, String fileName, String password) {
        if (!storageDir.exists())
            //noinspection ResultOfMethodCallIgnored
            storageDir.mkdirs();

        // password must be ascii
        if (password != null && !password.matches("\\p{ASCII}*")) {
            throw new IllegalArgumentException("Password must be ASCII.");
        }

        boolean unprotected = password == null;
        int memKib = unprotected ? PasswordKdf.UNPROTECTED_MEM_KIB : PasswordKdf.DEFAULT_MEM_KIB;
        int iterations = unprotected ? PasswordKdf.UNPROTECTED_ITERATIONS : PasswordKdf.DEFAULT_ITERATIONS;
        int parallelism = PasswordKdf.DEFAULT_PARALLELISM;
        byte[] salt = PasswordKdf.generateSalt();
        try {
            SecretKey kek = Encryption.getSecretKeyFromBytes(PasswordKdf.deriveKey(password, salt, memKib, iterations, parallelism));
            byte[] blob = Encryption.encryptV2(key.getEncoded(), kek);
            ByteBuffer buf = ByteBuffer.allocate(SYM_FILE_MAGIC.length + 2 + 12 + salt.length + blob.length);
            buf.put(SYM_FILE_MAGIC).put(SYM_FILE_VERSION).put((byte) PasswordKdf.KDF_ARGON2ID)
                    .putInt(memKib).putInt(iterations).putInt(parallelism).put(salt).put(blob);

            // write to a temp file, verify the round trip, then atomically swap it in, so a failed
            // or unverified write can never replace the existing key file
            File keyFile = new File(storageDir, fileName);
            File tempFile = new File(storageDir, fileName + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(buf.array());
                fos.flush();
                fos.getFD().sync();
            }
            SecretKey readBack = loadSecretKeyV2(tempFile, password);
            if (!Arrays.equals(readBack.getEncoded(), key.getEncoded())) {
                throw new IOException("Key file verification failed");
            }
            FileUtil.atomicReplace(tempFile, keyFile);

            // remove the legacy PKCS#12 file and backups still unlockable with weak KDF or old
            // passwords, then keep a fresh backup so the wrapped key never exists as a single copy
            FileUtil.deleteRollingBackup(storageDir, fileName);
            FileUtil.deleteFileIfExists(legacySymFile());
            FileUtil.deleteRollingBackup(storageDir, LEGACY_SYM_FILE_NAME);
            FileUtil.rollingBackup(storageDir, fileName, 20);
        } catch (Exception e) {
            throw new RuntimeException("Could not save key " + fileName, e);
        }
    }

}
