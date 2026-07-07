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

import haveno.common.Payload;
import haveno.common.crypto.Encryption;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.crypto.Sig;
import haveno.common.file.FileUtil;
import haveno.common.util.Utilities;
import haveno.common.proto.persistable.NavigationPath;
import haveno.common.proto.persistable.PersistableEnvelope;
import haveno.common.proto.persistable.PersistablePayload;
import haveno.common.proto.persistable.PersistenceProtoResolver;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PersistenceManagerTest {

    private File dir;
    private KeyRing keyRing;
    private PersistenceManager<NavigationPath> persistenceManager;

    private static final PersistenceProtoResolver RESOLVER = new PersistenceProtoResolver() {
        @Override
        public PersistableEnvelope fromProto(protobuf.PersistableEnvelope proto) {
            if (proto.getMessageCase() == protobuf.PersistableEnvelope.MessageCase.NAVIGATION_PATH) {
                return NavigationPath.fromProto(proto.getNavigationPath());
            }
            throw new IllegalArgumentException("Unexpected message case " + proto.getMessageCase());
        }

        @Override
        public Payload fromProto(protobuf.PaymentAccountPayload proto) {
            return null;
        }

        @Override
        public PersistablePayload fromProto(protobuf.PersistableNetworkPayload proto) {
            return null;
        }
    };

    @BeforeEach
    public void setup() throws Exception {
        dir = File.createTempFile("persistence_test", "");
        assertTrue(dir.delete());
        assertTrue(dir.mkdir());
        keyRing = new KeyRing(new KeyStorage(dir), null, true);
        persistenceManager = new PersistenceManager<>(dir, RESOLVER, null, keyRing);
        PersistenceManager.allServicesInitialized.set(true);
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (persistenceManager != null) persistenceManager.shutdown();
        // Restore the shared static flag so we don't leak state into other tests in the same JVM.
        PersistenceManager.allServicesInitialized.set(false);
        FileUtil.deleteDirectory(dir);
    }

    // A NavigationPath whose serialized form spans several 64 KiB streaming-decrypt chunks.
    private NavigationPath largeNavigationPath() {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < 5000; i++) entries.add("navigation/path/segment/number/" + i + "/with/some/padding");
        return new NavigationPath(entries);
    }

    private void persistAndWait(NavigationPath data, String fileName) throws InterruptedException {
        persistenceManager.initialize(data, fileName, PersistenceManager.Source.PRIVATE);
        CountDownLatch latch = new CountDownLatch(1);
        persistenceManager.persistNow(latch::countDown);
        assertTrue(latch.await(15, TimeUnit.SECONDS), "write did not complete");
    }

    // Writes a legacy (AES-ECB + HMAC) encrypted store file as a migration fixture.
    private void writeLegacyEncryptedFile(byte[] payload, SecretKey key, File file) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            Encryption.encryptPayloadWithHmacToStream(payload, key, fos);
        }
    }

    @Test
    public void testEncryptedWriteThenStreamingReadRoundTrip() throws Exception {
        NavigationPath data = largeNavigationPath();
        persistAndWait(data, "EncryptedStore");

        // Sanity: the on-disk file is actually present and in the v2 format.
        assertTrue(new File(dir, "EncryptedStore").length() > 0);
        byte[] head = new byte[4];
        try (var fis = new java.io.FileInputStream(new File(dir, "EncryptedStore"))) {
            assertEquals(4, fis.read(head));
        }
        assertArrayEquals(Encryption.V2_MAGIC, head);

        NavigationPath read = persistenceManager.getPersisted("EncryptedStore");
        assertEquals(data, read);
    }

    @Test
    public void testLegacyEncryptedFileIsReadAndUpgradedOnPersist() throws Exception {
        NavigationPath data = largeNavigationPath();
        byte[] payload = ((protobuf.PersistableEnvelope) data.toProtoMessage()).toByteArray();
        writeLegacyEncryptedFile(payload, keyRing.getSymmetricKey(), new File(dir, "LegacyEncryptedStore"));

        persistenceManager.initialize(data, "LegacyEncryptedStore", PersistenceManager.Source.PRIVATE);
        NavigationPath read = persistenceManager.getPersisted("LegacyEncryptedStore");
        assertEquals(data, read);

        // The next persist rewrites the store in the v2 format and it stays readable.
        CountDownLatch latch = new CountDownLatch(1);
        persistenceManager.persistNow(latch::countDown);
        assertTrue(latch.await(15, TimeUnit.SECONDS), "write did not complete");
        byte[] head = new byte[4];
        try (var fis = new java.io.FileInputStream(new File(dir, "LegacyEncryptedStore"))) {
            assertEquals(4, fis.read(head));
        }
        assertArrayEquals(Encryption.V2_MAGIC, head);
        assertEquals(data, persistenceManager.getPersisted("LegacyEncryptedStore"));
        assertBackupsAreEncrypted(dir, "LegacyEncryptedStore");
    }

    // The migration write must not copy the pre-v2 store into the rolling backups; the first
    // backup is taken from the encrypted replacement.
    private static void assertBackupsAreEncrypted(File dir, String fileName) throws Exception {
        List<File> backups = FileUtil.getBackupFiles(dir, fileName);
        assertFalse(backups.isEmpty());
        for (File backup : backups) {
            byte[] head = new byte[4];
            try (var fis = new java.io.FileInputStream(backup)) {
                assertEquals(4, fis.read(head));
            }
            assertArrayEquals(Encryption.V2_MAGIC, head, "backup is not encrypted: " + backup.getName());
        }
    }

    @Test
    public void testPlaintextFileIsRejectedWithoutLegacyKeyMaterial() throws Exception {
        NavigationPath data = largeNavigationPath();
        // A plaintext store is forgeable, so a v2-native keyring must reject it as corrupt.
        File storageFile = new File(dir, "PlaintextStore");
        try (FileOutputStream fos = new FileOutputStream(storageFile)) {
            data.toProtoMessage().writeDelimitedTo(fos);
        }
        persistenceManager.initialize(data, "PlaintextStore", PersistenceManager.Source.PRIVATE);

        assertNull(persistenceManager.getPersisted("PlaintextStore"));
        assertFalse(storageFile.exists());
        File[] backups = new File(dir, FileUtil.CORRUPTED_BACKUP_FOLDER).listFiles();
        assertNotNull(backups);
        assertEquals(1, backups.length);
    }

    @Test
    public void testPlaintextFileIsReadableDuringLegacyMigration() throws Exception {
        // build an account still in the legacy key format: PKCS#12 sym.p12 + AES-ECB-with-hmac key files
        File legacyDir = File.createTempFile("persistence_test_legacy", "");
        assertTrue(legacyDir.delete());
        assertTrue(legacyDir.mkdir());
        PersistenceManager<NavigationPath> legacyManager = null;
        try {
            String password = "test password 123";
            SecretKey symKey = Encryption.generateSecretKey(256);
            writeLegacySymFile(legacyDir, symKey, password);
            writeLegacyKeyFile(legacyDir, "sig.key", Sig.generateKeyPair().getPrivate().getEncoded(), symKey);
            writeLegacyKeyFile(legacyDir, "enc.key", Encryption.generateKeyPair().getPrivate().getEncoded(), symKey);
            KeyRing legacyKeyRing = new KeyRing(new KeyStorage(legacyDir), password, false);
            assertTrue(legacyKeyRing.isUnlocked());

            // a plaintext store from the pre-encryption era is still readable during this migration
            NavigationPath data = largeNavigationPath();
            try (FileOutputStream fos = new FileOutputStream(new File(legacyDir, "LegacyStore"))) {
                data.toProtoMessage().writeDelimitedTo(fos);
            }
            legacyManager = new PersistenceManager<>(legacyDir, RESOLVER, null, legacyKeyRing);
            legacyManager.initialize(data, "LegacyStore", PersistenceManager.Source.PRIVATE);
            assertEquals(data, legacyManager.getPersisted("LegacyStore"));
        } finally {
            if (legacyManager != null) legacyManager.shutdown();
            FileUtil.deleteDirectory(legacyDir);
        }
    }

    @Test
    public void testRecordedPlaintextStoreSurvivesRestartAfterKeyMigration() throws Exception {
        File appDir = File.createTempFile("persistence_test_restart", "");
        assertTrue(appDir.delete());
        File keysDir = new File(appDir, "keys");
        File dbDir = new File(appDir, "db");
        assertTrue(keysDir.mkdirs());
        assertTrue(dbDir.mkdirs());
        PersistenceManager<NavigationPath> manager = null;
        try {
            // legacy account with a plaintext store already on disk before the first unlock
            String password = "test password 123";
            SecretKey symKey = Encryption.generateSecretKey(256);
            writeLegacySymFile(keysDir, symKey, password);
            writeLegacyKeyFile(keysDir, "sig.key", Sig.generateKeyPair().getPrivate().getEncoded(), symKey);
            writeLegacyKeyFile(keysDir, "enc.key", Encryption.generateKeyPair().getPrivate().getEncoded(), symKey);
            NavigationPath data = largeNavigationPath();
            File storageFile = new File(dbDir, "RestartStore");
            try (FileOutputStream fos = new FileOutputStream(storageFile)) {
                data.toProtoMessage().writeDelimitedTo(fos);
            }

            // the unlock records the store in the migration inventory before upgrading the keys
            KeyRing legacyKeyRing = new KeyRing(new KeyStorage(keysDir), dbDir, password, false);
            assertTrue(legacyKeyRing.isUnlocked());
            assertTrue(new File(dbDir, PlaintextMigration.FILE_NAME).exists());
            assertFalse(new File(keysDir, "sym.p12").exists());

            // simulate a crash before the store was rewritten: a fresh process loads only v2 keys
            KeyRing restarted = new KeyRing(new KeyStorage(keysDir), dbDir, password, false);
            assertTrue(restarted.isUnlocked());
            assertFalse(restarted.getKeyStorage().hasLegacyFormatEverLoaded());
            manager = new PersistenceManager<>(dbDir, RESOLVER, null, restarted);
            manager.initialize(data, "RestartStore", PersistenceManager.Source.PRIVATE);
            assertEquals(data, manager.getPersisted("RestartStore"));

            // re-persisting encrypted removes the store from the inventory, so a plaintext
            // replacement (even byte-identical to the original) is rejected afterwards
            CountDownLatch latch = new CountDownLatch(1);
            manager.persistNow(latch::countDown);
            assertTrue(latch.await(15, TimeUnit.SECONDS), "write did not complete");
            // the emptied inventory remains as a durable marker that migration ran
            assertTrue(new File(dbDir, PlaintextMigration.FILE_NAME).exists());
            assertBackupsAreEncrypted(dbDir, "RestartStore");
            try (FileOutputStream fos = new FileOutputStream(storageFile)) {
                data.toProtoMessage().writeDelimitedTo(fos);
            }
            assertNull(manager.getPersisted("RestartStore"));
        } finally {
            if (manager != null) manager.shutdown();
            FileUtil.deleteDirectory(appDir);
        }
    }

    @Test
    public void testTamperedRecordedPlaintextStoreIsRejected() throws Exception {
        File appDir = File.createTempFile("persistence_test_tamper", "");
        assertTrue(appDir.delete());
        File keysDir = new File(appDir, "keys");
        File dbDir = new File(appDir, "db");
        assertTrue(keysDir.mkdirs());
        assertTrue(dbDir.mkdirs());
        PersistenceManager<NavigationPath> manager = null;
        try {
            String password = "test password 123";
            SecretKey symKey = Encryption.generateSecretKey(256);
            writeLegacySymFile(keysDir, symKey, password);
            writeLegacyKeyFile(keysDir, "sig.key", Sig.generateKeyPair().getPrivate().getEncoded(), symKey);
            writeLegacyKeyFile(keysDir, "enc.key", Encryption.generateKeyPair().getPrivate().getEncoded(), symKey);
            NavigationPath data = largeNavigationPath();
            File storageFile = new File(dbDir, "TamperStore");
            try (FileOutputStream fos = new FileOutputStream(storageFile)) {
                data.toProtoMessage().writeDelimitedTo(fos);
            }
            new KeyRing(new KeyStorage(keysDir), dbDir, password, false);

            // replacing the recorded store's content after the crash fails the recorded hash
            NavigationPath forged = new NavigationPath(List.of("forged/entry"));
            try (FileOutputStream fos = new FileOutputStream(storageFile)) {
                forged.toProtoMessage().writeDelimitedTo(fos);
            }
            KeyRing restarted = new KeyRing(new KeyStorage(keysDir), dbDir, password, false);
            manager = new PersistenceManager<>(dbDir, RESOLVER, null, restarted);
            manager.initialize(data, "TamperStore", PersistenceManager.Source.PRIVATE);
            assertNull(manager.getPersisted("TamperStore"));
        } finally {
            if (manager != null) manager.shutdown();
            FileUtil.deleteDirectory(appDir);
        }
    }

    @Test
    public void testTamperedPlaintextStoreRejectedInSameProcess() throws Exception {
        File appDir = File.createTempFile("persistence_test_sameproc", "");
        assertTrue(appDir.delete());
        File keysDir = new File(appDir, "keys");
        File dbDir = new File(appDir, "db");
        assertTrue(keysDir.mkdirs());
        assertTrue(dbDir.mkdirs());
        PersistenceManager<NavigationPath> manager = null;
        try {
            String password = "test password 123";
            SecretKey symKey = Encryption.generateSecretKey(256);
            writeLegacySymFile(keysDir, symKey, password);
            writeLegacyKeyFile(keysDir, "sig.key", Sig.generateKeyPair().getPrivate().getEncoded(), symKey);
            writeLegacyKeyFile(keysDir, "enc.key", Encryption.generateKeyPair().getPrivate().getEncoded(), symKey);
            NavigationPath data = largeNavigationPath();
            File storageFile = new File(dbDir, "SameProcStore");
            try (FileOutputStream fos = new FileOutputStream(storageFile)) {
                data.toProtoMessage().writeDelimitedTo(fos);
            }

            // the unlock records the inventory; the same process may read the recorded content
            KeyRing legacyKeyRing = new KeyRing(new KeyStorage(keysDir), dbDir, password, false);
            assertTrue(legacyKeyRing.getKeyStorage().hasLegacyFormatEverLoaded());
            manager = new PersistenceManager<>(dbDir, RESOLVER, null, legacyKeyRing);
            manager.initialize(data, "SameProcStore", PersistenceManager.Source.PRIVATE);
            assertEquals(data, manager.getPersisted("SameProcStore"));

            // but a replacement written after the inventory is rejected even in the same process
            NavigationPath forged = new NavigationPath(List.of("forged/entry"));
            try (FileOutputStream fos = new FileOutputStream(storageFile)) {
                forged.toProtoMessage().writeDelimitedTo(fos);
            }
            assertNull(manager.getPersisted("SameProcStore"));
        } finally {
            if (manager != null) manager.shutdown();
            FileUtil.deleteDirectory(appDir);
        }
    }

    @Test
    public void testResidualPlaintextBackupsAreEncryptedAtMigration() throws Exception {
        File appDir = File.createTempFile("persistence_test_residual", "");
        assertTrue(appDir.delete());
        File keysDir = new File(appDir, "keys");
        File dbDir = new File(appDir, "db");
        assertTrue(keysDir.mkdirs());
        assertTrue(dbDir.mkdirs());
        try {
            String password = "test password 123";
            SecretKey symKey = Encryption.generateSecretKey(256);
            writeLegacySymFile(keysDir, symKey, password);
            writeLegacyKeyFile(keysDir, "sig.key", Sig.generateKeyPair().getPrivate().getEncoded(), symKey);
            writeLegacyKeyFile(keysDir, "enc.key", Encryption.generateKeyPair().getPrivate().getEncoded(), symKey);

            // historical plaintext copies in the backup and corruption-recovery trees
            byte[] backupBytes = "old plaintext backup".getBytes();
            byte[] corruptedBytes = "old corrupted store".getBytes();
            File backupFile = new File(dbDir, "backup/backups_OldStore/123_OldStore");
            File corruptedFile = new File(dbDir, FileUtil.CORRUPTED_BACKUP_FOLDER + "/456_OldStore");
            assertTrue(backupFile.getParentFile().mkdirs());
            assertTrue(corruptedFile.getParentFile().mkdirs());
            java.nio.file.Files.write(backupFile.toPath(), backupBytes);
            java.nio.file.Files.write(corruptedFile.toPath(), corruptedBytes);

            // migration encrypts them in place, preserving their content
            new KeyRing(new KeyStorage(keysDir), dbDir, password, false);
            assertArrayEquals(backupBytes, Encryption.decryptV2(java.nio.file.Files.readAllBytes(backupFile.toPath()), symKey));
            assertArrayEquals(corruptedBytes, Encryption.decryptV2(java.nio.file.Files.readAllBytes(corruptedFile.toPath()), symKey));
        } finally {
            FileUtil.deleteDirectory(appDir);
        }
    }

    @Test
    public void testPlaintextPlantedAfterEmptyMigrationIsRejected() throws Exception {
        File appDir = File.createTempFile("persistence_test_empty", "");
        assertTrue(appDir.delete());
        File keysDir = new File(appDir, "keys");
        File dbDir = new File(appDir, "db");
        assertTrue(keysDir.mkdirs());
        assertTrue(dbDir.mkdirs());
        PersistenceManager<NavigationPath> manager = null;
        try {
            // legacy account with no plaintext stores at all
            String password = "test password 123";
            SecretKey symKey = Encryption.generateSecretKey(256);
            writeLegacySymFile(keysDir, symKey, password);
            writeLegacyKeyFile(keysDir, "sig.key", Sig.generateKeyPair().getPrivate().getEncoded(), symKey);
            writeLegacyKeyFile(keysDir, "enc.key", Encryption.generateKeyPair().getPrivate().getEncoded(), symKey);
            KeyRing keyRing = new KeyRing(new KeyStorage(keysDir), dbDir, password, false);
            assertTrue(keyRing.getKeyStorage().hasLegacyFormatEverLoaded());
            assertTrue(new File(dbDir, PlaintextMigration.FILE_NAME).exists()); // empty inventory recorded

            // a plaintext store planted after the (empty) migration must be rejected
            NavigationPath data = largeNavigationPath();
            try (FileOutputStream fos = new FileOutputStream(new File(dbDir, "PlantedStore"))) {
                data.toProtoMessage().writeDelimitedTo(fos);
            }
            manager = new PersistenceManager<>(dbDir, RESOLVER, null, keyRing);
            manager.initialize(data, "PlantedStore", PersistenceManager.Source.PRIVATE);
            assertNull(manager.getPersisted("PlantedStore"));
        } finally {
            if (manager != null) manager.shutdown();
            FileUtil.deleteDirectory(appDir);
        }
    }

    @Test
    public void testUnlistableBackupDirAbortsMigrationRecording() throws Exception {
        Assumptions.assumeFalse(Utilities.isWindows()); // POSIX permissions
        File dbDir = File.createTempFile("persistence_test_unlistable", "");
        assertTrue(dbDir.delete());
        File backupsDir = new File(dbDir, "backup/backups_OldStore");
        assertTrue(backupsDir.mkdirs());
        java.nio.file.Files.write(new File(backupsDir, "123_OldStore").toPath(), "plaintext".getBytes());
        assertTrue(backupsDir.setReadable(false));
        Assumptions.assumeTrue(backupsDir.listFiles() == null, "permissions not enforced (running as root?)");
        try {
            // an uninspectable directory must abort the migration, not be skipped
            assertThrows(IOException.class, () -> PlaintextMigration.record(dbDir, Encryption.generateSecretKey(256)));
        } finally {
            assertTrue(backupsDir.setReadable(true));
            FileUtil.deleteDirectory(dbDir);
        }
    }

    private static void writeLegacySymFile(File keysDir, SecretKey symKey, String password) throws Exception {
        KeyStore p12 = KeyStore.getInstance("PKCS12");
        p12.load(null, null);
        p12.setKeyEntry("sym", symKey, password.toCharArray(), null);
        try (FileOutputStream fos = new FileOutputStream(new File(keysDir, "sym.p12"))) {
            p12.store(fos, password.toCharArray());
        }
    }

    private static void writeLegacyKeyFile(File dir, String fileName, byte[] privateKeyEncoded, SecretKey symKey) throws Exception {
        byte[] pkcs8 = new PKCS8EncodedKeySpec(privateKeyEncoded).getEncoded();
        try (FileOutputStream fos = new FileOutputStream(new File(dir, fileName))) {
            fos.write(Encryption.encryptPayloadWithHmac(pkcs8, symKey));
        }
    }

    @Test
    public void testStoreOverProtobufDefaultSizeLimitRoundTrips() throws Exception {
        // A store whose serialized payload exceeds protobuf's default 64 MB stream-parse limit. The
        // streaming read must lift that limit (as the old parseFrom(byte[]) path implicitly did) so a
        // large valid store is not rejected as "Protocol message too large" and moved to backup.
        int oversize = 66 * 1024 * 1024;
        String big = "a".repeat(oversize); // Latin-1 -> compact (1 byte/char) on the heap
        NavigationPath data = new NavigationPath(List.of(big));
        byte[] payload = ((protobuf.PersistableEnvelope) data.toProtoMessage()).toByteArray();
        writeLegacyEncryptedFile(payload, keyRing.getSymmetricKey(), new File(dir, "LargeStore"));
        payload = null; // allow GC before the read rebuilds the payload
        persistenceManager.initialize(data, "LargeStore", PersistenceManager.Source.PRIVATE);

        NavigationPath read = persistenceManager.getPersisted("LargeStore");
        assertEquals(1, read.getPath().size());
        assertEquals(oversize, read.getPath().get(0).length());
    }

    @Test
    public void testV2StoreOverProtobufDefaultSizeLimitRoundTrips() throws Exception {
        // Same as above but through the v2 read path, which must also lift the 64 MB stream limit.
        int oversize = 66 * 1024 * 1024;
        String big = "a".repeat(oversize);
        NavigationPath data = new NavigationPath(List.of(big));
        byte[] payload = ((protobuf.PersistableEnvelope) data.toProtoMessage()).toByteArray();
        try (FileOutputStream fos = new FileOutputStream(new File(dir, "LargeStoreV2"))) {
            Encryption.encryptV2ToStream(out -> out.write(payload), keyRing.getSymmetricKey(), fos);
        }
        persistenceManager.initialize(data, "LargeStoreV2", PersistenceManager.Source.PRIVATE);

        NavigationPath read = persistenceManager.getPersisted("LargeStoreV2");
        assertEquals(1, read.getPath().size());
        assertEquals(oversize, read.getPath().get(0).length());
    }

    @Test
    public void testCorruptEncryptedFileIsMovedToBackup() throws Exception {
        NavigationPath data = largeNavigationPath();
        persistAndWait(data, "CorruptStore");

        // Corrupt a swath of the ciphertext so neither decryption-verification nor the unencrypted
        // fallback can parse it.
        File storageFile = new File(dir, "CorruptStore");
        byte[] bytes = java.nio.file.Files.readAllBytes(storageFile.toPath());
        for (int i = 0; i < bytes.length; i++) bytes[i] ^= 0x5a;
        java.nio.file.Files.write(storageFile.toPath(), bytes);

        NavigationPath read = persistenceManager.getPersisted("CorruptStore");
        assertNull(read, "corrupt file must not return data");
        assertFalse(storageFile.exists(), "corrupt file should be moved out of place");
        assertTrue(new File(dir, "backup_of_corrupted_data/CorruptStore").exists(),
                "corrupt file should be preserved in backup_of_corrupted_data");
    }

    @Test
    public void testCorruptV2FileWithIntactMagicIsMovedToBackup() throws Exception {
        NavigationPath data = largeNavigationPath();
        persistAndWait(data, "CorruptV2Store");

        // Flip one ciphertext byte but keep the v2 magic, so the failure surfaces in the v2 read path.
        File storageFile = new File(dir, "CorruptV2Store");
        byte[] bytes = java.nio.file.Files.readAllBytes(storageFile.toPath());
        bytes[bytes.length / 2] ^= 0x01;
        java.nio.file.Files.write(storageFile.toPath(), bytes);

        NavigationPath read = persistenceManager.getPersisted("CorruptV2Store");
        assertNull(read, "corrupt file must not return data");
        assertFalse(storageFile.exists(), "corrupt file should be moved out of place");
        assertTrue(new File(dir, "backup_of_corrupted_data/CorruptV2Store").exists(),
                "corrupt file should be preserved in backup_of_corrupted_data");
    }
}
