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

package haveno.common.persistence;

import haveno.common.crypto.Encryption;
import haveno.common.file.FileUtil;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;

/**
 * Durable, authenticated inventory of the plaintext store files that existed before an account's
 * legacy key material was replaced by the v2 format. Plaintext is forgeable, so after that
 * replacement a plaintext store may only be read if it was recorded here (by name and content hash)
 * while the legacy key files - which cannot be planted without the password - still existed.
 * Entries are removed as stores are re-persisted encrypted. The inventory itself is a permanent
 * migration-state marker: it stays on disk (empty once migration completes) so plaintext is never
 * again authorized without a matching entry.
 */
@Slf4j
public class PlaintextMigration {

    public static final String FILE_NAME = "plaintext_migration";

    /**
     * Scans the storage dir and durably records every file that is neither v2 nor valid
     * legacy-encrypted, then encrypts residual plaintext in the backup and corruption-recovery
     * trees in place. Must be called before the legacy key files are replaced; throwing here
     * must abort that replacement so the record can be retried.
     */
    public static synchronized void record(File storageDir, SecretKey masterKey) throws IOException {
        Map<String, String> entries = new TreeMap<>();
        File[] files = storageDir.listFiles();
        if (files == null) throw new IOException("Could not list storage directory " + storageDir.getAbsolutePath());
        for (File file : files) {
            if (!file.isFile() || file.length() == 0) continue;
            String name = file.getName();
            if (name.equals(FILE_NAME) || name.endsWith(".tmp") || name.startsWith("temp_")) continue;
            if (isEncrypted(file, masterKey)) continue;
            entries.put(name, HexFormat.of().formatHex(sha256(file)));
        }
        // written even when empty: the inventory is the durable marker that migration ran, so
        // plaintext is never again authorized without a matching entry
        if (!entries.isEmpty()) log.info("Recording {} unmigrated plaintext store(s) before key migration: {}", entries.size(), entries.keySet());
        write(storageDir, entries, masterKey);

        // historical plaintext copies must not outlive the migration either; encrypting them in
        // place preserves their recovery value without leaving plaintext on disk
        for (File backupsDir : FileUtil.getRollingBackupDirs(storageDir)) encryptResidualPlaintext(backupsDir, masterKey);
        encryptResidualPlaintext(new File(storageDir, FileUtil.CORRUPTED_BACKUP_FOLDER), masterKey);
    }

    public static boolean hasInventory(File storageDir) {
        return inventoryFile(storageDir).exists();
    }

    private static void encryptResidualPlaintext(File dir, SecretKey masterKey) throws IOException {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        // fail closed: an uninspectable directory must abort the migration, not be skipped
        if (files == null) throw new IOException("Could not list directory " + dir.getAbsolutePath());
        for (File file : files) {
            if (!file.isFile() || file.length() == 0 || file.getName().endsWith(".tmp")) continue;
            if (isEncrypted(file, masterKey)) continue;
            log.info("Encrypting residual plaintext file {}", file.getName());
            try {
                // streamed so a large backup cannot exhaust the heap
                File tempFile = new File(dir, file.getName() + ".tmp");
                try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                    Encryption.encryptV2ToStream(out -> {
                        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                            in.transferTo(out);
                        }
                    }, masterKey, fos);
                    fos.flush();
                    fos.getFD().sync();
                }
                FileUtil.atomicReplace(tempFile, file);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Could not encrypt residual plaintext file " + file.getName(), e);
            }
        }
    }

    /**
     * Returns the recorded pre-migration content hash for a store, or null if not recorded.
     */
    public static synchronized byte[] getRecordedHash(File storageDir, String fileName, SecretKey masterKey) {
        Map<String, String> entries = read(storageDir, masterKey);
        String hash = entries == null ? null : entries.get(fileName);
        return hash == null ? null : HexFormat.of().parseHex(hash);
    }

    /**
     * Removes a store from the inventory after it was durably persisted in an encrypted format.
     * The (possibly empty) inventory is rewritten, never deleted, and a failure propagates so the
     * caller does not treat the write as complete while the old plaintext hash stays authorized.
     */
    public static synchronized void markMigrated(File storageDir, String fileName, SecretKey masterKey) throws IOException {
        if (masterKey == null || !inventoryFile(storageDir).exists()) return;
        Map<String, String> entries = read(storageDir, masterKey);
        if (entries == null || entries.remove(fileName) == null) return;
        write(storageDir, entries, masterKey);
    }

    private static File inventoryFile(File storageDir) {
        return new File(storageDir, FILE_NAME);
    }

    // A file is already protected if it is a v2 blob or verifies as a legacy encrypted stream.
    private static boolean isEncrypted(File file, SecretKey masterKey) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            if (Encryption.blobVersion(in) > 0) return true;
        } catch (IOException e) {
            return false;
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            Encryption.verifyPayloadWithHmacStream(in, masterKey);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buf = new byte[64 * 1024];
                int read;
                while ((read = in.read(buf)) != -1) digest.update(buf, 0, read);
            }
            return digest.digest();
        } catch (Exception e) {
            throw new IOException("Could not hash " + file.getName(), e);
        }
    }

    private static Map<String, String> read(File storageDir, SecretKey masterKey) {
        File file = inventoryFile(storageDir);
        if (masterKey == null || !file.exists()) return null;
        try {
            byte[] plain = Encryption.decryptV2(Files.readAllBytes(file.toPath()), masterKey);
            Map<String, String> entries = new TreeMap<>();
            for (String line : new String(plain, StandardCharsets.UTF_8).split("\n")) {
                if (line.isEmpty()) continue;
                int sep = line.indexOf(' ');
                entries.put(line.substring(sep + 1), line.substring(0, sep));
            }
            return entries;
        } catch (Exception e) {
            log.error("Could not read plaintext migration inventory; treating as absent", e);
            return null;
        }
    }

    private static void write(File storageDir, Map<String, String> entries, SecretKey masterKey) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            sb.append(entry.getValue()).append(' ').append(entry.getKey()).append('\n');
        }
        try {
            byte[] blob = Encryption.encryptV2(sb.toString().getBytes(StandardCharsets.UTF_8), masterKey);
            File tempFile = new File(storageDir, FILE_NAME + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(blob);
                fos.flush();
                fos.getFD().sync();
            }
            FileUtil.atomicReplace(tempFile, inventoryFile(storageDir));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not write plaintext migration inventory", e);
        }
    }
}
