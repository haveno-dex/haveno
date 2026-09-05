package haveno.core.xmr.wallet;

import java.util.ArrayList;
import java.util.List;
import monero.wallet.MoneroWallet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WalletPasswordChangeTest {
    @Test
    void alreadyChangedWalletIsVerifiedAndSaved() {
        MoneroWallet wallet = mock(MoneroWallet.class);
        doThrow(new IllegalStateException("old password rejected")).when(wallet).changePassword("old", "new");
        WalletPasswordChange.change(wallet, "old", "new");
        verify(wallet).changePassword("new", "new");
        verify(wallet).save();
    }

    @Test
    void unrelatedWalletAndDiskFailuresAreNeverSwallowed() {
        MoneroWallet wallet = mock(MoneroWallet.class);
        doThrow(new IllegalStateException("wrong key")).when(wallet).changePassword(anyString(), anyString());
        assertThrows(IllegalStateException.class, () -> WalletPasswordChange.change(wallet, "old", "new"));
        verify(wallet, never()).save();
        MoneroWallet diskFailure = mock(MoneroWallet.class);
        doThrow(new IllegalStateException("disk full")).when(diskFailure).save();
        assertThrows(IllegalStateException.class, () -> WalletPasswordChange.change(diskFailure, "old", "new"));
    }

    @Test
    void pendingPasswordIsTriedBeforeAnyCacheRepair() {
        List<String> attempts = new ArrayList<>();
        String result = WalletPasswordChange.open("new", "old", password -> {
            attempts.add(password);
            if (password.equals("new")) throw new IllegalStateException("not changed yet");
            return "opened";
        });
        assertEquals("opened", result);
        assertEquals(List.of("new", "old"), attempts);
        assertThrows(IllegalStateException.class, () -> WalletPasswordChange.open("new", null, password -> {
            throw new IllegalStateException("wrong password");
        }));
    }
    @Test
    void missingWalletKeysCannotBeReportedAsDurable(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
        assertThrows(IllegalStateException.class, () -> WalletPasswordChange.syncFiles(directory.resolve("wallet")));
    }
}
