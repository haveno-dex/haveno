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
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertTrue(new File(dir, "EncryptedStore").length() > 0);

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
}
