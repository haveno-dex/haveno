package haveno.common.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static java.nio.file.Files.createTempDirectory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class FileUtilTest {

    @Test
    public void rollingBackupRestrictsNewAndExistingBackups(@TempDir Path dir) throws IOException {
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView(PosixFileAttributeView.class));
        for (boolean existing : List.of(false, true)) {
            Path storage = Files.createDirectory(dir.resolve(existing ? "existing" : "new"));
            Path source = Files.writeString(storage.resolve("wallet.keys"), "current");
            Path backupDir = storage.resolve("backup");
            Path backups = backupDir.resolve("backups_wallet_keys");
            if (existing) {
                Files.createDirectories(backups);
                Files.setPosixFilePermissions(backupDir, PosixFilePermissions.fromString("rwxr-xr-x"));
                Files.setPosixFilePermissions(backups, PosixFilePermissions.fromString("rwxr-xr-x"));
                Files.writeString(backups.resolve("0000000000000_wallet.keys"), "oldest");
                Files.writeString(backups.resolve("0000000000001_wallet.keys"), "retained");
            }

            assertTrue(FileUtil.rollingBackup(storage.toFile(), "wallet.keys", 2));

            Path latest = FileUtil.getLatestBackupFile(storage.toFile(), "wallet.keys").toPath();
            assertEquals("current", Files.readString(source));
            assertEquals("current", Files.readString(latest));
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(backupDir));
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(backups));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(latest));
            assertEquals(existing ? 2 : 1, FileUtil.getBackupFiles(storage.toFile(), "wallet.keys").size());
            if (existing) {
                assertFalse(Files.exists(backups.resolve("0000000000000_wallet.keys")));
                assertEquals("retained", Files.readString(backups.resolve("0000000000001_wallet.keys")));
            }
        }
    }

    @Test
    public void rollingBackupReportsFailureWithoutChangingSource(@TempDir Path dir) throws IOException {
        Path source = Files.writeString(dir.resolve("wallet.keys"), "current");
        Path blocker = Files.writeString(dir.resolve("backup"), "keep");
        boolean posix = Files.getFileStore(blocker).supportsFileAttributeView(PosixFileAttributeView.class);
        if (posix) Files.setPosixFilePermissions(blocker, PosixFilePermissions.fromString("rw-r--r--"));

        assertFalse(FileUtil.rollingBackup(dir.toFile(), "wallet.keys", 2));
        assertEquals("current", Files.readString(source));
        assertEquals("keep", Files.readString(blocker));
        if (posix) assertEquals(PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(blocker));
        assertTrue(FileUtil.rollingBackup(dir.toFile(), "missing", 2));
        assertTrue(FileUtil.rollingBackup(dir.toFile(), "wallet.keys", 0));
    }

    @Test
    public void rollingBackupCleansUpFailedCopyWithoutPruning(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("wallet.keys"));
        Path backups = Files.createDirectories(dir.resolve("backup/backups_wallet_keys"));
        Path retained = Files.writeString(backups.resolve("0000000000000_wallet.keys"), "retained");

        assertFalse(FileUtil.rollingBackup(dir.toFile(), "wallet.keys", 1));

        assertEquals("retained", Files.readString(retained));
        assertEquals(1, FileUtil.getBackupFiles(dir.toFile(), "wallet.keys").size());
    }

    @Test
    public void ownerOnlyPermissionsAreOptionalOnNonPosixFileSystems(@TempDir Path dir) throws IOException {
        try (FileSystem zip = FileSystems.newFileSystem(dir.resolve("backup.zip"), Map.of("create", "true"))) {
            Path file = Files.writeString(zip.getPath("/key"), "secret");
            assertFalse(Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView.class));

            FileUtil.setOwnerOnlyPermissions(file);

            assertEquals("secret", Files.readString(file));
        }
    }

    @Test
    public void deleteDirectoryKeepsNestedExcludeAndItsAncestors() throws IOException {
        File root = createTempDirectory("FileUtilTest").toFile();
        File keep = new File(root, "a/b/keep");
        assertTrue(keep.mkdirs());
        assertTrue(new File(keep, "key").createNewFile());
        assertTrue(new File(root, "a/b/other").createNewFile());
        assertTrue(new File(root, "a/sibling").mkdirs());
        assertTrue(new File(root, "top").createNewFile());

        assertFalse(FileUtil.deleteDirectory(root, keep, false));

        assertTrue(new File(keep, "key").exists());
        assertFalse(new File(root, "a/b/other").exists());
        assertFalse(new File(root, "a/sibling").exists());
        assertFalse(new File(root, "top").exists());
        FileUtil.deleteDirectory(root);
    }

    @Test
    public void deleteDirectoryDeletesRootWhenExcludeIsAbsent() throws IOException {
        File root = createTempDirectory("FileUtilTest").toFile();
        assertTrue(new File(root, "a/b").mkdirs());
        assertTrue(new File(root, "a/b/file").createNewFile());

        assertTrue(FileUtil.deleteDirectory(root, new File(root, "missing"), false));

        assertFalse(root.exists());
    }
}
