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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
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
    // Present only during a password change: a second sym.key wrapper under the new password plus
    // an authenticated journal of both passwords, so a crash leaves the account unlockable with either.
    private static final String PENDING_SYM_FILE_NAME = "sym.key.new";
    private static final String PASSWORD_CHANGE_JOURNAL_FILE_NAME = "password_change";
    // The header is unauthenticated, so bound KDF cost and file size before any allocation.
    private static final int MAX_MEM_KIB = 256 * 1024;
    private static final int MAX_ITERATIONS = 10;
    private static final int MAX_PARALLELISM = 4;
    private static final int MAX_SYM_FILE_SIZE = 4096;
    // bounds the extra key derivations spent probing distinct backups of a corrupt key file
    private static final int MAX_KEY_BACKUP_PROBES = 3;
    // Generous for a PKCS#8 RSA/DSA private key in a v2 blob; anything larger is corruption.
    private static final int MAX_KEY_FILE_SIZE = 64 * 1024;

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
    // Like legacyFormatLoaded but never reset: gates acceptance of not-yet-migrated plaintext stores.
    private boolean legacyFormatEverLoaded = false;

    @Inject
    public KeyStorage(@Named(Config.KEY_STORAGE_DIR) File storageDir) {
        this.storageDir = checkDir(storageDir);
    }

    public boolean allKeyFilesExist() {
        return fileExists(KeyEntry.MSG_SIGNATURE) && fileExists(KeyEntry.MSG_ENCRYPTION)
                && (fileExists(KeyEntry.SYM_ENCRYPTION) || legacySymFile().exists() || hasRecoverablePendingWrapper());
    }

    // A journaled pending wrapper can recover the account even if the live wrapper was lost to a
    // crash during a non-atomic replacement, so it must count as an existing account.
    private boolean hasRecoverablePendingWrapper() {
        return hasPasswordChangeJournal() && new File(storageDir, PENDING_SYM_FILE_NAME).exists();
    }

    public boolean needsFormatUpgrade() {
        return legacyFormatLoaded;
    }

    // Whether any key material was ever read in a pre-v2 format during this process lifetime.
    public boolean hasLegacyFormatEverLoaded() {
        return legacyFormatEverLoaded;
    }

    private boolean fileExists(KeyEntry keyEntry) {
        return new File(storageDir + "/" + keyEntry.getFileName()).exists();
    }

    private File legacySymFile() {
        return new File(storageDir + "/" + LEGACY_SYM_FILE_NAME);
    }

    private byte[] loadKeyBytes(KeyEntry keyEntry, SecretKey secretKey) {
        File keyFile = new File(storageDir + "/" + keyEntry.getFileName());
        try {
            if (keyFile.length() > MAX_KEY_FILE_SIZE) throw new IOException("Key file too large: " + keyFile.length());
            byte[] encodedKey = Files.readAllBytes(keyFile.toPath());
            if (Encryption.blobVersion(encodedKey) < Encryption.CURRENT_BLOB_VERSION) legacyFormatLoaded = legacyFormatEverLoaded = true;
            return Encryption.decryptPayloadWithHmacAuto(encodedKey, secretKey);
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
            // backup only after a successful load, so retries against a corrupt file cannot rotate out good backups
            FileUtil.rollingBackup(storageDir, keyEntry.getFileName(), 20);
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
        // stale transaction temps from a crash can hold a master-key wrapper or a journal of both
        // passwords; they are never valid at unlock and must not survive into backups
        for (String staleName : new String[]{KeyEntry.SYM_ENCRYPTION.getFileName() + ".tmp",
                PENDING_SYM_FILE_NAME + ".tmp", PASSWORD_CHANGE_JOURNAL_FILE_NAME + ".tmp"}) {
            try {
                deleteStrict(new File(storageDir, staleName));
            } catch (IOException e) {
                throw new RuntimeException("Could not delete stale key file " + staleName, e);
            }
        }
        // an orphan pending wrapper (no journal) from a failed transaction initialization is
        // unused, but still wraps the master key under an abandoned password: remove it strictly
        File orphanPendingFile = new File(storageDir, PENDING_SYM_FILE_NAME);
        if (orphanPendingFile.exists() && !hasPasswordChangeJournal()) {
            try {
                deleteStrict(orphanPendingFile);
                log.warn("Removed orphan pending key wrapper from an interrupted password change");
            } catch (IOException e) {
                throw new RuntimeException("Could not delete orphan pending key wrapper", e);
            }
        }
        File keyFile = new File(storageDir + "/" + keyEntry.getFileName());
        if (keyFile.exists()) {
            SecretKey key;
            boolean keyFromPendingWrapper = false;
            try {
                key = loadSecretKeyV2(keyFile, password);
            } catch (IncorrectPasswordException e) {
                // during an interrupted password change the other password unlocks via the pending
                // wrapper; while a journal exists the recovery machinery owns the state and the
                // backup probe must not run (backups may still wrap the retiring password)
                if (hasPasswordChangeJournal()) {
                    File pendingFile = new File(storageDir, PENDING_SYM_FILE_NAME);
                    if (!pendingFile.exists()) throw e;
                    key = loadSecretKeyV2(pendingFile, password);
                    keyFromPendingWrapper = true;
                } else {
                    // rotation is done inside the probe only after the live file is restored, so
                    // a corrupt file cannot be copied over the good backups
                    return recoverSecretKeyFromBackups(keyFile, password, e);
                }
            } catch (RuntimeException e) {
                // structural corruption (bad header, truncation) must not lock the user out
                // while the pending wrapper or a good backup can still unlock; while a journal
                // exists the backup probe must not run (backups may still wrap the retiring
                // password), but the pending wrapper is part of the transaction and safe to try
                if (hasPasswordChangeJournal()) {
                    File pendingFile = new File(storageDir, PENDING_SYM_FILE_NAME);
                    if (!pendingFile.exists()) throw e;
                    key = loadSecretKeyV2(pendingFile, password);
                    keyFromPendingWrapper = true;
                } else {
                    return recoverSecretKeyFromBackups(keyFile, password, e);
                }
            }
            // backup only after a successful load, so retries against a corrupt file cannot rotate
            // out good backups. When the pending wrapper supplied the key the live wrapper did not
            // authenticate (counterpart password or bit rot - indistinguishable here), so neither
            // rotate it into the backups nor purge legacy material; recovery rewraps it shortly
            if (!keyFromPendingWrapper) {
                FileUtil.rollingBackup(storageDir, keyEntry.getFileName(), 20);
                purgeStaleLegacyWrapper(keyEntry.getFileName());
            }
            return key;
        }
        // a crash during a non-atomic replacement of the live wrapper can lose it while the
        // transaction's pending wrapper remains; unlock from it so recovery can recommit
        if (hasRecoverablePendingWrapper()) {
            log.warn("{} is missing but a journaled pending wrapper exists; unlocking from the pending wrapper", keyEntry.getFileName());
            return loadSecretKeyV2(new File(storageDir, PENDING_SYM_FILE_NAME), password);
        }
        legacyFormatLoaded = legacyFormatEverLoaded = true;
        SecretKey key = loadSecretKeyLegacy(keyEntry, password);
        FileUtil.rollingBackup(storageDir, LEGACY_SYM_FILE_NAME, 20);
        return key;
    }

    // Removes a legacy PKCS#12 wrapper (and its backups) left behind by a failed purge; it is
    // never current once the v2 file unlocks, but stays brute-forceable (weak KDF). A fresh
    // verified backup of the live wrapper is taken first, so the purge can never leave it as
    // the only copy. Failures are logged and retried on the next unlock.
    private void purgeStaleLegacyWrapper(String liveFileName) {
        if (!legacySymFile().exists() && !FileUtil.hasBackups(storageDir, LEGACY_SYM_FILE_NAME)) return;
        try {
            FileUtil.rollingBackupStrict(storageDir, liveFileName, 20);
            FileUtil.deleteFileIfExists(legacySymFile(), false);
            FileUtil.deleteRollingBackupStrict(storageDir, LEGACY_SYM_FILE_NAME);
        } catch (IOException e) {
            log.error("Could not remove the stale legacy key file; it remains brute-forceable until deleted", e);
        }
    }

    // Distinguishes a corrupted live wrapper from a genuinely wrong password by probing recent
    // backups: byte-identical copies are skipped, so a wrong password costs no extra key
    // derivations; a backup that unlocks is restored over the corrupt file.
    private SecretKey recoverSecretKeyFromBackups(File keyFile, String password, Exception original) throws IncorrectPasswordException {
        byte[] liveBytes;
        try {
            liveBytes = Files.readAllBytes(keyFile.toPath());
        } catch (IOException e) {
            throw rethrow(original);
        }
        List<File> backups = new ArrayList<>(FileUtil.getBackupFiles(storageDir, keyFile.getName()));
        backups.sort(Comparator.comparing(File::getName).reversed()); // <epoch millis>_<name>, newest first
        List<byte[]> probed = new ArrayList<>();
        probed.add(liveBytes);
        for (File backup : backups) {
            if (probed.size() > MAX_KEY_BACKUP_PROBES) break;
            if (backup.length() == 0 || backup.length() > MAX_SYM_FILE_SIZE) continue;
            byte[] backupBytes;
            try {
                backupBytes = Files.readAllBytes(backup.toPath());
            } catch (IOException e) {
                continue;
            }
            // an identical wrapper fails the same way; probe each distinct one only once
            if (probed.stream().anyMatch(seen -> Arrays.equals(seen, backupBytes))) continue;
            probed.add(backupBytes);
            SecretKey key;
            try {
                // authenticate exactly the bytes that would be restored
                key = loadSecretKeyV2(backupBytes, password, backup.getName());
            } catch (Exception e) {
                continue;
            }
            log.warn("{} did not unlock but backup {} did; restoring the backup over the corrupt file", keyFile.getName(), backup.getName());
            try {
                File tempFile = new File(storageDir, keyFile.getName() + ".tmp");
                try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                    fos.write(backupBytes);
                    fos.flush();
                    fos.getFD().sync();
                }
                FileUtil.atomicReplace(tempFile, keyFile);
                // rotate a backup of the restored file only now, so a failed restore cannot
                // copy the corrupt live file over the good generations
                FileUtil.rollingBackup(storageDir, keyFile.getName(), 20);
                purgeStaleLegacyWrapper(keyFile.getName());
            } catch (IOException e) {
                log.warn("Could not restore key backup over {}; unlocking anyway", keyFile.getName(), e);
            }
            return key;
        }
        throw rethrow(original);
    }

    private static RuntimeException rethrow(Exception original) throws IncorrectPasswordException {
        if (original instanceof IncorrectPasswordException e) throw e;
        return (RuntimeException) original;
    }

    private SecretKey loadSecretKeyV2(File keyFile, String password) throws IncorrectPasswordException {
        try {
            if (keyFile.length() > MAX_SYM_FILE_SIZE) throw new IOException("Key file too large: " + keyFile.length());
            return loadSecretKeyV2(Files.readAllBytes(keyFile.toPath()), password, keyFile.getName());
        } catch (IOException e) {
            log.error("Could not load key " + keyFile.getName(), e);
            throw new RuntimeException("Could not load key " + keyFile.getName(), e);
        }
    }

    // Byte-based so a caller can authenticate exactly the bytes it goes on to use.
    private SecretKey loadSecretKeyV2(byte[] fileBytes, String password, String name) throws IncorrectPasswordException {
        try {
            if (fileBytes.length > MAX_SYM_FILE_SIZE) throw new IOException("Key file too large: " + fileBytes.length);
            ByteBuffer buf = ByteBuffer.wrap(fileBytes);
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
            log.error("Could not load key " + name, e);
            throw new RuntimeException("Could not load key " + name, e);
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
     * Moves any leftover key material of a previous account (live files, legacy wrapper,
     * transaction artifacts and all rolling backups) into a timestamped folder before fresh keys
     * are generated over it, e.g. after a partial key-file loss made the account unopenable. The
     * material may be that account's only remaining key copy, so it must leave the rolling-backup
     * machinery entirely - the replacement account's saves, password changes and backup rotation
     * would otherwise eventually purge it. Throws on failure, aborting the account creation.
     */
    public void preserveLostAccountKeys() throws IOException {
        List<String> names = new ArrayList<>();
        for (KeyEntry keyEntry : KeyEntry.values()) {
            names.add(keyEntry.getFileName());
            names.add(keyEntry.getFileName() + ".tmp"); // a crash-left temp may be the only usable copy
        }
        names.addAll(List.of(LEGACY_SYM_FILE_NAME, PENDING_SYM_FILE_NAME, PASSWORD_CHANGE_JOURNAL_FILE_NAME,
                PENDING_SYM_FILE_NAME + ".tmp", PASSWORD_CHANGE_JOURNAL_FILE_NAME + ".tmp"));
        List<File> artifacts = new ArrayList<>();
        for (String name : names) {
            File file = new File(storageDir, name);
            if (file.exists()) artifacts.add(file);
        }
        File backupDir = FileUtil.getBackupRoot(storageDir);
        if (artifacts.isEmpty() && !backupDir.exists()) return;
        File preserveDir = new File(storageDir, "lost_account_" + new Date().getTime());
        Files.createDirectory(preserveDir.toPath());
        for (File artifact : artifacts) FileUtil.renameFile(artifact, new File(preserveDir, artifact.getName()));
        if (backupDir.exists()) FileUtil.renameFile(backupDir, new File(preserveDir, backupDir.getName()));
        // best effort: sync the destination's new entries before the source removals, so a power
        // loss cannot durably remove the material without its preserved copy
        FileUtil.syncDir(preserveDir);
        FileUtil.syncDir(storageDir);
        log.warn("Moved key material of a previous account to {}; it may be that account's only remaining key copy", preserveDir.getName());
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
        saveSecretKey(symmetric, KeyEntry.SYM_ENCRYPTION.getFileName(), password, true);

        // use symmetric encryption to encrypt the key pairs
        saveKey(keyRing.getSignatureKeyPair().getPrivate(), KeyEntry.MSG_SIGNATURE.getFileName(), symmetric);
        saveKey(keyRing.getEncryptionKeyPair().getPrivate(), KeyEntry.MSG_ENCRYPTION.getFileName(), symmetric);
        legacyFormatLoaded = false;
    }

    /**
     * Durably begins a password change: writes an authenticated journal of both passwords and a
     * second sym.key wrapper under the new password. sym.key itself stays on the old password, so
     * a crash at any point leaves the account unlockable with either password.
     */
    public void beginPasswordChange(KeyRing keyRing, String oldPassword, String newPassword) {
        // write the pending wrapper first: without the journal it is inert, so a failure at any
        // point here leaves no durable transaction behind
        saveSecretKey(keyRing.getSymmetricKey(), PENDING_SYM_FILE_NAME, newPassword, false);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            writeNullableUtf(out, oldPassword);
            writeNullableUtf(out, newPassword);
            byte[] blob = Encryption.encryptV2(bos.toByteArray(), keyRing.getSymmetricKey());
            File journalFile = new File(storageDir, PASSWORD_CHANGE_JOURNAL_FILE_NAME);
            File tempFile = new File(storageDir, PASSWORD_CHANGE_JOURNAL_FILE_NAME + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(blob);
                fos.flush();
                fos.getFD().sync();
            }
            FileUtil.atomicReplace(tempFile, journalFile);
        } catch (Exception e) {
            RuntimeException ex = new RuntimeException("Could not journal password change", e);
            for (String name : new String[]{PENDING_SYM_FILE_NAME, PASSWORD_CHANGE_JOURNAL_FILE_NAME + ".tmp"}) {
                try {
                    deleteStrict(new File(storageDir, name));
                } catch (Exception e2) {
                    ex.addSuppressed(e2);
                }
            }
            throw ex;
        }
    }

    /**
     * Returns [oldPassword, newPassword] from the password change journal, or null if there is none
     * or it cannot be authenticated.
     */
    public String[] readPasswordChangeJournal(SecretKey masterKey) {
        File journalFile = new File(storageDir, PASSWORD_CHANGE_JOURNAL_FILE_NAME);
        if (!journalFile.exists()) return null;
        try {
            byte[] plain = Encryption.decryptV2(Files.readAllBytes(journalFile.toPath()), masterKey);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain));
            return new String[]{readNullableUtf(in), readNullableUtf(in)};
        } catch (Exception e) {
            log.error("Could not read password change journal", e);
            return null;
        }
    }

    public boolean hasPasswordChangeJournal() {
        return new File(storageDir, PASSWORD_CHANGE_JOURNAL_FILE_NAME).exists();
    }

    /** Whether any password-change transaction file exists, including crash-left temps. */
    public boolean hasPasswordChangeArtifacts() {
        for (String name : new String[]{PASSWORD_CHANGE_JOURNAL_FILE_NAME, PASSWORD_CHANGE_JOURNAL_FILE_NAME + ".tmp",
                PENDING_SYM_FILE_NAME, PENDING_SYM_FILE_NAME + ".tmp", KeyEntry.SYM_ENCRYPTION.getFileName() + ".tmp"}) {
            if (new File(storageDir, name).exists()) return true;
        }
        return false;
    }

    /**
     * Commits a password change: rewraps sym.key under the given password, then removes the
     * pending wrapper and journal.
     */
    public void commitPasswordChange(KeyRing keyRing, String password) {
        saveSecretKey(keyRing.getSymmetricKey(), KeyEntry.SYM_ENCRYPTION.getFileName(), password, false);
    }

    /**
     * Completes a committed password change: purges stale sym.key artifacts with verification,
     * takes a fresh backup and removes the pending wrapper and journal. On failure the journal
     * remains, keeping the transaction visibly pending for recovery to retry.
     */
    public void finishPasswordChange() {
        String fileName = KeyEntry.SYM_ENCRYPTION.getFileName();
        try {
            // fresh verified backup first, then purge the stale generations, so a failure can
            // never leave the committed wrapper as the only copy
            FileUtil.replaceRollingBackups(storageDir, fileName);
            FileUtil.deleteFileIfExists(legacySymFile());
            FileUtil.deleteRollingBackup(storageDir, LEGACY_SYM_FILE_NAME);
            // the deletion helpers tolerate failures, so verify the stale wrappers are gone
            if (legacySymFile().exists() || FileUtil.hasBackups(storageDir, LEGACY_SYM_FILE_NAME)) {
                throw new IOException("Could not remove stale key file backups");
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not clean up after password change", e);
        }
        clearPasswordChange();
    }

    /**
     * Removes the pending wrapper and journal. The wrapper is deleted strictly and first, so an
     * abandoned-password wrapper can never silently outlive the transaction; on failure the
     * journal is retained and the transaction stays visibly pending.
     */
    public void clearPasswordChange() {
        try {
            deleteStrict(new File(storageDir, PENDING_SYM_FILE_NAME));
            deleteStrict(new File(storageDir, PASSWORD_CHANGE_JOURNAL_FILE_NAME));
        } catch (IOException e) {
            throw new RuntimeException("Could not clear password change journal", e);
        }
    }

    private static void deleteStrict(File file) throws IOException {
        FileUtil.deleteFileIfExists(file, false);
        if (file.exists()) throw new IOException("Could not delete " + file.getName());
    }

    private static void writeNullableUtf(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) out.writeUTF(value);
    }

    private static String readNullableUtf(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
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
        File keyFile = new File(storageDir, fileName);
        File tempFile = new File(storageDir, fileName + ".tmp");
        try {
            // write to a temp file, verify the round trip, then atomically swap it in, so a failed
            // or unverified write can never replace the existing key file
            byte[] encrypted = Encryption.encryptV2(keyBytes, secretKey);
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(encrypted);
                fos.flush();
                fos.getFD().sync();
            }
            byte[] readBack = Encryption.decryptV2(Files.readAllBytes(tempFile.toPath()), secretKey);
            if (!Arrays.equals(readBack, keyBytes)) throw new IOException("Key file verification failed");
            FileUtil.atomicReplace(tempFile, keyFile);

            // replace backups, which may still hold the key in a legacy format; fresh verified
            // backup first, so a failure can never leave the key file as the only copy
            FileUtil.replaceRollingBackups(storageDir, fileName);
        } catch (Exception e) {
            log.error("Could not save key " + fileName, e);
            RuntimeException ex = new RuntimeException("Could not save key " + fileName, e);
            try {
                deleteStrict(tempFile);
            } catch (Exception e2) {
                ex.addSuppressed(e2);
            }
            throw ex;
        }
    }

    /**
     * Saves the symmetric key wrapped with a key derived from the password, then verifies the
     * write and removes legacy PKCS#12 artifacts and stale password-wrapped backups.
     *
     * @param key             The symmetric key
     * @param fileName        Filename of the key file
     * @param password        Optional password protecting the key
     * @param manageArtifacts Whether to purge legacy files and stale backups and keep a fresh one
     */
    private void saveSecretKey(SecretKey key, String fileName, String password, boolean manageArtifacts) {
        if (!storageDir.exists())
            //noinspection ResultOfMethodCallIgnored
            storageDir.mkdirs();

        boolean unprotected = password == null;
        int memKib = unprotected ? PasswordKdf.UNPROTECTED_MEM_KIB : PasswordKdf.DEFAULT_MEM_KIB;
        int iterations = unprotected ? PasswordKdf.UNPROTECTED_ITERATIONS : PasswordKdf.DEFAULT_ITERATIONS;
        int parallelism = PasswordKdf.DEFAULT_PARALLELISM;
        byte[] salt = PasswordKdf.generateSalt();
        File tempFile = new File(storageDir, fileName + ".tmp");
        try {
            SecretKey kek = Encryption.getSecretKeyFromBytes(PasswordKdf.deriveKey(password, salt, memKib, iterations, parallelism));
            byte[] blob = Encryption.encryptV2(key.getEncoded(), kek);
            ByteBuffer buf = ByteBuffer.allocate(SYM_FILE_MAGIC.length + 2 + 12 + salt.length + blob.length);
            buf.put(SYM_FILE_MAGIC).put(SYM_FILE_VERSION).put((byte) PasswordKdf.KDF_ARGON2ID)
                    .putInt(memKib).putInt(iterations).putInt(parallelism).put(salt).put(blob);

            // write to a temp file, verify the round trip, then atomically swap it in, so a failed
            // or unverified write can never replace the existing key file
            File keyFile = new File(storageDir, fileName);
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
            // passwords; the fresh verified backup is taken first, so a purge failure can never
            // leave the wrapped key as a single copy (the purge is retried on the next save)
            if (manageArtifacts) {
                FileUtil.replaceRollingBackups(storageDir, fileName);
                FileUtil.deleteFileIfExists(legacySymFile());
                FileUtil.deleteRollingBackup(storageDir, LEGACY_SYM_FILE_NAME);
                // the deletion helpers tolerate failures, so verify the stale wrappers are gone
                if (legacySymFile().exists() || FileUtil.hasBackups(storageDir, LEGACY_SYM_FILE_NAME)) {
                    throw new IOException("Could not remove stale key file backups");
                }
            }
        } catch (Exception e) {
            // the temp wraps the master key under an abandoned password; it must not linger
            RuntimeException ex = new RuntimeException("Could not save key " + fileName, e);
            try {
                deleteStrict(tempFile);
            } catch (Exception e2) {
                ex.addSuppressed(e2);
            }
            throw ex;
        }
    }

}
