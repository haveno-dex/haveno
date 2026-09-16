/*
 * This file is part of Haveno.
 * See LICENSE for licensing information.
 */
package haveno.core.util;

import ch.qos.logback.classic.LoggerContext;
import haveno.common.crypto.Encryption;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.file.FileUtil;
import haveno.common.persistence.PersistenceManager;
import haveno.core.xmr.model.EncryptedConnectionList;
import haveno.core.xmr.wallet.WalletPasswordChange;
import haveno.core.xmr.wallet.WalletPasswordRecovery;
import haveno.core.xmr.wallet.WalletPasswordRecovery.RecoveredWallet;
import java.io.Console;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import monero.daemon.model.MoneroNetworkType;
import org.slf4j.LoggerFactory;

/** Repairs interrupted password changes offline using credentials supplied at hidden prompts. */
public final class RecoverPassword {

    private static final String RECOVERY_COMMAND = "java -cp daemon/build/libs/daemon-all.jar haveno.core.util.RecoverPassword <network-data-directory> [monero-wallet-rpc-path]";

    private RecoverPassword() {
    }

    public static void main(String[] args) throws Exception {
        boolean exportKeys = args.length > 0 && "--export-retained-keys".equals(args[0]);
        if ((exportKeys && args.length != 3) || (!exportKeys && (args.length < 1 || args.length > 2 || "--help".equals(args[0])))) {
            System.out.println("Close Haveno and back up its data, then run: " + RECOVERY_COMMAND);
            System.out.println("The directory is xmr_mainnet, xmr_local or xmr_stagenet. The optional executable selects wallet RPC instead of the native library.");
            System.out.println("To export protected account-key copies: java -cp daemon/build/libs/daemon-all.jar haveno.core.util.RecoverPassword --export-retained-keys <network-data-directory> <new-output-directory>");
            return;
        }
        try {
            // disable library wire logging before any recovery password can be sent over RPC
            ((LoggerContext) LoggerFactory.getILoggerFactory()).stop();
        } catch (LinkageError | ClassCastException e) {
            System.err.println("Recovery cannot run with incompatible libraries. Run it with the built all-in-one jar: " + RECOVERY_COMMAND);
            System.exit(1);
            return;
        }
        Console console = System.console();
        if (console == null) throw new IllegalStateException("Run in a terminal with hidden password input; passwords cannot be supplied as arguments");
        if (exportKeys) {
            String password = readPassword(console, "Current account password (empty if unset): ");
            new KeyStorage(Path.of(args[1]).resolve("keys").toFile()).exportRetainedAccountKeys(password, Path.of(args[2]));
            console.printf("Original account-key copies exported. They may contain unprotected keys; keep the output directory private.%n");
            return;
        }
        console.printf("Close Haveno and its wallet processes and back up the data directory before continuing.%n");
        console.printf("Recovery restores wallets and connection credentials to the current account password. Unset passwords are tried automatically.%n");
        console.printf("If the account key must be restored from a backup, this will be the password for the recovered account.%n");
        String current = readPassword(console, "Current account password (empty if unset): ");
        String confirmation = readPassword(console, "Confirm account password (empty if unset): ");
        if (!Objects.equals(current, confirmation)) {
            console.printf("Account passwords do not match. No files were changed.%n");
            System.exit(1);
            return;
        }
        List<String> candidates = new ArrayList<>();
        while (true) {
            String attempted = readPassword(console, "Password used during a failed change (empty when finished): ");
            if (attempted == null) break;
            if (!candidates.contains(attempted)) candidates.add(attempted);
        }
        try {
            List<String> retained = recover(Path.of(args[0]), current, confirmation, candidates, args.length == 2 ? Path.of(args[1]) : null);
            console.printf("Recovery completed. Start Haveno with the current account password.%n");
            if (!retained.isEmpty()) console.printf("Wallet files or backups were preserved: %s%nKeep their previous passwords. Restore any missing active wallet from its backup.%n", String.join(", ", retained));
        } catch (Exception e) {
            console.printf("Recovery did not finish: %s%nKeep all passwords. After resolving the error, rerun this tool with every password used during the failed changes.%n", e.getMessage());
            System.exit(1);
        } finally {
            candidates.clear();
        }
    }

    private static String readPassword(Console console, String prompt) {
        char[] value = console.readPassword(prompt);
        if (value == null) throw new IllegalStateException("Password input cancelled");
        try {
            return value.length == 0 ? null : new String(value);
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    public static List<String> recover(Path networkDir, String currentPassword, String confirmedPassword,
                                       List<String> attemptedPasswords, Path rpcExecutable) throws Exception {
        String target = currentPassword == null || currentPassword.isEmpty() ? null : currentPassword;
        String confirmation = confirmedPassword == null || confirmedPassword.isEmpty() ? null : confirmedPassword;
        if (!Objects.equals(target, confirmation)) throw new IllegalArgumentException("Account passwords do not match. No files were changed.");
        networkDir = networkDir.toAbsolutePath().normalize();
        MoneroNetworkType network;
        switch (networkDir.getFileName().toString()) {
            case "xmr_mainnet": network = MoneroNetworkType.MAINNET; break;
            case "xmr_local":
            case "xmr_testnet": network = MoneroNetworkType.TESTNET; break;
            case "xmr_stagenet": network = MoneroNetworkType.STAGENET; break;
            default: throw new IllegalArgumentException("Expected an xmr_mainnet, xmr_local or xmr_stagenet data directory");
        }
        Path walletDir = networkDir.resolve("wallet");
        if (!Files.isDirectory(networkDir.resolve("keys")) || !Files.isDirectory(walletDir)) {
            throw new IllegalArgumentException("Expected a network data directory containing keys and wallet directories");
        }
        KeyStorage storage = new KeyStorage(networkDir.resolve("keys").toFile());
        KeyRing ring = new KeyRing(storage);
        List<String> candidates = new ArrayList<>();
        candidates.add(target);
        for (String password : attemptedPasswords) {
            String candidate = password == null || password.isEmpty() ? null : password;
            if (!candidates.contains(candidate)) candidates.add(candidate);
        }
        if (!candidates.contains(null)) candidates.add(null);
        candidates.add(""); // accounts created with an empty password before normalization used it literally
        String mainAddress = null;
        Set<String> protectedBackups = Set.of();
        try (WalletPasswordRecovery recovery = new WalletPasswordRecovery(walletDir, network, candidates, target, rpcExecutable)) {
            List<Path> wallets;
            try (var files = Files.list(walletDir)) {
                wallets = files.filter(path -> path.getFileName().toString().endsWith(".keys"))
                        .filter(path -> !path.getFileName().toString().equals("haveno_XMR_seed_validation.keys"))
                        .sorted(Comparator.comparing(path -> !path.getFileName().toString().equals("haveno_XMR.keys")))
                        .toList();
            }

            checkMissingWalletKeys(walletDir, wallets);

            // close every probe before opening a native wallet; closing another descriptor releases its POSIX lock
            for (Path keys : wallets) {
                try (FileChannel channel = FileChannel.open(keys, StandardOpenOption.READ, StandardOpenOption.WRITE);
                     FileLock lock = channel.tryLock()) {
                    if (lock == null) throw new IllegalStateException("A wallet is open. Close Haveno and its wallet processes before recovery");
                } catch (IOException e) {
                    throw new IOException("Cannot access wallet keys " + keys.getFileName()
                            + ". Close Haveno and its wallet processes and check file permissions before recovery", e);
                }
            }

            storage.recoverAccountKey(target, candidates);
            if (!ring.unlockKeys(target, false)) throw new IllegalStateException("Could not unlock account keys");

            // validate and stage connection credentials before changing any wallet
            Path connectionFile = networkDir.resolve("db/EncryptedConnectionList");
            protobuf.PersistableEnvelope connections = null;
            if (Files.exists(connectionFile)) {
                protobuf.PersistableEnvelope stored = PersistenceManager.readEncrypted(connectionFile.toFile(), ring.getSymmetricKey());
                if (stored == null || !stored.hasEncryptedConnectionList()) throw new IOException("Invalid stored connection list; restore it from backup");
                EncryptedConnectionList list = EncryptedConnectionList.fromProto(stored.getEncryptedConnectionList());
                list.reconcilePasswords(candidates, target);
                connections = (protobuf.PersistableEnvelope) list.toProtoMessage();
            } else if (Files.exists(networkDir.resolve("db/" + FileUtil.CORRUPTED_BACKUP_FOLDER + "/EncryptedConnectionList"))) {
                throw new IOException("Stored connections were quarantined. Restore db/EncryptedConnectionList from a readable backup before recovery");
            }

            for (Path keys : wallets) {
                String name = keys.getFileName().toString();
                Path path = keys.resolveSibling(name.substring(0, name.length() - ".keys".length()));
                RecoveredWallet recovered;
                try {
                    recovered = recovery.recover(path);
                } catch (Exception e) {
                    throw new IOException("Could not recover wallet " + name + ": " + e.getMessage(), e);
                }
                byte[][] repaired = recovered.data();
                if (!Files.exists(path)) checkCacheBackupsBeforeRebuilding(path, recovered.address());
                if (name.equals("haveno_XMR.keys")) mainAddress = recovered.address();
                // a crash between these durable replacements leaves a mixed pair that can be repaired again
                FileUtil.writeAtomically(path, repaired[1]);
                FileUtil.writeAtomically(keys, repaired[0]);
                recovery.cleanupTemporaryFiles(path, recovered);
            }
            if (connections != null) {
                FileUtil.rollingBackup(connectionFile.getParent().toFile(), connectionFile.getFileName().toString(), 20);
                byte[] encrypted = Encryption.encryptPayloadWithHmac(connections.toByteArray(), ring.getSymmetricKey());
                FileUtil.writeAtomically(connectionFile, encrypted);
            }
            if (mainAddress != null) protectedBackups = recovery.rekeyMainWalletBackups(walletDir.resolve("haveno_XMR.keys"));
            storage.finishPasswordChange(ring, target, candidates);
        } finally {
            ring.lockKeys();
            candidates.clear();
        }
        FileUtil.deleteDirectory(walletDir.resolve(".password-recovery").toFile(), null, false);
        return WalletPasswordChange.cleanupBackups(walletDir.toFile(), null, mainAddress, protectedBackups);
    }

    // a rebuilt cache must not cause cleanup to discard the remaining local state
    private static void checkCacheBackupsBeforeRebuilding(Path path, String address) throws IOException {
        String name = path.getFileName().toString();
        Path walletDir = path.getParent();
        // an interrupted cache rebuild can leave atomic writes; cleanup preserves those after retry
        if (Files.exists(path.resolveSibling(name + ".new")) || Files.exists(path.resolveSibling(name + ".unportable"))) {
            throw new IOException("Wallet cache is missing for " + name
                    + ". Preserve its temporary caches and restore a readable cache backup before recovery");
        }
        boolean mainWallet = name.equals("haveno_XMR");
        boolean hasBackup = !mainWallet && !FileUtil.getBackupFiles(walletDir.toFile(), name).isEmpty();
        Path backupDir = walletDir.resolve("backup");
        if (!hasBackup && Files.isDirectory(backupDir)) {
            try (var snapshots = Files.list(backupDir)) {
                hasBackup = snapshots.filter(snapshot -> snapshot.getFileName().toString().startsWith("password-change-"))
                        .filter(Files::isDirectory)
                        .anyMatch(snapshot -> Files.exists(snapshot.resolve(name)) && (!mainWallet
                                || !Files.exists(snapshot.resolve(name + ".keys"))
                                || (address != null && WalletPasswordChange.getMainWalletId(address).equals(WalletPasswordChange.readMainWalletId(snapshot)))));
            }
        }
        if (hasBackup) throw new IOException("Wallet cache is missing for " + name
                + ". Restore its cache backup before recovery; backups were preserved");
    }

    // restore a missing main wallet before startup can generate a replacement; absent trade backups are retained
    private static void checkMissingWalletKeys(Path walletDir, List<Path> wallets) throws IOException {
        if (wallets.stream().anyMatch(path -> path.getFileName().toString().equals("haveno_XMR_restore.keys"))) return;
        if (!Files.exists(walletDir.resolve("haveno_XMR.keys")) && Files.exists(walletDir.resolve("haveno_XMR.keys.new"))) {
            throw new IOException("Wallet keys are missing from " + walletDir
                    + ". Preserve haveno_XMR.keys.new and restore the main wallet keys and cache before recovery");
        }
        Path backupDir = walletDir.resolve("backup");
        if (!Files.isDirectory(backupDir)) return;
        Set<String> rollingDirs = wallets.stream().map(path -> "backups_" + path.getFileName().toString().replace('.', '_')).collect(Collectors.toSet());
        try (var dirs = Files.list(backupDir)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                String name = dir.getFileName().toString();
                if (!name.startsWith("password-change-") && !(name.startsWith("backups_") && name.endsWith("_keys"))) continue;
                try (var files = Files.list(dir)) {
                    for (Path keys : files.filter(path -> path.getFileName().toString().endsWith(".keys")).toList()) {
                        boolean missing = name.startsWith("password-change-") ? !Files.exists(walletDir.resolve(keys.getFileName())) : !rollingDirs.contains(name);
                        boolean main = name.equals("backups_haveno_XMR_keys") || keys.getFileName().toString().equals("haveno_XMR.keys");
                        if (missing && main) throw new IOException("Wallet keys are missing from " + walletDir + ". Restore the wallet keys and cache from " + dir + " before recovery; backups were preserved");
                    }
                }
            }
        }
    }

}
