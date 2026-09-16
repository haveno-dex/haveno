/*
 * This file is part of Haveno.
 * See LICENSE for licensing information.
 */
package haveno.core.xmr.wallet;

import haveno.common.file.FileUtil;
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
import monero.common.MoneroRpcError;

/** Idempotent operations for wallet password changes. */
public final class WalletPasswordChange {
    public static final String MAIN_WALLET_ADDRESS_FILE = ".main-wallet-address";

    private WalletPasswordChange() {}

    public static String normalizePassword(String password) {
        return password == null || password.isEmpty() ? "password" : password;
    }

    // trades can retain absent wallets, and main-wallet backups can contain a different seed after a restore
    public static List<String> cleanupBackups(File walletDir, File currentSnapshot, String mainAddress) throws IOException {
        if (mainAddress == null && currentSnapshot != null) {
            mainAddress = readMainWalletAddress(currentSnapshot.toPath());
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
        // preserve temporary keys until they can be matched to a wallet
        try (var files = Files.list(walletDir.toPath())) {
            files.filter(path -> {
                String name = path.getFileName().toString();
                return name.endsWith(".new") || (name.startsWith(".") && name.contains(".keys.haveno-write-") && name.endsWith(".tmp"));
            }).forEach(path -> retained.add("wallet/" + path.getFileName()));
        }
        File backupDir = new File(walletDir, "backup");
        if (!backupDir.exists()) return retained;
        try (var entries = Files.list(backupDir.toPath())) {
            for (var entry : entries.toList()) {
                if (currentSnapshot != null && entry.toFile().equals(currentSnapshot)) continue;
                String name = entry.getFileName().toString();
                boolean keep = true;
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) && name.startsWith("backups_")) {
                    keep = !rollingDirs.contains(name) || name.equals("backups_haveno_XMR")
                            || name.equals("backups_haveno_XMR_keys") || name.equals("backups_haveno_XMR_address_txt");
                }
                if (name.startsWith("password-change-") && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    boolean sameMainWallet = mainAddress != null && mainAddress.equals(readMainWalletAddress(entry));
                    try (var files = Files.list(entry)) {
                        keep = files.anyMatch(path -> path.getFileName().toString().endsWith(".keys")
                                && ((path.getFileName().toString().equals("haveno_XMR.keys") && !sameMainWallet)
                                || !Files.exists(walletDir.toPath().resolve(path.getFileName()))
                                || !Files.exists(walletDir.toPath().resolve(path.getFileName().toString().replaceFirst("\\.keys$", "")))));
                    }
                }
                if (keep) retained.add(name);
                else FileUtil.deleteDirectory(entry.toFile(), null, false);
            }
        }
        FileUtil.syncDirectory(backupDir.toPath());
        return retained;
    }

    public static String readMainWalletAddress(Path snapshot) {
        try {
            return Files.readString(snapshot.resolve(MAIN_WALLET_ADDRESS_FILE));
        } catch (IOException e) {
            return null; // preserve snapshots whose identity cannot be read
        }
    }

    public static boolean isPasswordError(Throwable error) {
        if (error instanceof MoneroRpcError && Integer.valueOf(-22).equals(((MoneroRpcError) error).getCode())) return true;
        String message = error.getMessage();
        return message != null && (message.toLowerCase(Locale.ROOT).contains("invalid password")
                || message.toLowerCase(Locale.ROOT).contains("invalid original password"));
    }

}
