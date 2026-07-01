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

import haveno.common.crypto.Encryption;
import haveno.common.file.FileUtil;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EncryptedAppendLogTest {

    private File dir;
    private SecretKey key;

    @BeforeEach
    public void setup() throws Exception {
        dir = File.createTempFile("append_log_test", "");
        assertTrue(dir.delete());
        assertTrue(dir.mkdir());
        key = Encryption.generateSecretKey(256);
    }

    @AfterEach
    public void tearDown() throws IOException {
        FileUtil.deleteDirectory(dir);
    }

    private EncryptedAppendLog newLog() {
        return new EncryptedAppendLog(dir, "Test.log", key, 3);
    }

    private static byte[] rec(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertRecords(List<String> expected, List<byte[]> actual) {
        assertEquals(expected.size(), actual.size(), "record count");
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(rec(expected.get(i)), actual.get(i), "record " + i);
        }
    }

    @Test
    public void testEmptyLogReturnsEmpty() {
        assertFalse(newLog().exists());
        assertTrue(newLog().readAllValidRecords().isEmpty());
    }

    @Test
    public void testAppendThenReadInOrder() throws Exception {
        EncryptedAppendLog log = newLog();
        List<String> expected = List.of("alpha", "bravo", "charlie", "delta");
        for (String s : expected) log.append(rec(s));
        // Read with a fresh instance to prove durability across "restarts".
        assertRecords(expected, newLog().readAllValidRecords());
    }

    @Test
    public void testLargeRecordsAcrossCryptoChunkBoundary() throws Exception {
        EncryptedAppendLog log = newLog();
        Random random = new Random(42);
        int[] sizes = {0, 1, 16, 65_535, 65_536, 65_537, 200_000};
        List<byte[]> expected = new ArrayList<>();
        for (int size : sizes) {
            byte[] payload = new byte[size];
            random.nextBytes(payload);
            expected.add(payload);
            log.append(payload);
        }
        List<byte[]> read = newLog().readAllValidRecords();
        assertEquals(expected.size(), read.size());
        for (int i = 0; i < expected.size(); i++) assertArrayEquals(expected.get(i), read.get(i), "size " + sizes[i]);
    }

    @Test
    public void testTornTailIsTruncatedAndPrefixKept() throws Exception {
        EncryptedAppendLog log = newLog();
        List<String> full = List.of("one", "two", "three");
        for (String s : full) log.append(rec(s));

        File logFile = new File(dir, "Test.log");
        long goodLength = logFile.length();

        // Simulate a crash mid-append: append a 4th record, then chop its frame partway through.
        log.append(rec("torn-tail-record-that-was-being-written"));
        try (RandomAccessFile raf = new RandomAccessFile(logFile, "rw")) {
            raf.setLength(goodLength + 5); // keep the 3 good frames + a few bytes of the 4th
        }

        List<byte[]> read = newLog().readAllValidRecords();
        assertRecords(full, read);
        // The torn tail must have been repaired on disk.
        assertEquals(goodLength, logFile.length(), "log should be truncated back to last good frame");
        // And a re-read returns the same clean prefix.
        assertRecords(full, newLog().readAllValidRecords());
    }

    @Test
    public void testMidLogCorruptionBacksUpAndRebuildsFromPrefix() throws Exception {
        EncryptedAppendLog log = newLog();
        List<String> all = List.of("keep-1", "keep-2", "CORRUPT-ME", "after-1", "after-2");
        for (String s : all) log.append(rec(s));

        File logFile = new File(dir, "Test.log");
        byte[] bytes = Files.readAllBytes(logFile.toPath());

        // Find the ciphertext of the 3rd frame and flip a byte inside it (a full frame, not the tail).
        int offset = 0;
        int targetFrame = 2; // zero-based
        int corruptBytePos = -1;
        for (int frame = 0; frame <= targetFrame; frame++) {
            int len = ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                    | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
            if (frame == targetFrame) corruptBytePos = offset + 4 + len / 2;
            offset += 4 + len;
        }
        bytes[corruptBytePos] ^= 0x5a;
        Files.write(logFile.toPath(), bytes);

        List<byte[]> read = newLog().readAllValidRecords();
        // Only the valid leading prefix survives.
        assertRecords(List.of("keep-1", "keep-2"), read);
        // Original corrupt log preserved for recovery.
        assertTrue(new File(dir, "backup_of_corrupted_data/Test.log").exists(), "corrupt log must be backed up");
        // Log rebuilt clean from the prefix; re-read is stable and equal.
        assertRecords(List.of("keep-1", "keep-2"), newLog().readAllValidRecords());
    }

    @Test
    public void testRewriteReplacesContentAtomically() throws Exception {
        EncryptedAppendLog log = newLog();
        for (String s : List.of("old-1", "old-2", "old-3", "old-4")) log.append(rec(s));

        List<byte[]> compacted = new ArrayList<>();
        compacted.add(rec("new-a"));
        compacted.add(rec("new-b"));
        log.rewrite(compacted);

        assertRecords(List.of("new-a", "new-b"), newLog().readAllValidRecords());
        // Appends continue to work after a rewrite.
        log.append(rec("new-c"));
        assertRecords(List.of("new-a", "new-b", "new-c"), newLog().readAllValidRecords());
    }

    @Test
    public void testWrongKeyTreatsRecordsAsCorrupt() throws Exception {
        newLog().append(rec("secret"));
        // A different key cannot decrypt -> first full frame fails HMAC -> treated as corruption.
        EncryptedAppendLog wrongKeyLog = new EncryptedAppendLog(dir, "Test.log", Encryption.generateSecretKey(256), 3);
        List<byte[]> read = wrongKeyLog.readAllValidRecords();
        assertTrue(read.isEmpty(), "no records should be recovered with the wrong key");
        assertTrue(new File(dir, "backup_of_corrupted_data/Test.log").exists());
    }
}
