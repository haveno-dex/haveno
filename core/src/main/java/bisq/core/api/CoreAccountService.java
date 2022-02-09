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

package bisq.core.api;

import static com.google.common.base.Preconditions.checkState;

import bisq.common.config.Config;
import bisq.common.crypto.IncorrectPasswordException;
import bisq.common.crypto.KeyRing;
import bisq.common.crypto.KeyStorage;
import bisq.common.file.FileUtil;
import bisq.common.persistence.PersistenceManager;
import bisq.common.util.ZipUtils;
import java.io.File;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages the account state. A created account must have a password which encrypts
 * all persistence in the PersistenceManager. As a result, opening the account requires
 * a correct password to be passed in to deserialize the account properties that are
 * persisted. It is possible to persist the objects without a password (legacy).
 *
 * Backup and restore flushes the persistence objects in the app folder and sends or
 * restores a zip stream.
 */
@Singleton
@Slf4j
public class CoreAccountService {
    
    private final Config config;
    private final KeyStorage keyStorage;
    private final KeyRing keyRing;
    
    @Getter
    private String password;
    private List<AccountServiceListener> listeners = new ArrayList<AccountServiceListener>();
    
    @Inject
    public CoreAccountService(Config config,
                              KeyStorage keyStorage,
                              KeyRing keyRing) {
        this.config = config;
        this.keyStorage = keyStorage;
        this.keyRing = keyRing;
    }
    
    public void addListener(AccountServiceListener listener) {
        listeners.add(listener);
    }
    
    public boolean removeListener(AccountServiceListener listener) {
        return listeners.remove(listener);
    }
    
    public boolean accountExists() {
        return keyStorage.allKeyFilesExist(); // public and private key pair indicate the existence of the account
    }
    
    public boolean isAccountOpen() {
        return keyRing.isUnlocked() && accountExists();
    }
    
    public void checkAccountOpen() {
        checkState(isAccountOpen(), "Account not open");
    }
    
    public void createAccount(String password) {
        if (accountExists()) throw new IllegalStateException("Cannot create account if account already exists");
        keyRing.generateKeys(password);
        this.password = password;
        for (AccountServiceListener listener : listeners) listener.onAccountCreated();
    }
    
    public void openAccount(String password) throws IncorrectPasswordException {
        if (!accountExists()) throw new IllegalStateException("Cannot open account if account does not exist");
        if (keyRing.unlockKeys(password, false)) {
            this.password = password;
            for (AccountServiceListener listener : listeners) listener.onAccountOpened();
        } else {
            throw new IllegalStateException("keyRing.unlockKeys() returned false, that should never happen");
        }
    }
    
    public void changePassword(String password) {
        if (!isAccountOpen()) throw new IllegalStateException("Cannot change password on unopened account");
        keyStorage.saveKeyRing(keyRing, password);
        String oldPassword = this.password;
        this.password = password;
        for (AccountServiceListener listener : listeners) listener.onPasswordChanged(oldPassword, password);
    }
    
    public void closeAccount() {
        if (!isAccountOpen()) throw new IllegalStateException("Cannot close unopened account");
        keyRing.lockKeys(); // closed account means the keys are locked
        for (AccountServiceListener listener : listeners) listener.onAccountClosed();
    }
    
    public void backupAccount(int bufferSize, Consumer<InputStream> consume, Consumer<Exception> error) {
        if (!accountExists()) throw new IllegalStateException("Cannot backup non existing account");

        // flush all known persistence objects to disk
        PersistenceManager.flushAllDataToDiskAtBackup(() -> {
            try {
                File dataDir = new File(config.appDataDir.getPath());
                PipedInputStream in = new PipedInputStream(bufferSize); // pipe the serialized account object to stream which will be read by the consumer
                PipedOutputStream out = new PipedOutputStream(in);
                log.info("Zipping directory " + dataDir);
                new Thread(() -> {
                    try {
                        ZipUtils.zipDirToStream(dataDir, out, bufferSize);
                    } catch (Exception ex) {
                        error.accept(ex);
                    }
                }).start();
                consume.accept(in);
            } catch (java.io.IOException err) {
                error.accept(err);
            }
        });
    }
    
    public void restoreAccount(InputStream inputStream, int bufferSize, Runnable onShutdown) throws Exception {
        if (accountExists()) throw new IllegalStateException("Cannot restore account if there is an existing account");
        File dataDir = new File(config.appDataDir.getPath());
        ZipUtils.unzipToDir(dataDir, inputStream, bufferSize);
        for (AccountServiceListener listener : listeners) listener.onAccountRestored(onShutdown);
    }
    
    public void deleteAccount(Runnable onShutdown) {
        try {
            keyRing.lockKeys();
            for (AccountServiceListener listener : listeners) listener.onAccountDeleted(onShutdown);
            File dataDir = new File(config.appDataDir.getPath()); // TODO (woodser): deleting directory after gracefulShutdown() so services don't throw when they try to persist (e.g. XmrTxProofService), but gracefulShutdown() should honor read-only shutdown
            FileUtil.deleteDirectory(dataDir, null, false);
        } catch (Exception err) {
            throw new RuntimeException(err);
        }
    }
}
