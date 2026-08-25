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

import com.google.common.io.ByteStreams;
import com.google.protobuf.CodedInputStream;
import haveno.common.crypto.Encryption;
import haveno.common.file.FileUtil;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;

/**
 * One-time migration of plaintext store files to the v2 encrypted format. Plaintext is forgeable,
 * so it is only ever parsed while the account still holds unmigrated legacy key material, which
 * cannot be planted without the password. Before that material is replaced, every plaintext file
 * in the persistence tree (live stores, crash-left temps, rolling backups and corruption backups)
 * is encrypted in place, and a durable marker is then written so plaintext is never again
 * accepted. The marker is written only after every file is encrypted, and the legacy key files
 * are only replaced after migration completes, so an interruption at any point simply resumes at
 * the next unlock.
 */
@Slf4j
public class PlaintextMigration {

    public static final String FILE_NAME = "plaintext_migration";

    // Latched per storage dir once migration completes or its marker verifies, so deleting the
    // on-disk marker cannot re-enable plaintext acceptance within this process.
    private static final Set<String> MIGRATED_DIRS = ConcurrentHashMap.newKeySet();
    // Existence-only marker observations, latched separately: they harden the plaintext gate but
    // must never authorize skipping the migration itself (the marker could be planted or corrupt).
    private static final Set<String> OBSERVED_MARKER_DIRS = ConcurrentHashMap.newKeySet();

    /**
     * Encrypts every plaintext file and upgrades every legacy encrypted file under the storage
     * dir in place, then durably writes the migration marker. Must be called before the legacy
     * key files are replaced; throwing here must abort that replacement so the migration can be
     * retried.
     */
    public static synchronized void migrate(File storageDir, SecretKey masterKey) throws IOException {
        // never re-run after completion: replaying captured legacy key files over a migrated
        // directory must not re-enable encrypting (and thereby authenticating) planted plaintext
        if (hasVerifiedMarker(storageDir, masterKey)) return;
        encryptResidualPlaintext(storageDir, masterKey, false);
        for (File backupsDir : FileUtil.getRollingBackupDirs(storageDir)) encryptResidualPlaintext(backupsDir, masterKey, false);
        // quarantined frame logs are never replayed again, so they are wrapped whole here rather
        // than left to the log's own frame migration
        encryptResidualPlaintext(new File(storageDir, FileUtil.CORRUPTED_BACKUP_FOLDER), masterKey, true);
        writeMarker(storageDir, masterKey);
        MIGRATED_DIRS.add(storageDir.getAbsolutePath());
    }

    /** Forgets latched migrations so an in-process restart re-reads the on-disk markers. */
    public static void reset() {
        MIGRATED_DIRS.clear();
        OBSERVED_MARKER_DIRS.clear();
    }

    /** Whether migration has completed, after which plaintext stores are never accepted. */
    public static boolean hasMarker(File storageDir) {
        String key = storageDir.getAbsolutePath();
        if (MIGRATED_DIRS.contains(key) || OBSERVED_MARKER_DIRS.contains(key)) return true;
        if (!markerFile(storageDir).exists()) return false;
        OBSERVED_MARKER_DIRS.add(key); // latch observed markers too
        return true;
    }

    // The skip below authorizes replacing the legacy key files, so the marker must authenticate
    // under the master key: a planted or corrupt marker re-runs the migration (idempotent) and is
    // rewritten, instead of stranding unencrypted stores behind the plaintext rejection.
    private static boolean hasVerifiedMarker(File storageDir, SecretKey masterKey) {
        if (MIGRATED_DIRS.contains(storageDir.getAbsolutePath())) return true;
        File marker = markerFile(storageDir);
        if (!marker.exists()) return false;
        try {
            Encryption.decryptV2(Files.readAllBytes(marker.toPath()), masterKey);
            MIGRATED_DIRS.add(storageDir.getAbsolutePath()); // a verified marker latches like a completed migration
            return true;
        } catch (Exception e) {
            log.warn("Migration marker failed verification; re-running the migration", e);
            return false;
        }
    }

    private static void encryptResidualPlaintext(File dir, SecretKey masterKey, boolean wrapFrameLogs) throws IOException {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        // fail closed: an uninspectable directory must abort the migration, not be skipped
        if (files == null) throw new IOException("Could not list directory " + dir.getAbsolutePath());
        for (File file : files) {
            if (!file.isFile() || file.length() == 0 || file.getName().equals(FILE_NAME)) continue;
            // JSON dumps (e.g. --dump-statistics) are exports for external readers, never stores
            if (file.getName().endsWith(".json")) continue;
            if (isCurrentFormat(file)) continue;
            // append-logs are encrypted per record inside a length-prefixed framing, not as one
            // blob; live ones migrate at replay
            if (!wrapFrameLogs && EncryptedAppendLog.isEncryptedFrameLog(file, masterKey)) continue;
            // legacy encrypted files are upgraded eagerly: files that are never persisted again
            // (archives, stale backups, rarely written stores) would otherwise keep the
            // deprecated format indefinitely
            if (isLegacyEncrypted(file, masterKey)) reEncryptLegacyInPlace(file, masterKey);
            else encryptInPlace(file, masterKey);
        }
    }

    private static void encryptInPlace(File file, SecretKey masterKey) throws IOException {
        log.info("Encrypting plaintext file {}", file.getName());
        // plaintext stores were written length-delimited while encrypted stores hold the raw
        // message, so a matching delimiter is stripped; other content is preserved byte for byte
        long payloadOffset = delimitedPayloadOffset(file);
        try {
            // streamed so a large file cannot exhaust the heap
            File tempFile = new File(file.getParentFile(), file.getName() + ".enc.tmp");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                Encryption.encryptV2ToStream(out -> {
                    try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                        in.skipNBytes(payloadOffset);
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
            throw new IOException("Could not encrypt plaintext file " + file.getName(), e);
        }
    }

    // Returns the size of a leading varint whose value equals exactly the remaining file length
    // (the layout writeDelimitedTo produces), or 0 for any other content.
    private static long delimitedPayloadOffset(File file) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            CodedInputStream codedInput = CodedInputStream.newInstance(in);
            long payloadLength = codedInput.readRawVarint32() & 0xffffffffL;
            int headerSize = codedInput.getTotalBytesRead();
            return payloadLength == file.length() - headerSize ? headerSize : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static File markerFile(File storageDir) {
        return new File(storageDir, FILE_NAME);
    }

    // A file is already protected if it is a v2 blob or verifies as a legacy encrypted stream.
    private static boolean isCurrentFormat(File file) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            return Encryption.blobVersion(in) >= Encryption.CURRENT_BLOB_VERSION;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isLegacyEncrypted(File file, SecretKey masterKey) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            Encryption.verifyPayloadWithHmacStream(in, masterKey);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // Legacy encrypted stores hold the raw message like v2 does, so the authenticated payload is
    // carried over byte for byte under the new format.
    private static void reEncryptLegacyInPlace(File file, SecretKey masterKey) throws IOException {
        log.info("Re-encrypting legacy encrypted file {}", file.getName());
        try {
            long payloadLength;
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                payloadLength = Encryption.verifyPayloadWithHmacStream(in, masterKey);
            }
            File tempFile = new File(file.getParentFile(), file.getName() + ".enc.tmp");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                Encryption.encryptV2ToStream(out -> {
                    try (InputStream in = new BufferedInputStream(new FileInputStream(file));
                         InputStream decrypted = Encryption.decryptStream(in, masterKey)) {
                        // re-verify the hmac over the bytes actually copied, so a file swapped
                        // between the two passes cannot be sealed unauthenticated
                        Mac mac = Encryption.createHmac(masterKey);
                        byte[] buffer = new byte[64 * 1024];
                        long remaining = payloadLength;
                        while (remaining > 0) {
                            int read = decrypted.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                            if (read < 0) throw new IOException("Unexpected end of legacy payload in " + file.getName());
                            mac.update(buffer, 0, read);
                            out.write(buffer, 0, read);
                            remaining -= read;
                        }
                        byte[] expectedHmac = new byte[32];
                        ByteStreams.readFully(decrypted, expectedHmac);
                        if (!MessageDigest.isEqual(mac.doFinal(), expectedHmac)) {
                            throw new IOException("Legacy file " + file.getName() + " changed while being re-encrypted");
                        }
                    } catch (IOException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IOException(e);
                    }
                }, masterKey, fos);
                fos.flush();
                fos.getFD().sync();
            }
            FileUtil.atomicReplace(tempFile, file);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not re-encrypt legacy encrypted file " + file.getName(), e);
        }
    }

    private static void writeMarker(File storageDir, SecretKey masterKey) throws IOException {
        try {
            byte[] blob = Encryption.encryptV2(new byte[0], masterKey);
            File tempFile = new File(storageDir, FILE_NAME + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(blob);
                fos.flush();
                fos.getFD().sync();
            }
            FileUtil.atomicReplace(tempFile, markerFile(storageDir));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not write plaintext migration marker", e);
        }
    }
}
