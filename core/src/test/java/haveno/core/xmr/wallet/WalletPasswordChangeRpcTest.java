package haveno.core.xmr.wallet;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import monero.wallet.MoneroWalletRpc;
import monero.wallet.model.MoneroWalletConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses only fresh, unfunded offline wallets under @TempDir; never the user's wallet directory. */
class WalletPasswordChangeRpcTest {
    @TempDir Path directory;

    @Test
    @Timeout(90)
    void realWalletsRecoverAnInterruptedPasswordChange() throws Exception {
        Path binary = Path.of("src/main/resources/bin/monero-wallet-rpc").toAbsolutePath();
        assumeTrue(Files.isExecutable(binary), "monero-wallet-rpc is not available for this platform");
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        MoneroWalletRpc rpc = new MoneroWalletRpc(List.of(binary.toString(), "--offline", "--no-initial-sync",
                "--rpc-bind-ip", "127.0.0.1", "--rpc-bind-port", Integer.toString(port),
                "--rpc-login", "test:test", "--wallet-dir", directory.toString(),
                "--log-file", directory.resolve("wallet-rpc.log").toString()));
        try {
            rpc.createWallet(new MoneroWalletConfig().setPath("first").setPassword("old-password").setLanguage("English"));
            String firstAddress = rpc.getPrimaryAddress();
            WalletPasswordChange.change(rpc, "old-password", "new-password");
            rpc.close(true);
            WalletPasswordChange.syncFiles(directory.resolve("first"));
            rpc.createWallet(new MoneroWalletConfig().setPath("second").setPassword("old-password").setLanguage("English"));
            String secondAddress = rpc.getPrimaryAddress();
            rpc.close(true);

            // Simulated restart: the first wallet committed; the second is still on the old password.
            for (String name : List.of("first", "second")) {
                WalletPasswordChange.open("new-password", "old-password", password -> {
                    rpc.openWallet(new MoneroWalletConfig().setPath(name).setPassword(password));
                    return true;
                });
                WalletPasswordChange.change(rpc, "old-password", "new-password");
                assertEquals(name.equals("first") ? firstAddress : secondAddress, rpc.getPrimaryAddress());
                rpc.close(true);
                WalletPasswordChange.syncFiles(directory.resolve(name));
                assertThrows(RuntimeException.class, () -> rpc.openWallet(new MoneroWalletConfig().setPath(name).setPassword("old-password")));
                rpc.openWallet(new MoneroWalletConfig().setPath(name).setPassword("new-password"));
                rpc.close(true);
            }
        } finally {
            rpc.stopProcess();
        }
    }
}
