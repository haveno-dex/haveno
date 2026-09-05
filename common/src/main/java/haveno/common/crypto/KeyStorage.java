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
import java.nio.file.Files;
import java.nio.file.Path;
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
import javax.crypto.SecretKey;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * KeyStorage wraps the account master key with Argon2id and authenticated encryption.
 * The symmetric key is used to encrypt and decrypt other keys in the key ring and other types of persistence.
 */
@Singleton
public class KeyStorage {

    private static final Logger log = LoggerFactory.getLogger(KeyStorage.class);

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

    private static final String LEGACY_KEY_FILE = "sym.p12";
    // Contains only a dummy random key under a discarded random password, never an account key.
    // Old builds must see a complete, locked keyring instead of generating a replacement identity.
    private static final byte[] MIGRATION_GUARD = loadMigrationGuard();
    private final File storageDir;

    @Inject
    public KeyStorage(@Named(Config.KEY_STORAGE_DIR) File storageDir) {
        this.storageDir = checkDir(storageDir);
    }

    public boolean allKeyFilesExist() {
        return fileExists(KeyEntry.MSG_SIGNATURE) && fileExists(KeyEntry.MSG_ENCRYPTION) && (fileExists(KeyEntry.SYM_ENCRYPTION) || new File(storageDir, LEGACY_KEY_FILE).exists());
    }

    private boolean fileExists(KeyEntry keyEntry) {
        return new File(storageDir + "/" + keyEntry.getFileName()).exists();
    }

    public boolean anyKeyFilesExist() {
        return fileExists(KeyEntry.MSG_SIGNATURE) || fileExists(KeyEntry.MSG_ENCRYPTION)
                || fileExists(KeyEntry.SYM_ENCRYPTION) || new File(storageDir, LEGACY_KEY_FILE).exists()
                || new File(storageDir, "sym.key.bak").exists() || new File(storageDir, "sig.key.bak").exists()
                || new File(storageDir, "enc.key.bak").exists() || new File(storageDir, "backup").exists()
                || hasKeyTemps();
    }

    private static byte[] loadMigrationGuard() {
        try (java.io.InputStream in = KeyStorage.class.getResourceAsStream("migration-guard.p12")) {
            if (in == null) throw new IllegalStateException("Missing key migration guard resource");
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read key migration guard resource", e);
        }
    }

    private boolean hasKeyTemps() {
        if (!storageDir.exists()) return false;
        String[] names = storageDir.list((dir, name) -> name.endsWith(".tmp")
                && (name.startsWith("sym.") || name.startsWith("sig.key.") || name.startsWith("enc.key.")));
        if (names == null) throw new IllegalStateException("Cannot list key directory");
        return names.length != 0;
    }

    private byte[] loadKeyBytes(KeyEntry keyEntry, SecretKey secretKey) {
        try {
            byte[] encoded = readKeyFile(new File(storageDir, keyEntry.getFileName()));
            return AuthenticatedEncryption.isEnvelope(encoded)
                    ? AuthenticatedEncryption.decrypt(encoded, secretKey, "identity/" + keyEntry.getFileName())
                    : Encryption.decryptPayloadWithHmac(encoded, secretKey);
        } catch (IOException | CryptoException e) {
            throw new IllegalStateException("Could not load " + keyEntry.getFileName(), e);
        }
    }

    private static byte[] readKeyFile(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = in.readNBytes(16 * 1024 + 1);
            if (bytes.length > 16 * 1024) throw new IOException("Oversized key file " + file.getName());
            return bytes;
        }
    }

    /**
     * Loads the public private KeyPair from a key file.
     *
     * @param keyEntry   The key entry that defines the public private key
     * @param secretKey  The symmetric key that protects the key entry file
     */
    public KeyPair loadKeyPair(KeyEntry keyEntry, SecretKey secretKey) {
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
        if (keyEntry != KeyEntry.SYM_ENCRYPTION) throw new IllegalArgumentException("Expected account key entry");
        File current = new File(storageDir, keyEntry.getFileName());
        if (current.exists()) {
            try {
                return PasswordKey.unwrap(readKeyFile(current), password);
            } catch (IOException e) {
                throw new IllegalStateException("Could not load account key", e);
            }
        }
        try {
            if (java.util.Arrays.equals(readKeyFile(new File(storageDir, LEGACY_KEY_FILE)), MIGRATION_GUARD)) {
                throw new IllegalStateException("Migrated account is missing sym.key; restore its recovery copy");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not load legacy account key", e);
        }
        char[] passwordChars = password == null ? new char[0] : password.toCharArray();
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");

            try (FileInputStream fileInputStream = new FileInputStream(new File(storageDir, LEGACY_KEY_FILE))) {
                keyStore.load(fileInputStream, passwordChars);
            }

            Key key = keyStore.getKey(keyEntry.getAlias(), passwordChars);
            if (!(key instanceof SecretKey) || key.getEncoded().length != 32) {
                throw new IllegalStateException("Invalid legacy account key");
            }
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
        } finally {
            java.util.Arrays.fill(passwordChars, '\0');
        }
    }

    /** Migrates only after both identity keys have been decoded and validated by KeyRing. */
    public void migrateKeyRing(KeyRing keyRing, String password) {
        try {
            if (!fileExists(KeyEntry.SYM_ENCRYPTION)) saveMasterKey(keyRing.getSymmetricKey(), password);
            for (KeyEntry entry : new KeyEntry[]{KeyEntry.MSG_SIGNATURE, KeyEntry.MSG_ENCRYPTION}) {
                if (!AuthenticatedEncryption.isEnvelope(readKeyFile(new File(storageDir, entry.getFileName())))) {
                    PrivateKey key = entry == KeyEntry.MSG_SIGNATURE
                            ? keyRing.getSignatureKeyPair().getPrivate() : keyRing.getEncryptionKeyPair().getPrivate();
                    savePrivateKey(key, entry, keyRing.getSymmetricKey());
                }
            }
            // A fully migrated account can unlock without writing. Repair missing recovery copies
            // when possible; deletion of a real legacy password wrapper still requires a good copy.
            File legacyFile = new File(storageDir, LEGACY_KEY_FILE);
            boolean retiringLegacy = !legacyFile.exists() || !java.util.Arrays.equals(readKeyFile(legacyFile), MIGRATION_GUARD);
            for (KeyEntry entry : KeyEntry.values()) {
                refreshRecoveryCopy(entry.getFileName(), retiringLegacy);
            }
            retireLegacyWrappers();
        } catch (IOException | CryptoException e) {
            throw new IllegalStateException("Could not migrate account keys", e);
        }
    }

    /** Password changes rewrap the same master; they never replace identity keys. */
    public void saveKeyRing(KeyRing keyRing, String oldPassword, String password) {
        validatePassword(password);
        try {
            if (fileExists(KeyEntry.SYM_ENCRYPTION) || new File(storageDir, LEGACY_KEY_FILE).exists()) {
                SecretKey existing = loadSecretKey(KeyEntry.SYM_ENCRYPTION, oldPassword);
                if (!java.security.MessageDigest.isEqual(existing.getEncoded(), keyRing.getSymmetricKey().getEncoded())) {
                    throw new IllegalStateException("Account key does not match stored key");
                }
            }
            if (!fileExists(KeyEntry.MSG_SIGNATURE)) {
                savePrivateKey(keyRing.getSignatureKeyPair().getPrivate(), KeyEntry.MSG_SIGNATURE, keyRing.getSymmetricKey());
            }
            if (!fileExists(KeyEntry.MSG_ENCRYPTION)) {
                savePrivateKey(keyRing.getEncryptionKeyPair().getPrivate(), KeyEntry.MSG_ENCRYPTION, keyRing.getSymmetricKey());
            }
            saveMasterKey(keyRing.getSymmetricKey(), password);
            retireLegacyWrappers();
        } catch (IOException | CryptoException | IncorrectPasswordException e) {
            throw new IllegalStateException("Could not save account keys", e);
        }
    }

    public static void validatePassword(String password) {
        // Keep existing password compatibility with the Monero wallet and existing account UI.
        if (password != null && !password.matches("\\p{ASCII}*")) {
            throw new IllegalArgumentException("Password must be ASCII.");
        }
    }

    public PreparedPasswordChange preparePasswordChange(KeyRing keyRing, String oldPassword, String newPassword) {
        validatePassword(newPassword);
        try {
            SecretKey existing = loadSecretKey(KeyEntry.SYM_ENCRYPTION, oldPassword);
            if (!java.security.MessageDigest.isEqual(existing.getEncoded(), keyRing.getSymmetricKey().getEncoded())) {
                throw new IllegalStateException("Account key does not match stored key");
            }
            return new PreparedPasswordChange(wrapAndVerify(existing, newPassword));
        } catch (IOException | CryptoException | IncorrectPasswordException e) {
            throw new IllegalStateException("Could not prepare account password change", e);
        }
    }

    /** Completes expensive KDF work before external password listeners can change wallets. */
    public final class PreparedPasswordChange {
        private final byte[] wrapped;

        private PreparedPasswordChange(byte[] wrapped) {
            this.wrapped = wrapped;
        }

        public void commit() {
            try {
                writeMasterKey(wrapped);
                retireLegacyWrappers();
            } catch (IOException e) {
                throw new IllegalStateException("Could not commit account password change", e);
            }
        }
    }

    private void saveMasterKey(SecretKey key, String password) throws IOException, CryptoException {
        writeMasterKey(wrapAndVerify(key, password));
    }

    private byte[] wrapAndVerify(SecretKey key, String password) throws IOException, CryptoException {
        byte[] wrapped = PasswordKey.wrap(key, password);
        try {
            if (!java.security.MessageDigest.isEqual(key.getEncoded(), PasswordKey.unwrap(wrapped, password).getEncoded())) {
                throw new IOException("Account key read-back mismatch");
            }
        } catch (IncorrectPasswordException e) {
            throw new IOException("Account key read-back failed", e);
        }
        return wrapped;
    }

    private void writeMasterKey(byte[] wrapped) throws IOException {
        writeKeyFile(KeyEntry.SYM_ENCRYPTION.getFileName(), wrapped);
        writeKeyFile("sym.key.bak", wrapped);
    }

    private void savePrivateKey(PrivateKey key, KeyEntry entry, SecretKey master) throws IOException, CryptoException {
        byte[] encoded = key.getEncoded();
        try {
            byte[] encrypted = AuthenticatedEncryption.encrypt(encoded, master, "identity/" + entry.getFileName());
            byte[] verified = AuthenticatedEncryption.decrypt(encrypted, master, "identity/" + entry.getFileName());
            try {
                if (!java.security.MessageDigest.isEqual(encoded, verified)) throw new IOException("Identity read-back mismatch");
            } finally {
                java.util.Arrays.fill(verified, (byte) 0);
            }
            writeKeyFile(entry.getFileName(), encrypted);
            writeKeyFile(entry.getFileName() + ".bak", encrypted);
        } finally {
            java.util.Arrays.fill(encoded, (byte) 0);
        }
    }

    private void writeKeyFile(String name, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(storageDir.toPath(), name + ".", ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temp.toFile())) {
                out.write(bytes);
                out.getFD().sync();
            }
            if (!java.security.MessageDigest.isEqual(bytes, readKeyFile(temp.toFile()))) {
                throw new IOException("Key file read-back mismatch");
            }
            // Refuse filesystems without atomic replacement: never delete a live key to rename a temp.
            Files.move(temp, new File(storageDir, name).toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            FileUtil.syncDirectory(storageDir);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void refreshRecoveryCopy(String name, boolean required) throws IOException {
        try {
            byte[] current = readKeyFile(new File(storageDir, name));
            File backup = new File(storageDir, name + ".bak");
            if (backup.isFile() && java.util.Arrays.equals(current, readKeyFile(backup))) return;
            writeKeyFile(name + ".bak", current);
        } catch (IOException e) {
            if (required) throw e;
            log.warn("Could not refresh recovery copy of {}; live keys remain usable", name, e);
        }
    }

    private void retireLegacyWrappers() throws IOException {
        File legacy = new File(storageDir, LEGACY_KEY_FILE);
        if (!legacy.isFile() || !java.util.Arrays.equals(readKeyFile(legacy), MIGRATION_GUARD)) {
            writeKeyFile(LEGACY_KEY_FILE, MIGRATION_GUARD);
        }
        for (File backup : FileUtil.getBackupFiles(storageDir, LEGACY_KEY_FILE)) {
            if (backup.isFile() && backup.getName().matches("[0-9]+_sym\\.p12")
                    && !java.util.Arrays.equals(readKeyFile(backup), MIGRATION_GUARD)) {
                Files.delete(backup.toPath());
                FileUtil.syncDirectory(backup.getParentFile());
            }
        }
        File[] temps = storageDir.listFiles((dir, name) -> name.matches("(?:sym\\.(?:key(?:\\.bak)?|p12)|sig\\.key(?:\\.bak)?|enc\\.key(?:\\.bak)?)\\.[0-9]+\\.tmp"));
        if (temps == null) throw new IOException("Cannot list key directory");
        for (File temp : temps) Files.delete(temp.toPath());
        if (temps.length > 0) FileUtil.syncDirectory(storageDir);
    }
}
