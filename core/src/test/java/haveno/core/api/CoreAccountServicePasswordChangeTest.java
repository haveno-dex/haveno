package haveno.core.api;

import haveno.common.config.Config;
import haveno.common.crypto.IncorrectPasswordException;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.persistence.PersistenceManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CoreAccountServicePasswordChangeTest {
    @TempDir Path directory;
    private final List<CoreAccountService> accounts = new ArrayList<>();

    private CoreAccountService create(String password) {
        KeyStorage storage = new KeyStorage(directory.toFile());
        CoreAccountService account = new CoreAccountService(null, storage, new KeyRing(storage));
        account.createAccount(password);
        accounts.add(account);
        return account;
    }

    private CoreAccountService reopen(String password) throws Exception {
        KeyStorage storage = new KeyStorage(directory.toFile());
        CoreAccountService account = new CoreAccountService(null, storage, new KeyRing(storage));
        account.openAccount(password);
        accounts.add(account);
        return account;
    }

    private void ready(CoreAccountService account) {
        PersistenceManager.allServicesInitialized.set(true);
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.CONNECTIONS, (oldPassword, newPassword) -> {});
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.WALLETS, (oldPassword, newPassword) -> {});
    }

    @AfterEach
    void cleanup() {
        for (CoreAccountService account : accounts) if (account.isAccountOpen()) account.closeAccount();
        PersistenceManager.allServicesInitialized.set(false);
    }

    @Test
    void rejectsEarlyChangesAndInvalidPasswordsBeforePreparingAnything() throws Exception {
        CoreAccountService account = create(null);
        byte[] original = Files.readAllBytes(directory.resolve("sym.key"));
        assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));
        ready(account);
        assertThrows(IllegalArgumentException.class, () -> account.changePassword(null, "short"));
        assertThrows(IllegalArgumentException.class, () -> account.changePassword(null, "password\u0000"));
        assertThrows(IllegalStateException.class, () -> account.changePassword("incorrect", "new-password"));
        assertFalse(account.isPasswordChangePending());
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("sym.key")));
    }

    @Test
    void componentsOnlyRunAfterPrepareAndWrapperCommitsLast() throws Exception {
        CoreAccountService account = create("old-password");
        ready(account);
        List<String> calls = new ArrayList<>();
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.CONNECTIONS, (oldPassword, newPassword) -> {
            assertTrue(account.isPasswordChangePending());
            assertTrue(Files.exists(directory.resolve("sym.key.next")));
            assertTrue(new KeyRing(new KeyStorage(directory.toFile()), oldPassword, false).isUnlocked());
            calls.add("connections");
        });
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.WALLETS, (oldPassword, newPassword) -> {
            assertTrue(account.isPasswordChangePending());
            calls.add("wallets");
        });
        account.changePassword("old-password", "new-password");
        assertEquals(List.of("connections", "wallets"), calls);
        assertFalse(account.isPasswordChangePending());
        account.verifyPassword("new-password");
        assertFalse(new KeyRing(new KeyStorage(directory.toFile()), "old-password", false).isUnlocked());
    }

    @Test
    void interruptedWalletChangeRecoversForwardAfterRestartWithEitherPassword() throws Exception {
        CoreAccountService account = create("old-password");
        ready(account);
        String[] wallets = {"old-password", "old-password", "old-password"};
        AtomicBoolean interrupt = new AtomicBoolean(true);
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.WALLETS, (oldPassword, newPassword) -> {
            wallets[0] = newPassword;
            if (interrupt.getAndSet(false)) throw new IllegalStateException("injected process interruption after first wallet");
        });
        assertThrows(IllegalStateException.class, () -> account.changePassword("old-password", "new-password"));
        assertTrue(account.isPasswordChangePending());
        assertEquals("new-password", wallets[0]);
        assertEquals("old-password", wallets[1]);
        assertThrows(IllegalStateException.class, () -> account.withAccountBackup(() -> fail("journal must not enter an exported backup")));
        account.closeAccount();

        CoreAccountService recovered = reopen("old-password");
        ready(recovered);
        assertEquals("new-password", recovered.getPassword());
        assertEquals("old-password", recovered.getPasswordForLegacyData());
        recovered.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.WALLETS, (oldPassword, newPassword) -> {
            for (int i = 0; i < wallets.length; i++) {
                assertTrue(wallets[i].equals(oldPassword) || wallets[i].equals(newPassword));
                wallets[i] = newPassword;
            }
        });
        recovered.recoverPasswordChange();
        assertArrayEquals(new String[]{"new-password", "new-password", "new-password"}, wallets);
        assertFalse(recovered.isPasswordChangePending());
        recovered.closeAccount();
        assertTrue(reopen("new-password").isAccountOpen());
    }

    @Test
    void failedCredentialPersistenceLeavesWalletsAndPrimaryWrapperUntouched() throws Exception {
        CoreAccountService account = create(null);
        ready(account);
        byte[] original = Files.readAllBytes(directory.resolve("sym.key"));
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.CONNECTIONS, (oldPassword, newPassword) -> {
            throw new IllegalStateException("disk full");
        });
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.WALLETS, (oldPassword, newPassword) -> fail("must not run"));
        assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("sym.key")));
        assertTrue(account.isPasswordChangePending());
    }

    @Test
    void cannotChangeRememberedPasswordByOpeningAlreadyUnlockedAccount() throws Exception {
        CoreAccountService account = create("real-password");
        assertThrows(IncorrectPasswordException.class, () -> account.openAccount("fake-password"));
        account.verifyPassword("real-password");
    }
    @Test
    void failureAfterJournalRemovalReportsTheCommittedPassword() throws Exception {
        KeyStorage storage = new KeyStorage(directory.toFile()) {
            @Override public synchronized void completePasswordChange(javax.crypto.SecretKey key) {
                super.completePasswordChange(key);
                throw new IllegalStateException("injected directory-sync failure after journal deletion");
            }
        };
        CoreAccountService account = new CoreAccountService(null, storage, new KeyRing(storage));
        account.createAccount(null);
        accounts.add(account);
        ready(account);
        account.changePassword(null, "new-password");
        account.verifyPassword("new-password");
        assertFalse(account.isPasswordChangePending());
        assertNull(account.getPreviousWalletPassword());
        assertTrue(new KeyRing(new KeyStorage(directory.toFile()), "new-password", false).isUnlocked());
    }

    @Test
    void backupFinishesBeforePasswordChangeCanPrepareRecoveryData() throws Exception {
        CoreAccountService account = create(null);
        ready(account);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch backupStarted = new CountDownLatch(1);
        CountDownLatch releaseBackup = new CountDownLatch(1);
        CountDownLatch changeStarted = new CountDownLatch(1);
        try {
            Future<?> backup = executor.submit(() -> account.withAccountBackup(() -> {
                backupStarted.countDown();
                try {
                    assertTrue(releaseBackup.await(30, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }));
            assertTrue(backupStarted.await(5, TimeUnit.SECONDS));
            Future<?> change = executor.submit(() -> {
                changeStarted.countDown();
                account.changePassword(null, "new-password");
            });
            assertTrue(changeStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> change.get(200, TimeUnit.MILLISECONDS));
            assertFalse(account.isPasswordChangePending());
            releaseBackup.countDown();
            backup.get(5, TimeUnit.SECONDS);
            change.get(30, TimeUnit.SECONDS);
            account.verifyPassword("new-password");
            assertFalse(account.isPasswordChangePending());
        } finally {
            releaseBackup.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void restoreRestartPreventsPasswordRecoveryAndDesktopBackup() throws Exception {
        Config config = new Config("password-recovery-test", directory.toFile());
        KeyStorage storage = new KeyStorage(config.keyStorageDir);
        CoreAccountService account = new CoreAccountService(config, storage, new KeyRing(storage));
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            zip.finish();
        }
        account.restoreAccount(new ByteArrayInputStream(archive.toByteArray()), 1024, () -> {});

        assertThrows(IllegalStateException.class, account::recoverPasswordChange);
        assertThrows(IllegalStateException.class, () -> account.withAccountBackup(() -> fail("must wait for restart")));
        assertThrows(IllegalStateException.class, () -> account.createAccount(null));
        assertFalse(storage.anyKeyFilesExist());
    }
}
