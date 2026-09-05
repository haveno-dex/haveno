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

package haveno.core.api;

import static com.google.common.base.Preconditions.checkState;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.common.app.Log;
import haveno.common.UserThread;
import haveno.common.config.Config;
import haveno.common.crypto.IncorrectPasswordException;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.file.FileUtil;
import haveno.common.persistence.PersistenceManager;
import haveno.common.util.ZipUtils;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.network.TorMode;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

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
    private volatile String password;
    private volatile KeyStorage.PasswordChange passwordChange;
    public enum PasswordChangeTarget { CONNECTIONS, WALLETS }
    private final java.util.Map<PasswordChangeTarget, java.util.function.BiConsumer<String, String>> passwordChangeHandlers =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean recoveryScheduled = new AtomicBoolean();
    private List<AccountServiceListener> listeners = new ArrayList<AccountServiceListener>();

    // seed and restore height or date to import when the main wallet is first created, held in memory only
    @Getter
    @Nullable
    private String walletImportSeed;
    @Getter
    @Nullable
    private Long walletImportRestoreHeight;
    @Getter
    @Nullable
    private LocalDate walletImportRestoreDate;

    private final ReentrantLock accountLock = new ReentrantLock(); // account operations must not interleave, e.g. an open or restore during a delete
    private volatile boolean restartPending; // set by delete and restore, after which this instance serves no further account operations

    @Inject
    public CoreAccountService(Config config,
                              KeyStorage keyStorage,
                              KeyRing keyRing) {
        this.config = config;
        this.keyStorage = keyStorage;
        this.keyRing = keyRing;
    }

    public void addListener(AccountServiceListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    public boolean removeListener(AccountServiceListener listener) {
        synchronized (listeners) {
            return listeners.remove(listener);
        }
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

    private void checkNotRestarting() {
        if (restartPending) throw new IllegalStateException("Restarting after the account was deleted or restored");
    }

    private void lockAccount() {
        accountLock.lock();
        try {
            checkNotRestarting();
        } catch (IllegalStateException e) {
            accountLock.unlock();
            throw e;
        }
    }

    /** Set the seed and restore height or date to import when the main wallet is first created. */
    public void setWalletImportDetails(@Nullable String seed, @Nullable Long restoreHeight, @Nullable LocalDate restoreDate) {
        this.walletImportSeed = seed;
        this.walletImportRestoreHeight = restoreHeight;
        this.walletImportRestoreDate = restoreDate;
    }

    public void createAccount(String password) {
        lockAccount();
        try {
            if (accountExists()) throw new IllegalStateException("Cannot create account if account already exists");
            password = normalizePassword(password);
            validatePassword(password);
            keyRing.generateKeys(password);
            this.password = password;
            synchronized (listeners) {
                for (AccountServiceListener listener : new ArrayList<>(listeners)) listener.onAccountCreated();
            }
        } finally {
            accountLock.unlock();
        }
    }

    public void openAccount(String password) throws IncorrectPasswordException {
        lockAccount();
        try {
            if (!accountExists()) throw new IllegalStateException("Cannot open account if account does not exist");
            password = normalizePassword(password);
            // unlockKeys is deliberately idempotent, so explicitly verify even if already unlocked.
            var verifiedKey = keyStorage.loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, password);
            if (keyRing.isUnlocked() && !verifiedKey.equals(keyRing.getSymmetricKey())) throw new IllegalStateException("Open account master key does not match disk");
            if (keyRing.unlockKeys(password, false)) {
                this.password = password;
                loadPasswordChange();
                if (passwordChange != null) this.password = passwordChange.getNewPassword();
                schedulePasswordChangeRecovery();
                synchronized (listeners) {
                    for (AccountServiceListener listener : new ArrayList<>(listeners)) listener.onAccountOpened();
                }
            } else {
                throw new IllegalStateException("keyRing.unlockKeys() returned false, that should never happen");
            }
        } finally {
            accountLock.unlock();
        }
    }

    public void addPasswordChangeHandler(PasswordChangeTarget target, java.util.function.BiConsumer<String, String> handler) {
        passwordChangeHandlers.put(target, handler);
    }

    public void changePassword(String oldPassword, String newPassword) {
        lockAccount();
        try {
            checkAccountOpen();
            oldPassword = normalizePassword(oldPassword);
            newPassword = normalizePassword(newPassword);
            validatePassword(newPassword);
            if (!PersistenceManager.allServicesInitialized.get() || passwordChangeHandlers.size() != PasswordChangeTarget.values().length) {
                throw new IllegalStateException("Wait until account services finish initializing before changing the password");
            }
            loadPasswordChange();
            if (passwordChange != null) {
                if (!StringUtils.equals(newPassword, passwordChange.getNewPassword())
                        || (!StringUtils.equals(oldPassword, passwordChange.getOldPassword())
                        && !StringUtils.equals(oldPassword, passwordChange.getNewPassword()))) {
                    throw new IllegalStateException("An earlier password change needs recovery; keep both passwords");
                }
            } else {
                if (!StringUtils.equals(password, oldPassword)) throw new IllegalStateException("Incorrect password");
                if (StringUtils.equals(oldPassword, newPassword)) return;
                try {
                    keyStorage.beginPasswordChange(keyRing.getSymmetricKey(), oldPassword, newPassword);
                } finally {
                    // A failed directory fsync can still leave a complete prepare on disk.
                    loadPasswordChange();
                    if (passwordChange != null) password = passwordChange.getNewPassword();
                    schedulePasswordChangeRecovery();
                }
            }
            finishPasswordChange();
        } finally {
            accountLock.unlock();
        }
    }

    private void loadPasswordChange() {
        try {
            passwordChange = keyStorage.readPasswordChange(keyRing.getSymmetricKey());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read password-change recovery data; original files preserved", e);
        }
    }

    /** Legacy credentials are either entirely old-format or entirely master-key encrypted. */
    public String getPasswordForLegacyData() {
        KeyStorage.PasswordChange change = passwordChange;
        return change == null ? password : change.getOldPassword();
    }

    /** Only for trying a wallet's pre-change password before attempting any cache-file repair. */
    public String getPreviousWalletPassword() {
        KeyStorage.PasswordChange change = passwordChange;
        if (change == null) return null;
        return change.getOldPassword() == null ? "password" : change.getOldPassword();
    }

    public boolean isPasswordChangePending() {
        return keyStorage.hasPasswordChange();
    }

    public void recoverPasswordChange() {
        lockAccount();
        try {
            if (!isAccountOpen() || !keyStorage.hasPasswordChange()) return;
            if (!PersistenceManager.allServicesInitialized.get() || passwordChangeHandlers.size() != PasswordChangeTarget.values().length) {
                throw new IllegalStateException("Password recovery is waiting for account services");
            }
            loadPasswordChange();
            password = passwordChange.getNewPassword();
            finishPasswordChange();
        } finally {
            accountLock.unlock();
        }
    }

    private void finishPasswordChange() {
        KeyStorage.PasswordChange change = passwordChange;
        boolean commitAttempted = false;
        try {
            for (PasswordChangeTarget target : PasswordChangeTarget.values()) {
                passwordChangeHandlers.get(target).accept(change.getOldPassword(), change.getNewPassword());
            }
            commitAttempted = true;
            keyStorage.completePasswordChange(keyRing.getSymmetricKey());
        } catch (RuntimeException e) {
            if (!commitAttempted || keyStorage.hasPasswordChange()) {
                schedulePasswordChangeRecovery();
                throw new IllegalStateException("Password change is pending recovery. Keep both passwords; recovery resumes automatically.", e);
            }
            // Journal deletion is last, after wallets and both current wrappers are durable.
            // Failure syncing that deletion can only make recovery repeat after a crash; it
            // cannot make the old password authoritative again. Report the committed state.
            log.warn("Password changed; final recovery-journal cleanup could not be synced", e);
        }
        password = change.getNewPassword();
        passwordChange = null;
        synchronized (listeners) {
            for (AccountServiceListener listener : new ArrayList<>(listeners)) {
                try {
                    listener.onPasswordChanged(change.getOldPassword(), change.getNewPassword());
                } catch (RuntimeException e) {
                    log.warn("Password changed, but an account notification failed", e);
                }
            }
        }
    }

    private void schedulePasswordChangeRecovery() {
        if (restartPending || !keyStorage.hasPasswordChange() || !recoveryScheduled.compareAndSet(false, true)) return;
        UserThread.runAfter(() -> new Thread(() -> {
            try {
                if (!restartPending && isAccountOpen()) recoverPasswordChange();
            } catch (RuntimeException e) {
                log.warn("Password change remains pending; retaining recovery data", e);
            } finally {
                recoveryScheduled.set(false);
                if (!restartPending && isAccountOpen()) schedulePasswordChangeRecovery();
            }
        }, "PasswordChangeRecovery").start(), 30);
    }

    public void withAccountBackup(Runnable backup) {
        lockAccount();
        try {
            if (keyStorage.hasPasswordChange()) throw new IllegalStateException("Finish password-change recovery before backing up the account");
            backup.run();
        } finally {
            accountLock.unlock();
        }
    }

    private static String normalizePassword(String password) {
        return password == null || password.isEmpty() ? null : password;
    }

    private static void validatePassword(String password) {
        // Retain the account/wallet password contract. Do not normalize or silently replace characters.
        if (password != null && (password.length() < 8 || password.length() > 1024 || !password.matches("[ -~]+"))) {
            throw new IllegalArgumentException("Password must be 8 to 1024 printable ASCII characters");
        }
    }

    public void verifyPassword(String password) throws IncorrectPasswordException {
        if (!StringUtils.equals(this.password, normalizePassword(password))) {
            throw new IncorrectPasswordException("Incorrect password");
        }
    }

    public void closeAccount() {
        lockAccount();
        try {
            if (!isAccountOpen()) throw new IllegalStateException("Cannot close unopened account");
            keyRing.lockKeys();
            passwordChange = null;
            synchronized (listeners) {
                for (AccountServiceListener listener : new ArrayList<>(listeners)) listener.onAccountClosed();
            }
        } finally {
            accountLock.unlock();
        }
    }

    public void backupAccount(int bufferSize, Consumer<InputStream> consume, Consumer<Exception> error) {
        new Thread(() -> { // off the user thread, which must not block on flushing, closing wallets and the transfer
            accountLock.lock(); // one backup at a time, since flushing, closing and reopening the account must not interleave
            try {
                checkNotRestarting();
                if (!accountExists()) throw new IllegalStateException("Cannot backup non existing account");
                if (keyStorage.hasPasswordChange()) throw new IllegalStateException("Finish password-change recovery before backing up the account");

                // flush all known persistence objects to disk before locking the keys: encrypted stores
                // skip writes while the key ring is locked, which would silently back up stale files
                CountDownLatch flushed = new CountDownLatch(1);
                PersistenceManager.flushAllDataToDiskAtBackup(flushed::countDown);
                if (!flushed.await(2, TimeUnit.MINUTES)) throw new IllegalStateException("Timed out waiting for persistence to flush before backup");

                // Needed to unlock haveno_XMR.keys
                var accountWasOpen = isAccountOpen();
                if (accountWasOpen)
                    closeAccount();

                File dataDir = new File(config.appDataDir.getPath());
                PipedInputStream pipe = new PipedInputStream(bufferSize); // pipe the serialized account object to stream which will be read by the consumer
                PipedOutputStream out = new PipedOutputStream(pipe);
                log.info("Zipping directory " + dataDir);

                // exclude monero binaries so they're reinstalled with permissions and the api hidden service keys which identify this installation
                List<File> excludedFiles = Arrays.asList(
                        new File(XmrWalletService.getMoneroWalletRpcPath()),
                        new File(XmrLocalNode.getMonerodPath()),
                        TorMode.getApiHiddenServiceDir(config.torDir)
                );

                Thread zipThread = new Thread(() -> {
                    try {
                        ZipUtils.zipDirToStream(dataDir, out, bufferSize, excludedFiles);
                    } catch (Exception ex) {
                        error.accept(ex);
                    }
                });
                zipThread.start();

                // reopen the account when the consumer closes the stream, so completion is reported only once the account is usable
                AtomicBoolean reopened = new AtomicBoolean();
                InputStream in = new FilterInputStream(pipe) {
                    @Override
                    public void close() throws IOException {
                        super.close(); // unblocks the zip thread if the consumer stopped reading early
                        if (!reopened.compareAndSet(false, true)) return;
                        try {
                            zipThread.join(); // reopen only once the zip has read its last file, not concurrently with it
                            if (accountWasOpen) openAccount(password);
                        } catch (Exception e) {
                            throw new IOException("Failed to reopen account after backup", e);
                        }
                    }
                };
                try {
                    consume.accept(in);
                } finally {
                    in.close();
                }
            } catch (Exception err) {
                error.accept(err);
            } finally {
                accountLock.unlock();
            }
        }, "BackupAccount").start();
    }

    public void restoreAccount(InputStream inputStream, int bufferSize, Runnable onShutdown) throws Exception {
        lockAccount();
        try {
            if (accountExists()) throw new IllegalStateException("Cannot restore account if there is an existing account");
            File dataDir = new File(config.appDataDir.getPath());
            ZipUtils.unzipToDir(dataDir, inputStream, bufferSize, CoreAccountService::isTorRuntimeEntry);
            restartPending = true;
            synchronized (listeners) {
                for (AccountServiceListener listener : new ArrayList<>(listeners)) listener.onAccountRestored(onShutdown);
            }
        } finally {
            accountLock.unlock();
        }
    }

    // tor runtime files of any network, which a tor started before login may be using, and the api hidden service keys which identify this installation
    private static boolean isTorRuntimeEntry(String entryName) {
        return entryName.matches("(?i)[^/]+/tor/(?!hiddenservice/).*") || entryName.matches("(?i)[^/]+/tor/hiddenservice/" + TorMode.API_HIDDEN_SERVICE_NAME + "/.*"); // case-insensitive for case-insensitive filesystems
    }

    public void deleteAccount(Runnable onShutdown) {
        lockAccount();
        try {
            try {
                if (isAccountOpen()) closeAccount();

                // Log files are locked on Windows so we need to release them. Logging resumes on automatic restart
                Log.stopFileLogging();

                File dataDir = new File(config.appDataDir.getPath()); // TODO (woodser): deleting directory after gracefulShutdown() so services don't throw when they try to persist (e.g. XmrTxProofService), but gracefulShutdown() should honor read-only shutdown
                FileUtil.deleteDirectory(dataDir, TorMode.getApiHiddenServiceDir(config.torDir), false); // keep the api onion so remote clients can reconnect
            } catch (Exception err) {
                throw new RuntimeException(err);
            } finally {

                // restart after deleting so the reply to a remote client is not raced by the shutdown, even if deleting failed partway
                restartPending = true;
                synchronized (listeners) {
                    for (AccountServiceListener listener : new ArrayList<>(listeners)) listener.onAccountDeleted(onShutdown);
                }
            }
        } finally {
            accountLock.unlock();
        }
    }
}
