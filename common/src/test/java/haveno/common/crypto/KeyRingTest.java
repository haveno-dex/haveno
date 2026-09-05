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

package haveno.common.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyRingTest {
    @TempDir
    Path dir;

    @Test
    void injectedConstructorDefersPasswordlessUnlockUntilLogin() throws Exception {
        assertDeferredUnlock(null);
    }

    @Test
    void injectedConstructorDefersEmptyPasswordUnlockUntilLogin() throws Exception {
        assertDeferredUnlock("");
    }

    @Test
    void injectedConstructorDefersPasswordProtectedUnlockUntilLogin() throws Exception {
        assertDeferredUnlock("password");
    }

    private void assertDeferredUnlock(String password) throws Exception {
        KeyStorage storage = new KeyStorage(dir.toFile());
        KeyRing original = new KeyRing(storage, password, true);
        Map<KeyStorage.KeyEntry, byte[]> savedKeys = new EnumMap<>(KeyStorage.KeyEntry.class);
        for (KeyStorage.KeyEntry entry : KeyStorage.KeyEntry.values()) {
            savedKeys.put(entry, Files.readAllBytes(dir.resolve(entry.getFileName())));
        }

        KeyRing restarted = new KeyRing(storage);
        assertFalse(restarted.isUnlocked());
        assertNull(restarted.getSymmetricKey());
        assertNull(restarted.getPubKeyRing());
        for (KeyStorage.KeyEntry entry : savedKeys.keySet()) {
            assertArrayEquals(savedKeys.get(entry), Files.readAllBytes(dir.resolve(entry.getFileName())));
        }

        assertTrue(restarted.unlockKeys(password, false));
        assertEquals(original.getPubKeyRing(), restarted.getPubKeyRing());
        assertArrayEquals(original.getSymmetricKey().getEncoded(), restarted.getSymmetricKey().getEncoded());
        for (KeyStorage.KeyEntry entry : savedKeys.keySet()) {
            assertArrayEquals(savedKeys.get(entry), Files.readAllBytes(dir.resolve(entry.getFileName())));
        }
    }

    @Test
    void incorrectPasswordCanBeRetriedAfterDeferredUnlock() throws Exception {
        KeyStorage storage = new KeyStorage(dir.toFile());
        KeyRing original = new KeyRing(storage, "password", true);
        KeyRing restarted = new KeyRing(storage);

        assertThrows(IncorrectPasswordException.class, () -> restarted.unlockKeys(null, false));
        assertFalse(restarted.isUnlocked());
        assertTrue(restarted.unlockKeys("password", false));
        assertEquals(original.getPubKeyRing(), restarted.getPubKeyRing());
    }
}
