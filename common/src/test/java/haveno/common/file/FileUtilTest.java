/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.common.file;

import haveno.common.util.Utilities;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FileUtilTest {

    @Test
    public void testUnlistableBackupDirsFailClosed(@TempDir File dir) throws Exception {
        Assumptions.assumeFalse(Utilities.isWindows()); // POSIX permissions

        File backupRoot = new File(dir, "backup");
        File backupsDir = new File(backupRoot, "backups_test");
        assertTrue(backupsDir.mkdirs());
        Files.write(new File(backupsDir, "1_test").toPath(), new byte[]{1});
        assertTrue(FileUtil.hasBackups(dir, "test"));

        // an unlistable, still-populated directory must count as present, not empty
        assertTrue(backupsDir.setReadable(false));
        Assumptions.assumeTrue(backupsDir.listFiles() == null, "permissions not enforced (running as root?)");
        try {
            assertTrue(FileUtil.hasBackups(dir, "test"));
        } finally {
            assertTrue(backupsDir.setReadable(true));
        }

        // and an unlistable backup root must fail the sweep instead of reporting success
        assertTrue(backupRoot.setReadable(false));
        try {
            assertThrows(IOException.class, () -> FileUtil.getRollingBackupDirsExcept(dir, Set.of()));
        } finally {
            assertTrue(backupRoot.setReadable(true));
        }

        FileUtil.deleteRollingBackup(dir, "test");
        assertFalse(FileUtil.hasBackups(dir, "test"));
    }
}
