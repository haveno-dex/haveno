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
import haveno.common.persistence.PlaintextMigration;
import java.io.File;
import java.security.KeyPair;
import javax.annotation.Nullable;
import javax.crypto.SecretKey;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Getter
@EqualsAndHashCode
@Slf4j
@Singleton
public final class KeyRing {

    private final KeyStorage keyStorage;
    // Persisted-store dir, used to record unmigrated plaintext stores before key migration.
    @Nullable
    private final File storageDir;

    private SecretKey symmetricKey;
    private KeyPair signatureKeyPair;
    private KeyPair encryptionKeyPair;
    private PubKeyRing pubKeyRing;

    /**
     * Creates the KeyRing. Unlocks if not encrypted. Does not generate keys.
     *
     * @param keyStorage Persisted storage
     * @param storageDir Directory of the persisted stores
     */
    @Inject
    public KeyRing(KeyStorage keyStorage, @Named(Config.STORAGE_DIR) File storageDir) {
        this(keyStorage, storageDir, null, false);
    }

    public KeyRing(KeyStorage keyStorage) {
        this(keyStorage, null, null, false);
    }

    /**
     * Creates KeyRing with a password. Attempts to generate keys if they don't exist.
     *
     * @param keyStorage Persisted storage
     * @param password The password to unlock the keys or to generate new keys, nullable.
     * @param generateKeys Generate new keys with password if not created yet.
     */
    public KeyRing(KeyStorage keyStorage, String password, boolean generateKeys) {
        this(keyStorage, null, password, generateKeys);
    }

    public KeyRing(KeyStorage keyStorage, @Nullable File storageDir, String password, boolean generateKeys) {
        this.keyStorage = keyStorage;
        this.storageDir = storageDir;
        try {
            unlockKeys(password, generateKeys);
        } catch(IncorrectPasswordException ex) {
            // no action
        }
    }

    public boolean isUnlocked() {
        boolean isUnlocked = this.symmetricKey != null
                && this.signatureKeyPair != null
                && this.encryptionKeyPair != null
                && this.pubKeyRing != null;
        return isUnlocked;
    }

    /**
     * Locks the keyring disabling access to the keys until unlock is called.
     * If the keys are never persisted then the keys are lost and will be regenerated.
     */
    public void lockKeys() {
        signatureKeyPair = null;
        encryptionKeyPair = null;
        symmetricKey = null;
        pubKeyRing = null;
    }

    /**
     * Unlocks the keyring with a given password if required. If the keyring is already
     * unlocked, do nothing.
     *
     * @param password Decrypts the or encrypts newly generated keys with the given password.
     * @return Whether KeyRing is unlocked
     */
    public boolean unlockKeys(@Nullable String password, boolean generateKeys) throws IncorrectPasswordException {
        if (isUnlocked()) return true;
        if (keyStorage.allKeyFilesExist()) {
            symmetricKey = keyStorage.loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, password);
            signatureKeyPair = keyStorage.loadKeyPair(KeyStorage.KeyEntry.MSG_SIGNATURE, symmetricKey);
            encryptionKeyPair = keyStorage.loadKeyPair(KeyStorage.KeyEntry.MSG_ENCRYPTION, symmetricKey);
            if (signatureKeyPair != null && encryptionKeyPair != null) pubKeyRing = new PubKeyRing(signatureKeyPair.getPublic(), encryptionKeyPair.getPublic());
            if (isUnlocked() && keyStorage.needsFormatUpgrade()) {
                try {
                    // encrypt all plaintext stores before replacing the legacy key files, whose
                    // presence is what authorizes reading plaintext (see PersistenceManager)
                    if (storageDir != null) PlaintextMigration.migrate(storageDir, symmetricKey);
                    keyStorage.saveKeyRing(this, password);
                } catch (Exception e) {
                    log.error("Failed to upgrade key storage format, will retry on next unlock", e);
                }
            }
        } else if (generateKeys) {
            generateKeys(password);
        }
        return isUnlocked();
    }

    /**
     * Generates a new set of keys if the current keyring is closed.
     *
     * @param password The password to unlock the keys or to generate new keys, nullable.
     */
    public void generateKeys(String password) {
        if (isUnlocked()) throw new IllegalStateException("Current keyring must be closed to generate new keys");
        try {
            // key material left by a previous (unopenable) account may be its only remaining key
            // copy; move it out of the way so the new account's saves can never purge it
            keyStorage.preserveLostAccountKeys();
        } catch (Exception e) {
            throw new RuntimeException("Could not preserve a previous account's key files", e);
        }
        symmetricKey = Encryption.generateSecretKey(256);
        signatureKeyPair = Sig.generateKeyPair();
        encryptionKeyPair = Encryption.generateKeyPair();
        pubKeyRing = new PubKeyRing(signatureKeyPair.getPublic(), encryptionKeyPair.getPublic());
        keyStorage.saveKeyRing(this, password);
    }

    // Don't print keys for security reasons
    @Override
    public String toString() {
        return "KeyRing{" +
                "symmetricKey.hashCode()=" + symmetricKey.hashCode() +
                ", signatureKeyPair.hashCode()=" + signatureKeyPair.hashCode() +
                ", encryptionKeyPair.hashCode()=" + encryptionKeyPair.hashCode() +
                ", pubKeyRing.hashCode()=" + pubKeyRing.hashCode() +
                '}';
    }
}
