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
import haveno.common.ThreadUtils;
import haveno.common.UserThread;
import haveno.common.app.Log;
import haveno.common.config.Config;
import haveno.common.crypto.IncorrectPasswordException;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.file.FileUtil;
import haveno.common.persistence.PersistenceManager;
import haveno.common.util.ZipUtils;
import haveno.core.xmr.wallet.XmrWalletService;
import java.io.File;
import java.io.InputStream;
import java.time.LocalDate;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private List<AccountServiceListener> listeners = new ArrayList<AccountServiceListener>();
    private final Object passwordChangeLock = new Object();
    private volatile boolean backupInProgress;
    // Gate between wallet creation and the password change transaction: each side fails fast
    // while the other is active, so no wallet file can appear with a password the transaction's
    // rotation scans will not see.
    private final Object walletCreationLock = new Object();
    private int walletCreationCount; // guarded by walletCreationLock
    private boolean passwordChangeInProgress; // guarded by walletCreationLock

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

    /** Set the seed and restore height or date to import when the main wallet is first created. */
    public void setWalletImportDetails(@Nullable String seed, @Nullable Long restoreHeight, @Nullable LocalDate restoreDate) {
        this.walletImportSeed = seed;
        this.walletImportRestoreHeight = restoreHeight;
        this.walletImportRestoreDate = restoreDate;
    }

    public void createAccount(String password) {
        if (accountExists()) throw new IllegalStateException("Cannot create account if account already exists");
        password = normalizePassword(password);
        validatePassword(password);
        keyRing.generateKeys(password);
        this.password = password;
        synchronized (listeners) {
            for (AccountServiceListener listener : new ArrayList<>(listeners)) listener.onAccountCreated();
        }
    }

    public void openAccount(String password) throws IncorrectPasswordException {
        if (!accountExists()) throw new IllegalStateException("Cannot open account if account does not exist");
        password = normalizePassword(password);
        if (keyRing.unlockKeys(password, false)) {
            this.password = password;
            synchronized (listeners) {
                for (AccountServiceListener listener : new ArrayList<>(listeners)) listener.onAccountOpened();
            }
            maybeRecoverPasswordChange();
        } else {
            throw new IllegalStateException("keyRing.unlockKeys() returned false, that should never happen");
        }
    }

    public void changePassword(String oldPassword, String newPassword) {
        synchronized (passwordChangeLock) {
            if (!isAccountOpen()) throw new IllegalStateException("Cannot change password on unopened account");
            // the transaction converges wallets and credentials through listeners, which only all
            // exist once services are initialized; committing earlier would leave components on
            // the old password with no journal left to heal them
            if (!PersistenceManager.allServicesInitialized.get()) throw new IllegalStateException("Cannot change password until the application is fully initialized");
            oldPassword = normalizePassword(oldPassword);
            newPassword = normalizePassword(newPassword);
            if ("".equals(oldPassword)) oldPassword = null; // normalize to null
            if (!StringUtils.equals(this.password, oldPassword)) throw new IllegalStateException("Incorrect password");
            if (newPassword != null && newPassword.length() < 8) throw new IllegalStateException("Password must be at least 8 characters");
            validatePassword(newPassword); // validate before any durable mutation
            if (StringUtils.equals(oldPassword, newPassword)) return;
            if (keyStorage.hasPasswordChangeJournal()) throw new IllegalStateException("A previous password change is incomplete; reopen the account to recover it first");
            if (backupInProgress) throw new IllegalStateException("Cannot change password while an account backup is in progress");

            // refuse wallet creation for the whole transaction, so every wallet file is visible
            // to the rotation scans and none is created with a password about to be retired
            blockWalletCreation();
            try {
                // durably journal both passwords and add a second sym.key wrapper under the new
                // password, so a crash at any point leaves the account unlockable with either
                keyStorage.beginPasswordChange(keyRing, oldPassword, newPassword);

                // change wallet and credential passwords; handlers are idempotent so a partial
                // failure can be converged back to the old password
                try {
                    notifyPasswordChanged(oldPassword, newPassword);
                } catch (RuntimeException e) {
                    try {
                        notifyPasswordChanged(newPassword, oldPassword); // revert
                        keyStorage.clearPasswordChange();
                    } catch (RuntimeException e2) {
                        log.error("Could not revert partial password change; it will be recovered when the account is reopened", e2);
                        e.addSuppressed(e2);
                    }
                    throw e;
                }

                // all components confirmed on the new password: rewrap sym.key (the commit point).
                // a failed commit leaves sym.key on the old password, so roll the components back
                try {
                    keyStorage.commitPasswordChange(keyRing, newPassword);
                } catch (RuntimeException e) {
                    try {
                        notifyPasswordChanged(newPassword, oldPassword); // revert
                        keyStorage.clearPasswordChange();
                    } catch (RuntimeException e2) {
                        log.error("Could not revert partial password change; it will be recovered when the account is reopened", e2);
                        e.addSuppressed(e2);
                    }
                    throw e;
                }
                this.password = newPassword;
            } finally {
                unblockWalletCreation();
            }

            // past the commit point a cleanup failure must not report a failed change; the
            // journal stays pending and cleanup is retried in the background until it succeeds,
            // since a surviving backup still unwraps the master key with the old password
            try {
                keyStorage.finishPasswordChange();
            } catch (RuntimeException e) {
                log.error("Password change committed but cleanup is incomplete; retrying in the background", e);
                scheduleFinishPasswordChangeRetry(1);
            }
        }
    }

    /**
     * Registers a wallet creation, failing while a password change transaction is active. The
     * caller must read the account password after this returns and call
     * {@link #endWalletCreation()} once the wallet exists on disk.
     */
    public void beginWalletCreation() {
        synchronized (walletCreationLock) {
            if (passwordChangeInProgress) throw new IllegalStateException("Cannot create a wallet while a password change is in progress; please retry shortly");
            walletCreationCount++;
        }
    }

    public void endWalletCreation() {
        synchronized (walletCreationLock) {
            walletCreationCount--;
        }
    }

    /** Whether a password change transaction is active, during which wallets may be rotated ahead of the account password. */
    public boolean isPasswordChangeInProgress() {
        synchronized (walletCreationLock) {
            return passwordChangeInProgress;
        }
    }

    // Raises the creation gate for the transaction, refusing if a creation is in flight; either
    // side fails fast, so no waiting and no lock cycle is possible.
    private void blockWalletCreation() {
        synchronized (walletCreationLock) {
            if (walletCreationCount > 0) throw new IllegalStateException("Cannot change password while a wallet is being created; please retry shortly");
            passwordChangeInProgress = true;
        }
    }

    private void unblockWalletCreation() {
        synchronized (walletCreationLock) {
            passwordChangeInProgress = false;
        }
    }

    // Retries cleanup of a committed password change with capped backoff, so old-password key
    // wrappers do not silently persist until the account happens to be reopened.
    private void scheduleFinishPasswordChangeRetry(int attempt) {
        UserThread.runAfter(() -> ThreadUtils.submitToPool(() -> {
            synchronized (passwordChangeLock) {
                if (!isAccountOpen() || !keyStorage.hasPasswordChangeJournal()) return;
                if (backupInProgress) {
                    scheduleFinishPasswordChangeRetry(attempt + 1);
                    return;
                }
                try {
                    keyStorage.finishPasswordChange();
                    log.info("Completed deferred password change cleanup");
                } catch (RuntimeException e) {
                    log.error("Password change cleanup failed again; retrying", e);
                    scheduleFinishPasswordChangeRetry(attempt + 1);
                }
            }
        }), Math.min(10L * attempt, 300L));
    }

    /**
     * The counterpart password of an interrupted password change (may be null for no password),
     * wrapped in a single-element array, or null if no change is pending. Wallets may still be
     * on the counterpart until recovery converges them.
     */
    public String[] getPendingPasswordChangeCounterpart() {
        if (!keyStorage.hasPasswordChangeJournal() || !keyRing.isUnlocked()) return null;
        String[] change = keyStorage.readPasswordChangeJournal(keyRing.getSymmetricKey());
        if (change == null) return null;
        return new String[]{StringUtils.equals(this.password, change[1]) ? change[0] : change[1]};
    }

    // Completes an interrupted password change by converging every component to the password the
    // user just proved, then committing it. Deferred until all services are initialized, so the
    // stores have been read and wallets are serviceable; until then wallets on the counterpart
    // password are healed on open (see XmrWalletService).
    private void maybeRecoverPasswordChange() {
        if (!keyStorage.hasPasswordChangeJournal()) return;
        runAfterAllServicesInitialized(() -> ThreadUtils.submitToPool(this::recoverPasswordChange));
    }

    private void runAfterAllServicesInitialized(Runnable runnable) {
        if (PersistenceManager.allServicesInitialized.get()) runnable.run();
        else UserThread.runAfter(() -> runAfterAllServicesInitialized(runnable), 1);
    }

    private void recoverPasswordChange() {
        synchronized (passwordChangeLock) {
            if (!isAccountOpen() || !keyStorage.hasPasswordChangeJournal()) return;
            String[] change = keyStorage.readPasswordChangeJournal(keyRing.getSymmetricKey());
            if (change == null) {
                // never delete the wrappers here: the pending wrapper may be the only artifact
                // matching the entered password, so require manual intervention instead
                log.error("Password change journal is unreadable; keeping both key wrappers. " +
                        "Wallets that fail to open may require the counterpart password.");
                return;
            }
            String other = StringUtils.equals(this.password, change[1]) ? change[0] : change[1];
            log.warn("Recovering interrupted password change by converging to the entered password");
            try {
                notifyPasswordChanged(other, this.password);
                keyStorage.commitPasswordChange(keyRing, this.password);
            } catch (RuntimeException e) {
                log.error("Password change recovery failed; will retry when the account is reopened", e);
                return;
            }
            try {
                keyStorage.finishPasswordChange();
                log.info("Recovered interrupted password change");
            } catch (RuntimeException e) {
                log.error("Recovered password change but cleanup is incomplete; retrying in the background", e);
                scheduleFinishPasswordChangeRetry(1);
            }
        }
    }

    private static void validatePassword(String password) {
        if (password != null && password.chars().anyMatch(Character::isISOControl)) throw new IllegalStateException("Password must not contain control characters");
    }

    // The canonical password form, applied before any comparison, derivation or durable mutation,
    // so key wrapping, wallets, credentials and the journal all see the same code points.
    private static String normalizePassword(String password) {
        return password == null ? null : Normalizer.normalize(password, Normalizer.Form.NFC);
    }

    private void notifyPasswordChanged(String oldPassword, String newPassword) {
        synchronized (listeners) {
            for (AccountServiceListener listener : new ArrayList<>(listeners)) listener.onPasswordChanged(oldPassword, newPassword);
        }
    }

    public void verifyPassword(String password) throws IncorrectPasswordException {
        if (!StringUtils.equals(this.password, normalizePassword(password))) {
            throw new IncorrectPasswordException("Incorrect password");
        }
    }

    public void closeAccount() {
        if (!isAccountOpen()) throw new IllegalStateException("Cannot close unopened account");
        keyRing.lockKeys(); // closed account means the keys are locked
        synchronized (listeners) {
            for (AccountServiceListener listener : new ArrayList<>(listeners)) listener.onAccountClosed();
        }
    }

    /**
     * Acquires the exclusive backup guard for any backup of the data directory (API stream or
     * desktop copy), refusing while a password change is pending or another backup runs; a backup
     * must not archive the password change journal, pending wrapper or old-password key backups.
     * The owner must call {@link #endBackup()} when done.
     */
    public void beginBackup() {
        synchronized (passwordChangeLock) {
            // any surviving transaction artifact (journal, pending wrapper or their temps) can
            // hold key material or both passwords and must never be archived
            if (keyStorage.hasPasswordChangeArtifacts()) throw new IllegalStateException("Cannot backup account while a password change is pending or incomplete");
            if (backupInProgress) throw new IllegalStateException("Another account backup is in progress");
            backupInProgress = true;
        }
    }

    public void endBackup() {
        backupInProgress = false;
    }

    // TODO: share common code with BackupView to backup
    public void backupAccount(int bufferSize, Consumer<InputStream> consume, Consumer<Exception> error) {
        if (!accountExists()) throw new IllegalStateException("Cannot backup non existing account");
        beginBackup();

        var accountWasOpen = isAccountOpen();
        var producerStarted = new AtomicBoolean();

        // flush all known persistence objects to disk before locking the keys: encrypted stores
        // skip writes while the key ring is locked, which would silently back up stale files
        PersistenceManager.flushAllDataToDiskAtBackup(flushError -> {
            if (flushError != null) {
                // stale or inconsistent stores must not be backed up
                endBackup();
                error.accept(flushError instanceof Exception ? (Exception) flushError : new RuntimeException(flushError));
                return;
            }
            try {
                // Needed to unlock haveno_XMR.keys
                if (accountWasOpen)
                    closeAccount();

                File dataDir = new File(config.appDataDir.getPath());
                PipedInputStream in = new PipedInputStream(bufferSize); // pipe the serialized account object to stream which will be read by the consumer
                PipedOutputStream out = new PipedOutputStream(in);
                log.info("Zipping directory " + dataDir);

                // exclude monero binaries from backup so they're reinstalled with permissions,
                // and the throwaway seed validation wallet, which must never be archived
                List<File> excludedFiles = new ArrayList<>(Arrays.asList(
                        new File(XmrWalletService.getMoneroWalletRpcPath()),
                        new File(XmrLocalNode.getMonerodPath())
                ));
                excludedFiles.addAll(XmrWalletService.getSeedValidationWalletFiles(config.walletDir));

                Thread producer = new Thread(() -> {
                    try {
                        ZipUtils.zipDirToStream(dataDir, out, bufferSize, excludedFiles);
                    } catch (Exception ex) {
                        error.accept(ex);
                    } finally {
                        endBackup();
                        // reopen only once the zip has read its last file, not concurrently with it
                        if (accountWasOpen) {
                            try {
                                openAccount(password);
                            } catch (Exception ex) {
                                error.accept(ex);
                            }
                        }
                    }
                }, "backup-account-producer");
                producer.start();
                // once started (and only then), the producer thread owns clearing the guard
                producerStarted.set(true);
                consume.accept(in);
            } catch (Exception err) {
                if (!producerStarted.get()) endBackup();
                error.accept(err);
            }
        });
    }

    public void restoreAccount(InputStream inputStream, int bufferSize, Runnable onShutdown) throws Exception {
        if (accountExists()) throw new IllegalStateException("Cannot restore account if there is an existing account");
        File dataDir = new File(config.appDataDir.getPath());
        ZipUtils.unzipToDir(dataDir, inputStream, bufferSize);
        synchronized (listeners) {
            for (AccountServiceListener listener : new ArrayList<>(listeners)) listener.onAccountRestored(onShutdown);
        }
    }

    public void deleteAccount(Runnable onShutdown) {
        try {
            if (isAccountOpen()) closeAccount();
            synchronized (listeners) {
                for (AccountServiceListener listener : new ArrayList<>(listeners)) listener.onAccountDeleted(onShutdown);
            }

            // Log files are locked on Windows so we need to release them. Logging resumes on automatic restart
            Log.stopFileLogging();

            File dataDir = new File(config.appDataDir.getPath()); // TODO (woodser): deleting directory after gracefulShutdown() so services don't throw when they try to persist (e.g. XmrTxProofService), but gracefulShutdown() should honor read-only shutdown
            FileUtil.deleteDirectory(dataDir, null, false);
        } catch (Exception err) {
            throw new RuntimeException(err);
        }
    }
}
