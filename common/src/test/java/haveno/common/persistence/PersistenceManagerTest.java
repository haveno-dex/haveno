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
import haveno.common.crypto.AuthenticatedEncryption;
import haveno.common.crypto.Encryption;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.file.FileUtil;
import haveno.common.proto.persistable.NavigationPath;
import haveno.common.proto.persistable.PersistableEnvelope;
import haveno.common.proto.persistable.PersistablePayload;
import haveno.common.proto.persistable.PersistenceProtoResolver;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        PersistenceManager.setReadFailureHandler(null);
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

    // Writes encrypt(payload || hmac(payload)) to a file with constant memory through the same
    // production helper PersistenceManager uses, so the fixture format can never drift from the
    // real writer. Avoids the array encrypt's peak-memory amplification for large fixtures.
    private void writeEncryptedFile(byte[] payload, SecretKey key, File file) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            Encryption.encryptPayloadWithHmacToStream(payload, key, fos);
        }
    }

    @Test
    public void testEncryptedWriteThenStreamingReadRoundTrip() throws Exception {
        NavigationPath data = largeNavigationPath();
        persistAndWait(data, "EncryptedStore");

        // Sanity: the on-disk file is actually present.
        assertTrue(AuthenticatedEncryption.isEnvelope(java.nio.file.Files.readAllBytes(new File(dir, "EncryptedStore").toPath())));

        NavigationPath read = persistenceManager.getPersisted("EncryptedStore");
        assertEquals(data, read);
    }

    @Test
    public void testLegacyUnencryptedFileIsStillReadable() throws Exception {
        NavigationPath data = largeNavigationPath();
        // Write the store the old, unencrypted way (delimited protobuf) directly to the storage file.
        File storageFile = new File(dir, "LegacyStore");
        try (FileOutputStream fos = new FileOutputStream(storageFile)) {
            data.toProtoMessage().writeDelimitedTo(fos);
        }
        persistenceManager.initialize(data, "LegacyStore", PersistenceManager.Source.PRIVATE);

        NavigationPath read = persistenceManager.getPersisted("LegacyStore");
        assertEquals(data, read);
        assertTrue(AuthenticatedEncryption.isEnvelope(java.nio.file.Files.readAllBytes(storageFile.toPath())));
        assertEquals(data, persistenceManager.getPersisted("LegacyStore"));
    }

    @Test
    public void testFailedMigrationDoesNotRejectValidRequiredData() throws Exception {
        assertMigrationCanBeDeferred(false);
    }

    @Test
    public void testFailedMigrationDoesNotQuarantineValidCache() throws Exception {
        assertMigrationCanBeDeferred(true);
    }

    private void assertMigrationCanBeDeferred(boolean recoverable) throws Exception {
        NavigationPath data = largeNavigationPath();
        File file = new File(dir, "MigrationStore");
        writeEncryptedFile(data.toProtoMessage().toByteArray(), keyRing.getSymmetricKey(), file);
        byte[] original = java.nio.file.Files.readAllBytes(file.toPath());
        File blockedBackup = new File(dir, "backup");
        assertTrue(blockedBackup.createNewFile());
        if (recoverable) persistenceManager.allowCorruptionRecovery();
        persistenceManager.initialize(data, "MigrationStore", PersistenceManager.Source.PRIVATE);
        assertEquals(data, persistenceManager.getPersisted("MigrationStore"));
        assertArrayEquals(original, java.nio.file.Files.readAllBytes(file.toPath()));
        assertFalse(new File(dir, FileUtil.CORRUPTED_BACKUP_FOLDER).exists());

        assertTrue(blockedBackup.delete());
        CountDownLatch written = new CountDownLatch(1);
        persistenceManager.forcePersistNow(written::countDown, error -> org.junit.jupiter.api.Assertions.fail(error));
        assertTrue(written.await(15, TimeUnit.SECONDS));
        assertTrue(AuthenticatedEncryption.isEnvelope(java.nio.file.Files.readAllBytes(file.toPath())));
        assertEquals(data, persistenceManager.getPersisted("MigrationStore"));
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
        writeEncryptedFile(payload, keyRing.getSymmetricKey(), new File(dir, "LargeStore"));
        payload = null; // allow GC before the read rebuilds the payload
        persistenceManager.initialize(data, "LargeStore", PersistenceManager.Source.PRIVATE);

        NavigationPath read = persistenceManager.getPersisted("LargeStore");
        assertEquals(1, read.getPath().size());
        assertEquals(oversize, read.getPath().get(0).length());
    }

    @Test
    public void testCorruptEncryptedFileIsPreservedAndCannotBeOverwritten() throws Exception {
        NavigationPath data = largeNavigationPath();
        persistAndWait(data, "CorruptStore");

        // Corrupt a swath of the ciphertext so neither decryption-verification nor the unencrypted
        // fallback can parse it.
        File storageFile = new File(dir, "CorruptStore");
        byte[] bytes = java.nio.file.Files.readAllBytes(storageFile.toPath());
        for (int i = 0; i < bytes.length; i++) bytes[i] ^= 0x5a;
        java.nio.file.Files.write(storageFile.toPath(), bytes);

        assertThrows(IllegalStateException.class, () -> persistenceManager.getPersisted("CorruptStore"));
        assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(storageFile.toPath()));
        CountDownLatch written = new CountDownLatch(1);
        persistenceManager.persistNow(written::countDown);
        assertTrue(written.await(15, TimeUnit.SECONDS));
        assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(storageFile.toPath()));
        assertFalse(new File(dir, "backup_of_corrupted_data/CorruptStore").exists());
    }
    @Test
    public void testExplicitNetworkCacheRecoveryCompletesWithDefaults() throws Exception {
        persistenceManager.allowCorruptionRecovery();
        NavigationPath data = largeNavigationPath();
        persistAndWait(data, "NetworkCache");
        File file = new File(dir, "NetworkCache");
        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
        bytes[bytes.length - 1] ^= 1;
        java.nio.file.Files.write(file.toPath(), bytes);
        CountDownLatch completed = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean recovered = new java.util.concurrent.atomic.AtomicBoolean();
        PersistenceManager.setReadFailureHandler(error -> org.junit.jupiter.api.Assertions.fail(error));
        persistenceManager.readPersisted(value -> org.junit.jupiter.api.Assertions.fail("Corrupt cache was returned"), () -> {
            recovered.set(true);
            completed.countDown();
        });
        assertTrue(completed.await(15, TimeUnit.SECONDS));
        assertTrue(recovered.get());
        assertFalse(file.exists());
        assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(new File(dir, "backup_of_corrupted_data/NetworkCache").toPath()));
    }

    @Test
    public void testRequiredReadFailureNotifiesStartupAndDoesNotReturnDefaults() throws Exception {
        NavigationPath data = largeNavigationPath();
        persistAndWait(data, "RequiredStore");
        File file = new File(dir, "RequiredStore");
        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
        bytes[bytes.length - 1] ^= 1;
        java.nio.file.Files.write(file.toPath(), bytes);
        CountDownLatch failed = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean accepted = new java.util.concurrent.atomic.AtomicBoolean();
        PersistenceManager.setReadFailureHandler(error -> failed.countDown());
        persistenceManager.readPersisted(value -> accepted.set(true), () -> accepted.set(true));
        assertTrue(failed.await(15, TimeUnit.SECONDS));
        assertFalse(accepted.get());
        assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(file.toPath()));
    }

    @Test
    public void testCallbackFailureIsReportedToStartup() throws Exception {
        persistAndWait(largeNavigationPath(), "CallbackFailure");
        CountDownLatch failed = new CountDownLatch(1);
        PersistenceManager.setReadFailureHandler(error -> failed.countDown());
        persistenceManager.readPersisted(value -> { throw new IllegalStateException("Credential migration failed"); },
                () -> org.junit.jupiter.api.Assertions.fail("Store unexpectedly missing"));
        assertTrue(failed.await(15, TimeUnit.SECONDS));
    }

    @Test
    public void testForcedWriteReportsDurabilityBeforeStartup() throws Exception {
        NavigationPath data = largeNavigationPath();
        persistenceManager.initialize(data, "StartupWrite", PersistenceManager.Source.PRIVATE);
        PersistenceManager.allServicesInitialized.set(false);
        CountDownLatch written = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        persistenceManager.forcePersistNow(written::countDown, failure -> { error.set(failure); written.countDown(); });
        assertTrue(written.await(15, TimeUnit.SECONDS));
        org.junit.jupiter.api.Assertions.assertNull(error.get());
        assertEquals(data, persistenceManager.getPersisted());
    }

    @Test
    public void testForcedWriteFailureNeverReportsSuccess() throws Exception {
        persistenceManager.initialize(largeNavigationPath(), "WriteFailure", PersistenceManager.Source.PRIVATE);
        File target = new File(dir, "WriteFailure");
        assertTrue(target.mkdir());
        java.nio.file.Files.writeString(new File(target, "sentinel").toPath(), "preserve");
        CountDownLatch failed = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean success = new java.util.concurrent.atomic.AtomicBoolean();
        persistenceManager.forcePersistNow(() -> success.set(true), failure -> failed.countDown());
        assertTrue(failed.await(15, TimeUnit.SECONDS));
        assertFalse(success.get());
        assertEquals("preserve", java.nio.file.Files.readString(new File(target, "sentinel").toPath()));
    }

    @Test
    public void testPersistenceSchedulingDoesNotWaitForAnInFlightRead() throws Exception {
        NavigationPath data = largeNavigationPath();
        File file = new File(dir, "SlowRead");
        java.nio.file.Files.write(file.toPath(), AuthenticatedEncryption.encrypt(data.toProtoMessage().toByteArray(),
                keyRing.getSymmetricKey(), "store/SlowRead"));
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        PersistenceProtoResolver slowResolver = new PersistenceProtoResolver() {
            @Override
            public PersistableEnvelope fromProto(protobuf.PersistableEnvelope proto) {
                reading.countDown();
                try {
                    if (!releaseRead.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Read was not released");
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return RESOLVER.fromProto(proto);
            }
            @Override public Payload fromProto(protobuf.PaymentAccountPayload proto) { return null; }
            @Override public PersistablePayload fromProto(protobuf.PersistableNetworkPayload proto) { return null; }
        };
        persistenceManager = new PersistenceManager<>(dir, slowResolver, null, keyRing);
        persistenceManager.initialize(data, "SlowRead", PersistenceManager.Source.PRIVATE);
        java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        CountDownLatch written = new CountDownLatch(1);
        try {
            var read = threads.submit(() -> persistenceManager.getPersisted());
            assertTrue(reading.await(15, TimeUnit.SECONDS));
            // Serialization/queueing must remain responsive while another operation owns the file.
            threads.submit(() -> persistenceManager.persistNow(written::countDown)).get(5, TimeUnit.SECONDS);
            releaseRead.countDown();
            assertEquals(data, read.get(15, TimeUnit.SECONDS));
            assertTrue(written.await(15, TimeUnit.SECONDS));
        } finally {
            releaseRead.countDown();
            threads.shutdownNow();
        }
    }

}
