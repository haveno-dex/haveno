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

import haveno.common.file.FileUtil;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Random;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class EncryptionTest {

    @TempDir
    Path keyDir;

    @Test
    public void testPasswordChangeOnlyRewrapsSymmetricKey() throws Exception {
        KeyStorage storage = new KeyStorage(keyDir.toFile());
        KeyRing ring = new KeyRing(storage, null, true);
        byte[] signature = Files.readAllBytes(keyDir.resolve("sig.key"));
        byte[] encryption = Files.readAllBytes(keyDir.resolve("enc.key"));
        byte[] original = Files.readAllBytes(keyDir.resolve("sym.p12"));

        byte[] replacement = storage.preparePasswordChange(null, "new-password");
        assertArrayEquals(original, Files.readAllBytes(keyDir.resolve("sym.p12")));
        storage.commitPasswordChange(replacement);
        assertArrayEquals(signature, Files.readAllBytes(keyDir.resolve("sig.key")));
        assertArrayEquals(encryption, Files.readAllBytes(keyDir.resolve("enc.key")));
        assertEquals(ring.getSymmetricKey(), storage.loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, "new-password"));
        assertThrows(IncorrectPasswordException.class, () -> storage.loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, null));

        storage.commitPasswordChange(storage.preparePasswordChange("new-password", null));
        assertTrue(new KeyRing(storage, null, false).isUnlocked());
    }

    @Test
    public void testInvalidPasswordLeavesKeystoreUntouched() throws Exception {
        KeyStorage storage = new KeyStorage(keyDir.toFile());
        new KeyRing(storage, "old-password", true);
        byte[] original = Files.readAllBytes(keyDir.resolve("sym.p12"));
        assertThrows(IllegalArgumentException.class, () -> storage.preparePasswordChange("old-password", "password-\u00e9"));
        assertThrows(IllegalStateException.class, () -> storage.preparePasswordChange("incorrect", "new-password"));
        assertArrayEquals(original, Files.readAllBytes(keyDir.resolve("sym.p12")));
        assertTrue(new KeyRing(storage, "old-password", false).isUnlocked());
    }

    @Test
    public void testIncompleteAccountsCannotBeOverwritten() throws Exception {
        KeyStorage storage = new KeyStorage(keyDir.toFile());
        new KeyRing(storage, null, true);
        byte[] signature = Files.readAllBytes(keyDir.resolve("sig.key"));
        Files.delete(keyDir.resolve("sym.p12"));
        assertThrows(IllegalStateException.class, () -> new KeyRing(storage, null, true));
        assertArrayEquals(signature, Files.readAllBytes(keyDir.resolve("sig.key")));
    }

    @Test
    public void testRecoveryReplacesOnlySupersededBackupsOfTheSameMasterKey() throws Exception {
        KeyStorage storage = new KeyStorage(keyDir.toFile());
        KeyRing ring = new KeyRing(storage, null, true);
        Path backups = Files.createDirectories(keyDir.resolve("backup/backups_sym_p12"));
        Path old = backups.resolve("old_sym.p12");
        Files.copy(keyDir.resolve("sym.p12"), old);
        Path foreignDir = Files.createDirectory(keyDir.resolve("foreign"));
        KeyStorage foreign = new KeyStorage(foreignDir.toFile());
        new KeyRing(foreign, null, true);
        Path foreignBackup = backups.resolve("password-change_sym.p12");
        Files.copy(foreignDir.resolve("sym.p12"), foreignBackup);
        byte[] foreignBytes = Files.readAllBytes(foreignBackup);
        storage.commitPasswordChange(storage.preparePasswordChange(null, "old-password"));
        storage.commitPasswordChange(storage.preparePasswordChange("old-password", "new-password"));
        storage.finishPasswordChange(ring, "new-password", Arrays.asList("new-password", "old-password"));
        assertEquals(false, Files.exists(old));
        assertArrayEquals(foreignBytes, Files.readAllBytes(foreignBackup));
        assertTrue(Files.exists(backups.resolve("password-change_sym.p12")));
        assertThrows(IncorrectPasswordException.class, () -> storage.verifyPassword(ring.getSymmetricKey(), null));
        storage.verifyPassword(ring.getSymmetricKey(), "new-password");
    }

    @Test
    public void testUnlockRemovesAbandonedUnprotectedWrapperButPreservesForeignTemps() throws Exception {
        KeyStorage storage = new KeyStorage(keyDir.toFile());
        KeyRing ring = new KeyRing(storage, "current-password", true);
        Path abandoned = keyDir.resolve(".haveno-write-abandoned.tmp");
        Files.write(abandoned, storage.preparePasswordChange("current-password", null));
        Path foreignDir = Files.createDirectory(keyDir.resolve("foreign"));
        new KeyRing(new KeyStorage(foreignDir.toFile()), null, true);
        Path foreign = keyDir.resolve(".sym.p12.haveno-write-foreign.tmp");
        Files.copy(foreignDir.resolve("sym.p12"), foreign);
        byte[] foreignBytes = Files.readAllBytes(foreign);
        Path unreadable = keyDir.resolve(".haveno-write-truncated.tmp");
        Files.write(unreadable, new byte[] {1, 2, 3});

        KeyRing reopened = new KeyRing(storage, "current-password", false);
        assertTrue(reopened.isUnlocked());
        assertEquals(ring.getSymmetricKey(), reopened.getSymmetricKey());
        assertFalse(Files.exists(abandoned));
        assertArrayEquals(foreignBytes, Files.readAllBytes(foreign));
        assertTrue(Files.exists(unreadable));
    }

    @Test
    public void testRepeatedUnlockKeepsAccountKeyBackupsBounded() throws Exception {
        KeyStorage storage = new KeyStorage(keyDir.toFile());
        KeyRing ring = new KeyRing(storage, "current-password", true);
        for (int i = 0; i < 24; i++) {
            ring.lockKeys();
            assertTrue(ring.unlockKeys("current-password", false));
            assertTrue(FileUtil.getBackupFiles(keyDir.toFile(), "sym.p12").size() <= 20);
        }
    }

    @Test
    public void testFailedUnlockCannotPruneTheLastMatchingMasterKey() throws Exception {
        for (boolean foreign : List.of(false, true)) {
            Path keys = Files.createDirectory(keyDir.resolve(Boolean.toString(foreign)));
            KeyStorage storage = new KeyStorage(keys.toFile());
            KeyRing original = new KeyRing(storage, "known-password", true);
            byte[] invalid = {0, 1, 2};
            if (foreign) {
                Path other = Files.createDirectory(keys.resolve("other"));
                new KeyRing(new KeyStorage(other.toFile()), "known-password", true);
                invalid = Files.readAllBytes(other.resolve("sym.p12"));
            }
            Path backups = Files.createDirectories(keys.resolve("backup/backups_sym_p12"));
            Path readable = backups.resolve("0000_sym.p12");
            Files.copy(keys.resolve("sym.p12"), readable);
            for (int i = 1; i < 20; i++) Files.write(backups.resolve(String.format("%04d_sym.p12", i)), invalid);
            Files.write(keys.resolve("sym.p12"), invalid);
            assertThrows(Exception.class, () -> new KeyRing(storage, "known-password", false));
            assertTrue(Files.exists(readable));
            Files.write(keys.resolve("sym.p12"), Files.readAllBytes(readable));
            assertEquals(original.getSymmetricKey(), new KeyRing(storage, "known-password", false).getSymmetricKey());
            assertArrayEquals(invalid, Files.readAllBytes(backups.resolve("0001_sym.p12")));
        }
    }

    @Test
    public void testFailedUnlockCannotPruneTheLastPrivateKeyBackup() throws Exception {
        for (String name : List.of("sig.key", "enc.key")) {
            Path keys = Files.createDirectory(keyDir.resolve(name));
            KeyStorage storage = new KeyStorage(keys.toFile());
            KeyRing original = new KeyRing(storage, "known-password", true);
            Path backups = Files.createDirectories(keys.resolve("backup/backups_" + name.replace('.', '_')));
            Path readable = backups.resolve("0000_" + name);
            Files.copy(keys.resolve(name), readable);
            byte[] invalid = {0, 1, 2};
            for (int i = 1; i < 20; i++) Files.write(backups.resolve(String.format("%04d_", i) + name), invalid);
            Files.write(keys.resolve(name), invalid);
            assertThrows(Exception.class, () -> new KeyRing(storage, "known-password", false));
            assertTrue(Files.exists(readable));
            Files.write(keys.resolve(name), Files.readAllBytes(readable));
            KeyRing reopened = new KeyRing(storage, "known-password", false);
            assertEquals(original.getSignatureKeyPair().getPublic(), reopened.getSignatureKeyPair().getPublic());
            assertEquals(original.getEncryptionKeyPair().getPublic(), reopened.getEncryptionKeyPair().getPublic());
        }
    }

    @Test
    public void testUnlockFinishesInterruptedUnprotectedBackupCleanup() throws Exception {
        KeyStorage storage = new KeyStorage(keyDir.toFile());
        new KeyRing(storage, null, true);
        storage.commitPasswordChange(storage.preparePasswordChange(null, "new-password"));
        var oldCopies = FileUtil.getBackupFiles(keyDir.toFile(), "sym.p12");
        assertFalse(oldCopies.isEmpty());
        assertTrue(new KeyRing(storage, "new-password", false).isUnlocked());
        for (var copy : oldCopies) assertFalse(copy.exists());
    }

    // Sizes around AES block (16) and stream chunk (64 KiB) boundaries, plus an empty payload.
    private static final int[] SIZES = {0, 1, 15, 16, 17, 1000, 65_535, 65_536, 65_537, 100_000, 5_000_000};

    @Test
    public void testKeyStorageRestrictsExistingKeysBeforeUnlock(@TempDir Path dir) throws Exception {
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView(PosixFileAttributeView.class));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        for (KeyStorage.KeyEntry entry : KeyStorage.KeyEntry.values()) {
            Path file = Files.writeString(dir.resolve(entry.getFileName()), "existing key");
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        }

        new KeyStorage(dir.toFile());

        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(dir));
        for (KeyStorage.KeyEntry entry : KeyStorage.KeyEntry.values()) {
            Path file = dir.resolve(entry.getFileName());
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
            assertEquals("existing key", Files.readString(file));
        }
    }

    @Test
    public void testKeyStorageRoundTripAndPasswordChange(@TempDir Path dir) throws Exception {
        KeyStorage storage = new KeyStorage(dir.toFile());
        KeyRing keys = new KeyRing(storage, null, true);
        assertTrue(keys.isUnlocked());

        storage.saveKeyRing(keys, null, "password");
        assertThrows(IncorrectPasswordException.class,
                () -> storage.loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, "wrong"));
        KeyRing loaded = new KeyRing(storage, "password", false);
        assertTrue(loaded.isUnlocked());
        assertArrayEquals(keys.getSymmetricKey().getEncoded(), loaded.getSymmetricKey().getEncoded());
        assertArrayEquals(keys.getSignatureKeyPair().getPrivate().getEncoded(), loaded.getSignatureKeyPair().getPrivate().getEncoded());
        assertArrayEquals(keys.getEncryptionKeyPair().getPrivate().getEncoded(), loaded.getEncryptionKeyPair().getPrivate().getEncoded());

        FileUtil.deleteDirectory(dir.toFile());
        storage.saveKeyRing(keys, null, null);
        assertTrue(new KeyRing(storage, null, false).isUnlocked());
        if (Files.getFileStore(dir).supportsFileAttributeView(PosixFileAttributeView.class)) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(dir));
            for (KeyStorage.KeyEntry entry : KeyStorage.KeyEntry.values()) {
                assertEquals(PosixFilePermissions.fromString("rw-------"),
                        Files.getPosixFilePermissions(dir.resolve(entry.getFileName())));
            }
        }
    }

    @Test
    public void testStreamWriteMatchesArrayAndRoundTrips() throws CryptoException {
        SecretKey key = Encryption.generateSecretKey(256);
        Random random = new Random(1234);
        for (int size : SIZES) {
            byte[] payload = new byte[size];
            random.nextBytes(payload);

            byte[] viaArray = Encryption.encryptPayloadWithHmac(payload, key);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Encryption.encryptPayloadWithHmacToStream(payload, key, bos);
            byte[] viaStream = bos.toByteArray();

            // The streaming variant must be byte-identical so existing persisted files stay readable.
            assertArrayEquals(viaArray, viaStream, "ciphertext differs for payload size " + size);

            // And it must decrypt back to the original payload with the existing array decrypt path.
            byte[] decrypted = Encryption.decryptPayloadWithHmac(viaStream, key);
            assertArrayEquals(payload, decrypted, "round-trip failed for payload size " + size);
        }
    }

    @Test
    public void testWriterVariantMatchesArray() throws CryptoException {
        // The PayloadWriter variant (used with protobuf Message::writeTo to avoid materializing the
        // payload) must be byte-identical to the array variant, including when the payload arrives
        // in many small writes as protobuf's 4 KB CodedOutputStream buffer produces.
        SecretKey key = Encryption.generateSecretKey(256);
        Random random = new Random(4321);
        byte[] payload = new byte[300_000];
        random.nextBytes(payload);

        byte[] viaArray = Encryption.encryptPayloadWithHmac(payload, key);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Encryption.encryptPayloadWithHmacToStream(out -> {
            for (int off = 0; off < payload.length; off += 4096) {
                out.write(payload, off, Math.min(4096, payload.length - off));
            }
        }, key, bos);

        assertArrayEquals(viaArray, bos.toByteArray(), "writer-based ciphertext differs from array variant");
    }

    @Test
    public void testStreamDoesNotCloseOutputStream() throws CryptoException {
        SecretKey key = Encryption.generateSecretKey(256);
        TrackingOutputStream out = new TrackingOutputStream();
        Encryption.encryptPayloadWithHmacToStream(new byte[1000], key, out);
        assertEquals(false, out.closed, "stream must not be closed by the helper");
    }

    private static class TrackingOutputStream extends ByteArrayOutputStream {
        boolean closed = false;

        @Override
        public void close() {
            closed = true;
        }
    }
}
