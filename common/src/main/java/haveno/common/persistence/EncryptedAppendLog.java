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

package haveno.common.persistence;

import haveno.common.crypto.CryptoException;
import haveno.common.crypto.Encryption;
import haveno.common.file.FileUtil;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;

/**
 * An append-only, encrypted, crash-safe record log.
 *
 * <p>Each record is framed on disk as {@code [4-byte big-endian length N][N bytes ciphertext]},
 * where {@code ciphertext = Encryption.encryptPayloadWithHmac(record)} (AES + trailing HMAC-SHA256).
 * The length prefix lives outside the ciphertext so frames can be located and a torn tail truncated
 * without decrypting. Each {@link #append(byte[])} fsyncs, so a fully-written frame is durable and a
 * process crash can only leave a partial trailing frame.
 *
 * <p>Replay ({@link #readAllValidRecords()}) distinguishes the two failure modes:
 * <ul>
 *   <li><b>Torn tail</b> (incomplete final frame from a crash mid-append): the log is truncated back
 *       to the last good frame boundary and the valid prefix is returned. This is the expected,
 *       silent self-repair.</li>
 *   <li><b>Corruption</b> (a fully-present frame whose HMAC fails — bit rot or tampering): the whole
 *       log is moved to {@code backup_of_corrupted_data/} (parity with {@code PersistenceManager}),
 *       a clean log is rebuilt from the valid prefix, and the prefix is returned so the maximum
 *       recoverable history survives. Logged loudly.</li>
 * </ul>
 *
 * <p>This class is generic (it deals in {@code byte[]} records and a symmetric key); domain encoding
 * is the caller's responsibility. Appends are serialized on the instance monitor so concurrent calls
 * cannot interleave frames; callers that need the log to match an in-memory order should append under
 * their own ordering lock.
 */
@Slf4j
public class EncryptedAppendLog {

    private static final String CORRUPTED_BACKUP_FOLDER = "backup_of_corrupted_data";

    private final File dir;
    private final String fileName;
    private final SecretKey secretKey;
    private final int numMaxBackupFiles;
    private final Object lock = new Object();

    public EncryptedAppendLog(File dir, String fileName, SecretKey secretKey, int numMaxBackupFiles) {
        this.dir = dir;
        this.fileName = fileName;
        this.secretKey = secretKey;
        this.numMaxBackupFiles = numMaxBackupFiles;
    }

    private File logFile() {
        return new File(dir, fileName);
    }

    private File tempFile() {
        return new File(dir, fileName + ".tmp");
    }

    public boolean exists() {
        return logFile().exists();
    }

    /**
     * Appends one record: encrypt + frame + write + fsync. Durable on return.
     */
    public void append(byte[] record) throws CryptoException {
        byte[] ciphertext = Encryption.encryptPayloadWithHmac(record, secretKey);
        synchronized (lock) {
            if (!dir.exists() && !dir.mkdir()) log.warn("make dir failed {}", dir);
            try (FileOutputStream fos = new FileOutputStream(logFile(), true);
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos))) {
                out.writeInt(ciphertext.length);
                out.write(ciphertext);
                out.flush();
                fos.getFD().sync();
            } catch (IOException e) {
                throw new RuntimeException("Could not append to " + fileName, e);
            }
        }
    }

    /**
     * Replays the log, returning the valid records in append order. Self-repairs a torn tail and
     * backs up + rebuilds on mid-log corruption (see class javadoc).
     */
    public List<byte[]> readAllValidRecords() {
        synchronized (lock) {
            File logFile = logFile();
            List<byte[]> records = new ArrayList<>();
            if (!logFile.exists()) return records;

            long fileLength = logFile.length();
            long goodLength = 0;
            boolean tornTail = false;
            boolean corrupt = false;

            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(logFile)))) {
                while (goodLength < fileLength) {
                    long remaining = fileLength - goodLength;
                    if (remaining < 4) {
                        tornTail = true; // partial length prefix
                        break;
                    }
                    int len = in.readInt();
                    if (len <= 0 || len > remaining - 4) {
                        tornTail = true; // garbage/torn length: ciphertext can't fit in remaining bytes
                        break;
                    }
                    byte[] ciphertext = new byte[len];
                    in.readFully(ciphertext);
                    byte[] record;
                    try {
                        record = Encryption.decryptPayloadWithHmac(ciphertext, secretKey);
                    } catch (CryptoException e) {
                        // A fully-present frame that fails its HMAC is not a torn write -> real corruption.
                        corrupt = true;
                        break;
                    }
                    records.add(record);
                    goodLength += 4L + len;
                }
            } catch (EOFException e) {
                tornTail = true; // hit EOF mid-frame
            } catch (IOException e) {
                throw new RuntimeException("Could not read " + fileName, e);
            }

            try {
                if (corrupt) {
                    log.error("Corrupt record in {} at offset {} (file length {}). Backing up to {} and " +
                                    "rebuilding from the {} valid leading record(s).",
                            fileName, goodLength, fileLength, CORRUPTED_BACKUP_FOLDER, records.size());
                    FileUtil.removeAndBackupFile(dir, logFile, fileName, CORRUPTED_BACKUP_FOLDER);
                    rewrite(records);
                } else if (tornTail && goodLength < fileLength) {
                    log.warn("Truncating torn tail of {}: keeping {} bytes ({} valid record(s)), dropping {} bytes.",
                            fileName, goodLength, records.size(), fileLength - goodLength);
                    truncateTo(logFile, goodLength);
                }
            } catch (IOException e) {
                throw new RuntimeException("Could not repair " + fileName, e);
            }
            return records;
        }
    }

    /**
     * Atomically replaces the log with a fresh one containing exactly {@code records} (compaction or
     * migration). Writes to a temp file, fsyncs, then renames into place; a failure leaves the
     * existing log untouched.
     */
    public void rewrite(List<byte[]> records) {
        synchronized (lock) {
            File logFile = logFile();
            File tempFile = tempFile();
            try {
                if (!dir.exists() && !dir.mkdir()) log.warn("make dir failed {}", dir);
                FileUtil.deleteFileIfExists(tempFile); // discard any stale temp from an interrupted rewrite
                try (FileOutputStream fos = new FileOutputStream(tempFile);
                     DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos))) {
                    for (byte[] record : records) {
                        byte[] ciphertext = Encryption.encryptPayloadWithHmac(record, secretKey);
                        out.writeInt(ciphertext.length);
                        out.write(ciphertext);
                    }
                    out.flush();
                    fos.getFD().sync();
                }
                // Keep a rolling backup of the pre-rewrite log as a safety net against a faulty compaction.
                if (logFile.exists()) FileUtil.rollingBackup(dir, fileName, numMaxBackupFiles);
                FileUtil.renameFile(tempFile, logFile);
            } catch (IOException | CryptoException e) {
                throw new RuntimeException("Could not rewrite " + fileName, e);
            } finally {
                if (tempFile.exists()) {
                    try {
                        FileUtil.deleteFileIfExists(tempFile);
                    } catch (IOException ignore) {
                        log.warn("Could not delete temp file {}", tempFile);
                    }
                }
            }
        }
    }

    private static void truncateTo(File file, long length) throws IOException {
        try (FileChannel ch = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
            ch.truncate(length);
            ch.force(true);
        }
    }
}
