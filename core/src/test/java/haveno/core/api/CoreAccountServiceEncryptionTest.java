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

package haveno.core.api;

import haveno.common.config.Config;
import haveno.common.persistence.PersistenceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CoreAccountServiceEncryptionTest {
    @TempDir Path dir;
    private boolean previouslyInitialized;

    @BeforeEach
    void readyAccount() {
        previouslyInitialized = PersistenceManager.allServicesInitialized.getAndSet(true);
    }

    @AfterEach
    void restoreInitializationState() {
        PersistenceManager.allServicesInitialized.set(previouslyInitialized);
    }

    @Test
    void passwordChangeIsBlockedUntilMigrationFinishes() throws Exception {
        KeyStorage storage = new KeyStorage(dir.toFile());
        KeyRing ring = new KeyRing(storage);
        CoreAccountService service = new CoreAccountService(mock(Config.class), storage, ring);
        service.createAccount("old-password");
        byte[] before = Files.readAllBytes(dir.resolve("sym.key"));
        PersistenceManager.allServicesInitialized.set(false);
        assertThrows(IllegalStateException.class, () -> service.changePassword("old-password", "new-password"));
        assertArrayEquals(before, Files.readAllBytes(dir.resolve("sym.key")));
        assertEquals("old-password", service.getPassword());
    }

    @Test
    void invalidPasswordIsRejectedBeforeWalletListenersRun() {
        KeyStorage storage = new KeyStorage(dir.toFile());
        KeyRing ring = new KeyRing(storage);
        CoreAccountService service = new CoreAccountService(mock(Config.class), storage, ring);
        service.createAccount("old-password");
        int[] changes = {0};
        service.addListener(new AccountServiceListener() {
            @Override
            public void onPasswordChanged(String oldPassword, String newPassword) {
                changes[0]++;
            }
        });
        assertThrows(IllegalArgumentException.class, () -> service.changePassword("old-password", "non-ascii-password-\u00e9"));
        assertEquals(0, changes[0]);
        assertEquals("old-password", service.getPassword());
    }

    @Test
    void backupFailureAfterLiveCommitKeepsNewPasswordInMemory() throws Exception {
        KeyStorage storage = new KeyStorage(dir.toFile());
        KeyRing ring = new KeyRing(storage);
        CoreAccountService service = new CoreAccountService(mock(Config.class), storage, ring);
        service.createAccount("old-password");
        Files.delete(dir.resolve("sym.key.bak"));
        Files.createDirectory(dir.resolve("sym.key.bak"));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.changePassword("old-password", "new-password"));
        assertTrue(error.getMessage().startsWith("Password changed,"));
        assertEquals("new-password", service.getPassword());
        assertArrayEquals(ring.getSymmetricKey().getEncoded(),
                storage.loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, "new-password").getEncoded());
    }
}
