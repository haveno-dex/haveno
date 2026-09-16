/*
 * This file is part of Haveno.
 * See LICENSE for licensing information.
 */
package haveno.core.xmr.wallet;

import com.google.common.hash.Hashing;
import haveno.common.crypto.Hash;
import haveno.common.file.FileUtil;
import haveno.common.util.Utilities;
import haveno.core.xmr.setup.MoneroWalletRpcManager;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import monero.common.MoneroUtils;
import monero.daemon.model.MoneroNetworkType;
import monero.wallet.MoneroWallet;
import monero.wallet.MoneroWalletFull;
import monero.wallet.MoneroWalletRpc;
import monero.wallet.model.MoneroWalletConfig;

/** Opens recovery copies offline, preserving the original keys and cache until repair succeeds. */
public final class WalletPasswordRecovery implements AutoCloseable {
    private final Path walletDir;
    private final MoneroNetworkType network;
    private final List<String> candidates;
    private final String target;
    private final Path rpcExecutable;
    private boolean initialized;
    private RecoveryRpc rpcSession;
    private Path scratch;
    private Path retainedDir;

    public WalletPasswordRecovery(Path walletDir, MoneroNetworkType network, List<String> passwords, String target, Path rpcExecutable) {
        this.walletDir = walletDir;
        this.network = network;
        this.candidates = passwords.stream().map(WalletPasswordChange::normalizePassword).distinct().toList();
        this.target = WalletPasswordChange.normalizePassword(target);
        this.rpcExecutable = rpcExecutable;
    }

    public RecoveredWallet recover(Path path) throws IOException {
        return recover(path, Files.readAllBytes(path.resolveSibling(path.getFileName() + ".keys")));
    }

    // a null path rekeys a detached keys backup without rebuilding its cache
    private RecoveredWallet recover(Path path, byte[] keys) throws IOException {
        initialize();
        return rpcSession == null ? recoverNativeWallet(path, keys) : recoverRpcWallet(path, keys);
    }

    // authenticate each rolling copy independently; their timestamps do not identify matching keys/cache pairs
    // currentKeys must be a detached snapshot while a native wallet is open
    public Set<String> rekeyMainWalletBackups(Path currentKeys) throws IOException {
        List<File> keys = FileUtil.getBackupFiles(walletDir.toFile(), "haveno_XMR.keys");
        List<File> caches = FileUtil.getBackupFiles(walletDir.toFile(), "haveno_XMR");
        List<File> addresses = FileUtil.getBackupFiles(walletDir.toFile(), "haveno_XMR.address.txt");
        if (keys.isEmpty() && caches.isEmpty() && addresses.isEmpty()) return Set.of();
        byte[] reference = Files.readAllBytes(currentKeys);
        initialize();
        String address = readKeyAddress(reference);
        if (address == null) throw new IOException("Could not verify the current main wallet before protecting its backups");
        Set<String> protectedDirectories = new HashSet<>();
        for (String suffix : List.of(".keys", "", ".address.txt")) {
            List<File> copies = suffix.equals(".keys") ? keys : suffix.isEmpty() ? caches : addresses;
            boolean allProtected = true;
            for (var copy : copies) {
                Path path = copy.toPath();
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    allProtected = false;
                    continue;
                }
                RecoveredWallet recovered;
                try {
                    if (suffix.equals(".address.txt")) {
                        if (!address.equals(Files.readString(path).trim())) allProtected = false;
                        continue;
                    }
                    recovered = recover(suffix.isEmpty() ? path : null,
                            suffix.isEmpty() ? reference : Files.readAllBytes(path));
                    if (!address.equals(recovered.address())) {
                        allProtected = false;
                        continue;
                    }
                } catch (Exception e) {
                    if (rpcSession != null) rpcSession.close();
                    allProtected = false; // preserve foreign or unreadable copies and report their directory
                    continue;
                }
                FileUtil.writeAtomically(path, recovered.data()[suffix.isEmpty() ? 1 : 0]);
            }
            if (allProtected) protectedDirectories.add("backups_haveno_XMR" + suffix.replace('.', '_'));
        }
        return protectedDirectories;
    }

    // delete only authenticated copies after both repaired primary files have been flushed
    public void cleanupTemporaryFiles(Path path, RecoveredWallet recovered) throws IOException {
        Path keys = path.resolveSibling(path.getFileName() + ".keys");
        List<Path> copies = new ArrayList<>();
        copies.add(keys.resolveSibling(keys.getFileName() + ".new"));
        String prefix = "." + keys.getFileName() + ".haveno-write-";
        try (var files = Files.list(walletDir)) {
            copies.addAll(files.filter(file -> file.getFileName().toString().startsWith(prefix)
                    && file.getFileName().toString().endsWith(".tmp")).toList());
        }
        for (Path copy : copies) {
            if (!Files.isRegularFile(copy, LinkOption.NOFOLLOW_LINKS)) continue;
            String copyAddress = readKeyAddress(Files.readAllBytes(copy));
            if (recovered.address() != null && recovered.address().equals(copyAddress)) Files.delete(copy);
            else retainTemporaryFile(copy);
        }
        // discard only exact copies of the recovered cache; other copies can contain unique local state
        for (Path copy : temporaryCacheFiles(path)) {
            if (!Files.isRegularFile(copy, LinkOption.NOFOLLOW_LINKS)) continue;
            if (recovered.cacheHash() != null && Arrays.equals(recovered.cacheHash(), hashCache(copy))) Files.delete(copy);
            else retainTemporaryFile(copy);
        }
        FileUtil.syncDirectory(walletDir);
    }

    private static List<Path> temporaryCacheFiles(Path path) throws IOException {
        List<Path> copies = new ArrayList<>();
        for (String suffix : List.of(".new", ".unportable")) {
            Path copy = path.resolveSibling(path.getFileName() + suffix);
            if (Files.exists(copy, LinkOption.NOFOLLOW_LINKS)) copies.add(copy);
        }
        String prefix = "." + path.getFileName() + ".haveno-write-";
        try (var files = Files.list(path.getParent())) {
            copies.addAll(files.filter(file -> file.getFileName().toString().startsWith(prefix)
                    && file.getFileName().toString().endsWith(".tmp")).toList());
        }
        return copies;
    }

    private static byte[] hashCache(Path path) throws IOException {
        return com.google.common.io.Files.asByteSource(path.toFile()).hash(Hashing.sha256()).asBytes();
    }

    private void retainTemporaryFile(Path source) throws IOException {
        byte[] contents = Files.readAllBytes(source);
        if (retainedDir == null) {
            Path backupDir = Files.createDirectories(walletDir.resolve("backup"));
            retainedDir = Files.createTempDirectory(backupDir, "recovery-retained-");
        }
        try {
            FileUtil.writeAtomically(retainedDir.resolve(source.getFileName()), contents);
            FileUtil.syncDirectory(retainedDir.getParent());
            FileUtil.syncDirectory(walletDir);
            Files.delete(source);
        } catch (IOException e) {
            Path directory = retainedDir;
            try {
                Files.delete(directory);
                retainedDir = null;
                FileUtil.syncDirectory(directory.getParent());
            } catch (DirectoryNotEmptyException ignored) {
                // preserve any copies already written during this recovery
            } catch (IOException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            throw e;
        }
    }

    private String readKeyAddress(byte[] keys) throws IOException {
        if (rpcSession == null) {
            MoneroWalletFull wallet;
            try {
                wallet = WalletPasswordChange.open(candidates,
                        password -> MoneroWalletFull.openWalletData(password, network, keys, null, null));
            } catch (RuntimeException e) {
                return null; // preserve foreign or unreadable copies for manual recovery
            }
            try {
                return wallet.getPrimaryAddress();
            } finally {
                wallet.close(false);
            }
        }
        Path copy = scratch.resolve("key-copy");
        Files.deleteIfExists(copy);
        Files.write(scratch.resolve("key-copy.keys"), keys);
        MoneroWalletRpc wallet = rpcSession.get();
        try {
            WalletPasswordChange.open(candidates, password -> wallet.openWallet(
                    new MoneroWalletConfig().setPath("key-copy").setPassword(password)));
        } catch (RuntimeException e) {
            rpcSession.close(); // an unreadable copy may have terminated the isolated process
            return null;
        }
        try {
            return wallet.getPrimaryAddress();
        } finally {
            wallet.close(false);
        }
    }

    private void initialize() throws IOException {
        if (initialized) return;
        Path binary = rpcExecutable;
        Path installed = walletDir.getParent().getParent().resolve(Utilities.isWindows() ? "monero-wallet-rpc.exe" : "monero-wallet-rpc");
        // prefer process isolation for cache probes; failed native buffer loads can retain allocations
        if (binary == null && Files.isRegularFile(installed)) binary = installed;
        if (binary == null) MoneroUtils.tryLoadNativeLibrary();
        if (binary != null || !MoneroUtils.isNativeLibraryLoaded()) {
            if (binary == null || !Files.isRegularFile(binary)) {
                throw new IOException("Native wallet library unavailable; supply the installed monero-wallet-rpc executable as the second argument");
            }
            Path recoveryDir = Files.createDirectories(walletDir.resolve(".password-recovery"));
            scratch = Files.createTempDirectory(recoveryDir, "rpc-");
            List<String> command = new ArrayList<>(List.of(binary.toAbsolutePath().toString(), "--offline", "--rpc-bind-ip", "127.0.0.1",
                    "--rpc-login", "recovery:" + UUID.randomUUID(), "--wallet-dir", scratch.toString()));
            if (network != MoneroNetworkType.MAINNET) command.add(network == MoneroNetworkType.TESTNET ? "--testnet" : "--stagenet");
            rpcSession = new RecoveryRpc(command);
        }
        initialized = true;
    }

    @Override
    public void close() throws IOException {
        if (rpcSession != null) rpcSession.close();
        if (scratch != null) FileUtil.deleteDirectory(scratch.toFile(), null, false);
    }

    private record OpenedWallet(MoneroWallet wallet, String password) {}
    public record RecoveredWallet(byte[][] data, String address, byte[] cacheHash) {}

    private RecoveredWallet recoverNativeWallet(Path path, byte[] keys) throws IOException {
        byte[] cache = path != null && Files.exists(path) ? Files.readAllBytes(path) : null;
        byte[] cacheHash = cache == null ? null : Hash.getSha256Hash(cache);
        if (cache != null && cache.length == 0) throw new IOException("Empty wallet cache: " + path.getFileName() + "; restore a readable cache backup");
        OpenedWallet opened = WalletPasswordChange.open(candidates, password -> new OpenedWallet(
                MoneroWalletFull.openWalletData(password, network, keys, null, null), password));
        MoneroWalletFull probe = (MoneroWalletFull) opened.wallet();
        String probePassword = opened.password();
        try {
            if (cache == null) {
                if (path != null && probe.getMultisigInfo().isMultisig()) throw unreadableCache(path, null);
                probe.changePassword(probePassword, target);
                return new RecoveredWallet(probe.getData(), probe.getPrimaryAddress(), cacheHash);
            }
            Throwable failure = null;
            for (String candidate : cachePasswords(candidates, probePassword)) {
                probe.changePassword(probePassword, candidate);
                probePassword = candidate;
                MoneroWalletFull wallet;
                try {
                    // buffer opens avoid leaked file locks and .unportable copies after a failed native cache load
                    wallet = MoneroWalletFull.openWalletData(candidate, network, probe.getData()[0], cache, null);
                } catch (Exception e) {
                    failure = e;
                    continue;
                } catch (OutOfMemoryError e) {
                    // wallet2 maps a garbage cache decode's std::bad_alloc to this JNI error
                    if (!"std::bad_alloc".equals(e.getMessage())) throw e;
                    failure = e;
                    continue;
                }
                try {
                    wallet.changePassword(candidate, target);
                    return new RecoveredWallet(wallet.getData(), wallet.getPrimaryAddress(), cacheHash);
                } finally {
                    wallet.close(false);
                }
            }
            throw unreadableCache(path, failure);
        } finally {
            probe.close(false);
        }
    }

    private RecoveredWallet recoverRpcWallet(Path path, byte[] originalKeys) throws IOException {
        Path copy = scratch.resolve("wallet");
        Path keys = scratch.resolve("wallet.keys");
        boolean hasCache = path != null && Files.exists(path);
        Files.deleteIfExists(copy);
        Files.write(keys, originalKeys);
        MoneroWalletRpc initial = rpcSession.get();
        OpenedWallet opened = WalletPasswordChange.open(candidates, password -> {
            initial.openWallet(new MoneroWalletConfig().setPath("wallet").setPassword(password));
            return new OpenedWallet(initial, password);
        });
        String keyPassword = opened.password();
        String address = initial.getPrimaryAddress();
        try {
            if (path != null && !hasCache && initial.getMultisigInfo().isMultisig()) throw unreadableCache(path, null);
        } finally {
            initial.close(false);
        }
        if (hasCache && Files.size(path) == 0) throw unreadableCache(path, null);
        byte[] cacheHash = hasCache ? hashCache(path) : null;
        Throwable failure = null;
        for (String candidate : hasCache ? cachePasswords(candidates, keyPassword) : List.of(target)) {
            // each candidate starts from pristine keys, even if the previous cache probe killed its process
            MoneroWalletRpc rpc = rpcSession.get();
            Files.deleteIfExists(copy);
            Files.write(keys, originalKeys);
            rpc.openWallet(new MoneroWalletConfig().setPath("wallet").setPassword(keyPassword));
            try {
                rpc.save(); // wallet2 canonicalizes the cache path before rekeying
                rpc.changePassword(keyPassword, candidate);
                rpc.save();
            } finally {
                rpc.close(false);
            }
            if (hasCache) Files.copy(path, copy, StandardCopyOption.REPLACE_EXISTING);
            try {
                rpc.openWallet(new MoneroWalletConfig().setPath("wallet").setPassword(candidate));
            } catch (Exception e) {
                String reason = rpc.getProcess().isAlive() ? "RPC cache probe failed or timed out"
                        : "RPC cache probe exited with code " + rpc.getProcess().exitValue();
                IOException probeFailure = new IOException(reason, e);
                if (failure == null) failure = probeFailure;
                else failure.addSuppressed(probeFailure);
                rpcSession.close(); // wait for exit before reusing scratch files, including after a timeout
                continue;
            }
            try {
                rpc.changePassword(candidate, target);
                rpc.save();
            } finally {
                rpc.close(false);
            }
            return new RecoveredWallet(new byte[][] { Files.readAllBytes(keys), Files.readAllBytes(copy) }, address, cacheHash);
        }
        throw unreadableCache(path, failure);
    }

    private static final class RecoveryRpc implements AutoCloseable {
        private final MoneroWalletRpcManager manager = new MoneroWalletRpcManager();
        private final List<String> command;
        private MoneroWalletRpc wallet;

        private RecoveryRpc(List<String> command) {
            this.command = command;
        }

        private MoneroWalletRpc get() {
            if (wallet == null) {
                wallet = manager.startInstance(command);
                wallet.getRpcConnection().setTimeout(300000L);
                wallet.stopSyncing();
            }
            return wallet;
        }

        @Override
        public void close() {
            if (wallet != null) {
                manager.stopInstance(wallet, null, true);
                wallet = null;
            }
        }
    }

    private static List<String> cachePasswords(List<String> candidates, String keyPassword) {
        List<String> passwords = new ArrayList<>(candidates);
        passwords.remove(keyPassword);
        passwords.add(0, keyPassword);
        return passwords;
    }

    private static IOException unreadableCache(Path path, Throwable failure) {
        return new IOException("Could not open the original cache for wallet " + (path == null ? "backup" : path.getFileName())
                + ". Files were preserved; check the supplied passwords or restore a readable cache backup"
                + (failure == null ? "" : ". " + failure.getMessage()), failure);
    }
}
