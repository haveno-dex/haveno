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
 * process crash can only leave a partial trailing frame. A <i>failed</i> append (e.g. disk full) can
 * also leave a partial trailing frame while the process keeps running; the log tracks its known-good
 * length across successful writes and truncates such a tear away before the next append, so a torn
 * frame can never end up buried mid-log by later appends.
 *
 * <p>Replay ({@link #readAllValidRecords()}) distinguishes the failure modes:
 * <ul>
 *   <li><b>Torn tail</b> (incomplete final frame from a crash mid-append): the log is truncated back
 *       to the last good frame boundary and the valid prefix is returned. Because a bit flip in an
 *       (unauthenticated) length prefix mid-log is indistinguishable from a torn tail, the dropped
 *       bytes are first preserved by copying the whole file to {@code backup_of_corrupted_data/}.</li>
 *   <li><b>Corruption</b> (a non-positive length prefix, or a fully-present frame whose HMAC fails —
 *       bit rot or tampering): the whole log is moved to {@code backup_of_corrupted_data/}, a clean
 *       log is rebuilt from the valid prefix, and the prefix is returned so the maximum recoverable
 *       history survives. Logged loudly.</li>
 * </ul>
 * Backups use a timestamped file name so successive incidents never overwrite each other.
 *
 * <p>This class is generic (it deals in {@code byte[]} records and a symmetric key); domain encoding
 * is the caller's responsibility. Appends are serialized on the instance monitor so concurrent calls
 * cannot interleave frames; callers that need the log to match an in-memory order should append under
 * their own ordering lock.
 */
@Slf4j
public class EncryptedAppendLog {

    private final File dir;
    private final String fileName;
    private final SecretKey secretKey;
    private final int numMaxBackupFiles;
    private final Object lock = new Object();
    // File length up to which all frames are known intact. -1 until the first full replay (or
    // rewrite) establishes it; maintained across appends so a failed append's partial frame can be
    // truncated away before the next write instead of being buried by it.
    private long knownGoodLength = -1;

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
        appendAll(List.of(record));
    }

    /**
     * Appends multiple records as consecutive frames with a single fsync. Durable on return; on
     * failure the file is truncated back to its pre-call length, so the batch is all-or-nothing on
     * disk (a crash between write and truncate leaves a tear that the next append or replay repairs).
     */
    public void appendAll(List<byte[]> records) throws CryptoException {
        if (records.isEmpty()) return;
        List<byte[]> ciphertexts = new ArrayList<>(records.size());
        for (byte[] record : records) ciphertexts.add(Encryption.encryptPayloadWithHmac(record, secretKey));
        synchronized (lock) {
            if (!dir.exists() && !dir.mkdir()) log.warn("make dir failed {}", dir);
            repairTailBeforeAppend();
            long written = 0;
            try (FileOutputStream fos = new FileOutputStream(logFile(), true);
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos))) {
                for (byte[] ciphertext : ciphertexts) {
                    writeFrame(out, ciphertext);
                    written += 4L + ciphertext.length;
                }
                out.flush();
                fos.getFD().sync();
            } catch (IOException e) {
                // The frame may have partially reached the disk. Truncate the tear away now
                // (best effort; repairTailBeforeAppend covers us if this fails too).
                try {
                    truncateTo(logFile(), knownGoodLength);
                } catch (IOException e2) {
                    log.warn("Could not truncate {} back to {} after failed append", fileName, knownGoodLength, e2);
                }
                throw new RuntimeException("Could not append to " + fileName, e);
            }
            knownGoodLength += written;
        }
    }

    // Called under the instance lock before every append. Ensures the file ends exactly at the
    // known-good frame boundary so a partial frame left by an earlier failed append (or anything
    // else that changed the file) is removed before new frames are written after it.
    private void repairTailBeforeAppend() {
        if (knownGoodLength < 0) {
            // First write of this session without a prior replay: replay once to validate the tail
            // (and self-repair it), which also initializes knownGoodLength.
            readAllValidRecords();
            return;
        }
        File logFile = logFile();
        long fileLength = logFile.exists() ? logFile.length() : 0;
        if (fileLength == knownGoodLength) return;
        if (fileLength < knownGoodLength) {
            // The file shrank behind our back; fall back to a full replay to re-establish the boundary.
            log.warn("{} is shorter ({}) than its known-good length ({}); re-validating.", fileName, fileLength, knownGoodLength);
            knownGoodLength = -1;
            readAllValidRecords();
            return;
        }
        log.warn("Truncating {} bytes of torn/unknown tail from {} before appending (known-good length {}).",
                fileLength - knownGoodLength, fileName, knownGoodLength);
        try {
            truncateTo(logFile, knownGoodLength);
        } catch (IOException e) {
            throw new RuntimeException("Could not repair " + fileName + " before append", e);
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
            if (!logFile.exists()) {
                knownGoodLength = 0;
                return records;
            }

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
                    if (len <= 0) {
                        // append() only ever writes positive lengths, so a complete non-positive
                        // prefix cannot be a torn write - it is corruption of the prefix itself.
                        corrupt = true;
                        break;
                    }
                    if (len > remaining - 4) {
                        tornTail = true; // torn length or torn frame: ciphertext can't fit in remaining bytes
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
                    File backupFile = corruptedBackupFile();
                    log.error("Corrupt record in {} at offset {} (file length {}). Backing up to {} and " +
                                    "rebuilding from the {} valid leading record(s).",
                            fileName, goodLength, fileLength, backupFile, records.size());
                    FileUtil.renameFile(logFile, backupFile);
                    rewrite(records);
                } else if (tornTail && goodLength < fileLength) {
                    // A mid-log length-prefix corruption is indistinguishable from a genuine torn
                    // tail, so preserve the dropped bytes before truncating - they may hold valid
                    // frames that a smarter recovery (or a fixed build) can still extract.
                    File backupFile = corruptedBackupFile();
                    log.warn("Truncating torn tail of {}: keeping {} bytes ({} valid record(s)), dropping {} bytes. " +
                                    "Pre-truncation copy preserved at {}.",
                            fileName, goodLength, records.size(), fileLength - goodLength, backupFile);
                    FileUtil.copyFile(logFile, backupFile);
                    truncateTo(logFile, goodLength);
                    knownGoodLength = goodLength;
                } else {
                    knownGoodLength = fileLength;
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
                long written = 0;
                try (FileOutputStream fos = new FileOutputStream(tempFile);
                     DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos))) {
                    for (byte[] record : records) {
                        byte[] ciphertext = Encryption.encryptPayloadWithHmac(record, secretKey);
                        writeFrame(out, ciphertext);
                        written += 4L + ciphertext.length;
                    }
                    out.flush();
                    fos.getFD().sync();
                }
                // Keep a rolling backup of the pre-rewrite log as a safety net against a faulty compaction.
                if (logFile.exists()) FileUtil.rollingBackup(dir, fileName, numMaxBackupFiles);
                FileUtil.renameFile(tempFile, logFile);
                knownGoodLength = written;
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

    // The single place that knows the on-disk frame encoding: [4-byte big-endian length][ciphertext].
    private static void writeFrame(DataOutputStream out, byte[] ciphertext) throws IOException {
        out.writeInt(ciphertext.length);
        out.write(ciphertext);
    }

    // Timestamped so successive incidents never overwrite an earlier backup.
    private File corruptedBackupFile() {
        File backupDir = new File(dir, FileUtil.CORRUPTED_BACKUP_FOLDER);
        if (!backupDir.exists() && !backupDir.mkdir()) log.warn("make dir failed {}", backupDir);
        return new File(backupDir, System.currentTimeMillis() + "_" + fileName);
    }

    private static void truncateTo(File file, long length) throws IOException {
        try (FileChannel ch = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
            ch.truncate(length);
            ch.force(true);
        }
    }
}
