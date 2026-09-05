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
import haveno.common.file.AtomicFileWriter;
import haveno.common.persistence.LegacyStorageMigration;
import static haveno.common.util.Preconditions.checkDir;
import java.io.File;
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
import java.util.Arrays;
import javax.crypto.SecretKey;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Password wrapping changes neither the master key nor the account's signing/encryption identity. */
@Singleton
public class KeyStorage {
    private static final Logger log = LoggerFactory.getLogger(KeyStorage.class);
    private static final String LEGACY_WRAPPER = "sym.p12";
    private static final int MAX_KEY_FILE_SIZE = 16 * 1024;

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
    private final File persistenceDir;

    @Inject
    public KeyStorage(@Named(Config.KEY_STORAGE_DIR) File storageDir,
                      @Named(Config.STORAGE_DIR) File persistenceDir) {
        this.storageDir = checkDir(storageDir);
        this.persistenceDir = persistenceDir;
    }

    public KeyStorage(File storageDir) {
        this(storageDir, null);
    }

    private Path path(String name) {
        return storageDir.toPath().resolve(name);
    }

    public boolean allKeyFilesExist() {
        return Files.isRegularFile(path("sig.key")) && Files.isRegularFile(path("enc.key"))
                && (Files.isRegularFile(path("sym.key")) || Files.isRegularFile(path(LEGACY_WRAPPER)));
    }

    public boolean anyKeyFilesExist() {
        File[] files = storageDir.listFiles();
        if (files == null) throw new IllegalStateException("Cannot inspect key storage directory");
        // Backups or crash-left temps may be the only surviving key material. Never generate a
        // replacement account over them, even when all three live key files are missing.
        return files.length != 0;
    }

    private static byte[] readBounded(Path path, int maxBytes) throws IOException {
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IOException("Key file is too large");
            return bytes;
        }
    }

    private byte[] loadKeyBytes(KeyEntry entry, SecretKey key) {
        try {
            byte[] bytes = readBounded(path(entry.getFileName()), MAX_KEY_FILE_SIZE);
            return AuthenticatedEncryption.hasEnvelope(bytes)
                    ? AuthenticatedEncryption.decrypt(bytes, key, "private-key/" + entry.getAlias())
                    : Encryption.decryptPayloadWithHmac(bytes, key);
        } catch (IOException | CryptoException e) {
            throw new IllegalStateException("Could not load " + entry.getFileName(), e);
        }
    }

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

    public SecretKey loadSecretKey(KeyEntry entry, String password) throws IncorrectPasswordException {
        if (entry != KeyEntry.SYM_ENCRYPTION) throw new IllegalArgumentException("Expected master-key entry");
        if (Files.exists(path("sym.key"))) {
            try {
                return PasswordKeyEnvelope.unwrap(readBounded(path("sym.key"), PasswordKeyEnvelope.LENGTH), password);
            } catch (CryptoException e) {
                // The pending wrapper is considered only while a separately authenticated journal
                // exists. A normal corrupt/wrong-password load never falls back to stale backups.
                if (hasPasswordChange()) {
                    try {
                        SecretKey pendingKey = PasswordKeyEnvelope.unwrap(readBounded(path("sym.key.next"), PasswordKeyEnvelope.LENGTH), password);
                        readPasswordChange(pendingKey); // reject a planted/unrelated pending wrapper
                        return pendingKey;
                    } catch (IOException | CryptoException ignored) {
                        // Report one generic password/authentication failure.
                    }
                }
                throw new IncorrectPasswordException("Incorrect password or damaged master-key wrapper");
            } catch (IOException e) {
                throw new IllegalStateException("Could not read master-key wrapper", e);
            }
        }
        return loadLegacySecretKey(password);
    }

    private SecretKey loadLegacySecretKey(String password) throws IncorrectPasswordException {
        char[] chars = password == null ? new char[0] : password.toCharArray();
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            byte[] bytes = readBounded(path(LEGACY_WRAPPER), MAX_KEY_FILE_SIZE);
            try (var in = new java.io.ByteArrayInputStream(bytes)) {
                store.load(in, chars);
            }
            Key key = store.getKey("sym", chars);
            if (!(key instanceof SecretKey) || key.getEncoded().length != 32) throw new IOException("Invalid legacy master key");
            return (SecretKey) key;
        } catch (UnrecoverableKeyException e) {
            throw new IncorrectPasswordException("Incorrect password");
        } catch (IOException e) {
            if (e.getCause() instanceof UnrecoverableKeyException) throw new IncorrectPasswordException("Incorrect password");
            throw new IllegalStateException("Could not read legacy master key", e);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read legacy master key", e);
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    /** Called only after all three keys have loaded and both private keys have decoded successfully. */
    public void migrate(KeyRing keyRing, String password) {
        try {
            if (hasPasswordChange()) {
                readPasswordChange(keyRing.getSymmetricKey());
                return; // recovery must retain the old primary and pending wrapper until completion
            }
            if (!Files.exists(path("sym.key"))) {
                LegacyStorageMigration.migrate(persistenceDir, keyRing.getSymmetricKey());
            }
            savePrivateKeyIfLegacy(keyRing.getSignatureKeyPair().getPrivate(), KeyEntry.MSG_SIGNATURE, keyRing.getSymmetricKey());
            savePrivateKeyIfLegacy(keyRing.getEncryptionKeyPair().getPrivate(), KeyEntry.MSG_ENCRYPTION, keyRing.getSymmetricKey());
            if (!Files.exists(path("sym.key"))) saveMasterKey(keyRing.getSymmetricKey(), password);
            else ensureCurrentBackup(password);
            removeLegacyWrappers();
            Files.deleteIfExists(path("sym.key.next")); // abandoned prepare, without a journal
        } catch (IOException | CryptoException e) {
            throw new IllegalStateException("Key migration did not complete; existing keys have been preserved", e);
        }
    }

    public void saveKeyRing(KeyRing keyRing, String oldPassword, String password) {
        try {
            // Verify the supplied old password before touching any files on an existing account.
            if (allKeyFilesExist()) {
                SecretKey existing = loadSecretKey(KeyEntry.SYM_ENCRYPTION, oldPassword);
                if (!existing.equals(keyRing.getSymmetricKey())) throw new IllegalStateException("Master key mismatch");
            }
            savePrivateKeyIfLegacy(keyRing.getSignatureKeyPair().getPrivate(), KeyEntry.MSG_SIGNATURE, keyRing.getSymmetricKey());
            savePrivateKeyIfLegacy(keyRing.getEncryptionKeyPair().getPrivate(), KeyEntry.MSG_ENCRYPTION, keyRing.getSymmetricKey());
            saveMasterKey(keyRing.getSymmetricKey(), password);
            removeLegacyWrappers();
        } catch (IOException | CryptoException | IncorrectPasswordException e) {
            throw new IllegalStateException("Could not save key ring", e);
        }
    }

    private void savePrivateKeyIfLegacy(PrivateKey privateKey, KeyEntry entry, SecretKey key) throws IOException, CryptoException {
        Path target = path(entry.getFileName());
        if (Files.exists(target) && AuthenticatedEncryption.hasEnvelope(target)) return;
        byte[] bytes = privateKey.getEncoded();
        try {
            byte[] encrypted = AuthenticatedEncryption.encrypt(bytes, key, "private-key/" + entry.getAlias());
            AtomicFileWriter.write(target, out -> out.write(encrypted), candidate -> {
                byte[] read = AuthenticatedEncryption.decrypt(readBounded(candidate, MAX_KEY_FILE_SIZE), key, "private-key/" + entry.getAlias());
                try {
                    if (!java.security.MessageDigest.isEqual(bytes, read)) throw new IOException("Private key read-back failed");
                } finally {
                    Arrays.fill(read, (byte) 0);
                }
            });
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private void saveMasterKey(SecretKey key, String password) throws IOException, CryptoException {
        byte[] wrapper = PasswordKeyEnvelope.wrap(key, password);
        AtomicFileWriter.write(path("sym.key"), out -> out.write(wrapper), candidate -> {
            if (!key.equals(PasswordKeyEnvelope.unwrap(readBounded(candidate, PasswordKeyEnvelope.LENGTH), password))) {
                throw new IOException("Master key read-back failed");
            }
        });
        // Keep one independently durable, current-password copy before retiring any legacy wrapper.
        AtomicFileWriter.write(path("sym.key.backup"), wrapper);
    }

    private void ensureCurrentBackup(String password) throws IOException, CryptoException {
        byte[] wrapper = readBounded(path("sym.key"), PasswordKeyEnvelope.LENGTH);
        PasswordKeyEnvelope.unwrap(wrapper, password);
        if (!Files.exists(path("sym.key.backup")) || !Arrays.equals(wrapper, readBounded(path("sym.key.backup"), PasswordKeyEnvelope.LENGTH))) {
            AtomicFileWriter.write(path("sym.key.backup"), wrapper);
        }
    }

    public boolean hasPasswordChange() {
        return Files.exists(path("password-change"));
    }

    /** Durable prepare. No password-dependent component may change before this returns. */
    public synchronized void beginPasswordChange(SecretKey key, String oldPassword, String newPassword) {
        if (hasPasswordChange()) throw new IllegalStateException("A password change already needs recovery");
        try {
            SecretKey current = loadSecretKey(KeyEntry.SYM_ENCRYPTION, oldPassword);
            if (!current.equals(key)) throw new IllegalStateException("Master key mismatch");
            var bytes = new java.io.ByteArrayOutputStream();
            try (var out = new java.io.DataOutputStream(bytes)) {
                out.writeInt(1);
                out.writeUTF(oldPassword == null ? "" : oldPassword);
                out.writeUTF(newPassword == null ? "" : newPassword);
            }
            byte[] plaintext = bytes.toByteArray();
            try {
                AtomicFileWriter.write(path("password-change"), AuthenticatedEncryption.encrypt(plaintext, key, "password-change"));
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
            // Journal first: even a failed/interrupting write of a passwordless next wrapper
            // must leave a durable recovery intent, never an untracked copy of the master key.
            byte[] next = PasswordKeyEnvelope.wrap(key, newPassword);
            AtomicFileWriter.write(path("sym.key.next"), out -> out.write(next), candidate -> {
                if (!key.equals(PasswordKeyEnvelope.unwrap(readBounded(candidate, PasswordKeyEnvelope.LENGTH), newPassword))) {
                    throw new IOException("Pending key verification failed");
                }
            });
        } catch (IOException | CryptoException | IncorrectPasswordException e) {
            throw new IllegalStateException("Could not prepare password change; keep both passwords if a journal was written", e);
        }
    }

    public PasswordChange readPasswordChange(SecretKey key) throws IOException, CryptoException {
        if (!hasPasswordChange()) return null;
        byte[] plaintext = AuthenticatedEncryption.decrypt(readBounded(path("password-change"), MAX_KEY_FILE_SIZE), key, "password-change");
        try (var in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(plaintext))) {
            if (in.readInt() != 1) throw new IOException("Unsupported password-change journal");
            String oldPassword = in.readUTF();
            String newPassword = in.readUTF();
            if (in.read() != -1) throw new IOException("Trailing password-change data");
            return new PasswordChange(oldPassword.isEmpty() ? null : oldPassword, newPassword.isEmpty() ? null : newPassword);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** Commit only after every password-dependent component has durably converged to the new password. */
    public synchronized void completePasswordChange(SecretKey key) {
        try {
            PasswordChange change = readPasswordChange(key);
            if (change == null) return;
            saveMasterKey(key, change.newPassword);
            removeLegacyWrappers();
            Files.deleteIfExists(path("sym.key.next"));
            AtomicFileWriter.syncDirectory(storageDir.toPath());
            // Last operation: until here either password can recover the same master key (except
            // after the primary commit, when the new password is authoritative).
            Files.delete(path("password-change"));
            AtomicFileWriter.syncDirectory(storageDir.toPath());
        } catch (IOException | CryptoException e) {
            throw new IllegalStateException("Password change needs recovery; keep both passwords", e);
        }
    }

    public static final class PasswordChange {
        private final String oldPassword;
        private final String newPassword;
        private PasswordChange(String oldPassword, String newPassword) {
            this.oldPassword = oldPassword;
            this.newPassword = newPassword;
        }
        public String getOldPassword() { return oldPassword; }
        public String getNewPassword() { return newPassword; }
        @Override public String toString() { return "PasswordChange{redacted}"; }
    }

    private void removeLegacyWrappers() throws IOException {
        // Only account-owned password wrappers are removed. Private-key backups and database
        // history stay available; exported account backups cannot be revoked by an upgrade.
        Path backupDir = path("backup/backups_sym_p12");
        if (Files.exists(backupDir)) {
            try (var files = Files.newDirectoryStream(backupDir)) {
                for (Path file : files) Files.delete(file);
            }
            Files.delete(backupDir);
        }
        Files.deleteIfExists(path(LEGACY_WRAPPER));
        AtomicFileWriter.syncDirectory(storageDir.toPath());
    }
}
