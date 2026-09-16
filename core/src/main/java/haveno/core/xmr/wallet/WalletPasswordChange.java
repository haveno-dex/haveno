/*
 * This file is part of Haveno.
 * See LICENSE for licensing information.
 */
package haveno.core.xmr.wallet;

import haveno.common.crypto.Hash;
import haveno.common.file.FileUtil;
import haveno.common.util.Utilities;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import monero.common.MoneroError;
import monero.common.MoneroRpcError;

/** Idempotent operations shared by password changes and interrupted-change recovery. */
public final class WalletPasswordChange {
    public static final String MAIN_WALLET_ID_FILE = ".main-wallet-id";

    private WalletPasswordChange() {}

    public static String normalizePassword(String password) {
        return password == null ? "password" : password;
    }

    // trades can retain absent wallets, and main-wallet backups can contain a different seed after a restore
    public static List<String> cleanupBackups(File walletDir, File currentSnapshot, String mainAddress) throws IOException {
        return cleanupBackups(walletDir, currentSnapshot, mainAddress, Set.of());
    }

    public static List<String> cleanupBackups(File walletDir, File currentSnapshot, String mainAddress,
                                             Set<String> protectedMainBackups) throws IOException {
        String mainId = getMainWalletId(mainAddress);
        if (mainId == null && currentSnapshot != null) {
            mainId = readMainWalletId(currentSnapshot.toPath());
        }
        Set<String> rollingDirs = new HashSet<>();
        try (var files = Files.list(walletDir.toPath())) {
            for (var keys : files.filter(path -> path.getFileName().toString().endsWith(".keys")).toList()) {
                String name = keys.getFileName().toString();
                name = name.substring(0, name.length() - ".keys".length());
                if (!Files.isRegularFile(walletDir.toPath().resolve(name))) continue;
                name = name.replace('.', '_');
                for (String suffix : List.of("", "_keys", "_address_txt")) rollingDirs.add("backups_" + name + suffix);
            }
        }
        List<String> retained = new ArrayList<>();
        // recovery can only discard temporary files it has matched to a repaired wallet
        try (var files = Files.list(walletDir.toPath())) {
            files.filter(path -> {
                String name = path.getFileName().toString();
                return name.endsWith(".new") || name.endsWith(".unportable")
                        || (name.startsWith(".") && name.contains(".haveno-write-") && name.endsWith(".tmp"));
            }).forEach(path -> retained.add("wallet/" + path.getFileName()));
        }
        File backupDir = new File(walletDir, "backup");
        if (!backupDir.exists()) return retained;
        try (var entries = Files.list(backupDir.toPath())) {
            for (var entry : entries.toList()) {
                if (currentSnapshot != null && entry.toFile().equals(currentSnapshot)) continue;
                String name = entry.getFileName().toString();
                if (name.startsWith("recovery-retained-") && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    try (var files = Files.list(entry)) {
                        files.forEach(path -> retained.add("wallet/backup/" + name + "/" + path.getFileName()));
                    }
                    continue;
                }
                boolean keep = true;
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) && name.startsWith("backups_")) {
                    keep = !rollingDirs.contains(name) || name.equals("backups_haveno_XMR")
                            || name.equals("backups_haveno_XMR_keys") || name.equals("backups_haveno_XMR_address_txt");
                }
                if (name.startsWith("password-change-") && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    boolean sameMainWallet = mainId != null && mainId.equals(readMainWalletId(entry));
                    try (var files = Files.list(entry)) {
                        // preserve incomplete pairs and unknown files left by interrupted snapshot writes
                        keep = files.anyMatch(path -> {
                            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return true;
                            String fileName = path.getFileName().toString();
                            if (fileName.equals(MAIN_WALLET_ID_FILE) || fileName.equals(".main-wallet-address")) return false;
                            String walletName = fileName.endsWith(".keys") ? fileName.substring(0, fileName.length() - ".keys".length()) : fileName;
                            return (walletName.equals("haveno_XMR") && !sameMainWallet)
                                    || !Files.isRegularFile(entry.resolve(walletName), LinkOption.NOFOLLOW_LINKS)
                                    || !Files.isRegularFile(entry.resolve(walletName + ".keys"), LinkOption.NOFOLLOW_LINKS)
                                    || !Files.isRegularFile(walletDir.toPath().resolve(walletName), LinkOption.NOFOLLOW_LINKS)
                                    || !Files.isRegularFile(walletDir.toPath().resolve(walletName + ".keys"), LinkOption.NOFOLLOW_LINKS);
                        });
                    }
                }
                if (keep) {
                    if (!protectedMainBackups.contains(name)) retained.add(name);
                }
                else FileUtil.deleteDirectory(entry.toFile(), null, false);
            }
        }
        FileUtil.syncDirectory(backupDir.toPath());
        return retained;
    }

    public static String getMainWalletId(String address) {
        return address == null ? null : Utilities.encodeToHex(Hash.getSha256Hash(address));
    }

    public static String readMainWalletId(Path snapshot) {
        try {
            Path marker = snapshot.resolve(MAIN_WALLET_ID_FILE);
            Path legacy = snapshot.resolve(".main-wallet-address");
            if (Files.exists(marker)) {
                String id = Files.readString(marker);
                if (!id.matches("[0-9a-f]{64}")) return null;
                if (Files.exists(legacy) && !id.equals(getMainWalletId(Files.readString(legacy)))) return null;
                return id;
            }
            // recognize snapshots written before the identity marker was hashed
            return getMainWalletId(Files.readString(legacy));
        } catch (IOException e) {
            return null; // preserve snapshots whose identity cannot be read
        }
    }

    public static <T> T open(List<String> candidates, Function<String, T> opener) {
        RuntimeException failure = null;
        for (String candidate : candidates) {
            try {
                return opener.apply(candidate);
            } catch (Exception e) {
                failure = asRuntimeException(e);
                if (!isPasswordError(e)) throw failure;
            }
        }
        throw failure;
    }

    public static boolean isPasswordError(Throwable error) {
        if (error instanceof MoneroRpcError && Integer.valueOf(-22).equals(((MoneroRpcError) error).getCode())) return true;
        String message = error.getMessage();
        return message != null && (message.toLowerCase(Locale.ROOT).contains("invalid password")
                || message.toLowerCase(Locale.ROOT).contains("invalid original password"));
    }

    private static RuntimeException asRuntimeException(Exception error) {
        // native JNI methods can throw checked exceptions despite their Java declarations
        return error instanceof RuntimeException ? (RuntimeException) error : new MoneroError(error);
    }

}
