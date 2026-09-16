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

package haveno.core.crypto;

import com.google.protobuf.ByteString;
import com.google.inject.Injector;
import haveno.common.config.BaseCurrencyNetwork;
import haveno.common.crypto.CryptoException;
import haveno.common.crypto.IncorrectPasswordException;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.file.FileUtil;
import haveno.common.persistence.PersistenceManager;
import haveno.common.setup.CommonSetup;
import haveno.core.api.AccountServiceListener;
import haveno.core.api.CoreAccountService;
import haveno.core.api.XmrConnectionService;
import haveno.core.app.HavenoExecutable;
import haveno.core.offer.OpenOfferManager;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.util.RecoverPassword;
import haveno.core.xmr.model.EncryptedConnectionList;
import haveno.core.xmr.model.XmrAddressEntryList;
import haveno.core.xmr.setup.WalletsSetup;
import haveno.core.xmr.setup.MoneroWalletRpcManager;
import haveno.core.xmr.wallet.XmrWalletBase;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.core.xmr.wallet.WalletPasswordChange;
import haveno.core.xmr.wallet.WalletPasswordRecovery;
import monero.common.MoneroError;
import monero.common.MoneroUtils;
import monero.daemon.model.MoneroNetworkType;
import monero.wallet.MoneroWalletFull;
import monero.wallet.MoneroWalletRpc;
import monero.wallet.model.MoneroWalletConfig;
import monero.wallet.model.MoneroMultisigInfo;
import org.junit.jupiter.api.Assumptions;
import monero.common.MoneroRpcConnection;
import monero.common.TaskLooper;
import monero.wallet.MoneroWallet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

public class EncryptionTest {
    private KeyRing keyRing;
    private File dir;
    private File walletDir;
    private KeyStorage keyStorage;
    private XmrWalletService previousWalletService;
    private TradeManager previousTradeManager;
    private final List<CoreAccountService> accounts = new ArrayList<>();

    @BeforeEach
    public void setup() throws CertificateException, NoSuchAlgorithmException, KeyStoreException, IOException, CryptoException {

        dir = File.createTempFile("temp_tests", "");
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdir();
        previousWalletService = HavenoUtils.xmrWalletService;
        previousTradeManager = HavenoUtils.tradeManager;
        walletDir = Files.createDirectory(new File(dir, "wallet").toPath()).toFile();
        keyStorage = new KeyStorage(dir);
        keyRing = new KeyRing(keyStorage, null, true);
    }

    @AfterEach
    public void tearDown() throws IOException {
        accounts.forEach(CoreAccountService::onShutDownStarted);
        HavenoUtils.xmrWalletService = previousWalletService;
        HavenoUtils.tradeManager = previousTradeManager;
        FileUtil.deleteDirectory(dir);
    }

    private CoreAccountService account(String password) throws Exception {
        CoreAccountService account = new CoreAccountService(null, keyStorage, new KeyRing(keyStorage));
        account.openAccount(password);
        ready(account);
        return account;
    }

    private void ready(CoreAccountService account) throws Exception {
        accounts.add(account);
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.CONNECTIONS, (oldPassword, newPassword) -> {});
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.WALLETS, (oldPassword, newPassword) -> {});
        account.onPersistedDataRead();
    }

    @Test
    public void testSetChangeAndRemovePassword() throws Exception {
        CoreAccountService account = account(null);
        List<String> seen = new ArrayList<>();
        account.addListener(new AccountServiceListener() {
            @Override
            public void onPasswordChanged(String oldPassword, String newPassword) {
                assertEquals(newPassword, account.getPassword());
                seen.add(newPassword);
            }
        });
        account.changePassword(null, "first-password");
        assertEquals("first-password", account("first-password").getPassword());
        account.changePassword("first-password", "second-password");
        assertEquals("second-password", account("second-password").getPassword());
        account.changePassword("second-password", "");
        assertNull(account(null).getPassword());
        assertEquals(Arrays.asList("first-password", "second-password", null), seen);
    }

    @Test
    public void testInvalidPasswordsAreRejectedBeforeParticipants() throws Exception {
        CoreAccountService account = account(null);
        AccountServiceListener listener = mock(AccountServiceListener.class);
        account.addListener(listener);
        assertThrows(IllegalStateException.class, () -> account.changePassword("wrong-password", "new-password"));
        assertThrows(IllegalStateException.class, () -> account.changePassword(null, "short"));
        assertThrows(IllegalArgumentException.class, () -> account.changePassword(null, "password-\u00e9"));
        verify(listener, never()).onPasswordChanged(any(), any());
        assertNull(account(null).getPassword());
    }

    @Test
    public void testReopeningAccountVerifiesPassword() throws Exception {
        CoreAccountService account = account("");
        assertThrows(IncorrectPasswordException.class, () -> account.openAccount("wrong-password"));
        assertNull(account.getPassword());
        account.closeAccount();
        account.openAccount("");
        assertNull(account.getPassword());
    }

    @Test
    public void testFailedChangeRequiresOfflineRecoveryWithoutRollback() throws Exception {
        CoreAccountService account = account(null);
        AtomicReference<String> actual = new AtomicReference<>();
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.WALLETS,
                (oldPassword, newPassword) -> actual.set(newPassword));
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.CONNECTIONS,
                (oldPassword, newPassword) -> { throw new IllegalStateException("injected failure"); });
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));
        assertTrue(error.getMessage().contains("password recovery"));
        assertTrue(account.isPasswordRecoveryRequired());
        assertEquals("new-password", actual.get());
        assertNull(account.getPassword());
        assertNull(account(null).getPassword());
        assertThrows(IllegalStateException.class, account::checkPasswordRecovery);
        assertThrows(IllegalStateException.class, () -> account.changePassword(null, "another-password"));
        assertThrows(IllegalStateException.class, () -> account.withAccountBackup(() -> { throw new AssertionError("export ran"); }));
        assertEquals("new-password", actual.get());
    }

    @Test
    public void testCreatingAccountWithEmptyPasswordUsesUnsetPassword() throws Exception {
        KeyStorage storage = new KeyStorage(Files.createDirectory(new File(dir, "empty-password").toPath()).toFile());
        CoreAccountService account = new CoreAccountService(null, storage, new KeyRing(storage));
        account.createAccount("");
        ready(account);
        assertNull(account.getPassword());
        account.changePassword("", "new-password");
        CoreAccountService restarted = new CoreAccountService(null, storage, new KeyRing(storage));
        restarted.openAccount("new-password");
        assertEquals("new-password", restarted.getPassword());
    }

    @Test
    public void testLoginReportsRecoveryFailureThroughItsFuture() throws Exception {
        CoreAccountService account = mock(CoreAccountService.class);
        doReturn(true).when(account).accountExists();
        IllegalStateException failure = new IllegalStateException("Account key cannot be loaded");
        doThrow(failure).when(account).openAccount(null);
        HavenoExecutable executable = mock(HavenoExecutable.class, CALLS_REAL_METHODS);
        setField(HavenoExecutable.class, executable, "accountService", account);
        Method login = HavenoExecutable.class.getDeclaredMethod("loginAccount");
        login.setAccessible(true);
        CompletableFuture<?> result = (CompletableFuture<?>) login.invoke(executable);
        assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
    }

    @Test
    public void testKeystoreCommitFailureRequiresRecovery() throws Exception {
        KeyStorage failing = spy(keyStorage);
        doThrow(new IllegalStateException("injected disk failure")).when(failing).commitPasswordChange(any());
        CoreAccountService account = new CoreAccountService(null, failing, new KeyRing(keyStorage));
        account.openAccount(null);
        ready(account);
        AtomicReference<String> walletPassword = new AtomicReference<>();
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.WALLETS,
                (oldPassword, newPassword) -> walletPassword.set(newPassword));
        assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));
        assertEquals("new-password", walletPassword.get());
        assertTrue(account.isPasswordRecoveryRequired());
        assertNull(account(null).getPassword());
    }

    @Test
    public void testBackupCleanupFailureLeavesNewPasswordUsable() throws Exception {
        KeyStorage failing = spy(keyStorage);
        doThrow(new IllegalStateException("backup is read-only")).when(failing).finishPasswordChange(any(), any(), any());
        CoreAccountService account = new CoreAccountService(null, failing, new KeyRing(keyStorage));
        account.openAccount(null);
        ready(account);
        AccountServiceListener listener = mock(AccountServiceListener.class);
        account.addListener(listener);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));
        assertTrue(error.getMessage().contains("Password changed successfully"));
        assertFalse(account.isPasswordRecoveryRequired());
        assertEquals("new-password", account.getPassword());
        assertEquals("new-password", account("new-password").getPassword());
        assertThrows(IncorrectPasswordException.class, () -> account(null));
        verify(listener).onPasswordChanged(null, "new-password");
        verify(listener, never()).onPasswordChangeFailed();
    }

    @Test
    public void testAbsentWalletBackupsSurviveFailedAndSuccessfulChanges() throws Exception {
        CoreAccountService account = account(null);
        walletService(account);
        Path backups = Files.createDirectories(walletDir.toPath().resolve("backup/backups_haveno_XMR_keys"));
        Path oldWallet = Files.writeString(backups.resolve("old_haveno_XMR.keys"), "old wallet keys");
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.CONNECTIONS,
                (oldPassword, newPassword) -> { throw new IllegalStateException("injected failure"); });
        assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));
        assertTrue(Files.exists(oldWallet));

        CoreAccountService restarted = account(null);
        walletService(restarted);
        Path abandoned = dir.toPath().resolve(".haveno-write-abandoned.tmp");
        Files.copy(dir.toPath().resolve("sym.p12"), abandoned);
        AccountServiceListener listener = mock(AccountServiceListener.class);
        restarted.addListener(listener);
        List<String> retained = restarted.changePassword(null, "new-password");
        assertTrue(retained.contains("backups_haveno_XMR_keys"));
        assertFalse(restarted.isPasswordRecoveryRequired());
        assertTrue(Files.exists(oldWallet));
        assertFalse(Files.exists(abandoned));
        verify(listener).onPasswordChanged(null, "new-password");
        assertEquals("new-password", account("new-password").getPassword());
    }

    @Test
    public void testServicesMustBeReadyBeforePasswordWrites() throws Exception {
        CoreAccountService account = account(null);
        setField(CoreAccountService.class, account, "persistedDataRead", false);
        assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));
        assertFalse(account.isPasswordRecoveryRequired());
        account.onPersistedDataRead();
        assertTrue(account.onShutDownStarted().isDone());
        assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));
        assertFalse(account.isPasswordRecoveryRequired());
    }

    @Test
    public void testStartupCallbacksDoNotWaitForAccountBackup() throws Exception {
        CoreAccountService account = new CoreAccountService(null, keyStorage, new KeyRing(keyStorage));
        account.openAccount(null);
        accounts.add(account);
        List<String> changed = new ArrayList<>();
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.WALLETS,
                (oldPassword, newPassword) -> changed.add("wallets"));
        CountDownLatch backupStarted = new CountDownLatch(1);
        CompletableFuture<Void> startup = CompletableFuture.runAsync(() -> {
            try {
                assertTrue(backupStarted.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.CONNECTIONS,
                    (oldPassword, newPassword) -> changed.add("connections"));
            account.onPersistedDataRead();
        });
        try {
            account.withAccountBackup(() -> {
                backupStarted.countDown();
                assertDoesNotThrow(() -> startup.get(10, TimeUnit.SECONDS));
                assertTrue(changed.isEmpty());
                assertNull(account.getPassword());
            });
        } finally {
            backupStarted.countDown();
            startup.get(10, TimeUnit.SECONDS);
        }

        account.changePassword(null, "new-password");
        assertEquals(List.of("wallets", "connections"), changed);
        assertEquals("new-password", account.getPassword());
    }

    @Test
    public void testShutdownWaitsForWalletPasswordsAndBackupCleanup() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService service = walletService(account);
        MoneroWallet mainWallet = mockWallet();
        MoneroWallet tradeWallet = mockWallet();
        Files.createFile(walletDir.toPath().resolve("haveno_XMR.keys"));
        Files.createFile(walletDir.toPath().resolve("trade.keys"));
        setField(XmrWalletBase.class, service, "wallet", mainWallet);
        Trade trade = mock(Trade.class);
        doReturn("trade").when(trade).getWalletName();
        doReturn(List.of(trade)).when(HavenoUtils.tradeManager).getAllTrades();
        doAnswer(invocation -> {
            service.changeWalletPassword("trade", tradeWallet, "new-password", true);
            return null;
        }).when(trade).changeWalletPassword(any());

        CountDownLatch mainChanged = new CountDownLatch(1);
        CountDownLatch resumeWallets = new CountDownLatch(1);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch resumeCleanup = new CountDownLatch(1);
        doAnswer(invocation -> {
            mainChanged.countDown();
            assertTrue(resumeWallets.await(10, TimeUnit.SECONDS));
            return null;
        }).when(mainWallet).save();
        AccountServiceListener listener = mock(AccountServiceListener.class);
        doAnswer(invocation -> {
            cleanupStarted.countDown();
            assertTrue(resumeCleanup.await(10, TimeUnit.SECONDS));
            return null;
        }).when(listener).onPasswordChanged(any(), any());
        account.addListener(listener);

        Map<Class<?>, Object> services = new ConcurrentHashMap<>();
        services.put(CoreAccountService.class, account);
        services.put(XmrWalletService.class, service);
        services.put(TradeManager.class, HavenoUtils.tradeManager);
        Injector injector = mock(Injector.class);
        doAnswer(invocation -> services.computeIfAbsent(invocation.getArgument(0), type -> mock(type)))
                .when(injector).getInstance(any(Class.class));
        OpenOfferManager offers = injector.getInstance(OpenOfferManager.class);
        CountDownLatch shutdownStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            shutdownStarted.countDown();
            return null;
        }).when(offers).shutDown(any());
        HavenoExecutable executable = mock(HavenoExecutable.class,
                withSettings().useConstructor("", "", "", "").defaultAnswer(CALLS_REAL_METHODS));
        setField(HavenoExecutable.class, executable, "accountService", account);
        setField(HavenoExecutable.class, executable, "injector", injector);
        CountDownLatch callbacks = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread changing = new Thread(() -> {
            try {
                account.changePassword(null, "new-password");
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        try {
            changing.start();
            assertTrue(mainChanged.await(10, TimeUnit.SECONDS));
            executable.gracefulShutDown(callbacks::countDown, false);
            executable.gracefulShutDown(callbacks::countDown, false);
            assertTrue(executable.isShutDownStarted());
            CompletableFuture<Void> completion = account.onShutDownStarted();
            assertFalse(completion.isDone());
            assertFalse(service.isShutDownStarted());
            verify(offers, never()).shutDown(any());
            verify(tradeWallet, never()).changePassword(any(), any());

            resumeWallets.countDown();
            assertTrue(cleanupStarted.await(10, TimeUnit.SECONDS));
            assertFalse(completion.isDone());
            assertFalse(service.isShutDownStarted());
            resumeCleanup.countDown();
            completion.get(10, TimeUnit.SECONDS);
            assertTrue(shutdownStarted.await(10, TimeUnit.SECONDS));
            assertTrue(service.isShutDownStarted());
            assertEquals(2, callbacks.getCount());
            Method complete = HavenoExecutable.class.getDeclaredMethod("notifyGracefulShutDownComplete");
            complete.setAccessible(true);
            complete.invoke(executable);
            assertTrue(callbacks.await(10, TimeUnit.SECONDS));
            AtomicInteger lateCallback = new AtomicInteger();
            executable.gracefulShutDown(lateCallback::incrementAndGet, false);
            assertEquals(1, lateCallback.get());
            verify(offers, times(1)).shutDown(any());
        } finally {
            resumeWallets.countDown();
            resumeCleanup.countDown();
            changing.join(10000);
        }
        assertFalse(changing.isAlive());
        assertNull(failure.get());
        assertFalse(account.isPasswordRecoveryRequired());
        verify(mainWallet).changePassword("password", "new-password");
        verify(tradeWallet).changePassword("password", "new-password");
        assertEquals("new-password", account("new-password").getPassword());
        assertThrows(IllegalStateException.class, () -> account.changePassword("new-password", "another-password"));
    }

    @Test
    public void testShutdownWatchdogCoversPendingPasswordChange() throws Exception {
        for (boolean systemExit : List.of(false, true)) {
            CompletableFuture<Void> passwordChange = new CompletableFuture<>();
            CoreAccountService account = mock(CoreAccountService.class);
            doReturn(passwordChange).when(account).onShutDownStarted();
            HavenoExecutable executable = mock(HavenoExecutable.class,
                    withSettings().useConstructor("", "", "", "").defaultAnswer(CALLS_REAL_METHODS));
            setField(HavenoExecutable.class, executable, "accountService", account);
            AtomicInteger callbacks = new AtomicInteger();
            try (var setup = mockStatic(CommonSetup.class)) {
                executable.gracefulShutDown(callbacks::incrementAndGet, systemExit);
                setup.verify(CommonSetup::startShutdownWatchdog, times(systemExit ? 1 : 0));
                assertTrue(executable.isShutDownStarted());
                assertFalse(passwordChange.isDone());
                assertEquals(0, callbacks.get());

                executable.gracefulShutDown(callbacks::incrementAndGet, true);
                setup.verify(CommonSetup::startShutdownWatchdog, times(systemExit ? 2 : 1));
                setup.verifyNoMoreInteractions();
                assertFalse(passwordChange.isDone());
                assertEquals(0, callbacks.get());
            }
        }
    }

    @Test
    public void testFailedPasswordChangeReleasesShutdown() throws Exception {
        CoreAccountService account = account(null);
        CountDownLatch changingWallets = new CountDownLatch(1);
        CompletableFuture<Void> resume = new CompletableFuture<>();
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.WALLETS, (oldPassword, newPassword) -> {
            changingWallets.countDown();
            resume.join();
            throw new IllegalStateException("injected wallet failure");
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread changing = new Thread(() -> {
            try {
                account.changePassword(null, "new-password");
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        try {
            changing.start();
            assertTrue(changingWallets.await(10, TimeUnit.SECONDS));
            CompletableFuture<Void> completion = account.onShutDownStarted();
            assertSame(completion, account.onShutDownStarted());
            assertFalse(completion.isDone());
            resume.complete(null);
            completion.get(10, TimeUnit.SECONDS);
        } finally {
            resume.complete(null);
            changing.join(10000);
        }
        assertFalse(changing.isAlive());
        assertTrue(failure.get() instanceof IllegalStateException);
        assertTrue(failure.get().getMessage().contains("injected wallet failure"));
        assertTrue(account.isPasswordRecoveryRequired());
        assertNull(account(null).getPassword());
    }

    @Test
    public void testCommitFailureAfterReplacementRequiresRecovery() throws Exception {
        KeyStorage failing = spy(keyStorage);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("injected failure after replacement");
        }).when(failing).commitPasswordChange(any());
        CoreAccountService account = new CoreAccountService(null, failing, new KeyRing(keyStorage));
        account.openAccount(null);
        ready(account);
        AtomicReference<String> participant = new AtomicReference<>();
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.WALLETS,
                (oldPassword, newPassword) -> participant.set(newPassword));
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));
        assertTrue(error.getMessage().contains("Keep both passwords"));
        assertNull(account.getPassword());
        assertEquals("new-password", participant.get());
        assertTrue(account.isPasswordRecoveryRequired());
        assertThrows(IncorrectPasswordException.class, () -> account(null));
        assertThrows(IllegalStateException.class, () -> account.changePassword(null, "another-password"));
        assertEquals("new-password", account("new-password").getPassword());
    }

    @SuppressWarnings("unchecked")
    private EncryptedConnectionList connectionList(CoreAccountService account, protobuf.EncryptedConnectionList persisted,
                                                  PersistenceManager<EncryptedConnectionList> persistence) {
        doAnswer(invocation -> {
            if (persisted == null) ((Runnable) invocation.getArgument(1)).run();
            else ((Consumer<EncryptedConnectionList>) invocation.getArgument(0)).accept(EncryptedConnectionList.fromProto(persisted));
            return null;
        }).when(persistence).readPersisted(any(), any());
        EncryptedConnectionList list = new EncryptedConnectionList(persistence, account);
        list.readPersisted(() -> {});
        return list;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testConnectionCredentialsRecoverWithExplicitPasswordsAfterRestart() throws Exception {
        CoreAccountService account = account(null);
        EncryptedConnectionList list = connectionList(account, null, mock(PersistenceManager.class));
        list.addConnection(new MoneroRpcConnection("http://localhost:18081", "user", "daemon-secret"));
        list.addConnection(new MoneroRpcConnection("http://localhost:18082"));
        list.changePassword(null, "new-password");
        protobuf.EncryptedConnectionList stored = ((protobuf.PersistableEnvelope) list.toProtoMessage()).getEncryptedConnectionList();
        EncryptedConnectionList recovered = connectionList(account(null), stored, mock(PersistenceManager.class));
        assertTrue(assertThrows(IllegalStateException.class, recovered::getConnections).getMessage().contains("recovery tool"));
        recovered.reconcilePasswords(Arrays.asList(null, "new-password"), null);
        assertEquals("daemon-secret", recovered.getConnections().stream()
                .filter(connection -> connection.getUri().endsWith("18081")).findFirst().orElseThrow().getPassword());
        assertNull(recovered.getConnections().stream()
                .filter(connection -> connection.getUri().endsWith("18082")).findFirst().orElseThrow().getPassword());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testInvalidConnectionCredentialDoesNotPartiallyReplaceList() throws Exception {
        CoreAccountService account = account(null);
        PersistenceManager<EncryptedConnectionList> persistence = mock(PersistenceManager.class);
        EncryptedConnectionList list = connectionList(account, null, persistence);
        list.addConnection(new MoneroRpcConnection("http://localhost:18081", "user", "secret"));
        protobuf.EncryptedConnectionList stored = ((protobuf.PersistableEnvelope) list.toProtoMessage()).getEncryptedConnectionList();
        protobuf.EncryptedConnectionList corrupt = stored.toBuilder().addItems(stored.getItems(0).toBuilder()
                .setUrl("http://localhost:18082").setEncryptedPassword(ByteString.copyFrom(new byte[1]))).build();
        EncryptedConnectionList loaded = connectionList(account, corrupt, mock(PersistenceManager.class));
        byte[] before = loaded.toProtoMessage().toByteArray();
        assertThrows(IllegalStateException.class, () -> loaded.changePassword(null, "new-password"));
        assertArrayEquals(before, loaded.toProtoMessage().toByteArray());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testWrongRecoveryCandidateDoesNotOverwriteConnectionCredentials() throws Exception {
        CoreAccountService account = account(null);
        EncryptedConnectionList list = connectionList(account, null, mock(PersistenceManager.class));
        list.addConnection(new MoneroRpcConnection("http://localhost:18081", "user", "daemon-secret"));
        list.changePassword(null, "unknown-password");
        protobuf.EncryptedConnectionList stored = ((protobuf.PersistableEnvelope) list.toProtoMessage()).getEncryptedConnectionList();
        EncryptedConnectionList recovered = EncryptedConnectionList.fromProto(stored);
        assertThrows(IllegalStateException.class, () -> recovered.reconcilePasswords(Arrays.asList(null, "wrong-password"), null));
        assertEquals(stored, ((protobuf.PersistableEnvelope) recovered.toProtoMessage()).getEncryptedConnectionList());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testOfflineRecoveryPreservesFilesUntilCredentialsCanBeReconciled() throws Exception {
        Path network = Files.createDirectory(dir.toPath().resolve("xmr_mainnet"));
        Files.createDirectory(network.resolve("wallet"));
        Files.createDirectory(network.resolve("db"));
        KeyStorage storage = new KeyStorage(Files.createDirectory(network.resolve("keys")).toFile());
        KeyRing ring = new KeyRing(storage, null, true);
        CoreAccountService account = new CoreAccountService(null, storage, ring);
        account.openAccount(null);
        EncryptedConnectionList list = connectionList(account, null, mock(PersistenceManager.class));
        list.addConnection(new MoneroRpcConnection("http://localhost:18081", "user", "daemon-secret"));
        list.changePassword(null, "attempted-password");
        Path connectionFile = network.resolve("db/EncryptedConnectionList");
        byte[] encrypted = haveno.common.crypto.Encryption.encryptPayloadWithHmac(list.toProtoMessage().toByteArray(), ring.getSymmetricKey());
        Files.write(connectionFile, encrypted);
        byte[] wrapper = Files.readAllBytes(network.resolve("keys/sym.p12"));

        assertThrows(IncorrectPasswordException.class, () -> RecoverPassword.recover(network, "wrong-password", "wrong-password", List.of("attempted-password"), null));
        assertThrows(IllegalStateException.class, () -> RecoverPassword.recover(network, null, null, List.of("wrong-password"), null));
        assertArrayEquals(encrypted, Files.readAllBytes(connectionFile));
        assertArrayEquals(wrapper, Files.readAllBytes(network.resolve("keys/sym.p12")));

        RecoverPassword.recover(network, null, null, List.of("attempted-password"), null);
        assertArrayEquals(wrapper, Files.readAllBytes(network.resolve("keys/sym.p12")));
        protobuf.PersistableEnvelope repaired = PersistenceManager.readEncrypted(connectionFile.toFile(), ring.getSymmetricKey());
        EncryptedConnectionList reopened = connectionList(account, repaired.getEncryptedConnectionList(), mock(PersistenceManager.class));
        assertEquals("daemon-secret", reopened.getConnections().get(0).getPassword());
    }

    @Test
    public void testRecoveryRejectsPasswordMismatchBeforeChangingFiles() throws Exception {
        for (boolean missing : List.of(false, true)) {
            Path network = Files.createDirectories(dir.toPath().resolve(Boolean.toString(missing)).resolve("xmr_mainnet"));
            Path keys = Files.createDirectory(network.resolve("keys"));
            Path wallets = Files.createDirectory(network.resolve("wallet"));
            KeyStorage storage = new KeyStorage(keys.toFile());
            KeyRing ring = new KeyRing(storage, "known-password", true);
            ring.lockKeys();
            assertTrue(ring.unlockKeys("known-password", false));
            ring.lockKeys();
            if (missing) Files.delete(keys.resolve("sym.p12"));
            else Files.write(keys.resolve("sym.p12"), new byte[] {1, 2, 3});
            Files.write(wallets.resolve("haveno_XMR.keys"), new byte[] {4, 5});
            Files.write(wallets.resolve("haveno_XMR"), new byte[] {6, 7});
            Files.write(Files.createDirectory(network.resolve("db")).resolve("EncryptedConnectionList"), new byte[] {8, 9});
            List<Path> paths;
            try (var files = Files.walk(network)) {
                paths = files.sorted().toList();
            }
            Map<Path, byte[]> contents = new HashMap<>();
            for (Path path : paths) {
                if (Files.isRegularFile(path)) contents.put(path, Files.readAllBytes(path));
            }

            for (String confirmation : Arrays.asList("known-password", null, "")) {
                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                        () -> RecoverPassword.recover(network, "known-passwrod", confirmation, List.of("known-password"), null));
                assertTrue(failure.getMessage().contains("do not match"));
            }
            assertThrows(IllegalArgumentException.class,
                    () -> RecoverPassword.recover(network, null, "known-password", List.of("known-password"), null));
            try (var files = Files.walk(network)) {
                assertEquals(paths, files.sorted().toList());
            }
            for (var entry : contents.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
        }
    }

    @Test
    public void testRecoveryRestoresAccountAndWalletWithConfirmedPassword() throws Exception {
        MoneroUtils.tryLoadNativeLibrary();
        Assumptions.assumeTrue(MoneroUtils.isNativeLibraryLoaded());
        for (boolean missing : List.of(false, true)) {
            for (String target : Arrays.asList("confirmed-password", null)) {
                Path network = Files.createDirectories(dir.toPath().resolve(missing + "-" + target).resolve("xmr_mainnet"));
                Path keys = Files.createDirectory(network.resolve("keys"));
                Path wallets = Files.createDirectory(network.resolve("wallet"));
                KeyStorage storage = new KeyStorage(keys.toFile());
                KeyRing ring = new KeyRing(storage, "old-password", true);
                ring.lockKeys();
                assertTrue(ring.unlockKeys("old-password", false));
                byte[] signature = Files.readAllBytes(keys.resolve("sig.key"));
                byte[] encryption = Files.readAllBytes(keys.resolve("enc.key"));
                Path path = wallets.resolve("haveno_XMR");
                MoneroWalletFull wallet = MoneroWalletFull.createWallet(new MoneroWalletConfig()
                        .setPath(path.toString()).setPassword("old-password").setNetworkType(MoneroNetworkType.MAINNET));
                String address;
                try {
                    address = wallet.getPrimaryAddress();
                    wallet.setAttribute("recovery-marker", "preserve local state");
                } finally {
                    wallet.close(true);
                }
                if (missing) Files.delete(keys.resolve("sym.p12"));
                else Files.write(keys.resolve("sym.p12"), new byte[] {1, 2, 3});

                RecoverPassword.recover(network, target, target == null ? "" : target, List.of("old-password"), null);

                assertEquals(ring.getSymmetricKey(), storage.loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, target));
                assertArrayEquals(signature, Files.readAllBytes(keys.resolve("sig.key")));
                assertArrayEquals(encryption, Files.readAllBytes(keys.resolve("enc.key")));
                MoneroWalletFull recovered = MoneroWalletFull.openWallet(path.toString(),
                        WalletPasswordChange.normalizePassword(target), MoneroNetworkType.MAINNET);
                try {
                    assertEquals(address, recovered.getPrimaryAddress());
                    assertEquals("preserve local state", recovered.getAttribute("recovery-marker"));
                } finally {
                    recovered.close(false);
                }
            }
        }
    }

    @Test
    public void testOfflineRecoveryAcceptsApplicationNetworkDirectories() throws Exception {
        for (BaseCurrencyNetwork network : BaseCurrencyNetwork.values()) {
            Path networkDir = Files.createDirectory(dir.toPath().resolve(network.name().toLowerCase(Locale.ROOT)));
            Files.createDirectory(networkDir.resolve("wallet"));
            KeyStorage storage = new KeyStorage(Files.createDirectory(networkDir.resolve("keys")).toFile());
            KeyRing ring = new KeyRing(storage, null, true);
            byte[] wrapper = Files.readAllBytes(networkDir.resolve("keys/sym.p12"));
            ring.lockKeys();

            RecoverPassword.recover(networkDir, null, null, List.of(), null);

            assertArrayEquals(wrapper, Files.readAllBytes(networkDir.resolve("keys/sym.p12")));
        }
    }

    private MoneroWalletFull mockWallet() {
        MoneroWalletFull wallet = mock(MoneroWalletFull.class);
        doReturn(new byte[][] { new byte[] {1}, new byte[] {2} }).when(wallet).getData();
        return wallet;
    }

    private XmrWalletService walletService(CoreAccountService account) throws Exception {
        return walletService(account, mock(XmrConnectionService.class), walletDir);
    }

    private XmrWalletService walletService(CoreAccountService account, XmrConnectionService connections, File walletDir) throws Exception {
        Constructor<XmrWalletService> constructor = XmrWalletService.class.getDeclaredConstructor(User.class, Preferences.class,
                CoreAccountService.class, XmrConnectionService.class, WalletsSetup.class, XmrAddressEntryList.class, File.class, int.class);
        constructor.setAccessible(true);
        HavenoUtils.tradeManager = mock(TradeManager.class);
        return constructor.newInstance(mock(User.class), mock(Preferences.class), account, connections,
                mock(WalletsSetup.class), mock(XmrAddressEntryList.class), walletDir, 0);
    }

    @Test
    public void testLiveWalletChangesWithoutReopening() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService service = walletService(account);
        MoneroWallet wallet = mockWallet();
        doAnswer(invocation -> {
            if (!"password".equals(invocation.getArgument(0))) throw new MoneroError("Invalid original password.");
            return null;
        }).when(wallet).changePassword(anyString(), anyString());
        service.changeWalletPassword("retained", wallet, "new-password", true);
        verify(wallet).changePassword("password", "new-password");
        verify(wallet).save();
        verify(wallet, never()).close(anyBoolean());
    }

    @Test
    public void testPasswordChangePreservesAbandonedCacheWrites() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService service = walletService(account);
        MoneroWalletFull wallet = mockWallet();
        setField(XmrWalletBase.class, service, "wallet", wallet);
        Files.write(walletDir.toPath().resolve("haveno_XMR.keys"), new byte[] {1});
        Files.write(walletDir.toPath().resolve("haveno_XMR"), new byte[] {2});
        byte[] abandonedCache = {3, 4, 5};
        Path cache = Files.write(walletDir.toPath().resolve(".haveno_XMR.haveno-write-abandoned.tmp"), abandonedCache);
        Path unknown = Files.createDirectory(walletDir.toPath().resolve(".haveno_XMR.haveno-write-unknown.tmp"));
        Files.write(unknown.resolve("retained"), abandonedCache);

        List<String> retained = account.changePassword(null, "new-password");

        assertFalse(account.isPasswordRecoveryRequired());
        assertEquals("new-password", account("new-password").getPassword());
        assertArrayEquals(abandonedCache, Files.readAllBytes(cache));
        assertArrayEquals(abandonedCache, Files.readAllBytes(unknown.resolve("retained")));
        assertTrue(retained.contains("wallet/" + cache.getFileName()));
        assertTrue(retained.contains("wallet/" + unknown.getFileName()));
        verify(wallet).changePassword("password", "new-password");
        verify(wallet, never()).close(anyBoolean());
    }

    @Test
    public void testNativePasswordChangePreservesMissingMultisigCacheBackup() throws Exception {
        MoneroUtils.tryLoadNativeLibrary();
        Assumptions.assumeTrue(MoneroUtils.isNativeLibraryLoaded());
        CoreAccountService account = account(null);
        XmrWalletService service = walletService(account);
        Preferences preferences = mock(Preferences.class);
        doReturn(true).when(preferences).isUseNativeXmrWallet();
        setField(XmrWalletService.class, service, "preferences", preferences);
        Path path = walletDir.toPath().resolve("orphan_trade");
        MoneroWalletFull wallet = MoneroWalletFull.createWallet(new MoneroWalletConfig()
                .setPath(path.toString()).setPassword("password").setNetworkType(XmrWalletService.getMoneroNetworkType()));
        MoneroWalletFull peer = MoneroWalletFull.createWallet(new MoneroWalletConfig()
                .setPassword("password").setNetworkType(XmrWalletService.getMoneroNetworkType()));
        try {
            String prepared = wallet.prepareMultisig();
            String peerPrepared = peer.prepareMultisig();
            String made = wallet.makeMultisig(List.of(peerPrepared), 2, "password");
            String peerMade = peer.makeMultisig(List.of(prepared), 2, "password");
            wallet.exchangeMultisigKeys(List.of(peerMade), "password");
            peer.exchangeMultisigKeys(List.of(made), "password");
            assertTrue(wallet.getMultisigInfo().isReady());
            wallet.setAttribute("recovery-marker", "preserve original cache");
            wallet.save();
        } finally {
            wallet.close(false);
            peer.close(false);
        }
        assertTrue(service.backupWallet("orphan_trade"));
        File backup = FileUtil.getLatestBackupFile(walletDir, "orphan_trade");
        byte[] cache = Files.readAllBytes(backup.toPath());
        byte[] keys = Files.readAllBytes(path.resolveSibling("orphan_trade.keys"));
        Files.delete(path);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));
        assertTrue(failure.getMessage().contains("cache"));
        assertFalse(Files.exists(path));
        assertArrayEquals(cache, Files.readAllBytes(backup.toPath()));
        assertArrayEquals(keys, Files.readAllBytes(path.resolveSibling("orphan_trade.keys")));
        assertNull(account(null).getPassword());
    }

    @Test
    public void testRpcPasswordChangesPreserveTradeAndMainWalletTrust() throws Exception {
        CoreAccountService account = account(null);
        XmrConnectionService connections = mock(XmrConnectionService.class, CALLS_REAL_METHODS);
        XmrWalletService service = walletService(account, connections, walletDir);
        MoneroWalletRpc mainWallet = mock(MoneroWalletRpc.class);
        MoneroWalletRpc tradeWallet = mock(MoneroWalletRpc.class);
        Trade trade = mock(Trade.class, CALLS_REAL_METHODS);
        setField(XmrWalletBase.class, service, "wallet", mainWallet);
        setField(XmrWalletBase.class, trade, "walletLock", new Object());
        setField(XmrWalletBase.class, trade, "wallet", tradeWallet);
        setField(Trade.class, trade, "xmrWalletService", service);
        doReturn("trade_wallet").when(trade).getWalletName();
        doReturn(List.of(trade)).when(HavenoUtils.tradeManager).getAllTrades();
        for (String name : List.of("haveno_XMR", "trade_wallet")) {
            Files.write(walletDir.toPath().resolve(name), new byte[] {1});
            Files.write(walletDir.toPath().resolve(name + ".keys"), new byte[] {2});
        }

        for (boolean local : List.of(false, true)) {
            MoneroRpcConnection connection = new MoneroRpcConnection(local ? "http://127.0.0.1:18081" : "http://192.0.2.1:18081");
            doReturn(connection).when(mainWallet).getDaemonConnection();
            doReturn(connection).when(tradeWallet).getDaemonConnection();
            clearInvocations(mainWallet, tradeWallet);
            String oldPassword = account.getPassword();
            String newPassword = oldPassword == null ? "new-password" : null;

            account.changePassword(oldPassword, newPassword);

            verify(mainWallet, times(2)).setDaemonConnection(eq(connection), eq(local), eq(null));
            verify(tradeWallet, times(2)).setDaemonConnection(eq(connection), eq(true), eq(null));
            for (MoneroWalletRpc wallet : List.of(mainWallet, tradeWallet)) {
                verify(wallet, times(2)).close(false);
                verify(wallet, times(2)).openWallet(any(MoneroWalletConfig.class));
                verify(wallet).changePassword(WalletPasswordChange.normalizePassword(oldPassword), WalletPasswordChange.normalizePassword(newPassword));
            }
            assertFalse(account.isPasswordRecoveryRequired());
        }
    }

    @Test
    public void testRpcPasswordChangeKeepsPreparedMultisig() throws Exception {
        Path binary = Path.of("src/main/resources/bin", XmrWalletService.MONERO_WALLET_RPC_NAME).toAbsolutePath();
        Assumptions.assumeTrue(Files.isExecutable(binary));
        MoneroUtils.tryLoadNativeLibrary();
        Assumptions.assumeTrue(MoneroUtils.isNativeLibraryLoaded());
        XmrWalletService service = walletService(account(null));
        MoneroWalletRpcManager manager = new MoneroWalletRpcManager();
        MoneroWalletRpc rpc = manager.startInstance(List.of(binary.toString(), "--offline", "--rpc-bind-ip", "127.0.0.1",
                "--rpc-login", "test:test", "--wallet-dir", walletDir.getAbsolutePath()));
        List<MoneroWalletFull> peers = new ArrayList<>();
        try {
            rpc.createWallet(new MoneroWalletConfig().setPath("trade").setPassword("password"));
            String prepared = rpc.prepareMultisig();
            for (int i = 0; i < 2; i++) peers.add(MoneroWalletFull.createWallet(new MoneroWalletConfig().setPassword("peer").setNetworkType(MoneroNetworkType.MAINNET)));
            List<String> preparedPeers = peers.stream().map(MoneroWalletFull::prepareMultisig).toList();

            service.changeWalletPassword("trade", rpc, "new-password", false);

            rpc.makeMultisig(preparedPeers, 2, "new-password");
            List<String> madePeers = new ArrayList<>();
            for (int i = 0; i < 2; i++) madePeers.add(peers.get(i).makeMultisig(List.of(prepared, preparedPeers.get(1 - i)), 2, "peer"));
            rpc.exchangeMultisigKeys(madePeers, "new-password");
            assertTrue(rpc.getMultisigInfo().isMultisig());
        } finally {
            peers.forEach(peer -> peer.close(false));
            manager.stopInstance(rpc, null, true);
        }
    }

    @Test
    public void testUnknownWalletPasswordAndSaveFailureAreNotIgnored() throws Exception {
        XmrWalletService service = walletService(account(null));
        MoneroWallet wallet = mockWallet();
        doThrow(new MoneroError("Invalid original password.")).when(wallet).changePassword(anyString(), anyString());
        assertThrows(MoneroError.class, () -> service.changeWalletPassword("retained", wallet, "new-password", true));
        verify(wallet, never()).save();
        XmrWalletService failingService = walletService(account(null));
        MoneroWallet failingSave = mockWallet();
        MoneroError error = new MoneroError("disk full");
        doThrow(error).when(failingSave).save();
        assertSame(error, assertThrows(MoneroError.class, () -> failingService.changeWalletPassword("retained", failingSave, "new-password", true)));
    }

    @Test
    public void testWalletPasswordsFollowEachWalletDuringChange() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService service = walletService(account);
        MoneroWallet first = mockWallet();
        MoneroWallet second = mockWallet();
        doReturn("first").when(first).getPath();
        doReturn("second").when(second).getPath();
        Files.createFile(walletDir.toPath().resolve("first.keys"));
        Files.createFile(walletDir.toPath().resolve("second.keys"));
        Trade firstTrade = mock(Trade.class);
        Trade secondTrade = mock(Trade.class);
        doReturn("first").when(firstTrade).getWalletName();
        doReturn("second").when(secondTrade).getWalletName();
        doReturn(List.of(firstTrade, secondTrade)).when(HavenoUtils.tradeManager).getAllTrades();
        doAnswer(invocation -> {
            assertEquals("password", service.getWalletPassword("first"));
            service.changeWalletPassword("first", first, "new-password", true);
            return null;
        }).when(firstTrade).changeWalletPassword(any());
        doAnswer(invocation -> {
            assertEquals("new-password", service.getWalletPassword("first"));
            assertEquals("password", service.getWalletPassword("second"));
            verify(second, never()).changePassword(any(), any());
            service.changeWalletPassword("second", second, "new-password", true);
            return null;
        }).when(secondTrade).changeWalletPassword(any());
        account.changePassword(null, "new-password");
        assertEquals("new-password", service.getWalletPassword("first"));
        assertEquals("new-password", service.getWalletPassword("second"));
        verify(first).changePassword("password", "new-password");
        verify(second).changePassword("password", "new-password");
        verify(first, never()).close(anyBoolean());
        verify(second, never()).close(anyBoolean());
    }

    @Test
    public void testPasswordChangeSkipsWalletDeletedAfterSnapshot() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService service = walletService(account);
        MoneroWallet mainWallet = mockWallet();
        Files.createFile(walletDir.toPath().resolve("haveno_XMR.keys"));
        setField(XmrWalletBase.class, service, "wallet", mainWallet);
        byte[] original = {1, 2, 3};
        for (String suffix : List.of("", ".keys", ".address.txt")) Files.write(walletDir.toPath().resolve("trade" + suffix), original);
        Trade trade = mock(Trade.class);
        doReturn("trade").when(trade).getWalletName();
        doReturn(List.of(trade)).when(HavenoUtils.tradeManager).getAllTrades();
        doAnswer(invocation -> {
            // protocol error cleanup deletes and unregisters the trade after the disk snapshot
            CompletableFuture.runAsync(() -> service.deleteWalletAndRetainBackup("trade")).get(10, TimeUnit.SECONDS);
            doReturn(List.of()).when(HavenoUtils.tradeManager).getAllTrades();
            return null;
        }).when(mainWallet).changePassword("password", "new-password");

        List<String> retained = account.changePassword(null, "new-password");

        assertFalse(service.walletExists("trade"));
        assertFalse(account.isPasswordRecoveryRequired());
        assertEquals("new-password", account("new-password").getPassword());
        assertTrue(retained.contains("backups_trade_keys"));
        File backup = FileUtil.getLatestBackupFile(walletDir, "trade.keys");
        assertTrue(backup != null);
        assertArrayEquals(original, Files.readAllBytes(backup.toPath()));
        verify(mainWallet).changePassword("password", "new-password");
    }

    @Test
    public void testPasswordChangeRejectsWalletWithMissingCacheAfterSnapshot() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService service = walletService(account);
        MoneroWallet mainWallet = mockWallet();
        Files.createFile(walletDir.toPath().resolve("haveno_XMR.keys"));
        setField(XmrWalletBase.class, service, "wallet", mainWallet);
        Path keys = Files.write(walletDir.toPath().resolve("orphan.keys"), new byte[] {1, 2, 3});
        Path cache = Files.write(walletDir.toPath().resolve("orphan"), new byte[] {4, 5, 6});
        doAnswer(invocation -> {
            Files.delete(cache);
            return null;
        }).when(mainWallet).changePassword("password", "new-password");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));

        assertTrue(failure.getMessage().contains("Wallet cache is missing for orphan"));
        assertTrue(account.isPasswordRecoveryRequired());
        assertNull(account(null).getPassword());
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(keys));
    }

    @Test
    public void testPasswordChangeAllowsBackgroundRefresh() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService service = walletService(account);
        MoneroWallet wallet = mockWallet();
        Files.createFile(walletDir.toPath().resolve("haveno_XMR.keys"));
        setField(XmrWalletBase.class, service, "wallet", wallet);
        setField(XmrWalletBase.class, service, "backgroundRefreshWallet", wallet);
        setField(XmrWalletBase.class, service, "backgroundRefreshLatch", new CountDownLatch(1));
        account.changePassword(null, "new-password");
        assertEquals("new-password", account("new-password").getPassword());
        assertFalse(account.isPasswordRecoveryRequired());
        verify(wallet).changePassword("password", "new-password");
        verify(wallet).save();
        verify(wallet, never()).close(anyBoolean());
    }

    @Test
    public void testPasswordChangeWaitsForMainWalletReplacementBeforeSnapshot() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService service = walletService(account);
        MoneroWallet restored = mockWallet();
        doReturn(walletDir.toPath().resolve("haveno_XMR").toString()).when(restored).getPath();
        doAnswer(invocation -> {
            if (!"password".equals(invocation.getArgument(0))) throw new MoneroError("Invalid original password.");
            return null;
        }).when(restored).changePassword(anyString(), anyString());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread changing = new Thread(() -> {
            try {
                account.changePassword(null, "new-password");
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        try {
            synchronized (service.getWalletLock()) {
                // restore has removed the old main wallet and is about to move the replacement into place
                Path restoreKeys = Files.createFile(walletDir.toPath().resolve("haveno_XMR_restore.keys"));
                changing.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (changing.getState() != Thread.State.BLOCKED && changing.isAlive() && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(Thread.State.BLOCKED, changing.getState());
                Files.move(restoreKeys, walletDir.toPath().resolve("haveno_XMR.keys"));
                setField(XmrWalletBase.class, service, "wallet", restored);
            }
        } finally {
            changing.join(10000);
        }
        assertFalse(changing.isAlive());
        assertNull(failure.get());
        verify(restored).changePassword("password", "new-password");
        verify(restored).save();
        assertEquals("new-password", account("new-password").getPassword());
    }

    @Test
    public void testStoppedTradeChangesRetainedWalletWithoutOpeningTrade() throws Exception {
        Trade trade = mock(Trade.class, CALLS_REAL_METHODS);
        setField(XmrWalletBase.class, trade, "walletLock", new Object());
        setField(XmrWalletBase.class, trade, "isShutDownStarted", true);
        XmrWalletService service = mock(XmrWalletService.class);
        setField(Trade.class, trade, "xmrWalletService", service);
        doReturn(true).when(trade).walletExists();
        doReturn("trade").when(trade).getShortId();
        doReturn("uid").when(trade).getShortUid();
        trade.changeWalletPassword("new-password");
        verify(service).changeWalletPassword(anyString(), eq(null), eq("new-password"), eq(true));
        verify(trade, never()).getWallet();
        assertTrue(trade.isShutDownStarted());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testPendingNativeCloseMustFinishSuccessfullyBeforeReopen() throws Exception {
        XmrWalletService service = walletService(account(null));
        Field field = XmrWalletService.class.getDeclaredField("pendingWalletCloses");
        field.setAccessible(true);
        Map<String, Future<?>> pending = (Map<String, Future<?>>) field.get(service);
        Method wait = XmrWalletService.class.getDeclaredMethod("awaitPendingWalletClose", String.class);
        wait.setAccessible(true);
        Future<?> closing = mock(Future.class);
        doThrow(new TimeoutException("still closing")).when(closing).get(anyLong(), any());
        pending.put("wallet", closing);
        assertThrows(InvocationTargetException.class, () -> wait.invoke(service, "wallet"));
        assertSame(closing, pending.get("wallet"));
        Future<?> failed = CompletableFuture.failedFuture(new MoneroError("native release failed"));
        pending.put("wallet", failed);
        assertThrows(InvocationTargetException.class, () -> wait.invoke(service, "wallet"));
        assertSame(failed, pending.get("wallet"));
        pending.put("wallet", CompletableFuture.completedFuture(null));
        wait.invoke(service, "wallet");
        assertFalse(pending.containsKey("wallet"));
    }

    @Test
    public void testFailedMainWalletCloseRetainsHandleForForceClose() throws Exception {
        XmrWalletService service = walletService(account(null));
        MoneroWallet wallet = mockWallet();
        setField(XmrWalletBase.class, service, "wallet", wallet);
        doThrow(new MoneroError("close failed")).when(wallet).close(true);
        Method close = XmrWalletService.class.getDeclaredMethod("closeMainWallet", boolean.class);
        close.setAccessible(true);
        assertEquals(false, close.invoke(service, false));
        Field handle = XmrWalletBase.class.getDeclaredField("wallet");
        handle.setAccessible(true);
        assertSame(wallet, handle.get(service));
        Method forceClose = XmrWalletService.class.getDeclaredMethod("forceCloseMainWallet");
        forceClose.setAccessible(true);
        forceClose.invoke(service);
        verify(wallet).close(false);
        assertNull(handle.get(service));
    }

    @Test
    public void testFailedMainWalletPasswordChangeStopsPolling() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService service = walletService(account);
        MoneroWallet wallet = mockWallet();
        TaskLooper poller = mock(TaskLooper.class);
        setField(XmrWalletBase.class, service, "wallet", wallet);
        setField(XmrWalletService.class, service, "pollLooper", poller);
        Files.createFile(walletDir.toPath().resolve("haveno_XMR.keys"));
        doThrow(new MoneroError("disk full")).when(wallet).changePassword(anyString(), anyString());
        assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));
        assertTrue(account.isPasswordRecoveryRequired());
        verify(poller).stop();
    }

    @Test
    public void testRestorePasswordFailureRetainsMainWalletForShutdown() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService service = spy(walletService(account));
        MoneroWallet wallet = mockWallet();
        setField(XmrWalletBase.class, service, "wallet", wallet);
        Files.createFile(walletDir.toPath().resolve("haveno_XMR.keys"));
        Files.createFile(walletDir.toPath().resolve("haveno_XMR_restore.keys"));
        doAnswer(invocation -> {
            account.requirePasswordRecovery();
            throw new MoneroError("restore password change failed");
        }).when(service).changeWalletPassword(eq("haveno_XMR_restore"), eq(null), any(), eq(false));
        Method change = XmrWalletService.class.getDeclaredMethod("changeWalletPasswords", String.class, String.class);
        change.setAccessible(true);
        assertThrows(InvocationTargetException.class, () -> change.invoke(service, null, "new-password"));
        verify(wallet).changePassword("password", "new-password");
        verify(wallet).save();
        verify(wallet, never()).close(anyBoolean());
        assertTrue(account.isPasswordRecoveryRequired());
        Method close = XmrWalletService.class.getDeclaredMethod("closeMainWallet", boolean.class);
        close.setAccessible(true);
        assertEquals(true, close.invoke(service, false));
        verify(wallet).close(true);
    }

    @Test
    public void testFailedDurabilityCheckPreservesOldKeyCopies() throws Exception {
        Path keyDir = dir.toPath();
        KeyStorage storage = keyStorage;
        KeyRing ring = keyRing;
        Path old = keyDir.resolve(".haveno-write-old.tmp");
        Files.copy(keyDir.resolve("sym.p12"), old);
        storage.commitPasswordChange(storage.preparePasswordChange(null, "new-password"));
        try (var fileUtil = mockStatic(FileUtil.class, CALLS_REAL_METHODS)) {
            fileUtil.when(() -> FileUtil.syncFileAndDirectory(keyDir.resolve("sym.p12")))
                    .thenThrow(new IOException("injected flush failure"));
            assertThrows(IllegalStateException.class, () -> storage.finishPasswordChange(ring, "new-password", Arrays.asList(null, "new-password")));
        }
        assertTrue(Files.exists(old));
        assertFalse(FileUtil.getBackupFiles(keyDir.toFile(), "sym.p12").isEmpty());
    }

    @Test
    public void testRetainedAccountKeyCopyMustBeWrittenAndVerifiedBeforeRemoval() throws Exception {
        Path original = Files.write(dir.toPath().resolve(".sym.p12.haveno-write-truncated.tmp"), new byte[] {1, 2, 3});
        for (boolean corrupt : List.of(false, true)) {
            try (var files = mockStatic(FileUtil.class, CALLS_REAL_METHODS)) {
                files.when(() -> FileUtil.writeAtomically(any(), any())).thenAnswer(invocation -> {
                    Path target = invocation.getArgument(0);
                    if (!target.getParent().getFileName().toString().equals("retained_sym_p12")) return invocation.callRealMethod();
                    if (!corrupt) throw new IOException("injected retained-copy write failure");
                    invocation.callRealMethod();
                    Files.write(target, new byte[] {4, 5, 6});
                    return null;
                });
                assertTrue(new KeyRing(keyStorage, null, false).isUnlocked());
                assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(original));
            }
        }
        keyStorage.finishPasswordChange(keyRing, null, Arrays.asList((String) null));
        assertFalse(Files.exists(original));
        Path exported = dir.toPath().resolve("exported-keys");
        keyStorage.exportRetainedAccountKeys(null, exported);
        try (var files = Files.list(exported)) {
            Path copy = files.findFirst().orElseThrow();
            assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(copy));
        }
    }

    @Test
    public void testFatalPasswordChangeFailureBlocksOperationsAndNotifiesEveryListener() throws Exception {
        CoreAccountService account = account(null);
        OutOfMemoryError failure = new OutOfMemoryError("injected after wallet mutation");
        AccountServiceListener broken = mock(AccountServiceListener.class);
        AccountServiceListener listener = mock(AccountServiceListener.class);
        doThrow(new IllegalStateException("injected listener failure")).when(broken).onPasswordChangeFailed();
        account.addListener(broken);
        account.addListener(listener);
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.CONNECTIONS,
                (oldPassword, newPassword) -> { throw failure; });
        assertSame(failure, assertThrows(OutOfMemoryError.class, () -> account.changePassword(null, "new-password")));
        assertTrue(account.isPasswordRecoveryRequired());
        assertThrows(IllegalStateException.class, account::checkPasswordRecovery);
        assertThrows(IllegalStateException.class, () -> account.withAccountBackup(() -> {}));
        verify(listener).onPasswordChangeFailed();
        assertEquals(1, failure.getSuppressed().length);
        assertNull(account(null).getPassword());
    }

    @Test
    public void testShutdownRejectionRetainsTradeWalletHandle() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService service = walletService(account);
        setField(XmrWalletBase.class, service, "isShutDownStarted", true);
        Trade trade = mock(Trade.class, CALLS_REAL_METHODS);
        MoneroWallet wallet = mockWallet();
        setField(XmrWalletBase.class, trade, "walletLock", new Object());
        setField(XmrWalletBase.class, trade, "wallet", wallet);
        setField(Trade.class, trade, "xmrWalletService", service);
        doReturn(true).when(trade).walletExists();
        doReturn("trade").when(trade).getShortId();
        doReturn("uid").when(trade).getShortUid();
        assertThrows(IllegalStateException.class, () -> trade.changeWalletPassword("new-password"));
        Field walletField = XmrWalletBase.class.getDeclaredField("wallet");
        walletField.setAccessible(true);
        assertSame(wallet, walletField.get(trade));
        verify(wallet, never()).close(anyBoolean());
        verify(wallet, never()).changePassword(anyString(), anyString());
        assertFalse(account.isPasswordRecoveryRequired());
    }

    @Test
    public void testNativeRecoveryPreservesCacheAcrossSplitPasswords() throws Exception {
        MoneroUtils.tryLoadNativeLibrary();
        Assumptions.assumeTrue(MoneroUtils.isNativeLibraryLoaded());
        Path network = new File(dir, "xmr_mainnet").toPath();
        Files.createDirectories(network.resolve("wallet"));
        new KeyRing(new KeyStorage(Files.createDirectory(network.resolve("keys")).toFile()), "old-password", true);
        Path path = network.resolve("wallet/haveno_XMR");
        MoneroWalletFull wallet = MoneroWalletFull.createWallet(new MoneroWalletConfig()
                .setPath(path.toString()).setPassword("old-password").setNetworkType(MoneroNetworkType.MAINNET));
        String address = wallet.getPrimaryAddress();
        byte[] cache;
        try {
            wallet.setAttribute("recovery-marker", "preserve original cache");
            wallet.save();
            cache = wallet.getData()[1];
            wallet.changePassword("old-password", "new-password");
            wallet.save();
        } finally {
            wallet.close(false);
        }
        Files.write(path, cache);
        byte[] keys = Files.readAllBytes(path.resolveSibling("haveno_XMR.keys"));
        assertThrows(IOException.class, () -> RecoverPassword.recover(network, "old-password", "old-password", List.of("unknown-password"), null));
        assertArrayEquals(cache, Files.readAllBytes(path));
        assertArrayEquals(keys, Files.readAllBytes(path.resolveSibling("haveno_XMR.keys")));
        RecoverPassword.recover(network, "old-password", "old-password", List.of("new-password"), null);
        MoneroWalletFull reopened = MoneroWalletFull.openWallet(path.toString(), "old-password", MoneroNetworkType.MAINNET);
        try {
            assertEquals(address, reopened.getPrimaryAddress());
            assertEquals("preserve original cache", reopened.getAttribute("recovery-marker"));
        } finally {
            reopened.close(false);
        }
        RecoverPassword.recover(network, "old-password", "old-password", List.of("new-password"), null);
    }

    @Test
    public void testRecoveryAuthenticatesAbandonedWalletKeys() throws Exception {
        MoneroUtils.tryLoadNativeLibrary();
        Assumptions.assumeTrue(MoneroUtils.isNativeLibraryLoaded());
        Path network = Files.createDirectory(dir.toPath().resolve("xmr_mainnet"));
        Path wallets = Files.createDirectory(network.resolve("wallet"));
        new KeyRing(new KeyStorage(Files.createDirectory(network.resolve("keys")).toFile()), "current-password", true);
        Path path = wallets.resolve("haveno_XMR");
        MoneroWalletFull wallet = MoneroWalletFull.createWallet(new MoneroWalletConfig()
                .setPath(path.toString()).setPassword("current-password").setNetworkType(MoneroNetworkType.MAINNET));
        byte[][] original;
        byte[] unprotected;
        byte[] oldCache;
        String address;
        try {
            wallet.setAttribute("recovery-marker", "original cache");
            original = wallet.getData();
            address = wallet.getPrimaryAddress();
            wallet.changePassword("current-password", "password");
            byte[][] old = wallet.getData();
            unprotected = old[0];
            oldCache = old[1];
        } finally {
            wallet.close(false);
        }
        Files.write(path, oldCache);
        Files.write(wallets.resolve("haveno_XMR.keys"), original[0]);
        Path nativeTemp = Files.write(wallets.resolve("haveno_XMR.keys.new"), unprotected);
        Path atomicTemp = Files.write(wallets.resolve(".haveno_XMR.keys.haveno-write-abandoned.tmp"), unprotected);
        List<Path> duplicateCaches = List.of(wallets.resolve("haveno_XMR.new"), wallets.resolve("haveno_XMR.unportable"),
                wallets.resolve(".haveno_XMR.haveno-write-abandoned.tmp"));
        for (Path copy : duplicateCaches) Files.write(copy, oldCache);
        assertTrue(RecoverPassword.recover(network, "current-password", "current-password", List.of(), null).isEmpty());
        for (Path copy : duplicateCaches) assertFalse(Files.exists(copy));
        assertFalse(Files.exists(wallets.resolve(".password-recovery")));
        assertFalse(Files.exists(nativeTemp));
        assertFalse(Files.exists(atomicTemp));
        wallet = MoneroWalletFull.openWallet(path.toString(), "current-password", MoneroNetworkType.MAINNET);
        try {
            assertEquals(address, wallet.getPrimaryAddress());
            assertEquals("original cache", wallet.getAttribute("recovery-marker"));
        } finally {
            wallet.close(false);
        }

        MoneroWalletFull foreign = MoneroWalletFull.createWallet(new MoneroWalletConfig()
                .setPassword("password").setNetworkType(MoneroNetworkType.MAINNET));
        byte[] foreignKeys;
        try {
            foreignKeys = foreign.getData()[0];
        } finally {
            foreign.close(false);
        }
        Files.write(nativeTemp, foreignKeys);
        Files.write(atomicTemp, new byte[] {1, 2, 3});
        Path cacheTemp = Files.write(wallets.resolve("haveno_XMR.new"), original[1]);
        Path unportable = Files.write(wallets.resolve("haveno_XMR.unportable"), oldCache);
        Path atomicCache = Files.write(wallets.resolve(".haveno_XMR.haveno-write-abandoned.tmp"), new byte[] {7, 8, 9});
        List<String> retained = RecoverPassword.recover(network, "current-password", "current-password", List.of(), null);
        Path savedKeys = retainedWalletFile(network, retained, "haveno_XMR.keys.new");
        Path savedAtomicKeys = retainedWalletFile(network, retained, ".haveno_XMR.keys.haveno-write-abandoned.tmp");
        Path savedCache = retainedWalletFile(network, retained, "haveno_XMR.new");
        assertFalse(Files.exists(nativeTemp));
        assertFalse(Files.exists(atomicTemp));
        assertFalse(Files.exists(cacheTemp));
        assertFalse(Files.exists(unportable));
        assertFalse(Files.exists(atomicCache));
        assertArrayEquals(oldCache, Files.readAllBytes(retainedWalletFile(network, retained, "haveno_XMR.unportable")));
        assertArrayEquals(new byte[] {7, 8, 9}, Files.readAllBytes(retainedWalletFile(network, retained, ".haveno_XMR.haveno-write-abandoned.tmp")));
        assertArrayEquals(foreignKeys, Files.readAllBytes(savedKeys));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(savedAtomicKeys));
        assertArrayEquals(original[1], Files.readAllBytes(savedCache));
        wallet = MoneroWalletFull.openWallet(path.toString(), "current-password", MoneroNetworkType.MAINNET);
        try {
            wallet.changePassword("current-password", "next-password");
            wallet.save();
        } finally {
            wallet.close(false);
        }
        assertArrayEquals(foreignKeys, Files.readAllBytes(savedKeys));
        assertArrayEquals(original[1], Files.readAllBytes(savedCache));
        assertTrue(RecoverPassword.recover(network, "current-password", "current-password", List.of("next-password"), null).containsAll(retained));
    }

    private static Path retainedWalletFile(Path network, List<String> retained, String name) {
        String relative = retained.stream().filter(path -> path.endsWith("/" + name)).findFirst().orElseThrow();
        assertTrue(relative.startsWith("wallet/backup/recovery-retained-"));
        return network.resolve(relative);
    }

    @Test
    public void testRecoveryKeepsTemporaryKeysUntilPrimaryIsDurable() throws Exception {
        Path network = Files.createDirectory(dir.toPath().resolve("xmr_mainnet"));
        Path wallets = Files.createDirectory(network.resolve("wallet"));
        new KeyRing(new KeyStorage(Files.createDirectory(network.resolve("keys")).toFile()), null, true);
        Path keys = Files.write(wallets.resolve("haveno_XMR.keys"), new byte[] {1});
        Path cache = Files.write(wallets.resolve("haveno_XMR"), new byte[] {2});
        Path temp = Files.write(wallets.resolve("haveno_XMR.keys.new"), new byte[] {3});
        Path unportable = Files.write(wallets.resolve("haveno_XMR.unportable"), new byte[] {2});
        Path atomicCache = Files.write(wallets.resolve(".haveno_XMR.haveno-write-abandoned.tmp"), new byte[] {2});
        MoneroWalletFull wallet = mockWallet();
        doReturn(new MoneroMultisigInfo()).when(wallet).getMultisigInfo();
        doReturn("current-address").when(wallet).getPrimaryAddress();
        try (var utils = mockStatic(MoneroUtils.class); var nativeWallets = mockStatic(MoneroWalletFull.class);
             var files = mockStatic(FileUtil.class, CALLS_REAL_METHODS)) {
            utils.when(MoneroUtils::isNativeLibraryLoaded).thenReturn(true);
            nativeWallets.when(() -> MoneroWalletFull.openWalletData(anyString(), any(), any(), any(), any())).thenReturn(wallet);
            files.when(() -> FileUtil.writeAtomically(eq(keys), any())).thenThrow(new IOException("injected durability failure"));
            assertThrows(IOException.class, () -> RecoverPassword.recover(network, null, null, List.of(), null));
            assertArrayEquals(new byte[] {3}, Files.readAllBytes(temp));
            assertArrayEquals(new byte[] {2}, Files.readAllBytes(unportable));
            assertArrayEquals(new byte[] {2}, Files.readAllBytes(atomicCache));
        }
        MoneroWalletFull foreign = mockWallet();
        doReturn("foreign-address").when(foreign).getPrimaryAddress();
        try (var utils = mockStatic(MoneroUtils.class); var nativeWallets = mockStatic(MoneroWalletFull.class);
             var files = mockStatic(FileUtil.class, CALLS_REAL_METHODS)) {
            utils.when(MoneroUtils::isNativeLibraryLoaded).thenReturn(true);
            nativeWallets.when(() -> MoneroWalletFull.openWalletData(anyString(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> Arrays.equals(new byte[] {3}, invocation.getArgument(2)) ? foreign : wallet);
            files.when(() -> FileUtil.writeAtomically(any(), any())).thenAnswer(invocation -> {
                Path target = invocation.getArgument(0);
                if (target.getParent().getFileName().toString().startsWith("recovery-retained-")) {
                    throw new IOException("injected retained-copy durability failure");
                }
                return invocation.callRealMethod();
            });
            assertThrows(IOException.class, () -> RecoverPassword.recover(network, null, null, List.of(), null));
            assertArrayEquals(new byte[] {3}, Files.readAllBytes(temp));
            try (var directories = Files.list(wallets.resolve("backup"))) {
                assertFalse(directories.anyMatch(path -> path.getFileName().toString().startsWith("recovery-retained-")));
            }
            Path cacheTemp = Files.write(wallets.resolve("haveno_XMR.new"), new byte[] {4});
            files.when(() -> FileUtil.writeAtomically(any(), any())).thenAnswer(invocation -> {
                Path target = invocation.getArgument(0);
                if (target.getParent().getFileName().toString().startsWith("recovery-retained-")
                        && target.getFileName().equals(cacheTemp.getFileName())) {
                    throw new IOException("injected second retained-copy durability failure");
                }
                return invocation.callRealMethod();
            });
            IOException failure = assertThrows(IOException.class, () -> RecoverPassword.recover(network, null, null, List.of(), null));
            assertEquals("injected second retained-copy durability failure", failure.getMessage());
            assertFalse(Files.exists(temp));
            assertArrayEquals(new byte[] {4}, Files.readAllBytes(cacheTemp));
            try (var directories = Files.list(wallets.resolve("backup"))) {
                List<Path> retained = directories.filter(path -> path.getFileName().toString().startsWith("recovery-retained-")).toList();
                assertEquals(1, retained.size());
                assertArrayEquals(new byte[] {3}, Files.readAllBytes(retained.get(0).resolve(temp.getFileName())));
            }
        }
        Files.write(temp, new byte[] {3});
        Files.delete(keys);
        assertThrows(IOException.class, () -> RecoverPassword.recover(network, null, null, List.of(), null));
        assertArrayEquals(new byte[] {3}, Files.readAllBytes(temp));
        assertTrue(Files.exists(cache));
    }

    @Test
    public void testWalletOpenFailurePreservesOriginalCache() throws Exception {
        XmrConnectionService connections = mock(XmrConnectionService.class);
        doReturn(new MoneroRpcConnection("http://127.0.0.1:18081")).when(connections).getConnection();
        XmrWalletService service = walletService(account(null), connections, dir);
        Path cache = dir.toPath().resolve("retained");
        byte[] original = {1, 2, 3, 4};
        Files.write(cache, original);
        MoneroWalletFull emptyWallet = mockWallet();
        Method open = XmrWalletService.class.getDeclaredMethod("openWalletFull", MoneroWalletConfig.class, boolean.class);
        open.setAccessible(true);
        try (var nativeWallets = mockStatic(MoneroWalletFull.class)) {
            nativeWallets.when(() -> MoneroWalletFull.openWallet(any(MoneroWalletConfig.class))).thenAnswer(invocation -> {
                if (Files.exists(cache)) throw new MoneroError("Failed to deserialize wallet cache");
                return emptyWallet;
            });
            InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> open.invoke(service,
                    new MoneroWalletConfig().setPath(cache.toString()).setPassword("password"), false));
            assertTrue(failure.getCause().getMessage().contains("Failed to deserialize wallet cache"));
            nativeWallets.verify(() -> MoneroWalletFull.openWallet(any(MoneroWalletConfig.class)));
        }
        assertArrayEquals(original, Files.readAllBytes(cache));
        assertFalse(Files.exists(cache.resolveSibling("retained.backup")));
    }

    @Test
    public void testRecoveryPreservesBackupsOfMissingWalletKeys() throws Exception {
        for (String backup : List.of("password-change-completed", "backups_haveno_XMR_keys")) {
            Path network = Files.createDirectories(dir.toPath().resolve(backup).resolve("xmr_mainnet"));
            Files.createDirectory(network.resolve("wallet"));
            new KeyRing(new KeyStorage(Files.createDirectory(network.resolve("keys")).toFile()), null, true);
            Path copies = Files.createDirectories(network.resolve("wallet/backup").resolve(backup));
            Path keys = copies.resolve(backup.startsWith("password-change") ? "haveno_XMR.keys" : "123_haveno_XMR.keys");
            byte[] original = {1, 2, 3};
            Files.write(keys, original);
            IOException failure = assertThrows(IOException.class, () -> RecoverPassword.recover(network, null, null, List.of(), null));
            assertTrue(failure.getMessage().contains("Wallet keys are missing"));
            assertArrayEquals(original, Files.readAllBytes(keys));
        }
    }

    @Test
    public void testRecoveryRequiresExistingCacheBackupsBeforeRebuilding() throws Exception {
        MoneroWalletFull wallet = mockWallet();
        doReturn(new MoneroMultisigInfo()).when(wallet).getMultisigInfo();
        doReturn("current-address").when(wallet).getPrimaryAddress();
        try (var utils = mockStatic(MoneroUtils.class); var nativeWallets = mockStatic(MoneroWalletFull.class)) {
            utils.when(MoneroUtils::isNativeLibraryLoaded).thenReturn(true);
            nativeWallets.when(() -> MoneroWalletFull.openWalletData(anyString(), any(), any(), any(), any())).thenReturn(wallet);
            for (String name : List.of("orphan_trade", "haveno_XMR")) {
                Path network = Files.createDirectories(dir.toPath().resolve(name).resolve("xmr_mainnet"));
                Path wallets = Files.createDirectory(network.resolve("wallet"));
                new KeyRing(new KeyStorage(Files.createDirectory(network.resolve("keys")).toFile()), null, true);
                Path keys = Files.write(wallets.resolve(name + ".keys"), new byte[] {1, 2, 3});
                for (String temporary : List.of(name + ".new", name + ".unportable")) {
                    Path copy = Files.write(wallets.resolve(temporary), new byte[] {4, 5, 6});
                    IOException failure = assertThrows(IOException.class, () -> RecoverPassword.recover(network, null, null, List.of(), null));
                    assertTrue(failure.getMessage().contains("temporary caches"));
                    assertArrayEquals(new byte[] {4, 5, 6}, Files.readAllBytes(copy));
                    assertFalse(Files.exists(wallets.resolve(name)));
                    Files.delete(copy);
                }
                for (String backup : List.of("backups_" + name, "password-change-completed", "password-change-hashed", "password-change-cache-only")) {
                    if (name.equals("haveno_XMR") && backup.startsWith("backups_")) continue;
                    Path copies = Files.createDirectories(wallets.resolve("backup").resolve(backup));
                    Path cache = copies.resolve(backup.startsWith("password-change") ? name : "123_" + name);
                    byte[] original = {4, 5, 6};
                    Files.write(cache, original);
                    if (!backup.endsWith("cache-only")) {
                        Files.write(copies.resolve(name + ".keys"), new byte[] {1, 2, 3});
                        if (backup.endsWith("hashed")) Files.writeString(copies.resolve(WalletPasswordChange.MAIN_WALLET_ID_FILE), WalletPasswordChange.getMainWalletId("current-address"));
                        else Files.writeString(copies.resolve(".main-wallet-address"), "current-address");
                    }

                    IOException failure = assertThrows(IOException.class, () -> RecoverPassword.recover(network, null, null, List.of(), null));
                    assertTrue(failure.getMessage().contains("cache backup"));
                    assertArrayEquals(original, Files.readAllBytes(cache));
                    assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(keys));
                    assertFalse(Files.exists(wallets.resolve(name)));
                    FileUtil.deleteDirectory(copies.toFile());
                }
            }
        }
    }

    @Test
    public void testRecoveryRetriesAnInterruptedCacheRebuild() throws Exception {
        Path network = Files.createDirectory(dir.toPath().resolve("xmr_mainnet"));
        Path wallets = Files.createDirectory(network.resolve("wallet"));
        new KeyRing(new KeyStorage(Files.createDirectory(network.resolve("keys")).toFile()), null, true);
        Path cache = wallets.resolve("haveno_XMR");
        Path keys = Files.write(wallets.resolve("haveno_XMR.keys"), new byte[] {1});
        Path temporary = wallets.resolve(".haveno_XMR.haveno-write-interrupted.tmp");
        MoneroWalletFull wallet = mockWallet();
        doReturn(new MoneroMultisigInfo()).when(wallet).getMultisigInfo();
        doReturn("current-address").when(wallet).getPrimaryAddress();
        try (var utils = mockStatic(MoneroUtils.class); var nativeWallets = mockStatic(MoneroWalletFull.class)) {
            utils.when(MoneroUtils::isNativeLibraryLoaded).thenReturn(true);
            nativeWallets.when(() -> MoneroWalletFull.openWalletData(anyString(), any(), any(), any(), any())).thenReturn(wallet);
            try (var files = mockStatic(FileUtil.class, CALLS_REAL_METHODS)) {
                files.when(() -> FileUtil.writeAtomically(eq(cache), any())).thenAnswer(invocation -> {
                    Files.write(temporary, new byte[] {4});
                    throw new IOException("injected crash during cache rebuild");
                });
                assertThrows(IOException.class, () -> RecoverPassword.recover(network, null, null, List.of(), null));
            }
            assertFalse(Files.exists(cache));
            assertArrayEquals(new byte[] {1}, Files.readAllBytes(keys));
            assertArrayEquals(new byte[] {4}, Files.readAllBytes(temporary));
            List<String> retained = RecoverPassword.recover(network, null, null, List.of(), null);
            assertEquals(1, retained.size());
            assertFalse(Files.exists(temporary));
            assertArrayEquals(new byte[] {4}, Files.readAllBytes(retainedWalletFile(network, retained, temporary.getFileName().toString())));
            assertArrayEquals(new byte[] {2}, Files.readAllBytes(cache));
            assertArrayEquals(new byte[] {1}, Files.readAllBytes(keys));
            assertEquals(retained, RecoverPassword.recover(network, null, null, List.of(), null));
        }
    }

    @Test
    public void testRecoveryRebuildsCacheWithoutDiscardingOtherMainWallets() throws Exception {
        MoneroWalletFull wallet = mockWallet();
        doReturn(new MoneroMultisigInfo()).when(wallet).getMultisigInfo();
        doReturn("current-address").when(wallet).getPrimaryAddress();
        try (var utils = mockStatic(MoneroUtils.class); var nativeWallets = mockStatic(MoneroWalletFull.class)) {
            utils.when(MoneroUtils::isNativeLibraryLoaded).thenReturn(true);
            nativeWallets.when(() -> MoneroWalletFull.openWalletData(anyString(), any(), any(), any(), any())).thenAnswer(invocation -> {
                byte[] cache = invocation.getArgument(3);
                if (cache != null) throw new MoneroError("Cache belongs to a different wallet");
                return wallet;
            });
            for (String name : List.of("orphan_trade", "haveno_XMR")) {
                Path network = Files.createDirectories(dir.toPath().resolve(name).resolve("xmr_mainnet"));
                Path wallets = Files.createDirectory(network.resolve("wallet"));
                new KeyRing(new KeyStorage(Files.createDirectory(network.resolve("keys")).toFile()), null, true);
                Files.write(wallets.resolve(name + ".keys"), new byte[] {1, 2, 3});
                List<Path> backups = new ArrayList<>();
                if (name.equals("haveno_XMR")) {
                    for (String backup : List.of("backups_haveno_XMR", "password-change-other", "password-change-unknown")) {
                        Path copies = Files.createDirectories(wallets.resolve("backup").resolve(backup));
                        backups.add(Files.write(copies.resolve(name), new byte[] {1, 2, 3}));
                        Files.write(copies.resolve(name + ".keys"), new byte[] {4, 5, 6});
                        if (backup.endsWith("other")) Files.writeString(copies.resolve(".main-wallet-address"), "other-address");
                    }
                }

                assertEquals(backups.size(), RecoverPassword.recover(network, null, null, List.of(), null).size());
                for (Path backup : backups) assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(backup));
                assertArrayEquals(new byte[] {2}, Files.readAllBytes(wallets.resolve(name)));
                assertArrayEquals(new byte[] {1}, Files.readAllBytes(wallets.resolve(name + ".keys")));
            }
        }
    }

    @Test
    public void testRecoveryKeepsBackupsOfAbsentTradeWallets() throws Exception {
        Path network = Files.createDirectory(dir.toPath().resolve("xmr_mainnet"));
        Path wallets = Files.createDirectory(network.resolve("wallet"));
        new KeyRing(new KeyStorage(Files.createDirectory(network.resolve("keys")).toFile()), null, true);
        for (String name : List.of("password-change-old", "backups_trade_keys", "backups_trade")) {
            Path backup = Files.createDirectories(wallets.resolve("backup").resolve(name));
            Files.write(backup.resolve(name.equals("backups_trade") ? "123_trade" : "trade.keys"), new byte[] {1, 2, 3});
        }
        List<String> retained = RecoverPassword.recover(network, null, null, List.of(), null);
        assertEquals(3, retained.size());
        for (String name : retained) assertTrue(Files.isDirectory(wallets.resolve("backup").resolve(name)));
    }

    @Test
    public void testRecoveryReportsOrphanTemporaryCaches() throws Exception {
        Path network = Files.createDirectory(dir.toPath().resolve("xmr_mainnet"));
        Path wallets = Files.createDirectory(network.resolve("wallet"));
        new KeyRing(new KeyStorage(Files.createDirectory(network.resolve("keys")).toFile()), null, true);
        for (String name : List.of("orphan.new", "orphan.unportable", ".orphan.haveno-write-abandoned.tmp")) {
            Files.write(wallets.resolve(name), new byte[] {1, 2, 3});
        }
        List<String> retained = RecoverPassword.recover(network, null, null, List.of(), null);
        assertEquals(3, retained.size());
        for (String name : retained) assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(network.resolve(name)));
    }

    @Test
    public void testBackupCleanupPreservesIncompleteAndUnknownSnapshotFiles() throws Exception {
        byte[] original = {1, 2, 3};
        for (String name : List.of("haveno_XMR", "active_trade", "superseded_trade")) {
            Files.write(walletDir.toPath().resolve(name), original);
            Files.write(walletDir.toPath().resolve(name + ".keys"), original);
        }
        Map<String, String> copies = Map.of(
                "main-cache-only", "haveno_XMR",
                "main-keys-only", "haveno_XMR.keys",
                "trade-cache-only", "active_trade",
                "trade-keys-only", "active_trade.keys",
                "absent-trade-cache", "absent_trade",
                "unknown", "unrecognized-file",
                "temporary", ".haveno_XMR.keys.haveno-write-interrupted.tmp");
        Path backups = Files.createDirectory(walletDir.toPath().resolve("backup"));
        for (Map.Entry<String, String> copy : copies.entrySet()) {
            Path snapshot = Files.createDirectory(backups.resolve("password-change-" + copy.getKey()));
            Files.write(snapshot.resolve(copy.getValue()), original);
            Files.writeString(snapshot.resolve(WalletPasswordChange.MAIN_WALLET_ID_FILE), WalletPasswordChange.getMainWalletId("current-address"));
            // a superseded wallet in the same snapshot must not cause its other files to be discarded
            Files.write(snapshot.resolve("superseded_trade"), original);
            Files.write(snapshot.resolve("superseded_trade.keys"), original);
        }

        List<String> retained = WalletPasswordChange.cleanupBackups(walletDir, null, "current-address");
        assertEquals(copies.size(), retained.size());
        for (Map.Entry<String, String> copy : copies.entrySet()) {
            String name = "password-change-" + copy.getKey();
            assertTrue(retained.contains(name));
            assertArrayEquals(original, Files.readAllBytes(backups.resolve(name).resolve(copy.getValue())));
        }
    }

    @Test
    public void testBackupCleanupPreservesPreviousMainWallets() throws Exception {
        Files.createFile(walletDir.toPath().resolve("haveno_XMR.keys"));
        Files.createFile(walletDir.toPath().resolve("active_trade.keys"));
        Files.createFile(walletDir.toPath().resolve("active_trade"));
        Files.createFile(walletDir.toPath().resolve("haveno_XMR"));
        Path backups = Files.createDirectory(walletDir.toPath().resolve("backup"));
        Path main = Files.createDirectory(backups.resolve("backups_haveno_XMR_keys")).resolve("old_haveno_XMR.keys");
        Path snapshot = Files.createDirectory(backups.resolve("password-change-old")).resolve("haveno_XMR.keys");
        Path trade = Files.createDirectory(backups.resolve("backups_active_trade_keys")).resolve("old_active_trade.keys");
        for (Path path : List.of(main, snapshot, trade)) Files.write(path, new byte[] {1, 2, 3});
        Files.write(snapshot.resolveSibling("haveno_XMR"), new byte[] {1, 2, 3});
        Path sameWallet = Files.createDirectory(backups.resolve("password-change-same"));
        Files.write(sameWallet.resolve("haveno_XMR.keys"), new byte[] {4, 5, 6});
        Files.write(sameWallet.resolve("haveno_XMR"), new byte[] {4, 5, 6});
        Files.writeString(sameWallet.resolve(".main-wallet-address"), "current-main-address");
        Path unknown = Files.createDirectory(backups.resolve("password-change-unreadable"));
        Files.write(unknown.resolve("haveno_XMR.keys"), new byte[] {7, 8, 9});
        Files.write(unknown.resolve("haveno_XMR"), new byte[] {7, 8, 9});
        Files.write(unknown.resolve(".main-wallet-address"), new byte[] {(byte) 0xc3, 0x28});
        Path sameHashedWallet = Files.createDirectory(backups.resolve("password-change-same-hashed"));
        Files.write(sameHashedWallet.resolve("haveno_XMR.keys"), new byte[] {4, 5, 6});
        Files.write(sameHashedWallet.resolve("haveno_XMR"), new byte[] {4, 5, 6});
        String mainId = WalletPasswordChange.getMainWalletId("current-main-address");
        assertEquals(64, mainId.length());
        Files.writeString(sameHashedWallet.resolve(WalletPasswordChange.MAIN_WALLET_ID_FILE), mainId);
        Path other = Files.createDirectory(backups.resolve("password-change-other-hashed"));
        Files.write(other.resolve("haveno_XMR.keys"), new byte[] {7, 8, 9});
        Files.write(other.resolve("haveno_XMR"), new byte[] {7, 8, 9});
        Files.writeString(other.resolve(WalletPasswordChange.MAIN_WALLET_ID_FILE), WalletPasswordChange.getMainWalletId("other-address"));
        Path malformed = Files.createDirectory(backups.resolve("password-change-malformed"));
        Files.write(malformed.resolve("haveno_XMR.keys"), new byte[] {7, 8, 9});
        Files.write(malformed.resolve("haveno_XMR"), new byte[] {7, 8, 9});
        Files.writeString(malformed.resolve(WalletPasswordChange.MAIN_WALLET_ID_FILE), "invalid");
        Files.writeString(malformed.resolve(".main-wallet-address"), "current-main-address");
        assertNull(WalletPasswordChange.readMainWalletId(malformed));
        Files.writeString(malformed.resolve(WalletPasswordChange.MAIN_WALLET_ID_FILE), WalletPasswordChange.getMainWalletId("different-address"));
        assertNull(WalletPasswordChange.readMainWalletId(malformed));
        assertEquals(5, WalletPasswordChange.cleanupBackups(walletDir, null, "current-main-address").size());
        assertFalse(Files.exists(sameHashedWallet));
        assertTrue(Files.exists(other));
        assertTrue(Files.exists(malformed));
        assertArrayEquals(new byte[] {7, 8, 9}, Files.readAllBytes(unknown.resolve("haveno_XMR.keys")));
        assertFalse(Files.exists(sameWallet));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(main));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(snapshot));
        assertFalse(Files.exists(trade));
        Path current = Files.createDirectory(backups.resolve("password-change-current"));
        Files.writeString(current.resolve(WalletPasswordChange.MAIN_WALLET_ID_FILE), mainId);
        Files.createDirectory(sameWallet);
        Files.write(sameWallet.resolve("haveno_XMR.keys"), new byte[] {4, 5, 6});
        Files.write(sameWallet.resolve("haveno_XMR"), new byte[] {4, 5, 6});
        Files.writeString(sameWallet.resolve(".main-wallet-address"), "current-main-address");
        WalletPasswordChange.cleanupBackups(walletDir, current.toFile(), null);
        assertFalse(Files.exists(sameWallet));
        assertTrue(Files.exists(current));
    }

    @Test
    public void testRecoveryAllowsAnInterruptedMainWalletRestore() throws Exception {
        MoneroUtils.tryLoadNativeLibrary();
        Assumptions.assumeTrue(MoneroUtils.isNativeLibraryLoaded());
        Path network = Files.createDirectory(dir.toPath().resolve("xmr_mainnet"));
        Path wallets = Files.createDirectory(network.resolve("wallet"));
        new KeyRing(new KeyStorage(Files.createDirectory(network.resolve("keys")).toFile()), null, true);
        Path path = wallets.resolve("haveno_XMR_restore");
        MoneroWalletFull restored = MoneroWalletFull.createWallet(new MoneroWalletConfig()
                .setPath(path.toString()).setPassword("password").setNetworkType(MoneroNetworkType.MAINNET));
        String address = restored.getPrimaryAddress();
        restored.close(true);
        Path old = Files.createDirectories(wallets.resolve("backup/backups_haveno_XMR_keys")).resolve("old_haveno_XMR.keys");
        Files.write(old, new byte[] {1, 2, 3});
        assertTrue(RecoverPassword.recover(network, null, null, List.of(), null).contains("backups_haveno_XMR_keys"));
        assertFalse(Files.exists(wallets.resolve("haveno_XMR.keys")));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(old));
        restored = MoneroWalletFull.openWallet(path.toString(), "password", MoneroNetworkType.MAINNET);
        try {
            assertEquals(address, restored.getPrimaryAddress());
        } finally {
            restored.close(false);
        }
    }

    @Test
    public void testCleanupCannotInterleaveWithRetainingADeletedTradeWallet() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService service = spy(walletService(account));
        for (String suffix : List.of("", ".keys", ".address.txt")) Files.write(new File(walletDir, "trade" + suffix).toPath(), new byte[] {1, 2, 3});
        CountDownLatch copied = new CountDownLatch(1);
        CountDownLatch allowDelete = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        doAnswer(invocation -> {
            boolean success = (boolean) invocation.callRealMethod();
            copied.countDown();
            assertTrue(allowDelete.await(5, TimeUnit.SECONDS));
            return success;
        }).when(service).backupWallet("trade");
        Field listeners = CoreAccountService.class.getDeclaredField("listeners");
        listeners.setAccessible(true);
        AccountServiceListener cleanup = (AccountServiceListener) ((List<?>) listeners.get(account)).get(0);
        Thread deleting = new Thread(() -> {
            try {
                service.deleteWalletAndRetainBackup("trade");
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        Thread cleaning = new Thread(() -> {
            try {
                cleanup.onPasswordChanged(null, "new-password");
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        try {
            deleting.start();
            assertTrue(copied.await(5, TimeUnit.SECONDS));
            cleaning.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (cleaning.getState() != Thread.State.BLOCKED && cleaning.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(Thread.State.BLOCKED, cleaning.getState());
        } finally {
            allowDelete.countDown();
            deleting.join(10000);
            cleaning.join(10000);
        }
        assertFalse(deleting.isAlive());
        assertFalse(cleaning.isAlive());
        assertNull(failure.get());
        assertFalse(new File(walletDir, "trade.keys").exists());
        File backup = FileUtil.getLatestBackupFile(walletDir, "trade.keys");
        assertTrue(backup != null);
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(backup.toPath()));
    }

    @Test
    public void testRpcRecoveryRestartsAfterCacheProbeDies() throws Exception {
        Path network = Files.createDirectory(dir.toPath().resolve("xmr_mainnet"));
        Path wallets = Files.createDirectory(network.resolve("wallet"));
        new KeyRing(new KeyStorage(Files.createDirectory(network.resolve("keys")).toFile()), null, true);
        byte[] keys = {1, 2}, cache = {3, 4}, repairedKeys = {5, 6}, repairedCache = {7, 8};
        Files.write(wallets.resolve("haveno_XMR.keys"), keys);
        Files.write(wallets.resolve("haveno_XMR"), cache);
        Path unportable = Files.write(wallets.resolve("haveno_XMR.unportable"), cache);
        Path binary = Files.createFile(dir.toPath().resolve("rpc"));
        AtomicReference<Path> scratch = new AtomicReference<>();
        AtomicInteger starts = new AtomicInteger();
        MoneroWalletRpc first = mock(MoneroWalletRpc.class);
        MoneroWalletRpc second = mock(MoneroWalletRpc.class);
        Process dead = mock(Process.class);
        doReturn(137).when(dead).exitValue();
        doReturn(dead).when(first).getProcess();
        for (MoneroWalletRpc rpc : List.of(first, second)) {
            doReturn(new MoneroRpcConnection("http://127.0.0.1:1")).when(rpc).getRpcConnection();
            MoneroMultisigInfo multisig = new MoneroMultisigInfo();
            multisig.setIsMultisig(false);
            doReturn(multisig).when(rpc).getMultisigInfo();
            doAnswer(invocation -> {
                Path copy = scratch.get().resolve("wallet");
                if (Files.exists(copy) && Arrays.equals(cache, Files.readAllBytes(copy)) && rpc == first) {
                    Files.write(scratch.get().resolve("wallet.keys"), new byte[] {0});
                    throw new MoneroError("connection reset after cache probe terminated");
                }
                if (rpc == second && !Files.exists(copy)) assertArrayEquals(keys, Files.readAllBytes(scratch.get().resolve("wallet.keys")));
                return rpc;
            }).when(rpc).openWallet(any(MoneroWalletConfig.class));
            doAnswer(invocation -> {
                Files.write(scratch.get().resolve("wallet.keys"), repairedKeys);
                Files.write(scratch.get().resolve("wallet"), repairedCache);
                return null;
            }).when(rpc).save();
        }
        try (var managers = mockConstruction(MoneroWalletRpcManager.class, (manager, context) -> {
            doAnswer(invocation -> {
                List<String> command = invocation.getArgument(0);
                scratch.set(Path.of(command.get(command.indexOf("--wallet-dir") + 1)));
                if (starts.incrementAndGet() == 1) return first;
                verify(manager).stopInstance(first, null, true);
                return second;
            }).when(manager).startInstance(any());
        })) {
            assertTrue(RecoverPassword.recover(network, null, null, List.of("other-password"), binary).isEmpty());
            assertEquals(2, starts.get());
            verify(managers.constructed().get(0)).stopInstance(second, null, true);
        }
        assertArrayEquals(repairedKeys, Files.readAllBytes(wallets.resolve("haveno_XMR.keys")));
        assertArrayEquals(repairedCache, Files.readAllBytes(wallets.resolve("haveno_XMR")));
        assertFalse(Files.exists(wallets.resolve(".password-recovery")));
        assertFalse(Files.exists(unportable));
    }

    @Test
    public void testPasswordChangeProtectsMainWalletBackupsAndPreservesTheirState() throws Exception {
        MoneroUtils.tryLoadNativeLibrary();
        Assumptions.assumeTrue(MoneroUtils.isNativeLibraryLoaded());
        CoreAccountService account = account(null);
        XmrWalletService service = walletService(account);
        MoneroNetworkType network = XmrWalletService.getMoneroNetworkType();
        Path path = walletDir.toPath().resolve("haveno_XMR");
        MoneroWalletFull wallet = MoneroWalletFull.createWallet(new MoneroWalletConfig()
                .setPath(path.toString()).setPassword("password").setNetworkType(network));
        try {
            wallet.setAttribute("backup-marker", "historical cache state");
            wallet.save();
            // release the native keys-file lock before creating the backup
            wallet.close(false);
            assertTrue(service.backupWallet("haveno_XMR"));
            wallet = MoneroWalletFull.openWallet(path.toString(), "password", network);
            Path keys = FileUtil.getLatestBackupFile(walletDir, "haveno_XMR.keys").toPath();
            Path cache = FileUtil.getLatestBackupFile(walletDir, "haveno_XMR").toPath();
            // rolling keys and cache timestamps are independent
            Path renamedCache = cache.resolveSibling("different-timestamp_haveno_XMR");
            Files.move(cache, renamedCache);
            wallet.setAttribute("backup-marker", "current cache state");
            wallet.save();
            setField(XmrWalletBase.class, service, "wallet", wallet);

            byte[][] foreignData;
            MoneroWalletFull foreign = MoneroWalletFull.createWallet(new MoneroWalletConfig()
                    .setPassword("password").setNetworkType(network));
            try {
                foreignData = foreign.getData();
            } finally {
                foreign.close(false);
            }
            Path foreignKeys = Files.write(keys.resolveSibling("foreign_haveno_XMR.keys"), foreignData[0]);
            Path foreignCache = Files.write(cache.resolveSibling("foreign_haveno_XMR"), foreignData[1]);

            List<String> retained = account.changePassword(null, "new-password");

            assertFalse(wallet.isClosed());
            assertEquals("current cache state", wallet.getAttribute("backup-marker"));
            assertArrayEquals(foreignData[0], Files.readAllBytes(foreignKeys));
            assertArrayEquals(foreignData[1], Files.readAllBytes(foreignCache));
            assertTrue(retained.contains("backups_haveno_XMR_keys"));
            assertTrue(retained.contains("backups_haveno_XMR"));
            byte[] protectedKeys = Files.readAllBytes(keys);
            assertThrows(Exception.class, () -> MoneroWalletFull.openWalletData("password", network, protectedKeys, null, null));
            MoneroWalletFull copy = MoneroWalletFull.openWalletData("new-password", network,
                    protectedKeys, Files.readAllBytes(renamedCache), null);
            try {
                assertEquals(wallet.getPrimaryAddress(), copy.getPrimaryAddress());
                assertEquals(wallet.getPrivateSpendKey(), copy.getPrivateSpendKey());
                assertEquals("historical cache state", copy.getAttribute("backup-marker"));
            } finally {
                copy.close(false);
            }

            Files.delete(foreignKeys);
            Files.delete(foreignCache);
            assertTrue(account.changePassword("new-password", "next-password").isEmpty());
        } finally {
            if (!wallet.isClosed()) wallet.close(false);
        }
    }

    @Test
    public void testRecoveryProtectsMainWalletBackupsWithRpc() throws Exception {
        Path binary = Path.of("src/main/resources/bin", XmrWalletService.MONERO_WALLET_RPC_NAME).toAbsolutePath();
        Assumptions.assumeTrue(Files.isExecutable(binary));
        MoneroUtils.tryLoadNativeLibrary();
        Assumptions.assumeTrue(MoneroUtils.isNativeLibraryLoaded());
        Path network = Files.createDirectory(dir.toPath().resolve("xmr_mainnet"));
        Path wallets = Files.createDirectory(network.resolve("wallet"));
        new KeyRing(new KeyStorage(Files.createDirectory(network.resolve("keys")).toFile()), "new-password", true);
        Path path = wallets.resolve("haveno_XMR");
        Path keys = Files.createDirectories(wallets.resolve("backup/backups_haveno_XMR_keys")).resolve("old_haveno_XMR.keys");
        Path cache = Files.createDirectories(wallets.resolve("backup/backups_haveno_XMR")).resolve("other_haveno_XMR");
        MoneroWalletFull wallet = MoneroWalletFull.createWallet(new MoneroWalletConfig()
                .setPath(path.toString()).setPassword("password").setNetworkType(MoneroNetworkType.MAINNET));
        try {
            wallet.setAttribute("backup-marker", "historical RPC backup state");
            byte[][] data = wallet.getData();
            Files.write(keys, data[0]);
            Files.write(cache, data[1]);
            wallet.setAttribute("backup-marker", "current state");
            wallet.changePassword("password", "new-password");
            wallet.save();
        } finally {
            wallet.close(false);
        }
        assertTrue(RecoverPassword.recover(network, "new-password", "new-password", List.of(), binary).isEmpty());
        MoneroWalletFull copy = MoneroWalletFull.openWalletData("new-password", MoneroNetworkType.MAINNET,
                Files.readAllBytes(keys), Files.readAllBytes(cache), null);
        try {
            assertEquals("historical RPC backup state", copy.getAttribute("backup-marker"));
        } finally {
            copy.close(false);
        }
    }

    @Test
    public void testFailedWalletBackupReplacementPreservesTheOldCopy() throws Exception {
        MoneroUtils.tryLoadNativeLibrary();
        Assumptions.assumeTrue(MoneroUtils.isNativeLibraryLoaded());
        Path keys = Files.createDirectories(walletDir.toPath().resolve("backup/backups_haveno_XMR_keys")).resolve("old.keys");
        MoneroWalletFull wallet = MoneroWalletFull.createWallet(new MoneroWalletConfig()
                .setPassword("password").setNetworkType(MoneroNetworkType.MAINNET));
        byte[] oldKeys;
        Path current = walletDir.toPath().resolve("current.keys");
        try {
            oldKeys = wallet.getData()[0];
            Files.write(keys, oldKeys);
            wallet.changePassword("password", "new-password");
            Files.write(current, wallet.getData()[0]);
        } finally {
            wallet.close(false);
        }
        try (WalletPasswordRecovery recovery = new WalletPasswordRecovery(walletDir.toPath(), MoneroNetworkType.MAINNET,
                Arrays.asList("new-password", null), "new-password", null);
             var files = mockStatic(FileUtil.class, CALLS_REAL_METHODS)) {
            files.when(() -> FileUtil.writeAtomically(eq(keys), any())).thenThrow(new IOException("injected backup write failure"));
            assertThrows(IOException.class, () -> recovery.rekeyMainWalletBackups(current));
        }
        assertArrayEquals(oldKeys, Files.readAllBytes(keys));
    }

    @Test
    public void testNativePasswordChangePreservesRecoveryCopiesOnSuccessAndFailure() throws Exception {
        MoneroUtils.tryLoadNativeLibrary();
        Assumptions.assumeTrue(MoneroUtils.isNativeLibraryLoaded());
        for (boolean fail : List.of(false, true)) {
            Path network = Files.createDirectories(dir.toPath().resolve(Boolean.toString(fail)).resolve("xmr_mainnet"));
            Path wallets = Files.createDirectory(network.resolve("wallet"));
            KeyStorage storage = new KeyStorage(Files.createDirectory(network.resolve("keys")).toFile());
            KeyRing ring = new KeyRing(storage, null, true);
            CoreAccountService account = new CoreAccountService(null, storage, ring);
            account.openAccount(null);
            ready(account);
            XmrWalletService service = walletService(account, mock(XmrConnectionService.class), wallets.toFile());
            Path path = wallets.resolve("haveno_XMR");
            MoneroWalletFull wallet = MoneroWalletFull.createWallet(new MoneroWalletConfig()
                    .setPath(path.toString()).setPassword("password").setNetworkType(MoneroNetworkType.MAINNET));
            String address = wallet.getPrimaryAddress();
            try {
                wallet.setAttribute("recovery-marker", "durable local state");
                wallet.save();
                MoneroWalletFull changing = wallet;
                if (fail) {
                    changing = mock(MoneroWalletFull.class, delegatesTo(wallet));
                    doAnswer(invocation -> {
                        wallet.changePassword(invocation.getArgument(0), invocation.getArgument(1));
                        throw new MoneroError("injected failure after wallet rewrite");
                    }).when(changing).changePassword(anyString(), anyString());
                }
                setField(XmrWalletBase.class, service, "wallet", changing);
                Path superseded = Files.createDirectories(wallets.resolve("backup/superseded")).resolve("old-cache");
                Files.write(superseded, new byte[] {1});
                if (fail) {
                    assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));
                    assertTrue(account.isPasswordRecoveryRequired());
                    assertTrue(wallet.isClosed());
                    assertTrue(Files.exists(superseded));
                } else {
                    account.changePassword(null, "new-password");
                    assertFalse(wallet.isClosed());
                    assertTrue(Files.exists(superseded));
                }
            } finally {
                wallet.close(false);
            }
            Path snapshot;
            try (var files = Files.list(wallets.resolve("backup"))) {
                snapshot = files.filter(file -> file.getFileName().toString().startsWith("password-change-")).findFirst().orElseThrow();
            }
            assertEquals(WalletPasswordChange.getMainWalletId(address), Files.readString(snapshot.resolve(WalletPasswordChange.MAIN_WALLET_ID_FILE)));
            assertFalse(Files.exists(snapshot.resolve(".main-wallet-address")));
            String target = fail ? null : "new-password";
            MoneroWalletFull copy = MoneroWalletFull.openWalletData(target == null ? "password" : target, MoneroNetworkType.MAINNET,
                    Files.readAllBytes(snapshot.resolve("haveno_XMR.keys")), Files.readAllBytes(snapshot.resolve("haveno_XMR")), null);
            try {
                assertEquals(address, copy.getPrimaryAddress());
                assertEquals("durable local state", copy.getAttribute("recovery-marker"));
            } finally {
                copy.close(false);
            }
            Files.delete(wallets.resolve("haveno_XMR.keys"));
            assertThrows(IOException.class, () -> RecoverPassword.recover(network, target, target, List.of("new-password"), null));
            assertTrue(Files.exists(snapshot.resolve("haveno_XMR.keys")));
            Files.copy(snapshot.resolve("haveno_XMR.keys"), wallets.resolve("haveno_XMR.keys"));
            Files.copy(snapshot.resolve("haveno_XMR"), path, StandardCopyOption.REPLACE_EXISTING);
            RecoverPassword.recover(network, target, target, List.of("new-password"), null);
            MoneroWalletFull recovered = MoneroWalletFull.openWallet(path.toString(), target == null ? "password" : target, MoneroNetworkType.MAINNET);
            try {
                assertEquals(address, recovered.getPrimaryAddress());
                assertEquals("durable local state", recovered.getAttribute("recovery-marker"));
            } finally {
                recovered.close(false);
            }
        }
    }

    @Test
    public void testRecoveryTriesLegacyEmptyPassword() throws Exception {
        MoneroUtils.tryLoadNativeLibrary();
        Assumptions.assumeTrue(MoneroUtils.isNativeLibraryLoaded());
        Path network = Files.createDirectory(dir.toPath().resolve("xmr_mainnet"));
        Path wallets = Files.createDirectory(network.resolve("wallet"));
        Files.createDirectory(network.resolve("db"));
        KeyStorage storage = new KeyStorage(Files.createDirectory(network.resolve("keys")).toFile());
        KeyRing ring = new KeyRing(storage, null, true);
        CoreAccountService account = new CoreAccountService(null, storage, ring);
        account.openAccount(null);
        // accounts created with an empty password before normalization used it literally
        EncryptedConnectionList list = connectionList(account, null, mock(PersistenceManager.class));
        list.addConnection(new MoneroRpcConnection("http://localhost:18081", "user", "daemon-secret"));
        list.changePassword(null, "");
        Path connectionFile = network.resolve("db/EncryptedConnectionList");
        Files.write(connectionFile, haveno.common.crypto.Encryption.encryptPayloadWithHmac(list.toProtoMessage().toByteArray(), ring.getSymmetricKey()));
        Path path = wallets.resolve("haveno_XMR");
        MoneroWalletFull wallet = MoneroWalletFull.createWallet(new MoneroWalletConfig()
                .setPath(path.toString()).setPassword("").setNetworkType(MoneroNetworkType.MAINNET));
        String address = wallet.getPrimaryAddress();
        wallet.close(true);

        RecoverPassword.recover(network, null, null, List.of(), null);

        MoneroWalletFull recovered = MoneroWalletFull.openWallet(path.toString(), "password", MoneroNetworkType.MAINNET);
        try {
            assertEquals(address, recovered.getPrimaryAddress());
        } finally {
            recovered.close(false);
        }
        protobuf.PersistableEnvelope repaired = PersistenceManager.readEncrypted(connectionFile.toFile(), ring.getSymmetricKey());
        assertEquals("daemon-secret", connectionList(account, repaired.getEncryptedConnectionList(), mock(PersistenceManager.class)).getConnections().get(0).getPassword());
    }

    private static void setField(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

}
