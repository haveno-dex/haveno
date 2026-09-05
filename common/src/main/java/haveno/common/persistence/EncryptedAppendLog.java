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

import haveno.common.crypto.AuthenticatedEncryption;
import haveno.common.crypto.CryptoException;
import haveno.common.crypto.Encryption;
import haveno.common.file.FileUtil;
import haveno.common.file.AtomicFileWriter;
import java.nio.file.Files;
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
 * <p>Each record is framed as {@code [4-byte big-endian length][ciphertext]}, with
 * a context-bound {@link AuthenticatedEncryption} envelope for each new record. The length prefix lives outside
 * the ciphertext so frames can be located and a torn tail truncated without decrypting. Appends
 * fsync, so a crash or failed write (e.g. disk full) can only leave a partial trailing frame; the
 * log tracks its known-good length and truncates such a tear away before the next append.
 *
 * <p>Replay ({@link #readAllValidRecords()}) repairs a torn tail only after a verified durable
 * backup. Complete unauthenticatable frames stop replay and leave the entire original file in place.
 * Legacy records remain readable and migrate on successful replay.
 *
 * <p>Records are opaque {@code byte[]}; domain encoding is the caller's responsibility. Appends are
 * serialized on the instance monitor; callers that need the log to match an in-memory order must
 * append under their own ordering lock.
 */
@Slf4j
public class EncryptedAppendLog {

    private final File dir;
    private final String fileName;
    private final SecretKey secretKey;
    private final int numMaxBackupFiles;
    private final Object lock = new Object();
    // File length up to which all frames are known intact (-1 until the first replay establishes
    // it), so a failed append's partial frame is truncated before the next write can bury it.
    private long knownGoodLength = -1;
    private long knownGoodRecords;
    private static final int MAX_RECORD_BYTES = 256 * 1024 * 1024;

    public EncryptedAppendLog(File dir, String fileName, SecretKey secretKey, int numMaxBackupFiles) {
        this.dir = dir;
        this.fileName = fileName;
        this.secretKey = secretKey;
        this.numMaxBackupFiles = numMaxBackupFiles;
    }

    private String recordContext(long index) {
        return "append-log/" + fileName + "/" + index;
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
     * disk after a reported I/O failure. A process crash may retain a prefix of complete records
     * from the batch, plus a torn final frame; callers must make record replay idempotent.
     */
    public void appendAll(List<byte[]> records) throws CryptoException {
        if (records.isEmpty()) return;
        synchronized (lock) {
            if (!dir.exists() && !dir.mkdir()) log.warn("make dir failed {}", dir);
            repairTailBeforeAppend();
            List<byte[]> ciphertexts = new ArrayList<>(records.size());
            for (byte[] record : records) {
                if (record.length > MAX_RECORD_BYTES - 88) throw new IllegalArgumentException("Append-log record too large");
                ciphertexts.add(AuthenticatedEncryption.encrypt(record, secretKey, recordContext(knownGoodRecords + ciphertexts.size())));
            }
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
            knownGoodRecords += records.size();
        }
    }

    // Called under the instance lock before every append: truncates the file back to the known-good
    // boundary so a partial frame from an earlier failed append is never buried by new writes.
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
     * Replays the log in append order. Repairs a torn tail, but stops on full-frame corruption.
     */
    public List<byte[]> readAllValidRecords() {
        synchronized (lock) {
            File logFile = logFile();
            List<byte[]> records = new ArrayList<>();
            if (!logFile.exists()) {
                // Salvage an interrupted rewrite: the temp is fully fsynced before the swap begins,
                // so with no log present it is the newest copy (a partial temp can only coexist
                // with the intact log, which takes precedence).
                File tempFile = tempFile();
                if (tempFile.exists()) {
                    log.warn("{} is missing but its rewrite temp exists; recovering the temp.", fileName);
                    try {
                        FileUtil.renameFile(tempFile, logFile);
                    } catch (IOException e) {
                        throw new RuntimeException("Could not recover " + fileName + " from its rewrite temp", e);
                    }
                } else {
                    knownGoodLength = 0;
                    knownGoodRecords = 0;
                    return records;
                }
            }

            long fileLength = logFile.length();
            long goodLength = 0;
            boolean tornTail = false;
            boolean corrupt = false;
            boolean legacyRecords = false;

            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(logFile)))) {
                while (goodLength < fileLength) {
                    long remaining = fileLength - goodLength;
                    if (remaining < 4) {
                        tornTail = true; // partial length prefix
                        break;
                    }
                    int len = in.readInt();
                    if (len <= 0 || len > MAX_RECORD_BYTES) {
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
                        record = AuthenticatedEncryption.hasEnvelope(ciphertext)
                                ? AuthenticatedEncryption.decrypt(ciphertext, secretKey, recordContext(records.size()))
                                : Encryption.decryptPayloadWithHmac(ciphertext, secretKey);
                        legacyRecords |= !AuthenticatedEncryption.hasEnvelope(ciphertext);
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

            if (corrupt) {
                knownGoodLength = -1;
                throw new IllegalStateException("Cannot authenticate " + fileName + " at offset " + goodLength + "; original log preserved");
            }
            knownGoodRecords = records.size();
            try {
                if (tornTail && goodLength < fileLength) {
                    // Preserve the dropped bytes before truncating back to the valid prefix - they
                    // may still be recoverable. Copy-then-truncate means a failure at any point
                    // leaves the log or its full backup on disk.
                    File backupFile = corruptedBackupFile();
                    log.warn("Preserving torn tail of {} at {} before truncating to {} bytes", fileName, backupFile, goodLength);
                    AtomicFileWriter.write(backupFile.toPath(), out -> Files.copy(logFile.toPath(), out), candidate -> {
                        if (Files.mismatch(logFile.toPath(), candidate) != -1) throw new IOException("Torn-tail backup differs from original log");
                    });
                    truncateTo(logFile, goodLength);
                    knownGoodLength = goodLength;
                } else {
                    knownGoodLength = fileLength;
                }
            } catch (IOException e) {
                throw new RuntimeException("Could not repair " + fileName, e);
            }
            if (legacyRecords && !tornTail) {
                try {
                    rewrite(records);
                } catch (RuntimeException e) {
                    log.warn("Could not upgrade {}; original authenticated history is preserved", fileName, e);
                }
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
                    long index = 0;
                    for (byte[] record : records) {
                        if (record.length > MAX_RECORD_BYTES - 88) throw new IllegalArgumentException("Append-log record too large");
                        byte[] ciphertext = AuthenticatedEncryption.encrypt(record, secretKey, recordContext(index++));
                        writeFrame(out, ciphertext);
                        written += 4L + ciphertext.length;
                    }
                    out.flush();
                    fos.getFD().sync();
                }
                verifyRewrite(tempFile, records);
                // Keep a rolling backup of the pre-rewrite log as a safety net against a faulty compaction.
                if (logFile.exists()) FileUtil.rollingBackup(dir, fileName, numMaxBackupFiles);
                FileUtil.atomicReplace(tempFile, logFile);
                knownGoodLength = written;
                knownGoodRecords = records.size();
                AtomicFileWriter.syncDirectory(dir.toPath());
            } catch (IOException | CryptoException e) {
                throw new RuntimeException("Could not rewrite " + fileName, e);
            } finally {
                // Never delete the temp while the log itself is missing (a failed non-atomic swap):
                // the fsynced temp is then the only remaining full copy and replay recovers it.
                if (tempFile.exists() && logFile.exists()) {
                    try {
                        FileUtil.deleteFileIfExists(tempFile);
                    } catch (IOException ignore) {
                        log.warn("Could not delete temp file {}", tempFile);
                    }
                }
            }
        }
    }

    private void verifyRewrite(File file, List<byte[]> records) throws IOException, CryptoException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            for (int index = 0; index < records.size(); index++) {
                int length = in.readInt();
                if (length <= 0 || length > MAX_RECORD_BYTES) throw new IOException("Invalid rewritten frame length");
                byte[] encrypted = in.readNBytes(length);
                if (encrypted.length != length || !java.security.MessageDigest.isEqual(records.get(index),
                        AuthenticatedEncryption.decrypt(encrypted, secretKey, recordContext(index)))) {
                    throw new IOException("Rewritten record differs from original");
                }
            }
            if (in.read() != -1) throw new IOException("Trailing rewritten log data");
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
