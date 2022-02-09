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

import javax.inject.Inject;
import javax.inject.Singleton;

import java.security.KeyPair;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

@Getter
@EqualsAndHashCode
@Slf4j
@Singleton
public final class KeyRing {

    private final KeyStorage keyStorage;

    private KeyPair signatureKeyPair;
    private KeyPair encryptionKeyPair;
    private PubKeyRing pubKeyRing;

    /**
     * Creates the KeyRing. Unlocks if not encrypted. Does not generate keys.
     * 
     * @param keyStorage Persisted storage
     */
    @Inject
    public KeyRing(KeyStorage keyStorage) {
        this(keyStorage, null, false);
    }

    /**
     * Creates KeyRing with a password. Attempts to generate keys if they don't exist.
     * 
     * @param keyStorage Persisted storage
     * @param password The password to unlock the keys or to generate new keys, nullable.
     * @param generateKeys Generate new keys with password if not created yet.
     */
    public KeyRing(KeyStorage keyStorage, String password, boolean generateKeys) {
        this.keyStorage = keyStorage;
        try {
            unlockKeys(password, generateKeys);
        } catch(IncorrectPasswordException ex) {
            // no action
        }
    }

    public boolean isUnlocked() {
        boolean isUnlocked = this.signatureKeyPair != null
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
            signatureKeyPair = keyStorage.loadKeyPair(KeyStorage.KeyEntry.MSG_SIGNATURE, password);
            encryptionKeyPair = keyStorage.loadKeyPair(KeyStorage.KeyEntry.MSG_ENCRYPTION, password);
            if (signatureKeyPair != null && encryptionKeyPair != null) pubKeyRing = new PubKeyRing(signatureKeyPair.getPublic(), encryptionKeyPair.getPublic());
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
        if (isUnlocked()) throw new Error("Current keyring must be closed to generate new keys");
        signatureKeyPair = Sig.generateKeyPair();
        encryptionKeyPair = Encryption.generateKeyPair();
        pubKeyRing = new PubKeyRing(signatureKeyPair.getPublic(), encryptionKeyPair.getPublic());
        keyStorage.saveKeyRing(this, password);
    }

    // Don't print keys for security reasons
    @Override
    public String toString() {
        return "KeyRing{" +
                "signatureKeyPair.hashCode()=" + signatureKeyPair.hashCode() +
                ", encryptionKeyPair.hashCode()=" + encryptionKeyPair.hashCode() +
                ", pubKeyRing.hashCode()=" + pubKeyRing.hashCode() +
                '}';
    }
}
