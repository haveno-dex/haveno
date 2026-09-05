/*
 * This file is part of Haveno.
 * See LICENSE for licensing information.
 */
package haveno.core.xmr.wallet;

import java.util.function.Function;
import haveno.common.file.AtomicFileWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import monero.wallet.MoneroWallet;

/** Idempotent operations used both during a password change and after an interrupted change. */
public final class WalletPasswordChange {
    private WalletPasswordChange() {}

    public static void change(MoneroWallet wallet, String oldPassword, String newPassword) {
        try {
            wallet.changePassword(oldPassword, newPassword);
        } catch (RuntimeException first) {
            // A previous attempt may already have changed this wallet. Verify that state using
            // the backend, rather than accepting a password-error string as proof of success.
            try {
                wallet.changePassword(newPassword, newPassword);
            } catch (RuntimeException second) {
                if (second != first) second.addSuppressed(first);
                throw second;
            }
        }
        wallet.save();
    }

    /** Monero save/rename alone is not a filesystem durability barrier. Close Windows wallets first. */
    public static void syncFiles(Path walletPath) {
        Path keys = walletPath.resolveSibling(walletPath.getFileName() + ".keys");
        try {
            try (FileChannel channel = FileChannel.open(keys, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            if (Files.exists(walletPath)) {
                try (FileChannel channel = FileChannel.open(walletPath, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
            }
            AtomicFileWriter.syncDirectory(walletPath.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Could not make changed wallet files durable; password recovery remains pending", e);
        }
    }

    static <T> T open(String password, String previousPassword, Function<String, T> opener) {
        try {
            return opener.apply(password);
        } catch (RuntimeException first) {
            if (previousPassword == null || previousPassword.equals(password)) throw first;
            try {
                return opener.apply(previousPassword);
            } catch (RuntimeException second) {
                if (first != second) first.addSuppressed(second);
                throw first;
            }
        }
    }
}
