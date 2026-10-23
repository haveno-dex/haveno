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

import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import com.google.inject.Injector;
import haveno.common.UserThread;
import haveno.common.config.BaseCurrencyNetwork;
import haveno.common.config.Config;
import haveno.common.crypto.CryptoException;
import haveno.common.crypto.IncorrectPasswordException;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.file.FileUtil;
import haveno.common.handlers.ResultHandler;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.persistence.PersistenceManager;
import haveno.common.setup.CommonSetup;
import haveno.common.util.Utilities;
import haveno.core.api.AccountServiceListener;
import haveno.core.api.CoreAccountService;
import haveno.core.api.CoreApi;
import haveno.core.app.AppStartupState;
import haveno.core.api.XmrConnectionService;
import haveno.core.app.HavenoExecutable;
import haveno.core.app.HavenoSetup;
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
import haveno.core.xmr.wallet.WalletsManager;
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
import org.mockito.stubbing.Answer;
import org.bitcoinj.crypto.KeyCrypterScrypt;
import org.bouncycastle.crypto.params.KeyParameter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.StandardCopyOption;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import static org.mockito.Mockito.doNothing;
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
    public void testRestorePreservesInstallationFilesAndRestoresAccountData() throws Exception {
        Path restoreDir = Files.createDirectory(dir.toPath().resolve("restore"));
        Path customConfig = Files.writeString(restoreDir.resolve("custom.conf"), "");
        Path torrc = Files.writeString(restoreDir.resolve("custom-torrc"), "# trusted");
        Config config = new Config("--appDataDir=" + restoreDir, "--configFile=" + customConfig, "--torrcFile=" + torrc);
        CoreAccountService account = new CoreAccountService(config, mock(KeyStorage.class), mock(KeyRing.class));
        Path binary = Files.writeString(restoreDir.resolve("monero-wallet-rpc"), "trusted binary");
        Files.createLink(restoreDir.resolve("binary-alias"), binary);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String name : List.of("monero-wallet-rpc", "MONERO-WALLET-RPC.EXE", "monerod/child", "MONEROD.EXE",
                    "./haveno.properties", "xmr_mainnet/../haveno.properties/child", "custom.conf", "custom-torrc",
                    "binary-alias", "monero-wallet-rpc.exe.", "haveno.properties..", " haveno.properties", "custom.conf.", "custom.conf ", "xmr_mainnet/tor./torrc",
                    "haveno.propertie\u017f", "have\u200cno.properties", "cu\u017ftom.conf", "xmr_mainnet/keys/key", "xmr_mainnet/wallet/haveno_XMR.keys", "xmr_mainnet/wallet/xmr_trade_caf\u00e9_1.keys", "xmr_mainnet/db/UserPayload",
                    "xmr_mainnet/tor/torrc", "xmr_mainnet/tor/hiddenservice/private_key")) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("restored".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        try (var utilities = mockStatic(Utilities.class, CALLS_REAL_METHODS)) {
            utilities.when(Utilities::isWindows).thenReturn(true);
            account.restoreAccount(new ByteArrayInputStream(bytes.toByteArray()), 1024, () -> {});
        }
        assertEquals("trusted binary", Files.readString(binary));
        assertEquals("", Files.readString(customConfig));
        assertEquals("# trusted", Files.readString(torrc));
        // listed rather than probed by name, since Windows rejects or aliases some of the excluded names
        assertEquals(Set.of("binary-alias", "custom-torrc", "custom.conf", "monero-wallet-rpc", "xmr_mainnet"), fileNames(restoreDir));
        assertEquals(Set.of("db", "keys", "tor", "wallet"), fileNames(restoreDir.resolve("xmr_mainnet")));
        assertEquals(Set.of("hiddenservice"), fileNames(restoreDir.resolve("xmr_mainnet/tor")));
        for (String name : List.of("xmr_mainnet/keys/key", "xmr_mainnet/wallet/haveno_XMR.keys", "xmr_mainnet/wallet/xmr_trade_caf\u00e9_1.keys", "xmr_mainnet/db/UserPayload", "xmr_mainnet/tor/hiddenservice/private_key")) {
            assertEquals("restored", Files.readString(restoreDir.resolve(name)));
        }
    }

    private static Set<String> fileNames(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    @Test
    public void testRestorePreservesInstallationFilesAtRenamedLogDestinations() throws Exception {
        Path restoreDir = Files.createDirectory(dir.toPath().resolve("restore"));
        Path networkDir = Files.createDirectory(restoreDir.resolve("xmr_mainnet"));
        Path customConfig = Files.writeString(restoreDir.resolve("haveno.backup.log"), "# trusted config");
        Path torrc = Files.writeString(networkDir.resolve("haveno.backup.log"), "# trusted torrc");
        Config config = new Config("--appDataDir=" + restoreDir, "--configFile=" + customConfig, "--torrcFile=" + torrc);
        CoreAccountService account = new CoreAccountService(config, mock(KeyStorage.class), mock(KeyRing.class));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String name : List.of("haveno.log", "xmr_mainnet/haveno.log", "logs/haveno.log", "xmr_mainnet/keys/key")) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("restored".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        account.restoreAccount(new ByteArrayInputStream(bytes.toByteArray()), 1024, () -> {});
        assertEquals("# trusted config", Files.readString(customConfig));
        assertEquals("# trusted torrc", Files.readString(torrc));
        assertEquals("restored", Files.readString(restoreDir.resolve("logs/haveno.backup.log")));
        assertEquals("restored", Files.readString(networkDir.resolve("keys/key")));
    }

    @Test
    public void testWalletRpcStartupUsesPrivateEphemeralCredentials() throws Exception {
        Path binary = Files.createFile(dir.toPath().resolve(XmrWalletService.MONERO_WALLET_RPC_NAME));
        Field binaries = XmrWalletService.class.getDeclaredField("moneroBinsDir");
        binaries.setAccessible(true);
        Object previous = binaries.get(null);
        binaries.set(null, dir.toString());
        XmrWalletService service = walletService(account(null));
        Method start = XmrWalletService.class.getDeclaredMethod("startWalletRpcInstance", Integer.class, MoneroRpcConnection.class);
        start.setAccessible(true);
        List<Path> configs = new ArrayList<>();
        List<String> passwords = new ArrayList<>();
        boolean[] failStartup = {false};
        Field managerField = XmrWalletService.class.getDeclaredField("MONERO_WALLET_RPC_MANAGER");
        managerField.setAccessible(true);
        MoneroWalletRpcManager manager = (MoneroWalletRpcManager) managerField.get(null);
        manager.onStartUp();
        try (var wallets = mockConstruction(MoneroWalletRpc.class, (rpc, context) -> {
            @SuppressWarnings("unchecked")
            List<String> command = (List<String>) context.arguments().get(0);
            assertEquals(binary.toString(), command.get(0));
            assertFalse(command.contains("--rpc-login"));
            assertEquals("127.0.0.1", command.get(command.indexOf("--rpc-bind-ip") + 1));
            Path config = Path.of(command.get(command.indexOf("--config-file") + 1));
            configs.add(config);
            if (failStartup[0]) throw new MoneroError("startup failed");
            if (Files.getFileStore(config).supportsFileAttributeView("posix")) {
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(config));
            }
            String password = Files.readString(config).strip().split(":", 2)[1];
            passwords.add(password);
            assertEquals(64, password.length());
            assertFalse(command.stream().anyMatch(arg -> arg.contains(password)));
            doReturn(new MoneroRpcConnection("http://127.0.0.1:" + command.get(command.indexOf("--rpc-bind-port") + 1)))
                    .when(rpc).getRpcConnection();
        })) {
            for (int i = 0; i < 2; i++) {
                MoneroWalletRpc rpc = (MoneroWalletRpc) start.invoke(service, null, null);
                assertEquals(passwords.get(i), rpc.getRpcConnection().getPassword());
                assertFalse(Files.exists(configs.get(i)));
                manager.stopInstance(rpc, null, true);
            }
            assertFalse(passwords.get(0).equals(passwords.get(1)));
            failStartup[0] = true;
            assertThrows(InvocationTargetException.class, () -> start.invoke(service, 19191, null));
            assertEquals(3, configs.size());
            assertFalse(Files.exists(configs.get(2)));
        } finally {
            binaries.set(null, previous);
        }
    }

    @Test
    public void testBundledWalletRpcRejectsPublicPassword() throws Exception {
        Path binary = Path.of("src/main/resources/bin", XmrWalletService.MONERO_WALLET_RPC_NAME).toAbsolutePath();
        Assumptions.assumeTrue(Files.isExecutable(binary));
        Field binaries = XmrWalletService.class.getDeclaredField("moneroBinsDir");
        binaries.setAccessible(true);
        Object previous = binaries.get(null);
        binaries.set(null, binary.getParent().toString());
        MoneroWalletRpc rpc = null;
        Field managerField = XmrWalletService.class.getDeclaredField("MONERO_WALLET_RPC_MANAGER");
        managerField.setAccessible(true);
        MoneroWalletRpcManager manager = (MoneroWalletRpcManager) managerField.get(null);
        manager.onStartUp();
        try {
            XmrWalletService service = walletService(account(null));
            Method start = XmrWalletService.class.getDeclaredMethod("startWalletRpcInstance", Integer.class, MoneroRpcConnection.class);
            start.setAccessible(true);
            rpc = (MoneroWalletRpc) start.invoke(service, null, null);
            assertTrue(rpc.getVersion().getNumber() > 0);
            MoneroWalletRpc unauthorized = new MoneroWalletRpc(rpc.getRpcConnection().getUri(), "haveno_user", "password");
            assertThrows(MoneroError.class, unauthorized::getVersion);
        } finally {
            if (rpc != null) manager.stopInstance(rpc, null, true);
            binaries.set(null, previous);
        }
    }

    @Test
    public void testSeedRestoreValidatesBeforeRemovingOffers() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService wallet = mock(XmrWalletService.class);
        OpenOfferManager offers = mock(OpenOfferManager.class);
        CoreApi api = seedRestoreApi(account, wallet, offers);
        for (String seed : Arrays.asList(null, "", " \n ", "invalid seed")) {
            assertThrows(IllegalArgumentException.class, () -> api.restoreXmrWalletFromSeed(seed, 0L, null, null));
        }
        for (long height : new long[] {-1, 1L << 53, Long.MAX_VALUE}) {
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> api.restoreXmrWalletFromSeed("seed", height, null, null)).getMessage().startsWith("Restore height"));
        }
        for (String date : Arrays.asList("2023-02-29", "2020-02-30", "2020-13-01", "2020-1-01", "2020-01-01T00:00:00Z",
                "+2020-01-01", "10000-01-01", LocalDate.now(ZoneOffset.UTC).plusDays(1).toString())) {
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> api.restoreXmrWalletFromSeed("seed", null, date, null)).getMessage().startsWith("Restore date"));
        }
        for (long height : new long[] {0, 12345}) {
            assertEquals("Specify either a restore height or a restore date", assertThrows(IllegalArgumentException.class,
                    () -> api.restoreXmrWalletFromSeed("seed", height, "2020-01-01", null)).getMessage());
        }
        verify(offers, never()).removeAllOpenOffers(any(), any());
        verify(wallet, never()).restoreWalletFromSeed(any(), any(), any());
        assertTrue(account.isAccountOpen());
    }

    @Test
    public void testCreateAccountFromSeedValidatesAndCreatesWalletBeforeKeys() throws Exception {
        KeyStorage storage = new KeyStorage(Files.createDirectory(new File(dir, "seed-import").toPath()).toFile());
        CoreAccountService account = new CoreAccountService(null, storage, new KeyRing(storage));
        XmrWalletService wallet = mock(XmrWalletService.class);
        CoreApi api = seedRestoreApi(account, wallet, mock(OpenOfferManager.class));
        for (String seed : Arrays.asList(null, "", " \n ")) {
            assertEquals("Wallet seed is required", assertThrows(IllegalArgumentException.class,
                    () -> api.createAccountFromSeed("password", seed, null, null)).getMessage());
        }
        assertEquals("Invalid wallet seed", assertThrows(IllegalArgumentException.class,
                () -> api.createAccountFromSeed("password", "invalid seed", null, null)).getMessage());
        assertEquals("Wallet seed is required", assertThrows(IllegalArgumentException.class,
                () -> api.createAccountFromSeed("password", " ", 100L, null)).getMessage());
        assertEquals("Specify either a restore height or a restore date", assertThrows(IllegalArgumentException.class,
                () -> api.createAccountFromSeed("password", "seed", 100L, "2020-01-01")).getMessage());
        verify(wallet, never()).createMainWalletFromSeed(any(), any(), any());
        assertFalse(account.accountExists());

        // the wallet is created with the account password before the keys exist
        doReturn(true).when(wallet).isSeedValid("seed words");
        doAnswer(invocation -> {
            assertFalse(account.accountExists());
            assertEquals("password", account.getPassword());
            return null;
        }).when(wallet).createMainWalletFromSeed("seed words", null, LocalDate.of(2020, 2, 29));
        api.createAccountFromSeed("password", " seed\n words ", null, " 2020-02-29 ");
        verify(wallet).createMainWalletFromSeed("seed words", null, LocalDate.of(2020, 2, 29));
        assertTrue(account.isAccountOpen());

        // an existing account is rejected before the seed is validated
        clearInvocations(wallet);
        assertEquals("Cannot create account if account already exists", assertThrows(IllegalStateException.class,
                () -> api.createAccountFromSeed("password", "other seed words", null, null)).getMessage());
        verify(wallet, never()).isSeedValid(any());
        assertEquals("password", account.getPassword());
    }

    @Test
    public void testFailedWalletCreationLeavesNoAccount() throws Exception {
        KeyStorage storage = new KeyStorage(Files.createDirectory(new File(dir, "failed-import").toPath()).toFile());
        CoreAccountService account = new CoreAccountService(null, storage, new KeyRing(storage));
        XmrWalletService wallet = mock(XmrWalletService.class);
        doReturn(true).when(wallet).isSeedValid("seed");
        doThrow(new IllegalStateException("wallet creation failed")).when(wallet).createMainWalletFromSeed("seed", 0L, null);
        CoreApi api = seedRestoreApi(account, wallet, mock(OpenOfferManager.class));
        assertEquals("wallet creation failed", assertThrows(IllegalStateException.class,
                () -> api.createAccountFromSeed("password", "seed", 0L, null)).getMessage());
        assertFalse(account.accountExists());
        assertNull(account.getPassword());
        api.createAccount("password");
        assertTrue(account.isAccountOpen());
    }

    @Test
    public void testAccountCreatedListenerFailurePreservesPassword() throws Exception {
        KeyStorage storage = new KeyStorage(Files.createDirectory(new File(dir, "created-listener-failure").toPath()).toFile());
        CoreAccountService account = new CoreAccountService(null, storage, new KeyRing(storage));
        AccountServiceListener listener = mock(AccountServiceListener.class);
        doThrow(new IllegalStateException("listener failed")).when(listener).onAccountCreated();
        account.addListener(listener);
        assertEquals("listener failed", assertThrows(IllegalStateException.class, () -> account.createAccount("password")).getMessage());
        assertTrue(account.isAccountOpen());
        assertEquals("password", account.getPassword());
    }

    @Test
    public void testCreateAccountRetainsWalletOrphanedByInterruptedImport() throws Exception {
        KeyStorage storage = new KeyStorage(Files.createDirectory(new File(dir, "orphan-import").toPath()).toFile());
        CoreAccountService account = new CoreAccountService(null, storage, new KeyRing(storage));
        XmrWalletService service = walletService(account);
        Files.write(walletDir.toPath().resolve("haveno_XMR.keys"), new byte[] {1}); // created from a seed with another password
        account.createAccount(null);
        assertTrue(account.isAccountOpen());
        assertFalse(service.walletExists("haveno_XMR"));
        File[] replaced = new File(walletDir, "backup").listFiles(file -> file.getName().startsWith("replaced-"));
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(new File(replaced[0], "haveno_XMR.keys").toPath()));
    }

    @Test
    public void testFailedWalletBackupFlushPreservesOrphanAndLeavesNoAccount() throws Exception {
        KeyStorage storage = new KeyStorage(Files.createDirectory(new File(dir, "failed-retention").toPath()).toFile());
        CoreAccountService account = new CoreAccountService(null, storage, new KeyRing(storage));
        XmrWalletService service = walletService(account);
        Path keys = Files.write(walletDir.toPath().resolve("haveno_XMR.keys"), new byte[] {1});
        Path cache = Files.write(walletDir.toPath().resolve("haveno_XMR"), new byte[] {2});
        try (var files = mockStatic(FileUtil.class, CALLS_REAL_METHODS)) {
            files.when(() -> FileUtil.syncDirectory(walletDir.toPath().resolve("backup")))
                    .thenThrow(new IOException("injected backup flush failure"));
            assertTrue(assertThrows(IllegalStateException.class, () -> account.createAccount("password"))
                    .getMessage().startsWith("Could not flush wallet backup directory"));
        }
        assertFalse(account.accountExists());
        assertNull(account.getPassword());
        assertTrue(service.walletExists("haveno_XMR"));
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(keys));
        assertArrayEquals(new byte[] {2}, Files.readAllBytes(cache));
    }

    @Test
    public void testFailedOrphanRemovalFlushLeavesNoAccount() throws Exception {
        KeyStorage storage = new KeyStorage(Files.createDirectory(new File(dir, "failed-orphan-removal").toPath()).toFile());
        CoreAccountService account = new CoreAccountService(null, storage, new KeyRing(storage));
        XmrWalletService service = walletService(account);
        Path keys = Files.write(walletDir.toPath().resolve("haveno_XMR.keys"), new byte[] {1});
        Files.write(walletDir.toPath().resolve("haveno_XMR"), new byte[] {2});
        try (var files = mockStatic(FileUtil.class, CALLS_REAL_METHODS)) {
            files.when(() -> FileUtil.syncDirectory(walletDir.toPath())).thenAnswer(invocation -> {
                if (!Files.exists(keys)) throw new IOException("injected removal flush failure");
                return invocation.callRealMethod();
            });
            assertThrows(IllegalStateException.class, () -> account.createAccount("password"));
        }
        assertFalse(account.accountExists());
        assertNull(account.getPassword());
        assertFalse(service.walletExists("haveno_XMR"));
        File[] replaced = new File(walletDir, "backup").listFiles(file -> file.getName().startsWith("replaced-"));
        assertEquals(1, replaced.length);
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(new File(replaced[0], "haveno_XMR.keys").toPath()));
        assertArrayEquals(new byte[] {2}, Files.readAllBytes(new File(replaced[0], "haveno_XMR").toPath()));
    }

    @Test
    public void testCreateAccountFromSeedCreatesNativeWalletOffline() throws Exception {
        MoneroUtils.tryLoadNativeLibrary();
        Assumptions.assumeTrue(MoneroUtils.isNativeLibraryLoaded());
        KeyStorage storage = new KeyStorage(Files.createDirectory(new File(dir, "native-import").toPath()).toFile());
        CoreAccountService account = new CoreAccountService(null, storage, new KeyRing(storage));
        XmrWalletService service = creatingWalletService(account);
        MoneroNetworkType network = XmrWalletService.getMoneroNetworkType();
        MoneroWalletFull source = MoneroWalletFull.createWallet(new MoneroWalletConfig().setNetworkType(network));
        String seed = source.getSeed();
        String address = source.getPrimaryAddress();
        source.close(false);
        Path path = walletDir.toPath().resolve("haveno_XMR");
        Files.write(path.resolveSibling("haveno_XMR.keys"), new byte[] {1}); // left by a creation interrupted before the keys were written
        seedRestoreApi(account, service, mock(OpenOfferManager.class)).createAccountFromSeed("password", seed, 12345L, null);
        assertTrue(account.isAccountOpen());
        MoneroWalletFull created = MoneroWalletFull.openWallet(path.toString(), "password", network);
        try {
            assertEquals(seed, created.getSeed());
            assertEquals(address, created.getPrimaryAddress());
            assertEquals(12345L, created.getRestoreHeight());
            assertEquals("12345", created.getAttribute("haveno.restoreHeight"));
        } finally {
            created.close(false);
        }
        File[] replaced = new File(walletDir, "backup").listFiles(file -> file.getName().startsWith("replaced-"));
        assertEquals(1, replaced.length);
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(new File(replaced[0], "haveno_XMR.keys").toPath()));
    }

    @Test
    public void testRpcSeedImportRecoversCreationDateAfterRestart() throws Exception {
        Path binary = Path.of("src/main/resources/bin", XmrWalletService.MONERO_WALLET_RPC_NAME).toAbsolutePath();
        Assumptions.assumeTrue(Files.isExecutable(binary));
        MoneroUtils.tryLoadNativeLibrary();
        Assumptions.assumeTrue(MoneroUtils.isNativeLibraryLoaded());
        KeyStorage storage = new KeyStorage(Files.createDirectory(new File(dir, "rpc-import").toPath()).toFile());
        CoreAccountService account = new CoreAccountService(null, storage, new KeyRing(storage));
        XmrWalletService service = walletService(account);
        MoneroWalletFull source = MoneroWalletFull.createWallet(new MoneroWalletConfig().setNetworkType(XmrWalletService.getMoneroNetworkType()));
        String seed = source.getSeed();
        String address = source.getPrimaryAddress();
        source.close(false);
        LocalDate date = LocalDate.of(2020, 2, 29);
        long height = XmrWalletService.estimateHeightForDate(date);
        Method estimate = XmrWalletService.class.getDeclaredMethod("estimateHeightTimestamp", long.class);
        estimate.setAccessible(true);

        try (var paths = mockStatic(XmrWalletService.class, CALLS_REAL_METHODS)) {
            paths.when(XmrWalletService::getMoneroWalletRpcPath).thenReturn(binary.toString());
            seedRestoreApi(account, service, mock(OpenOfferManager.class)).createAccountFromSeed("password", seed, null, date.toString());

            // no wallet initialization or user-data persistence before recreating the account and services
            CoreAccountService reopened = new CoreAccountService(null, storage, new KeyRing(storage));
            reopened.openAccount("password");
            String[] fallbackAttributes = {"", "invalid", "-1"};
            for (int reopen = 0; reopen <= fallbackAttributes.length + 2; reopen++) {
                XmrConnectionService connections = mock(XmrConnectionService.class);
                doReturn(new MoneroRpcConnection("http://127.0.0.1:1")).when(connections).getConnection();
                doReturn(true).when(connections).isConnectionLocalHost();
                XmrWalletService restarted = spy(walletService(reopened, connections, walletDir));
                User user = mock(User.class);
                setField(XmrWalletService.class, restarted, "user", user);
                boolean dateSaved = reopen == fallbackAttributes.length + 2;
                if (dateSaved) doReturn((long) estimate.invoke(null, height)).when(user).getWalletCreationDate();

                // a failed metadata RPC must not prevent opening, and a saved date needs no metadata RPC
                // the first handle reads the metadata, since Windows reopens the wallet after backing it up
                List<MoneroRpcConnection> metadataConnections = new ArrayList<>();
                if (reopen > fallbackAttributes.length) {
                    doAnswer(invocation -> {
                        MoneroWalletRpc rpc = (MoneroWalletRpc) invocation.callRealMethod();
                        MoneroRpcConnection connection = spy(rpc.getRpcConnection());
                        doThrow(new MoneroError("metadata unavailable")).when(connection).sendJsonRequest(eq("get_attribute"), any());
                        setField(MoneroWalletRpc.class, rpc, "rpc", connection);
                        metadataConnections.add(connection);
                        return rpc;
                    }).when(restarted).openWallet(anyString(), any(), anyBoolean(), anyBoolean());
                }
                try {
                    MoneroWallet wallet = openMainWallet(restarted);
                    assertTrue(wallet instanceof MoneroWalletRpc);
                    assertEquals(address, wallet.getPrimaryAddress());
                    if (reopen == 0) verify(user).setWalletCreationDate((long) estimate.invoke(null, height));
                    else verify(user, never()).setWalletCreationDate(anyLong()); // absent or malformed attributes preserve the legacy fallback
                    if (dateSaved) for (MoneroRpcConnection connection : metadataConnections) verify(connection, never()).sendJsonRequest(eq("get_attribute"), any());
                    else if (reopen > fallbackAttributes.length) verify(metadataConnections.get(0)).sendJsonRequest(eq("get_attribute"), any());
                    if (reopen < fallbackAttributes.length) wallet.setAttribute("haveno.restoreHeight", fallbackAttributes[reopen]);
                } finally {
                    closeMainWallet(restarted);
                }
            }
        }
    }

    @Test
    public void testFailedImportMetadataClosesWalletAndLeavesNoAccount() throws Exception {
        KeyStorage storage = new KeyStorage(Files.createDirectory(new File(dir, "failed-import-metadata").toPath()).toFile());
        CoreAccountService account = new CoreAccountService(null, storage, new KeyRing(storage));
        XmrWalletService service = creatingWalletService(account);
        MoneroWalletFull wallet = mockWallet();
        doThrow(new IllegalStateException("metadata failed")).when(wallet).setAttribute("haveno.restoreHeight", "12345");
        try (var utils = mockStatic(MoneroUtils.class, CALLS_REAL_METHODS);
             var wallets = mockStatic(MoneroWalletFull.class)) {
            utils.when(MoneroUtils::isNativeLibraryLoaded).thenReturn(true);
            wallets.when(() -> MoneroWalletFull.createWallet(any(MoneroWalletConfig.class))).thenReturn(wallet);
            assertEquals("metadata failed", assertThrows(IllegalStateException.class,
                    () -> account.createAccount("password", () -> service.createMainWalletFromSeed("seed", 12345L, null))).getMessage());
            verify(wallet).close(false);
            assertFalse(account.accountExists());
            assertNull(account.getPassword());
        }
    }

    @Test
    public void testSeedRestoreForwardsOptionalDate() throws Exception {
        for (String restoreDate : Arrays.asList(null, "", "  ", "0000-01-01", " 2020-02-29 ", LocalDate.now(ZoneOffset.UTC).toString())) {
            CoreAccountService account = account(null);
            XmrWalletService wallet = mock(XmrWalletService.class);
            doReturn(true).when(wallet).isSeedValid("seed");
            OpenOfferManager offers = mock(OpenOfferManager.class);
            doAnswer(invocation -> {
                invocation.<Runnable>getArgument(0).run();
                return null;
            }).when(offers).removeAllOpenOffers(any(), any());
            seedRestoreApi(account, wallet, offers).restoreXmrWalletFromSeed("seed", null, restoreDate, null);
            LocalDate expectedDate = restoreDate == null || restoreDate.trim().isEmpty() ? null : LocalDate.parse(restoreDate.trim());
            verify(wallet).restoreWalletFromSeed("seed", null, expectedDate);
        }
    }

    @Test
    public void testSeedRestoreAcceptsMaximumClientHeight() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService wallet = mock(XmrWalletService.class);
        doReturn(true).when(wallet).isSeedValid("seed");
        OpenOfferManager offers = mock(OpenOfferManager.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(offers).removeAllOpenOffers(any(), any());
        long height = (1L << 53) - 1;
        seedRestoreApi(account, wallet, offers).restoreXmrWalletFromSeed("seed", height, null, null);
        verify(wallet).restoreWalletFromSeed("seed", height, null);
        Method estimate = XmrWalletService.class.getDeclaredMethod("estimateHeightTimestamp", long.class);
        estimate.setAccessible(true);
        assertTrue((long) estimate.invoke(null, height) > 0);
    }

    @Test
    public void testSeedRestoreRequiresUsableAccount() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService wallet = mock(XmrWalletService.class);
        CoreApi api = seedRestoreApi(account, wallet, mock(OpenOfferManager.class));
        account.closeAccount();
        assertThrows(IllegalStateException.class, () -> api.restoreXmrWalletFromSeed("seed", 0L, null, null));
        account.openAccount(null);
        account.requirePasswordRecovery();
        assertThrows(IllegalStateException.class, () -> api.restoreXmrWalletFromSeed("seed", 0L, null, null));
        verify(wallet, never()).isSeedValid(any());
    }

    @Test
    public void testSeedRestoreStopsOnOfferRemovalFailure() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService wallet = mock(XmrWalletService.class);
        doReturn(true).when(wallet).isSeedValid("seed");
        OpenOfferManager offers = mock(OpenOfferManager.class);
        doAnswer(invocation -> {
            invocation.<ErrorMessageHandler>getArgument(1).handleErrorMessage("removal failed");
            return null;
        }).when(offers).removeAllOpenOffers(any(), any());
        AccountServiceListener listener = mock(AccountServiceListener.class);
        account.addListener(listener);
        CoreApi api = seedRestoreApi(account, wallet, offers);
        assertEquals("removal failed", assertThrows(IllegalStateException.class,
                () -> api.restoreXmrWalletFromSeed("seed", 0L, null, null)).getMessage());
        verify(wallet, never()).restoreWalletFromSeed(any(), any(), any());
        verify(listener, never()).onWalletRestored(any());
        account.closeAccount();
    }

    @Test
    public void testSeedRestoreRequiresDaemonConnectionBeforeRemovingOffers() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService wallet = mock(XmrWalletService.class);
        doReturn(true).when(wallet).isSeedValid("seed");
        OpenOfferManager offers = mock(OpenOfferManager.class);
        CoreApi api = seedRestoreApi(account, wallet, offers);
        XmrConnectionService connections = wallet.getXmrConnectionService();
        for (Boolean connected : Arrays.asList(null, false)) {
            doReturn(connected).when(connections).isConnected();
            assertTrue(assertThrows(IllegalStateException.class,
                    () -> api.restoreXmrWalletFromSeed("seed", 0L, null, null)).getMessage().contains("no connection"));
        }
        verify(offers, never()).removeAllOpenOffers(any(), any());
        verify(wallet, never()).restoreWalletFromSeed(any(), any(), any());
        assertTrue(account.isAccountOpen());
    }

    @Test
    public void testSeedRestoreWaitsForOfferRemovalAndRestartsOnlyAfterSuccess() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService wallet = mock(XmrWalletService.class);
        doReturn(true).when(wallet).isSeedValid("seed words");
        OpenOfferManager offers = mock(OpenOfferManager.class);
        CompletableFuture<Runnable> removal = new CompletableFuture<>();
        doAnswer(invocation -> {
            removal.complete(invocation.getArgument(0));
            return null;
        }).when(offers).removeAllOpenOffers(any(), any());
        AccountServiceListener listener = mock(AccountServiceListener.class);
        account.addListener(listener);
        CoreApi api = seedRestoreApi(account, wallet, offers);
        CompletableFuture<Void> restore = CompletableFuture.runAsync(() -> api.restoreXmrWalletFromSeed("  seed\n words  ", 12345L, null, null));
        try {
            Runnable removed = removal.get(10, TimeUnit.SECONDS);
            verify(wallet, never()).restoreWalletFromSeed(any(), any(), any());
            verify(listener, never()).onWalletRestored(any());
            removed.run();
            restore.get(10, TimeUnit.SECONDS);
            verify(wallet).restoreWalletFromSeed("seed words", 12345L, null);
            verify(listener).onWalletRestored(null);
            verify(listener, never()).onAccountRestored(any());
            assertThrows(IllegalStateException.class, account::closeAccount);
            assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));
        } finally {
            removal.get(10, TimeUnit.SECONDS).run();
            restore.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testFailedSeedRestoreDoesNotRestartOrLockAccount() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService wallet = mock(XmrWalletService.class);
        doReturn(true).when(wallet).isSeedValid("seed");
        doThrow(new IllegalStateException("wallet creation failed")).when(wallet).restoreWalletFromSeed("seed", 0L, null);
        OpenOfferManager offers = mock(OpenOfferManager.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(offers).removeAllOpenOffers(any(), any());
        AccountServiceListener listener = mock(AccountServiceListener.class);
        account.addListener(listener);
        CoreApi api = seedRestoreApi(account, wallet, offers);
        assertEquals("wallet creation failed", assertThrows(IllegalStateException.class,
                () -> api.restoreXmrWalletFromSeed("seed", 0L, null, null)).getMessage());
        verify(listener, never()).onWalletRestored(any());
        assertTrue(account.isAccountOpen());
        account.closeAccount();
    }

    @Test
    public void testShutdownWaitsForSeedRestoreAndPreventsFurtherRestores() throws Exception {
        CoreAccountService account = account(null);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        CompletableFuture<Void> restore = CompletableFuture.runAsync(() -> account.withWalletRestore(() -> {
            started.countDown();
            assertDoesNotThrow(() -> assertTrue(proceed.await(10, TimeUnit.SECONDS)));
            throw new IllegalStateException("restore failed");
        }, null));
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            CompletableFuture<Void> shutdown = account.onShutDownStarted();
            assertFalse(shutdown.isDone());
            proceed.countDown();
            assertThrows(ExecutionException.class, () -> restore.get(10, TimeUnit.SECONDS));
            shutdown.get(10, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () -> account.withWalletRestore(() -> {}, null));
        } finally {
            proceed.countDown();
        }
    }

    @Test
    public void testSeedRestoreReplacesNativeWalletAndPreservesOldWalletBackup() throws Exception {
        restoreNativeWalletAndCheckBackup("");
    }

    @Test
    public void testSeedRestoreFromDateReplacesNativeWalletAndPreservesOldWalletBackup() throws Exception {
        restoreNativeWalletAndCheckBackup("2020-02-29");
    }

    @Test
    public void testSeedRestoreFromPreGenesisDateScansFromGenesis() throws Exception {
        restoreNativeWalletAndCheckBackup("2010-01-01");
    }

    @Test
    public void testSeedRestoreRecoversAfterInterruptedWalletMoves() throws Exception {
        for (int move = 1; move <= 6; move++) restoreWithInterruptedMove(move, true);
    }

    @Test
    public void testSeedRestoreRecoversAfterWalletMoveIoFailures() throws Exception {
        for (int move = 1; move <= 6; move++) restoreWithInterruptedMove(move, false);
    }

    private void restoreWithInterruptedMove(int interruptedMove, boolean abrupt) throws Exception {
        MoneroUtils.tryLoadNativeLibrary();
        Assumptions.assumeTrue(MoneroUtils.isNativeLibraryLoaded());
        File wallets = Files.createDirectory(walletDir.toPath().resolve((abrupt ? "crash-" : "io-") + interruptedMove)).toFile();
        CoreAccountService account = account(null);
        XmrWalletService service = creatingWalletService(account, wallets);
        MoneroNetworkType network = XmrWalletService.getMoneroNetworkType();
        MoneroWalletFull source = MoneroWalletFull.createWallet(new MoneroWalletConfig().setNetworkType(network));
        String seed = source.getSeed();
        String restoredAddress = source.getPrimaryAddress();
        source.close(false);
        Path main = wallets.toPath().resolve("haveno_XMR");
        Path staged = wallets.toPath().resolve("haveno_XMR_restore");
        MoneroWalletFull original = MoneroWalletFull.createWallet(new MoneroWalletConfig()
                .setPath(main.toString()).setPassword("password").setNetworkType(network));
        String originalAddress = original.getPrimaryAddress();
        original.setAttribute("backup-marker", "original cache state");
        setField(XmrWalletBase.class, service, "wallet", original);
        Files.writeString(main.resolveSibling("haveno_XMR.address.txt"), originalAddress);
        AtomicInteger moves = new AtomicInteger();
        AtomicReference<Path> movedFile = new AtomicReference<>();
        Error interruption = new Error("injected interruption");
        try (var files = mockStatic(FileUtil.class, CALLS_REAL_METHODS);
             var pollers = mockConstruction(TaskLooper.class)) {
            files.when(() -> FileUtil.atomicReplace(any(File.class), any(File.class))).thenAnswer(invocation -> {
                if (moves.get() == 0) {
                    // exercise the address-file move even on mainnet, where it is normally absent
                    Path address = staged.resolveSibling("haveno_XMR_restore.address.txt");
                    Files.writeString(address, restoredAddress);
                    FileUtil.syncFileAndDirectory(address);
                }
                invocation.callRealMethod();
                movedFile.set(invocation.<File>getArgument(1).toPath());
                moves.incrementAndGet();
                return null;
            });
            files.when(() -> FileUtil.syncFileAndDirectory(any(Path.class))).thenAnswer(invocation -> {
                invocation.callRealMethod();
                if (invocation.getArgument(0).equals(movedFile.get()) && moves.get() == interruptedMove) {
                    if (abrupt) throw interruption;
                    throw new IOException("injected move flush failure");
                }
                return null;
            });
            if (abrupt) assertSame(interruption, assertThrows(Error.class, () -> service.restoreWalletFromSeed(seed, 12345L, null)));
            else assertThrows(RuntimeException.class, () -> service.restoreWalletFromSeed(seed, 12345L, null));
            assertEquals(interruptedMove, moves.get());
        } finally {
            closeMainWallet(service);
        }

        File[] backups = new File(wallets, "backup").listFiles(file -> file.getName().startsWith("replaced-"));
        assertEquals(1, backups.length);
        Path backup = backups[0].toPath();
        Path oldKeys = interruptedMove < 3 ? main.resolveSibling("haveno_XMR.keys") : backup.resolve("haveno_XMR.keys");
        // an interruption during retention can split the keys and cache, but both must remain recoverable
        MoneroWalletFull retained = MoneroWalletFull.openWalletData("password", network,
                Files.readAllBytes(oldKeys), Files.readAllBytes(backup.resolve("haveno_XMR")), null);
        try {
            assertEquals(originalAddress, retained.getPrimaryAddress());
            assertEquals("original cache state", retained.getAttribute("backup-marker"));
        } finally {
            retained.close(false);
        }

        XmrWalletService restarted = creatingWalletService(account, wallets);
        doReturn(true).when(restarted.getXmrConnectionService()).isConnectionLocalHost();
        try {
            MoneroWallet wallet = openMainWallet(restarted);
            assertEquals(interruptedMove < 3 ? originalAddress : restoredAddress, wallet.getPrimaryAddress());
            if (interruptedMove >= 3) assertEquals(12345L, ((MoneroWalletFull) wallet).getRestoreHeight());
        } finally {
            closeMainWallet(restarted);
        }
        assertTrue(Files.exists(main));
        assertTrue(Files.exists(main.resolveSibling("haveno_XMR.keys")));
        assertEquals(interruptedMove < 3, Files.exists(staged.resolveSibling("haveno_XMR_restore.keys")));
    }

    private MoneroWallet openMainWallet(XmrWalletService service) throws Exception {
        Method open = XmrWalletService.class.getDeclaredMethod("openOrCreateMainWallet");
        open.setAccessible(true);
        return (MoneroWallet) open.invoke(service);
    }

    private void closeMainWallet(XmrWalletService service) throws Exception {
        Method close = XmrWalletService.class.getDeclaredMethod("closeMainWallet", boolean.class);
        close.setAccessible(true);
        assertEquals(true, close.invoke(service, true));
    }

    private void restoreNativeWalletAndCheckBackup(String restoreDate) throws Exception {
        MoneroUtils.tryLoadNativeLibrary();
        Assumptions.assumeTrue(MoneroUtils.isNativeLibraryLoaded());
        CoreAccountService account = account(null);
        XmrWalletService service = creatingWalletService(account);
        MoneroNetworkType network = XmrWalletService.getMoneroNetworkType();
        MoneroWalletFull source = MoneroWalletFull.createWallet(new MoneroWalletConfig().setNetworkType(network));
        String seed = source.getSeed();
        String restoredAddress = source.getPrimaryAddress();
        source.close(false);
        Path path = walletDir.toPath().resolve("haveno_XMR");
        MoneroWalletFull original = MoneroWalletFull.createWallet(new MoneroWalletConfig()
                .setPath(path.toString()).setPassword("password").setNetworkType(network));
        String originalAddress = original.getPrimaryAddress();
        original.save();

        // read keys with the wallet closed because Windows locks the file
        original.close(false);
        byte[] keys = Files.readAllBytes(path.resolveSibling("haveno_XMR.keys"));
        original = MoneroWalletFull.openWallet(path.toString(), "password", network);
        setField(XmrWalletBase.class, service, "wallet", original);
        OpenOfferManager offers = mock(OpenOfferManager.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(offers).removeAllOpenOffers(any(), any());
        CoreApi api = seedRestoreApi(account, service, offers);
        try {
            assertThrows(IllegalArgumentException.class, () -> api.restoreXmrWalletFromSeed("invalid seed", 0L, null, null));
            assertFalse(original.isClosed());
            verify(offers, never()).removeAllOpenOffers(any(), any());
            original.close(false);
            assertArrayEquals(keys, Files.readAllBytes(path.resolveSibling("haveno_XMR.keys")));
            original = MoneroWalletFull.openWallet(path.toString(), "password", network);
            setField(XmrWalletBase.class, service, "wallet", original);

            Long restoreHeight = restoreDate.isEmpty() ? 12345L : null;
            long expectedHeight = restoreDate.isEmpty() ? 12345L : XmrWalletService.estimateHeightForDate(LocalDate.parse(restoreDate));
            api.restoreXmrWalletFromSeed(seed, restoreHeight, restoreDate, null);
            assertTrue(original.isClosed());
            MoneroWalletFull restored = MoneroWalletFull.openWallet(path.toString(), "password", network);
            try {
                assertEquals(seed, restored.getSeed());
                assertEquals(restoredAddress, restored.getPrimaryAddress());
                assertEquals(expectedHeight, restored.getRestoreHeight());
            } finally {
                restored.close(false);
            }
            File[] replaced = new File(walletDir, "backup").listFiles(file -> file.getName().startsWith("replaced-"));
            assertEquals(1, replaced.length);
            MoneroWalletFull backup = MoneroWalletFull.openWalletData("password", network,
                    Files.readAllBytes(new File(replaced[0], "haveno_XMR.keys").toPath()),
                    Files.readAllBytes(new File(replaced[0], "haveno_XMR").toPath()), null);
            try {
                assertEquals(originalAddress, backup.getPrimaryAddress());
            } finally {
                backup.close(false);
            }
        } finally {
            original.close(false);
        }
    }

    private CoreApi seedRestoreApi(CoreAccountService account, XmrWalletService wallet, OpenOfferManager offers) throws Exception {
        Class<?> type = Class.forName("haveno.core.api.CoreWalletsService");
        Object service = mock(type, CALLS_REAL_METHODS);
        AppStartupState startup = mock(AppStartupState.class);
        doReturn(true).when(startup).isWalletAndNetworkReady();
        WalletsManager wallets = mock(WalletsManager.class);
        doReturn(true).when(wallets).areWalletsAvailable();
        setField(type, service, "accountService", account);
        setField(type, service, "appStartupState", startup);
        setField(type, service, "walletsManager", wallets);
        setField(type, service, "xmrWalletService", wallet);
        setField(type, service, "openOfferManager", offers);
        if (wallet.getXmrConnectionService() == null) { // a mocked wallet service reports a connected daemon
            XmrConnectionService connections = mock(XmrConnectionService.class);
            doReturn(true).when(connections).isConnected();
            doReturn(connections).when(wallet).getXmrConnectionService();
        }
        CoreApi api = mock(CoreApi.class, CALLS_REAL_METHODS);
        setField(CoreApi.class, api, "coreAccountService", account);
        setField(CoreApi.class, api, "walletsService", service);
        return api;
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
        }).when(mainWallet).changePassword(any(), any());
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
                                                  PersistenceManager<EncryptedConnectionList> persistence) throws InterruptedException {
        doAnswer(invocation -> {
            if (persisted == null) ((Runnable) invocation.getArgument(1)).run();
            else ((Consumer<EncryptedConnectionList>) invocation.getArgument(0)).accept(EncryptedConnectionList.fromProto(persisted));
            return null;
        }).when(persistence).readPersisted(any(), any());
        EncryptedConnectionList list = new EncryptedConnectionList(persistence, account);
        CountDownLatch initialized = new CountDownLatch(1);
        list.readPersisted(initialized::countDown);
        assertTrue(initialized.await(10, TimeUnit.SECONDS));
        return list;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testConnectionKeyDerivationDoesNotBlockUserThread() throws Exception {
        Executor previousExecutor = UserThread.getExecutor();
        ExecutorService userExecutor = Executors.newSingleThreadExecutor();
        UserThread.setExecutor(userExecutor);
        try {
            for (boolean fail : new boolean[] {false, true}) {
                CoreAccountService account = mock(CoreAccountService.class);
                doReturn("password").when(account).getPassword();
                PersistenceManager<EncryptedConnectionList> persistence = mock(PersistenceManager.class);
                EncryptedConnectionList persisted = EncryptedConnectionList.fromProto(protobuf.EncryptedConnectionList.newBuilder()
                        .setSalt(ByteString.copyFrom(new byte[8])).build());
                KeyCrypterScrypt crypter = mock(KeyCrypterScrypt.class);
                CountDownLatch deriving = new CountDownLatch(1);
                CountDownLatch resume = new CountDownLatch(1);
                AtomicReference<Thread> derivationThread = new AtomicReference<>();
                doAnswer(invocation -> {
                    derivationThread.set(Thread.currentThread());
                    deriving.countDown();
                    assertTrue(resume.await(10, TimeUnit.SECONDS));
                    if (fail) throw new IllegalStateException("injected derivation failure");
                    return new KeyParameter(new byte[32]);
                }).when(crypter).deriveKey(any());
                setField(EncryptedConnectionList.class, persisted, "keyCrypterScrypt", crypter);
                doAnswer(invocation -> {
                    ((Consumer<EncryptedConnectionList>) invocation.getArgument(0)).accept(persisted);
                    return null;
                }).when(persistence).readPersisted(any(), any());
                EncryptedConnectionList list = new EncryptedConnectionList(persistence, account);
                CompletableFuture<Thread> initialized = new CompletableFuture<>();
                AtomicInteger callbacks = new AtomicInteger();
                try {
                    UserThread.execute(() -> list.readPersisted(() -> {
                        callbacks.incrementAndGet();
                        initialized.complete(Thread.currentThread());
                    }));
                    assertTrue(deriving.await(10, TimeUnit.SECONDS));
                    CompletableFuture<Thread> heartbeat = CompletableFuture.supplyAsync(() -> {
                        assertTrue(list.getConnections().isEmpty());
                        return Thread.currentThread();
                    }, userExecutor);
                    Thread userThread = heartbeat.get(10, TimeUnit.SECONDS);
                    assertFalse(userThread == derivationThread.get());
                    assertFalse(initialized.isDone());
                    verify(account, never()).addPasswordChangeHandler(any(), any());
                    resume.countDown();
                    assertSame(userThread, initialized.get(10, TimeUnit.SECONDS));
                    assertEquals(1, callbacks.get());
                    verify(account, times(fail ? 0 : 1)).addPasswordChangeHandler(eq(CoreAccountService.PasswordChangeTarget.CONNECTIONS), any());
                } finally {
                    resume.countDown();
                }
            }
        } finally {
            UserThread.setExecutor(previousExecutor);
            userExecutor.shutdownNow();
        }
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
        XmrAddressEntryList addresses = mock(XmrAddressEntryList.class);
        doReturn(ImmutableList.of()).when(addresses).getAddressEntriesAsListImmutable();
        return constructor.newInstance(mock(User.class), mock(Preferences.class), account, connections,
                mock(WalletsSetup.class), addresses, walletDir, 0);
    }

    private XmrWalletService creatingWalletService(CoreAccountService account) throws Exception {
        return creatingWalletService(account, walletDir);
    }

    private XmrWalletService creatingWalletService(CoreAccountService account, File walletDir) throws Exception {
        XmrConnectionService connections = mock(XmrConnectionService.class);
        doReturn(true).when(connections).isConnected();
        doReturn(new MoneroRpcConnection("http://127.0.0.1:18081")).when(connections).getConnection();
        XmrWalletService service = walletService(account, connections, walletDir);
        Preferences preferences = mock(Preferences.class);
        doReturn(true).when(preferences).isUseNativeXmrWallet();
        setField(XmrWalletService.class, service, "preferences", preferences);
        return service;
    }

    private CompletableFuture<MoneroWallet> createWalletAsync(XmrWalletService service, String name, Answer<MoneroWalletFull> factory) {
        return CompletableFuture.supplyAsync(() -> {
            try (var utils = mockStatic(MoneroUtils.class, CALLS_REAL_METHODS);
                 var wallets = mockStatic(MoneroWalletFull.class)) {
                utils.when(MoneroUtils::isNativeLibraryLoaded).thenReturn(true);
                wallets.when(() -> MoneroWalletFull.createWallet(any(MoneroWalletConfig.class))).thenAnswer(factory);
                return service.createWallet(name, false, true);
            }
        }, task -> new Thread(task).start());
    }

    private void awaitWalletLifecycleLock(XmrWalletService service, Thread thread) throws Exception {
        Field field = XmrWalletService.class.getDeclaredField("walletLifecycleLock");
        field.setAccessible(true);
        ReentrantReadWriteLock lock = (ReentrantReadWriteLock) field.get(service);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!lock.hasQueuedThread(thread) && thread.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(lock.hasQueuedThread(thread));
    }

    @Test
    public void testConcurrentWalletCreationsFinishBeforePasswordSnapshot() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService service = creatingWalletService(account);
        CountDownLatch creating = new CountDownLatch(2);
        CountDownLatch finishCreation = new CountDownLatch(1);
        List<Trade> trades = new ArrayList<>();
        List<MoneroWalletFull> wallets = new ArrayList<>();
        List<CompletableFuture<MoneroWallet>> creations = new ArrayList<>();
        for (String name : List.of("first", "second")) {
            MoneroWalletFull wallet = mockWallet();
            wallets.add(wallet);
            doReturn(new MoneroRpcConnection("http://127.0.0.1:18081")).when(wallet).getDaemonConnection();
            Trade trade = mock(Trade.class);
            trades.add(trade);
            doReturn(name).when(trade).getWalletName();
            doAnswer(invocation -> {
                service.changeWalletPassword(name, wallet, "new-password", true);
                return null;
            }).when(trade).changeWalletPassword("new-password");
            creations.add(createWalletAsync(service, name, invocation -> {
                MoneroWalletConfig config = invocation.getArgument(0);
                assertEquals("password", config.getPassword());
                creating.countDown();
                assertTrue(finishCreation.await(10, TimeUnit.SECONDS));
                Files.write(Path.of(config.getPath() + ".keys"), new byte[] {1});
                Files.write(Path.of(config.getPath()), new byte[] {2});
                return wallet;
            }));
        }
        doReturn(trades).when(HavenoUtils.tradeManager).getAllTrades();
        FutureTask<List<String>> change = new FutureTask<>(() -> account.changePassword(null, "new-password"));
        Thread changing = new Thread(change);
        try {
            assertTrue(creating.await(5, TimeUnit.SECONDS));
            // deleting another wallet can finish while both creations are still running
            for (String suffix : List.of("", ".keys", ".address.txt")) Files.write(walletDir.toPath().resolve("deleted" + suffix), new byte[] {3});
            CompletableFuture.runAsync(() -> service.deleteWalletAndRetainBackup("deleted")).get(5, TimeUnit.SECONDS);
            assertFalse(service.walletExists("deleted"));
            assertArrayEquals(new byte[] {3}, Files.readAllBytes(FileUtil.getLatestBackupFile(walletDir, "deleted.keys").toPath()));
            changing.start();
            awaitWalletLifecycleLock(service, changing);
            assertEquals("password", service.getWalletPassword("first"));
        } finally {
            finishCreation.countDown();
            for (CompletableFuture<MoneroWallet> creation : creations) creation.get(10, TimeUnit.SECONDS);
            changing.join(10000);
        }
        change.get(10, TimeUnit.SECONDS);
        for (MoneroWalletFull wallet : wallets) verify(wallet).changePassword("password", "new-password");
        assertFalse(account.isPasswordRecoveryRequired());
        assertEquals("new-password", account("new-password").getPassword());
    }

    @Test
    public void testWalletCreatedDuringPasswordChangeFinishesBackupBeforeCleanup() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService service = creatingWalletService(account);
        CountDownLatch changingWallets = new CountDownLatch(1);
        CountDownLatch finishChange = new CountDownLatch(1);
        Trade trade = mock(Trade.class);
        doReturn("existing").when(trade).getWalletName();
        doReturn(List.of(trade)).when(HavenoUtils.tradeManager).getAllTrades();
        doAnswer(invocation -> {
            changingWallets.countDown();
            assertTrue(finishChange.await(10, TimeUnit.SECONDS));
            return null;
        }).when(trade).changeWalletPassword("new-password");
        CountDownLatch backingUp = new CountDownLatch(1);
        CountDownLatch finishBackup = new CountDownLatch(1);
        MoneroWalletFull wallet = mockWallet();
        doReturn(new MoneroRpcConnection("http://127.0.0.1:18081")).when(wallet).getDaemonConnection();
        doAnswer(invocation -> {
            backingUp.countDown();
            assertTrue(finishBackup.await(10, TimeUnit.SECONDS));
            return new byte[][] {new byte[] {1}, new byte[] {2}};
        }).when(wallet).getData();
        FutureTask<List<String>> change = new FutureTask<>(() -> account.changePassword(null, "new-password"));
        Thread changing = new Thread(change);
        CompletableFuture<MoneroWallet> creation = null;
        try {
            changing.start();
            assertTrue(changingWallets.await(5, TimeUnit.SECONDS));
            creation = createWalletAsync(service, "new-trade", invocation -> {
                MoneroWalletConfig config = invocation.getArgument(0);
                assertEquals("new-password", config.getPassword());
                Files.write(Path.of(config.getPath() + ".keys"), new byte[] {1});
                Files.write(Path.of(config.getPath()), new byte[] {2});
                return wallet;
            });
            assertTrue(backingUp.await(5, TimeUnit.SECONDS));
            finishChange.countDown();
            awaitWalletLifecycleLock(service, changing);
        } finally {
            finishChange.countDown();
            finishBackup.countDown();
            if (creation != null) creation.get(10, TimeUnit.SECONDS);
            changing.join(10000);
        }
        change.get(10, TimeUnit.SECONDS);
        assertFalse(account.isPasswordRecoveryRequired());
        assertEquals("new-password", service.getWalletPassword("new-trade"));
        try (var backups = Files.list(walletDir.toPath().resolve("backup"))) {
            Path backup = backups.filter(path -> path.getFileName().toString().startsWith("password-change-")).findFirst().orElseThrow();
            assertArrayEquals(new byte[] {1}, Files.readAllBytes(backup.resolve("new-trade.keys")));
            assertArrayEquals(new byte[] {2}, Files.readAllBytes(backup.resolve("new-trade")));
        }
    }

    @Test
    public void testFailedWalletCreationReleasesPasswordChangeLock() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService service = creatingWalletService(account);
        CompletableFuture<MoneroWallet> creation = createWalletAsync(service, "failed", invocation -> {
            throw new MoneroError("injected creation failure");
        });
        assertThrows(ExecutionException.class, () -> creation.get(10, TimeUnit.SECONDS));
        CompletableFuture.supplyAsync(() -> account.changePassword(null, "new-password")).get(10, TimeUnit.SECONDS);
        assertFalse(account.isPasswordRecoveryRequired());
        assertEquals("new-password", account("new-password").getPassword());
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
    public void testNativePasswordChangeSkipsOrphanOnOlderPassword() throws Exception {
        MoneroUtils.tryLoadNativeLibrary();
        Assumptions.assumeTrue(MoneroUtils.isNativeLibraryLoaded());
        CoreAccountService account = account(null);
        XmrWalletService service = walletService(account);
        Preferences preferences = mock(Preferences.class);
        doReturn(true).when(preferences).isUseNativeXmrWallet();
        setField(XmrWalletService.class, service, "preferences", preferences);
        MoneroWallet mainWallet = mockWallet();
        Files.createFile(walletDir.toPath().resolve("haveno_XMR.keys"));
        setField(XmrWalletBase.class, service, "wallet", mainWallet);
        Path path = walletDir.toPath().resolve("orphan_trade");
        MoneroWalletFull.createWallet(new MoneroWalletConfig()
                .setPath(path.toString()).setPassword("older-password").setNetworkType(XmrWalletService.getMoneroNetworkType())).close(true);
        byte[] keys = Files.readAllBytes(path.resolveSibling("orphan_trade.keys"));
        byte[] cache = Files.readAllBytes(path);
        Path rollingBackup = Files.createDirectories(walletDir.toPath().resolve("backup/backups_orphan_trade"));

        List<String> retained = account.changePassword(null, "new-password");

        assertTrue(retained.contains("wallet/orphan_trade.keys"));
        assertTrue(Files.isDirectory(rollingBackup));
        assertFalse(account.isPasswordRecoveryRequired());
        assertEquals("new-password", account("new-password").getPassword());
        verify(mainWallet).changePassword("password", "new-password");
        assertArrayEquals(keys, Files.readAllBytes(path.resolveSibling("orphan_trade.keys")));
        assertArrayEquals(cache, Files.readAllBytes(path));
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
    public void testRpcStartupIgnoresOtherWalletDirectoriesAndExecutables() {
        File executable = new File(dir, XmrWalletService.MONERO_WALLET_RPC_NAME);
        ProcessHandle otherDir = walletRpcProcess(executable, new File(dir, "other-wallets"), null);
        ProcessHandle otherExecutable = walletRpcProcess(new File(dir, "other/" + executable.getName()), walletDir, null);
        ProcessHandle exited = walletRpcProcess(executable, walletDir, null);
        doReturn(false).when(exited).isAlive();
        ProcessHandle undescribed = mock(ProcessHandle.class);
        doThrow(new RuntimeException("sysctl failed")).when(undescribed).info();
        assertDoesNotThrow(() -> checkWalletRpcProcesses(executable, undescribed, otherDir, otherExecutable, exited));
    }

    @Test
    public void testRpcStartupRecognizesReplacedLinuxExecutables() {
        File executable = new File(dir, XmrWalletService.MONERO_WALLET_RPC_NAME);
        ProcessHandle process = walletRpcProcess(executable, walletDir, null);
        ProcessHandle.Info info = process.info();
        doReturn(Optional.of(executable.getAbsolutePath() + " (deleted)")).when(info).command();
        try (var utilities = mockStatic(Utilities.class)) {
            utilities.when(Utilities::isLinux).thenReturn(true);
            assertThrows(IllegalStateException.class, () -> checkWalletRpcProcesses(executable, process));
        }
    }

    @Test
    public void testApplicationChecksProcessesWithoutRepeatingOnWalletConstruction() throws Exception {
        HavenoExecutable executable = mock(HavenoExecutable.class, CALLS_REAL_METHODS);
        Config config = mock(Config.class);
        setField(Config.class, config, "walletDir", walletDir);
        setField(HavenoExecutable.class, executable, "config", config);
        Method check = HavenoExecutable.class.getDeclaredMethod("checkForRunningWalletProcesses");
        check.setAccessible(true);
        try (var manager = mockStatic(MoneroWalletRpcManager.class);
                var service = mockStatic(XmrWalletService.class, CALLS_REAL_METHODS)) {
            service.when(XmrWalletService::getMoneroWalletRpcPath).thenReturn(new File(dir, XmrWalletService.MONERO_WALLET_RPC_NAME).getPath());
            check.invoke(executable);
            walletService(account(null));
            manager.verify(() -> MoneroWalletRpcManager.checkForRunningProcesses(new File(dir, XmrWalletService.MONERO_WALLET_RPC_NAME), walletDir));
            manager.verifyNoMoreInteractions();
        }
    }

    @Test
    public void testStartupProcessCheckKeepsUserThreadResponsiveAndGatesStartup() throws Exception {
        Executor previousExecutor = UserThread.getExecutor();
        ExecutorService userExecutor = Executors.newSingleThreadExecutor();
        UserThread.setExecutor(userExecutor);
        Method launch = HavenoExecutable.class.getDeclaredMethod("onApplicationLaunched");
        launch.setAccessible(true);
        Method shutdown = HavenoExecutable.class.getDeclaredMethod("beginGracefulShutDown", ResultHandler.class);
        shutdown.setAccessible(true);
        Method restart = HavenoExecutable.class.getDeclaredMethod("shutDownNoPersist", Runnable.class, boolean.class);
        restart.setAccessible(true);
        try {
            for (String outcome : List.of("success", "check failure", "check error", "recheck failure", "service failure")) {
                for (String stop : List.of("none", "shutdown", "restart")) {
                    CountDownLatch checking = new CountDownLatch(1);
                    CountDownLatch resume = new CountDownLatch(1);
                    AtomicReference<Thread> checkThread = new AtomicReference<>();
                    AtomicInteger checks = new AtomicInteger();
                    AtomicInteger logins = new AtomicInteger();
                    AtomicInteger reads = new AtomicInteger();
                    AtomicReference<Throwable> reportedFailure = new AtomicReference<>();
                    CompletableFuture<Thread> completed = new CompletableFuture<>();
                    Throwable failure = outcome.equals("check error") ? new NoClassDefFoundError("injected " + outcome) : new IllegalStateException("injected " + outcome);
                    HavenoExecutable executable = mock(HavenoExecutable.class,
                            withSettings().useConstructor("", "", "", "").defaultAnswer(invocation -> {
                                switch (invocation.getMethod().getName()) {
                                    case "configUserThread", "setupGuice", "setupAvoidStandbyMode":
                                        return null;
                                    case "checkForRunningWalletProcesses":
                                        if (checks.incrementAndGet() > 1) { // repeated after login
                                            if (outcome.equals("recheck failure")) throw failure;
                                            return null;
                                        }
                                        checkThread.set(Thread.currentThread());
                                        checking.countDown();
                                        assertTrue(resume.await(10, TimeUnit.SECONDS));
                                        if (outcome.startsWith("check")) throw failure;
                                        return null;
                                    case "loginAccount":
                                        logins.incrementAndGet();
                                        return CompletableFuture.completedFuture(true);
                                    case "readAllPersisted":
                                        reads.incrementAndGet();
                                        if (outcome.equals("service failure")) throw failure;
                                        ((Runnable) invocation.getArgument(0)).run();
                                        return null;
                                    case "startApplication":
                                        completed.complete(Thread.currentThread());
                                        return null;
                                    case "onStartupFailed":
                                        reportedFailure.set(invocation.getArgument(0));
                                        completed.complete(Thread.currentThread());
                                        return null;
                                    default:
                                        return CALLS_REAL_METHODS.answer(invocation);
                                }
                            }));
                    Injector injector = mock(Injector.class);
                    doReturn(mock(CoreAccountService.class)).when(injector).getInstance(CoreAccountService.class);
                    setField(HavenoExecutable.class, executable, "injector", injector);
                    try {
                        CompletableFuture.runAsync(() -> {
                            try (var setup = mockStatic(CommonSetup.class); var havenoSetup = mockStatic(HavenoSetup.class)) {
                                assertDoesNotThrow(() -> launch.invoke(executable));
                            }
                        }, userExecutor).get(10, TimeUnit.SECONDS);
                        assertTrue(checking.await(10, TimeUnit.SECONDS));
                        Thread userThread = CompletableFuture.supplyAsync(Thread::currentThread, userExecutor).get(10, TimeUnit.SECONDS);
                        assertFalse(userThread == checkThread.get());
                        assertEquals(0, logins.get());
                        assertEquals(0, reads.get());
                        assertFalse(completed.isDone());

                        CompletableFuture.runAsync(() -> {
                            if (stop.equals("shutdown")) assertDoesNotThrow(() -> shutdown.invoke(executable, mock(ResultHandler.class)));
                            if (stop.equals("restart")) assertDoesNotThrow(() -> restart.invoke(executable, null, true));
                        }, userExecutor).get(10, TimeUnit.SECONDS);
                        resume.countDown();
                        checkThread.get().join(10000);
                        assertFalse(checkThread.get().isAlive());
                        userExecutor.submit(() -> {}).get(10, TimeUnit.SECONDS);

                        if (stop.equals("none")) {
                            assertSame(userThread, completed.get(10, TimeUnit.SECONDS));
                            assertSame(outcome.equals("success") ? null : failure, reportedFailure.get());
                        } else {
                            assertFalse(completed.isDone());
                            assertNull(reportedFailure.get());
                        }
                        int expectedLogins = stop.equals("none") && !outcome.startsWith("check") ? 1 : 0;
                        assertEquals(expectedLogins, logins.get());
                        assertEquals(expectedLogins == 1 ? 2 : 1, checks.get());
                        assertEquals(expectedLogins == 1 && !outcome.equals("recheck failure") ? 1 : 0, reads.get());
                    } finally {
                        resume.countDown();
                        if (checkThread.get() != null) checkThread.get().join(10000);
                    }
                }
            }
        } finally {
            UserThread.setExecutor(previousExecutor);
            userExecutor.shutdownNow();
        }
    }

    @Test
    public void testStartupFailureExitsWithFailureStatus() throws Exception {
        Method startupFailed = HavenoExecutable.class.getDeclaredMethod("onStartupFailed", Throwable.class);
        startupFailed.setAccessible(true);
        Method shutDownNoPersist = HavenoExecutable.class.getDeclaredMethod("shutDownNoPersist", Runnable.class, boolean.class);
        shutDownNoPersist.setAccessible(true);
        for (boolean failed : List.of(false, true)) {
            HavenoExecutable executable = mock(HavenoExecutable.class, withSettings().useConstructor("", "", "", "").defaultAnswer(CALLS_REAL_METHODS));
            try (var setup = mockStatic(CommonSetup.class)) {
                if (failed) startupFailed.invoke(executable, new IllegalStateException("injected"));
                else shutDownNoPersist.invoke(executable, null, false);
                setup.verify(() -> CommonSetup.exitAfter(failed ? HavenoExecutable.EXIT_FAILURE : HavenoExecutable.EXIT_SUCCESS, 100, TimeUnit.MILLISECONDS));
            }
        }
    }

    @Test
    public void testRpcStartupBlocksExistingProcessesRegardlessOfParent() {
        File executable = new File(dir, XmrWalletService.MONERO_WALLET_RPC_NAME);
        for (ProcessHandle parent : Arrays.asList(null, parentProcess(1, "/sbin/init"),
                parentProcess(4242, "/usr/lib/systemd/systemd"), parentProcess(Long.MAX_VALUE, "/usr/lib/jvm/bin/java"), ProcessHandle.current())) {
            ProcessHandle process = walletRpcProcess(executable, new File(walletDir, "../wallet"), parent);
            doReturn(4243L).when(process).pid();
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> checkWalletRpcProcesses(executable, process));
            assertTrue(error.getMessage().contains("monero-wallet-rpc process 4243"));
        }
    }

    @Test
    public void testRpcStartupBlocksUnverifiableWalletDirectories() {
        File executable = new File(dir, XmrWalletService.MONERO_WALLET_RPC_NAME);
        for (String[] args : Arrays.asList(null, new String[0], new String[] {"--wallet-dir"},
                new String[] {"--wallet-dir", "relative/wallet"}, new String[] {"--wallet-dir=relative/wallet"}, new String[] {"--wallet-dir="})) {
            ProcessHandle process = walletRpcProcess(executable, walletDir, null);
            ProcessHandle.Info info = process.info();
            doReturn(Optional.ofNullable(args)).when(info).arguments();
            assertThrows(IllegalStateException.class, () -> checkWalletRpcProcesses(executable, process));
        }
    }

    @Test
    public void testRpcStartupRecognizesEqualsWalletDirectory() {
        File executable = new File(dir, XmrWalletService.MONERO_WALLET_RPC_NAME);
        for (File wallets : List.of(walletDir, new File(dir, "other wallets"))) {
            ProcessHandle process = walletRpcProcess(executable, wallets, null);
            ProcessHandle.Info info = process.info();
            doReturn(Optional.of(new String[] {"--wallet-dir=" + wallets.getAbsolutePath()})).when(info).arguments();
            if (wallets.equals(walletDir)) assertThrows(IllegalStateException.class, () -> checkWalletRpcProcesses(executable, process));
            else assertDoesNotThrow(() -> checkWalletRpcProcesses(executable, process));
        }
    }

    private ProcessHandle walletRpcProcess(File executable, File wallets, ProcessHandle parent) {
        ProcessHandle process = mock(ProcessHandle.class);
        ProcessHandle.Info info = mock(ProcessHandle.Info.class);
        doReturn(info).when(process).info();
        doReturn(Optional.of(executable.getAbsolutePath())).when(info).command();
        doReturn(Optional.of(new String[] {"--wallet-dir", wallets.getAbsolutePath()})).when(info).arguments();
        doReturn(Optional.ofNullable(parent)).when(process).parent();
        doReturn(true).when(process).isAlive();
        return process;
    }

    private ProcessHandle parentProcess(long pid, String command) {
        ProcessHandle parent = mock(ProcessHandle.class);
        ProcessHandle.Info info = mock(ProcessHandle.Info.class);
        doReturn(info).when(parent).info();
        doReturn(Optional.of(command)).when(info).command();
        doReturn(pid).when(parent).pid();
        doReturn(true).when(parent).isAlive();
        return parent;
    }

    private void checkWalletRpcProcesses(File executable, ProcessHandle... processes) {
        try (var handles = mockStatic(ProcessHandle.class)) {
            handles.when(ProcessHandle::allProcesses).thenReturn(Stream.of(processes));
            MoneroWalletRpcManager.checkForRunningProcesses(executable, walletDir);
        } finally {
            for (ProcessHandle process : processes) {
                verify(process, never()).destroy();
                verify(process, never()).destroyForcibly();
            }
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
                "--wallet-dir", walletDir.getAbsolutePath()));
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
        XmrWalletService failingService = walletService(account(null));
        MoneroWallet failingSave = mockWallet();
        MoneroError error = new MoneroError("disk full");
        doThrow(error).when(failingSave).changePassword(anyString(), anyString());
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
        Field field = XmrWalletService.class.getDeclaredField("PENDING_WALLET_CLOSES");
        field.setAccessible(true);
        Map<MoneroWallet, ?> pending = (Map<MoneroWallet, ?>) field.get(null);
        Method wait = XmrWalletService.class.getDeclaredMethod("awaitPendingWalletClose", String.class);
        wait.setAccessible(true);
        MoneroWallet wallet = mockWallet();
        doReturn("wallet").when(wallet).getPath();
        MoneroError failure = new MoneroError("native release failed");
        doThrow(failure).when(wallet).close(anyBoolean());
        try {
            assertSame(failure, assertThrows(MoneroError.class, () -> service.closeWallet(wallet, true)));
            Object close = pending.get(wallet);
            Field task = close.getClass().getDeclaredField("task");
            task.setAccessible(true);
            Object failed = task.get(close);
            FutureTask<Void> closing = mock(FutureTask.class);
            TimeoutException timeout = new TimeoutException("still closing");
            doThrow(timeout).when(closing).get(anyLong(), any());
            task.set(close, closing);
            InvocationTargetException blocked = assertThrows(InvocationTargetException.class, () -> wait.invoke(service, "wallet"));
            assertTrue(blocked.getCause() instanceof IllegalStateException);
            assertSame(timeout, blocked.getCause().getCause());
            assertSame(close, pending.get(wallet));
            verify(wallet, never()).close(false);

            task.set(close, failed);
            blocked = assertThrows(InvocationTargetException.class, () -> wait.invoke(service, "wallet"));
            assertTrue(blocked.getCause() instanceof IllegalStateException);
            assertSame(failure, blocked.getCause().getCause().getCause());
            assertTrue(pending.containsKey(wallet));
            verify(wallet).close(false);

            doAnswer(invocation -> null).when(wallet).close(false);
            wait.invoke(service, "wallet");
            assertFalse(pending.containsKey(wallet));
            verify(wallet).close(true);
            verify(wallet, times(2)).close(false);
        } finally {
            pending.remove(wallet);
        }
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
    public void testDisconnectDuringWalletSyncIgnoresStaleNotifications() throws Exception {
        XmrConnectionService connections = mock(XmrConnectionService.class);
        XmrWalletService service = spy(walletService(account(null), connections, walletDir));
        MoneroRpcConnection connection = new MoneroRpcConnection("http://127.0.0.1:18081");
        MoneroWallet wallet = mockWallet();
        doReturn(connection).when(wallet).getDaemonConnection();
        setField(XmrWalletBase.class, service, "wallet", wallet);
        setField(XmrWalletBase.class, service, "isSyncingWithProgress", true);
        doNothing().when(service).forceRestartMainWallet();
        Method changed = XmrWalletService.class.getDeclaredMethod("onConnectionChanged", MoneroRpcConnection.class, MoneroWallet.class);
        changed.setAccessible(true);

        for (boolean wasSynced : List.of(false, true)) {
            setField(XmrWalletBase.class, service, "wasWalletSynced", wasSynced);
            clearInvocations(service);

            doReturn(connection).when(connections).getConnection();
            changed.invoke(service, null, wallet);
            verify(service, never()).forceRestartMainWallet();

            doReturn(null).when(connections).getConnection();
            changed.invoke(service, null, mockWallet());
            verify(service, never()).forceRestartMainWallet();

            changed.invoke(service, null, wallet);
            verify(service).forceRestartMainWallet();
        }
        verify(connections, never()).applyWalletProxyUri(any(), anyString(), anyBoolean());
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
            awaitWalletLifecycleLock(service, cleaning);
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
                assertFalse(command.contains("--rpc-login"));
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
