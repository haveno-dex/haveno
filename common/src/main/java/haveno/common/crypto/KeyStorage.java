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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        setStoragePermissions();
    }

    private void setStoragePermissions() {
        FileUtil.setOwnerOnlyPermissions(storageDir.toPath());
        for (KeyEntry keyEntry : KeyEntry.values()) {
            Path path = storageDir.toPath().resolve(keyEntry.getFileName());
            if (Files.exists(path)) FileUtil.setOwnerOnlyPermissions(path);
        }
    }

    public boolean allKeyFilesExist() {
        return fileExists(KeyEntry.MSG_SIGNATURE) && fileExists(KeyEntry.MSG_ENCRYPTION) && fileExists(KeyEntry.SYM_ENCRYPTION);
    }

    public boolean hasAccountFiles() {
        for (String name : new String[] {"sig.key", "enc.key", "sym.p12", "backup"}) {
            if (new File(storageDir, name).exists()) return true;
        }
        return false;
    }

    public void checkKeyFiles() {
        if (!allKeyFilesExist()) throw new IllegalStateException("Account key files are incomplete. Restore a complete backup; existing files have been preserved.");
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
        return loadSecretKey(new File(storageDir, keyEntry.getFileName()).toPath(), password);
    }

    // verify without creating or pruning backups
    public void verifyPassword(SecretKey expected, String password) throws IncorrectPasswordException {
        if (!expected.equals(loadSecretKey(KeyEntry.SYM_ENCRYPTION, password))) {
            throw new IllegalStateException("Account master key does not match the key on disk");
        }
    }

    private SecretKey loadSecretKey(Path path, String password) throws IncorrectPasswordException {
        char[] passwordChars = password == null ? new char[0] : password.toCharArray();
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");

            try (FileInputStream fileInputStream = new FileInputStream(path.toFile())) {
                keyStore.load(fileInputStream, passwordChars);
            }

            Key key = keyStore.getKey(KeyEntry.SYM_ENCRYPTION.getAlias(), passwordChars);
            return (SecretKey) key;
        } catch (UnrecoverableKeyException e) { // null password when password is required
            throw new IncorrectPasswordException("Incorrect password");
        } catch (IOException e) { // incorrect password
            if (e.getCause() instanceof UnrecoverableKeyException) {
                throw new IncorrectPasswordException("Incorrect password");
            } else {
                log.error("Could not load key " + path.getFileName(), e);
                throw new RuntimeException("Could not load key " + path.getFileName(), e);
            }
        } catch (Exception e) {
            log.error("Could not load key " + path.getFileName(), e);
            throw new RuntimeException("Could not load key " + path.getFileName(), e);
        } finally {
            Arrays.fill(passwordChars, '\0');
        }
    }

    /**
     * Saves the key ring to the key storage directory.
     *
     * @param keyRing  The key ring
     * @param password Optional password
     */
    public void saveKeyRing(KeyRing keyRing, String oldPassword, String password) {
        if (!storageDir.exists())
            //noinspection ResultOfMethodCallIgnored
            storageDir.mkdirs();
        setStoragePermissions();

        SecretKey symmetric = keyRing.getSymmetricKey();

        // password protect the symmetric key
        saveKey(symmetric, KeyEntry.SYM_ENCRYPTION.getAlias(), KeyEntry.SYM_ENCRYPTION.getFileName(), oldPassword, password);

        // use symmetric encryption to encrypt the key pairs
        saveKey(keyRing.getSignatureKeyPair().getPrivate(), KeyEntry.MSG_SIGNATURE.getFileName(), symmetric);
        saveKey(keyRing.getEncryptionKeyPair().getPrivate(), KeyEntry.MSG_ENCRYPTION.getFileName(), symmetric);
    }

    // validate both passwords and serialize the replacement before any wallet is changed
    public byte[] preparePasswordChange(String oldPassword, String newPassword) {
        if (newPassword != null && newPassword.codePoints().anyMatch(cp -> cp > 127)) {
            throw new IllegalArgumentException("Password must be ASCII.");
        }
        char[] oldChars = oldPassword == null ? new char[0] : oldPassword.toCharArray();
        char[] newChars = newPassword == null ? new char[0] : newPassword.toCharArray();
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (FileInputStream in = new FileInputStream(new File(storageDir, KeyEntry.SYM_ENCRYPTION.getFileName()))) {
                keyStore.load(in, oldChars);
            }
            String alias = KeyEntry.SYM_ENCRYPTION.getAlias();
            Key key = keyStore.getKey(alias, oldChars);
            keyStore.setKeyEntry(alias, key, newChars, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            keyStore.store(out, newChars);
            backupAccountKey(); // preserve a durable copy before any wallet password is changed
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not prepare account password change", e);
        } finally {
            Arrays.fill(oldChars, '\0');
            Arrays.fill(newChars, '\0');
        }
    }

    // only the symmetric key's wrapping password changes; the private key files remain valid
    public void commitPasswordChange(byte[] keyStore) {
        try {
            FileUtil.writeAtomically(new File(storageDir, KeyEntry.SYM_ENCRYPTION.getFileName()).toPath(), keyStore);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save account key", e);
        }
    }

    // keep a current backup before removing readable copies protected by superseded passwords
    public void finishPasswordChange(KeyRing keyRing, String password, List<String> candidates) {
        String fileName = KeyEntry.SYM_ENCRYPTION.getFileName();
        List<String> backupPasswords = new ArrayList<>(candidates);
        if (!backupPasswords.contains(null)) backupPasswords.add(null); // older builds may have left an unprotected wrapper
        backupPasswords.removeIf(candidate -> Objects.equals(candidate, password));
        try {
            Path current = storageDir.toPath().resolve(fileName);
            SecretKey expected = keyRing.getSymmetricKey();
            if (!matchesKey(current, password, expected)) throw new IOException("Current account key could not be verified");
            FileUtil.syncFileAndDirectory(current);
            Path saved = backupAccountKey();
            if (!matchesKey(saved, password, expected)) throw new IOException("Account-key backup could not be verified");

            // remove only superseded wrappers of this same key, preserving foreign or unreadable backups
            List<Path> currentBackups = new ArrayList<>();
            for (File backup : FileUtil.getBackupFiles(storageDir, fileName)) {
                if (!Files.isRegularFile(backup.toPath(), LinkOption.NOFOLLOW_LINKS) || backup.toPath().equals(saved)) continue;
                if (matchesKey(backup.toPath(), password, expected)) {
                    currentBackups.add(backup.toPath());
                    continue;
                }
                for (String candidate : backupPasswords) {
                    if (matchesKey(backup.toPath(), candidate, expected)) {
                        Files.delete(backup.toPath());
                        break;
                    }
                }
            }
            // prune only verified copies after a successful unlock or change and a flushed replacement backup
            currentBackups.sort(Comparator.comparing(path -> path.getFileName().toString()));
            for (int i = 0; i < currentBackups.size() - 19; i++) Files.delete(currentBackups.get(i));
            List<String> tempPasswords = new ArrayList<>(backupPasswords);
            tempPasswords.add(password);
            for (Path temp : getTemporaryAccountKeys()) {
                for (String candidate : tempPasswords) {
                    if (matchesKey(temp, candidate, expected)) {
                        Files.delete(temp);
                        break;
                    }
                }
            }
            FileUtil.syncDirectory(saved.getParent());
            FileUtil.syncDirectory(storageDir.toPath());
        } catch (IOException e) {
            throw new IllegalStateException("Could not update account-key backups after the password change", e);
        }
    }

    private Path backupAccountKey() throws IOException {
        Path backups = Files.createDirectories(storageDir.toPath().resolve("backup/backups_sym_p12"));
        Path saved = backups.resolve(System.currentTimeMillis() + "_" + UUID.randomUUID() + "_sym.p12");
        FileUtil.writeAtomically(saved, Files.readAllBytes(storageDir.toPath().resolve("sym.p12")));
        FileUtil.syncDirectory(backups.getParent());
        FileUtil.syncDirectory(storageDir.toPath());
        return saved;
    }

    private List<Path> getTemporaryAccountKeys() throws IOException {
        try (var files = Files.list(storageDir.toPath())) {
            return files.filter(path -> path.getFileName().toString().startsWith(".haveno-write-")
                            || path.getFileName().toString().startsWith(".sym.p12.haveno-write-"))
                    .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList();
        }
    }

    public void cleanupAfterUnlock(KeyRing keyRing, String password) {
        try {
            finishPasswordChange(keyRing, password, Arrays.asList(password, null));
            // refresh private-key backups only after both keys have been authenticated successfully
            for (KeyEntry entry : List.of(KeyEntry.MSG_SIGNATURE, KeyEntry.MSG_ENCRYPTION)) {
                FileUtil.syncFileAndDirectory(new File(storageDir, entry.getFileName()).toPath());
                if (!FileUtil.rollingBackup(storageDir, entry.getFileName(), 20)) throw new IOException("Could not back up " + entry.getFileName());
            }
        } catch (Exception e) {
            log.warn("Could not refresh account-key backups", e);
        }
    }

    private boolean matchesKey(Path path, String password, SecretKey expected) {
        try {
            return expected.equals(loadSecretKey(path, password));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Saves private key in PKCS#8 to a file and encrypts using the symmetric key.
     *
     * @param key       The key pair
     * @param fileName  File name to save
     * @param secretKey Secret key to encrypt the key pair
     */
    private void saveKey(PrivateKey key, String fileName, SecretKey secretKey) {
        PKCS8EncodedKeySpec pkcs8EncodedKeySpec = new PKCS8EncodedKeySpec(key.getEncoded());
        byte[] keyBytes = pkcs8EncodedKeySpec.getEncoded();
        try (FileOutputStream fos = new FileOutputStream(storageDir + "/" + fileName)) {
            FileUtil.setOwnerOnlyPermissions(storageDir.toPath().resolve(fileName));
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
        // password must be ascii
        if (password != null && !password.matches("\\p{ASCII}*")) {
            throw new IllegalArgumentException("Password must be ASCII.");
        }

        var oldPasswordChars = oldPassword == null ? new char[0] : oldPassword.toCharArray();
        var passwordChars = password == null ? new char[0] : password.toCharArray();
        try {
            var path = storageDir + "/" + fileName;
            KeyStore keyStore = KeyStore.getInstance("PKCS12");

            // load from existing file or initialize new
            if (Files.exists(Path.of(path))) {
                try (FileInputStream fileInputStream = new FileInputStream(path)) {
                    keyStore.load(fileInputStream, oldPasswordChars);
                }
            }
            else {
                keyStore.load(null, null);
            }

            // store in the keystore
            keyStore.setKeyEntry(alias, key, passwordChars, null);

            try (FileOutputStream fileOutputStream = new FileOutputStream(path)) {
                FileUtil.setOwnerOnlyPermissions(Path.of(path));
                // save the keystore
                keyStore.store(fileOutputStream, passwordChars);
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not save key " + alias, e);
        }
    }
}
