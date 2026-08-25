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

package haveno.common.file;

import com.google.common.io.Files;
import haveno.common.util.Utilities;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class FileUtil {

    // Where corrupted store files are preserved for recovery; referenced by the
    // popup.warning.incompatibleDB resource string shown to the user.
    public static final String CORRUPTED_BACKUP_FOLDER = "backup_of_corrupted_data";

    private static final String BACKUP_DIR = "backup";

    /** The root directory holding a directory's rolling backups. */
    public static File getBackupRoot(File dir) {
        return new File(dir, BACKUP_DIR);
    }

    // Backup subdirectory name for a file, e.g. "backups_sym_key" for "sym.key".
    private static String backupDirName(String fileName) {
        return ("backups_" + fileName).replace(".", "_");
    }

    public static void rollingBackup(File dir, String fileName, int numMaxBackupFiles) {
        if (numMaxBackupFiles <= 0) return;
        if (dir.exists()) {
            File backupDir = new File(Paths.get(dir.getAbsolutePath(), BACKUP_DIR).toString());
            if (!backupDir.exists())
                if (!backupDir.mkdir())
                    log.warn("make dir failed.\nBackupDir=" + backupDir.getAbsolutePath());

            File origFile = new File(Paths.get(dir.getAbsolutePath(), fileName).toString());
            if (origFile.exists()) {
                File backupFileDir = new File(Paths.get(backupDir.getAbsolutePath(), backupDirName(fileName)).toString());
                if (!backupFileDir.exists())
                    if (!backupFileDir.mkdir())
                        log.warn("make backupFileDir failed.\nBackupFileDir=" + backupFileDir.getAbsolutePath());

                File backupFile = new File(Paths.get(backupFileDir.getAbsolutePath(), new Date().getTime() + "_" + fileName).toString());

                try {
                    Files.copy(origFile, backupFile);

                    pruneBackup(backupFileDir, numMaxBackupFiles);
                } catch (IOException e) {
                    log.error("Backup key failed: {}\n", e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Rolling backup for critical files (key material, wallets): creates the directories, streams
     * the copy with fsync and verifies it by content hash, throwing on any failure instead of
     * logging it. Returns the created backup file.
     */
    public static File rollingBackupStrict(File dir, String fileName, int numMaxBackupFiles) throws IOException {
        File origFile = new File(Paths.get(dir.getAbsolutePath(), fileName).toString());
        if (!origFile.isFile()) throw new IOException("Cannot back up missing file " + origFile.getAbsolutePath());
        File backupFileDir = new File(Paths.get(dir.getAbsolutePath(), BACKUP_DIR, backupDirName(fileName)).toString());
        java.nio.file.Files.createDirectories(backupFileDir.toPath());
        File backupFile = new File(Paths.get(backupFileDir.getAbsolutePath(), new Date().getTime() + "_" + fileName).toString());
        byte[] sourceHash;
        try (InputStream in = new FileInputStream(origFile);
             FileOutputStream fos = new FileOutputStream(backupFile)) {
            MessageDigest digest = newSha256();
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) != -1) {
                digest.update(buf, 0, read);
                fos.write(buf, 0, read);
            }
            fos.flush();
            fos.getFD().sync();
            sourceHash = digest.digest();
        }
        if (!MessageDigest.isEqual(sourceHash, sha256(backupFile))) {
            throw new IOException("Backup verification failed for " + backupFile.getAbsolutePath());
        }
        // best effort: make the new file entry, and the backup dir entry if it was just created,
        // power-loss durable
        syncParentDir(backupFile);
        syncParentDir(backupFileDir);
        pruneBackup(backupFileDir, numMaxBackupFiles);
        return backupFile;
    }

    /**
     * Replaces a file's rolling backups with a single fresh verified copy, for content that must
     * not survive in backups (e.g. credentials under a retired password or a deprecated format).
     * The fresh backup is created and verified first, so a failure can never leave the file
     * without a usable backup; only then are the stale generations purged, with verification.
     * Returns the fresh backup, or null if the file does not exist (backups are still purged).
     */
    public static File replaceRollingBackups(File dir, String fileName) throws IOException {
        File source = new File(Paths.get(dir.getAbsolutePath(), fileName).toString());
        File freshBackup = source.isFile() ? rollingBackupStrict(dir, fileName, Integer.MAX_VALUE) : null;
        List<File> backups = getBackupFiles(dir, fileName);
        // an unlistable backup dir reads as empty; the just-created backup proves listability
        if (freshBackup != null && !backups.contains(freshBackup)) throw new IOException("Backup directory of " + fileName + " is not listable");
        boolean deleted = false;
        for (File backup : backups) {
            if (!backup.equals(freshBackup)) {
                deleteFileIfExists(backup, false);
                deleted = true;
            }
        }
        for (File backup : getBackupFiles(dir, fileName)) {
            if (!backup.equals(freshBackup)) throw new IOException("Could not delete stale backups of " + fileName);
        }
        // best effort: make the deletions power-loss durable, so purged generations (e.g. under a
        // retired password) cannot resurrect
        if (deleted) syncParentDir(freshBackup != null ? freshBackup : backups.get(0));
        return freshBackup;
    }

    private static byte[] sha256(File file) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) != -1) digest.update(buf, 0, read);
        }
        return digest.digest();
    }

    private static MessageDigest newSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    public static List<File> getBackupFiles(File dir, String fileName) {
        File backupDir = new File(Paths.get(dir.getAbsolutePath(), BACKUP_DIR).toString());
        if (!backupDir.exists()) return new ArrayList<File>();
        File backupFileDir = new File(Paths.get(backupDir.getAbsolutePath(), backupDirName(fileName)).toString());
        if (!backupFileDir.exists()) return new ArrayList<File>();
        File[] files = backupFileDir.listFiles();
        return files == null ? new ArrayList<File>() : Arrays.asList(files);
    }

    public static boolean hasBackups(File dir, String fileName) {
        File backupDir = new File(Paths.get(dir.getAbsolutePath(), BACKUP_DIR).toString());
        File backupFileDir = new File(Paths.get(backupDir.getAbsolutePath(), backupDirName(fileName)).toString());
        if (!backupFileDir.exists()) return false;
        File[] files = backupFileDir.listFiles();
        return files == null || files.length > 0; // an unlistable directory counts as present (fail closed)
    }

    /** The rolling-backup directories under dir, i.e. backup/backups_*; throws if unlistable. */
    public static List<File> getRollingBackupDirs(File dir) throws IOException {
        File backupDir = new File(Paths.get(dir.getAbsolutePath(), BACKUP_DIR).toString());
        List<File> dirs = new ArrayList<>();
        if (!backupDir.exists()) return dirs;
        File[] children = backupDir.listFiles();
        if (children == null) throw new IOException("Could not list backup directory " + backupDir.getAbsolutePath());
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith("backups_")) dirs.add(child);
        }
        return dirs;
    }

    public static File getLatestBackupFile(File dir, String fileName) {
        List<File> files = getBackupFiles(dir, fileName);
        if (files.isEmpty()) return null;
        files.sort(Comparator.comparing(File::getName));
        return files.get(files.size() - 1);
    }

    public static void deleteRollingBackup(File dir, String fileName) {
        File backupDir = new File(Paths.get(dir.getAbsolutePath(), BACKUP_DIR).toString());
        if (!backupDir.exists()) return;
        File backupFileDir = new File(Paths.get(backupDir.getAbsolutePath(), backupDirName(fileName)).toString());
        try {
            FileUtils.deleteDirectory(backupFileDir);
        } catch (IOException e) {
            log.error("Delete backup key failed: {}\n", e.getMessage(), e);
        }
    }

    /** Like {@link #deleteRollingBackup} but verifies the deletion and propagates failure. */
    public static void deleteRollingBackupStrict(File dir, String fileName) throws IOException {
        File backupDir = new File(Paths.get(dir.getAbsolutePath(), BACKUP_DIR).toString());
        if (!backupDir.exists()) return;
        File backupFileDir = new File(Paths.get(backupDir.getAbsolutePath(), backupDirName(fileName)).toString());
        if (!backupFileDir.exists()) return;
        FileUtils.deleteDirectory(backupFileDir);
        if (backupFileDir.exists()) throw new IOException("Failed to delete backup directory " + backupFileDir.getAbsolutePath());
        syncParentDir(backupFileDir); // best effort: make the removal power-loss durable
    }

    /**
     * The rolling-backup directories under dir not belonging to any of the given file names,
     * e.g. backups of wallets that no longer exist; throws if the backup root is unlistable.
     */
    public static List<File> getRollingBackupDirsExcept(File dir, Collection<String> fileNamesToKeep) throws IOException {
        Set<String> keep = fileNamesToKeep.stream().map(FileUtil::backupDirName).collect(Collectors.toSet());
        List<File> staleDirs = new ArrayList<>();
        for (File backupFileDir : getRollingBackupDirs(dir)) {
            if (!keep.contains(backupFileDir.getName())) staleDirs.add(backupFileDir);
        }
        return staleDirs;
    }

    private static void pruneBackup(File backupDir, int numMaxBackupFiles) {
        if (backupDir.isDirectory()) {
            File[] files = backupDir.listFiles();
            if (files != null) {
                List<File> filesList = Arrays.asList(files);
                if (filesList.size() > numMaxBackupFiles) {
                    filesList.sort(Comparator.comparing(File::getName));
                    File file = filesList.get(0);
                    if (file.isFile()) {
                        if (!file.delete())
                            log.error("Failed to delete file: " + file);
                        else
                            pruneBackup(backupDir, numMaxBackupFiles);

                    } else {
                        pruneBackup(new File(Paths.get(backupDir.getAbsolutePath(), file.getName()).toString()), numMaxBackupFiles);
                    }
                }
            }
        }
    }

    public static void deleteDirectory(File file) throws IOException {
        deleteDirectory(file, null, true);
    }

    public static void deleteDirectory(File file,
                                       @Nullable File exclude,
                                       boolean ignoreLockedFiles) throws IOException {
        boolean excludeFileFound = false;
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null)
                for (File f : files) {
                    boolean excludeFileFoundLocal = exclude != null && f.getAbsolutePath().equals(exclude.getAbsolutePath());
                    excludeFileFound |= excludeFileFoundLocal;
                    if (!excludeFileFoundLocal)
                        deleteDirectory(f, exclude, ignoreLockedFiles);
                }
        }
        // Finally delete main file/dir if exclude file was not found in directory
        if (!excludeFileFound && !(exclude != null && file.getAbsolutePath().equals(exclude.getAbsolutePath()))) {
            try {
                deleteFileIfExists(file, ignoreLockedFiles);
            } catch (Throwable t) {
                log.error("Could not delete file. Error=" + t.toString());
                throw new IOException(t);
            }
        }
    }

    public static void deleteFileIfExists(File file) throws IOException {
        deleteFileIfExists(file, true);
    }

    public static void deleteFileIfExists(File file, boolean ignoreLockedFiles) throws IOException {
        try {
            if (Utilities.isWindows())
                file = file.getCanonicalFile();

            if (file.exists() && !file.delete()) {
                if (ignoreLockedFiles) {
                    // We check if file is locked. On Windows all open files are locked by the OS, so we
                    if (isFileLocked(file))
                        log.info("Failed to delete locked file: " + file.getAbsolutePath());
                } else {
                    final String message = "Failed to delete file: " + file.getAbsolutePath();
                    log.error(message);
                    throw new IOException(message);
                }
            }
        } catch (Throwable t) {
            log.error("Could not delete file, error={}\n", t.getMessage(), t);
            throw new IOException(t);
        }
    }

    private static boolean isFileLocked(File file) {
        return !file.canWrite();
    }

    public static void resourceToFile(String resourcePath,
                                      File destinationFile) throws ResourceNotFoundException, IOException {
        try (InputStream inputStream = ClassLoader.getSystemClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new ResourceNotFoundException(resourcePath);
            }
            try (FileOutputStream fileOutputStream = new FileOutputStream(destinationFile)) {
                IOUtils.copy(inputStream, fileOutputStream);
            }
        }
    }

    public static boolean resourceEqualToFile(String resourcePath,
                                      File destinationFile) throws ResourceNotFoundException, IOException {
        try (InputStream inputStream = ClassLoader.getSystemClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new ResourceNotFoundException(resourcePath);
            }
            return IOUtils.contentEquals(inputStream, new FileInputStream(destinationFile));
        }
    }

    public static void renameFile(File oldFile, File newFile) throws IOException {
        if (Utilities.isWindows()) {
            // Work around an issue on Windows whereby you can't rename over existing files.
            final File canonical = newFile.getCanonicalFile();
            if (canonical.exists() && !canonical.delete()) {
                throw new IOException("Failed to delete canonical file for replacement with save");
            }
            if (!oldFile.renameTo(canonical)) {
                throw new IOException("Failed to rename " + oldFile + " to " + canonical);
            }
        } else if (!oldFile.renameTo(newFile)) {
            throw new IOException("Failed to rename " + oldFile + " to " + newFile);
        }
    }

    /**
     * Replaces target with source atomically where the filesystem supports it, so no crash window
     * exists with neither file in place; the non-atomic fallback still replaces in a single move
     * (unlike {@link #renameFile}, which deletes the target first on Windows). The directory entry
     * is fsynced afterwards where the platform supports it, for power-loss durability.
     */
    public static void atomicReplace(File source, File target) throws IOException {
        try {
            java.nio.file.Files.move(source.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            java.nio.file.Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        syncParentDir(target);
    }

    // Fsyncs a file's directory entry; a no-op where directories cannot be opened (e.g. Windows).
    public static void syncParentDir(File file) {
        File parent = file.getParentFile();
        if (parent != null) syncDir(parent);
    }

    // Fsyncs a directory's entries; a no-op where directories cannot be opened (e.g. Windows).
    public static void syncDir(File dir) {
        try (FileChannel channel = FileChannel.open(dir.toPath(), StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignore) {
        }
    }

    public static void copyFile(File origin, File target) throws IOException {
        if (!origin.exists()) {
            return;
        }

        try {
            Files.copy(origin, target);
        } catch (IOException e) {
            log.error("Copy file failed", e);
            throw new IOException("Failed to copy " + origin + " to " + target);
        }

    }

    public static void copyDirectory(File source, File destination) throws IOException {
        FileUtils.copyDirectory(source, destination);
    }

    public static File createNewFile(Path path) throws IOException {
        File file = path.toFile();
        if (!file.createNewFile()) {
            throw new IOException("There already exists a file with path: " + path);
        }
        return file;
    }

    public static void removeAndBackupFile(File dbDir, File storageFile, String fileName, String backupFolderName)
            throws IOException {
        File corruptedBackupDir = new File(Paths.get(dbDir.getAbsolutePath(), backupFolderName).toString());
        if (!corruptedBackupDir.exists() && !corruptedBackupDir.mkdir()) {
            log.warn("make dir failed");
        }

        File corruptedFile = new File(Paths.get(dbDir.getAbsolutePath(), backupFolderName, fileName).toString());
        if (storageFile.exists()) {
            renameFile(storageFile, corruptedFile);
        }
    }

    public static boolean doesFileContainKeyword(File file, String keyword) throws FileNotFoundException {
        Scanner s = new Scanner(file);
        while (s.hasNextLine()) {
            if (s.nextLine().contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
