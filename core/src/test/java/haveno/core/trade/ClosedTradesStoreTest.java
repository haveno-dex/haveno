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

package haveno.core.trade;

import com.google.inject.Provider;
import haveno.common.crypto.Encryption;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.file.CorruptedStorageFileHandler;
import haveno.common.file.FileUtil;
import haveno.common.persistence.EncryptedAppendLog;
import haveno.common.persistence.PersistenceManager;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferPayload;
import haveno.core.offer.OpenOffer;
import haveno.core.proto.persistable.CorePersistenceProtoResolver;
import haveno.core.xmr.wallet.BtcWalletService;
import haveno.core.xmr.wallet.XmrWalletService;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClosedTradesStoreTest {

    private File dir;
    private KeyRing keyRing;
    private CorePersistenceProtoResolver resolver;
    private CorruptedStorageFileHandler corruptedStorageFileHandler;

    @BeforeEach
    public void setup() throws Exception {
        PersistenceManager.reset(); // clear static registry/flags leaked by other tests in this JVM
        dir = File.createTempFile("closed_trades_store_test", "");
        assertTrue(dir.delete());
        assertTrue(dir.mkdir());
        keyRing = new KeyRing(new KeyStorage(dir), null, true);
        // OpenOffer reconstruction needs neither wallet nor network resolver, so null providers are fine.
        Provider<BtcWalletService> btc = () -> null;
        Provider<XmrWalletService> xmr = () -> null;
        resolver = new CorePersistenceProtoResolver(btc, xmr, null);
        corruptedStorageFileHandler = new CorruptedStorageFileHandler();
    }

    @AfterEach
    public void tearDown() throws IOException {
        PersistenceManager.reset();
        FileUtil.deleteDirectory(dir);
    }

    private ClosedTradesStore newStore() {
        Provider<XmrWalletService> xmr = () -> null;
        return new ClosedTradesStore(dir, keyRing, resolver, xmr, corruptedStorageFileHandler,
                new PersistenceManager<>(dir, resolver, null, keyRing));
    }

    // Builds a real, fully-serializable OpenOffer (with a real PubKeyRing so toProtoMessage/fromProto
    // round-trip), so the store test exercises genuine proto encode/decode, not stubs.
    private OpenOffer openOffer(String id, long triggerPrice) {
        OfferPayload payload = new OfferPayload(id,
                0L, null, keyRing.getPubKeyRing(), OfferDirection.BUY, 100000L, 0.0, false, 100000L, 100000L,
                0.0, 0.0, 0.0, 0.0, 0.0, "XMR", "USD", "SEPA", "", null, null, null, null,
                "1.0.0", 0L, 0L, 0L, false, false, 0L, 0L, false, null, null, 0, null, null, null, "extra");
        return new OpenOffer(new Offer(payload), triggerPrice, false);
    }

    private static List<String> ids(List<Tradable> tradables) {
        return tradables.stream().map(Tradable::getId).collect(Collectors.toList());
    }

    @Test
    public void testEmptyStoreLoadsEmpty() {
        assertTrue(newStore().load().isEmpty());
    }

    @Test
    public void testRoundTripPreservesOrderAndIds() {
        ClosedTradesStore store = newStore();
        store.appendUpsert(openOffer("a", 0));
        store.appendUpsert(openOffer("b", 0));
        store.appendUpsert(openOffer("c", 0));
        // Reload with a fresh store to prove durability across a "restart".
        assertEquals(List.of("a", "b", "c"), ids(newStore().load()));
    }

    @Test
    public void testDeleteRemovesTrade() {
        ClosedTradesStore store = newStore();
        store.appendUpsert(openOffer("a", 0));
        store.appendUpsert(openOffer("b", 0));
        store.appendUpsert(openOffer("c", 0));
        store.appendDelete("b");
        assertEquals(List.of("a", "c"), ids(newStore().load()));
    }

    @Test
    public void testUpsertReplacesInPlaceKeepingPosition() {
        ClosedTradesStore store = newStore();
        store.appendUpsert(openOffer("a", 11));
        store.appendUpsert(openOffer("b", 22));
        store.appendUpsert(openOffer("a", 99)); // update a in place

        List<Tradable> loaded = newStore().load();
        assertEquals(List.of("a", "b"), ids(loaded), "updating must keep first-seen position");
        OpenOffer a = (OpenOffer) loaded.get(0);
        assertEquals(99, a.getTriggerPrice(), "latest write must win");
    }

    @Test
    public void testDeleteThenReAddMovesToEnd() {
        ClosedTradesStore store = newStore();
        store.appendUpsert(openOffer("a", 0));
        store.appendUpsert(openOffer("b", 0));
        store.appendDelete("a");
        store.appendUpsert(openOffer("a", 0));
        assertEquals(List.of("b", "a"), ids(newStore().load()));
    }

    // Writes a legacy monolithic ClosedTrades file in the exact on-disk format PersistenceManager uses.
    private void writeLegacyFile(OpenOffer... offers) throws Exception {
        TradableList<Tradable> legacy = new TradableList<>();
        for (OpenOffer offer : offers) legacy.add(offer);
        byte[] payload = ((protobuf.PersistableEnvelope) legacy.toProtoMessage()).toByteArray();
        byte[] encrypted = Encryption.encryptPayloadWithHmac(payload, keyRing.getSymmetricKey());
        Files.write(new File(dir, ClosedTradesStore.LEGACY_FILE_NAME).toPath(), encrypted);
    }

    @Test
    public void testMigrationFromLegacyMonolithicFile() throws Exception {
        writeLegacyFile(openOffer("legacy-1", 0), openOffer("legacy-2", 0));

        List<Tradable> loaded = newStore().load();

        assertEquals(List.of("legacy-1", "legacy-2"), ids(loaded));
        assertTrue(new File(dir, ClosedTradesStore.LOG_FILE_NAME).exists(), "log should be created");
        assertTrue(new File(dir, ClosedTradesStore.LEGACY_BACKUP_NAME).exists(), "legacy file should be frozen as backup");
        assertFalse(new File(dir, ClosedTradesStore.LEGACY_FILE_NAME).exists(), "legacy file should be moved");
        // A second start does not re-migrate (log already present) and reads identically.
        assertEquals(List.of("legacy-1", "legacy-2"), ids(newStore().load()));
    }

    @Test
    public void testLegacyFileIsMergedEvenWhenLogAlreadyExists() throws Exception {
        // A log already exists (e.g. the first migration was deferred and an append created it, or
        // the user re-upgraded after a downgrade during which the old build recreated ClosedTrades).
        ClosedTradesStore store = newStore();
        store.appendUpsert(openOffer("log-a", 0));
        store.appendUpsert(openOffer("shared", 11));
        store.appendUpsert(openOffer("tombstoned", 0));
        store.appendDelete("tombstoned");

        // The legacy file holds a stale copy of "shared", a copy of the deliberately deleted
        // "tombstoned", and a trade the log has never seen.
        writeLegacyFile(openOffer("shared", 99), openOffer("tombstoned", 0), openOffer("legacy-only", 0));

        List<Tradable> loaded = newStore().load();

        // Ids the log has ever mentioned are not resurrected or overwritten; unseen ones are merged.
        assertEquals(List.of("log-a", "shared", "legacy-only"), ids(loaded));
        assertEquals(11, ((OpenOffer) loaded.get(1)).getTriggerPrice(), "log version must win over stale legacy copy");
        assertFalse(new File(dir, ClosedTradesStore.LEGACY_FILE_NAME).exists(), "legacy file should be moved after merge");
        // Durable across restarts.
        assertEquals(List.of("log-a", "shared", "legacy-only"), ids(newStore().load()));
    }

    @Test
    public void testCompactionKeepsTombstonesSoLegacyCannotResurrectDeletedTrades() throws Exception {
        ClosedTradesStore store = newStore();
        store.appendUpsert(openOffer("deleted", 0));
        store.appendDelete("deleted");
        // enough superseded records to exceed the compaction floor
        for (int i = 0; i < 600; i++) store.appendUpsert(openOffer("keep", i));

        // this load compacts the log; the tombstone for "deleted" must survive the rewrite
        assertEquals(List.of("keep"), ids(newStore().load()));

        // a legacy file that reappears afterwards (e.g. downgrade/upgrade cycle) must not
        // resurrect the deliberately deleted trade
        writeLegacyFile(openOffer("deleted", 0), openOffer("legacy-only", 0));
        assertEquals(List.of("keep", "legacy-only"), ids(newStore().load()));
    }

    @Test
    public void testSecondMergeDoesNotOverwriteEarlierLegacyBackup() throws Exception {
        writeLegacyFile(openOffer("first", 0));
        newStore().load();
        assertTrue(new File(dir, ClosedTradesStore.LEGACY_BACKUP_NAME).exists());

        writeLegacyFile(openOffer("second", 0));
        newStore().load();

        File[] backups = dir.listFiles((d, name) -> name.startsWith(ClosedTradesStore.LEGACY_BACKUP_NAME));
        assertEquals(2, backups.length, "an earlier legacy backup must never be overwritten");
    }

    @Test
    public void testUndecodableRecordIsSkippedAndSurfaced() throws Exception {
        ClosedTradesStore store = newStore();
        store.appendUpsert(openOffer("a", 0));
        store.appendUpsert(openOffer("b", 0));

        // Append two authenticated but undecodable records directly to the log: one that is not a
        // valid TradableLogEntry at all, and one whose upsert has no known tradable type (as a newer
        // version would write). Neither may hide the rest of the history.
        EncryptedAppendLog rawLog = new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME, keyRing.getSymmetricKey(), 3);
        rawLog.append(new byte[]{0x0a}); // truncated protobuf
        rawLog.append(protobuf.TradableLogEntry.newBuilder()
                .setUpsert(protobuf.Tradable.newBuilder().build())
                .build()
                .toByteArray());

        List<Tradable> loaded = newStore().load();

        assertEquals(List.of("a", "b"), ids(loaded), "decodable records must survive an undecodable one");
        assertTrue(corruptedStorageFileHandler.getFiles().isPresent(), "user must be notified of skipped records");
        assertTrue(corruptedStorageFileHandler.getFiles().get().contains(ClosedTradesStore.LOG_FILE_NAME));
        // The undecodable records stay on disk (no compaction while they exist): a later read with a
        // build that understands them would still see them; here we just prove the load is stable.
        assertEquals(List.of("a", "b"), ids(newStore().load()));
    }

    @Test
    public void testPendingQueueIsRecoveredMergedAndCleared() throws Exception {
        ClosedTradesStore store = newStore();
        store.appendUpsert(openOffer("a", 0));

        // Simulate a previous session whose append failed after "a": the failed batch was durably
        // queued in the pending file instead of reaching the log.
        EncryptedAppendLog pending = new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME, keyRing.getSymmetricKey(), 1);
        pending.appendAll(List.of(
                ClosedTradesStore.upsertBytes(openOffer("b", 7)),
                ClosedTradesStore.deleteBytes("a")));

        List<Tradable> loaded = newStore().load();

        assertEquals(List.of("b"), ids(loaded), "pending records must replay after the log");
        assertEquals(7, ((OpenOffer) loaded.get(0)).getTriggerPrice());
        // The recovery re-appended the records to the log and cleared the queue, so a later start
        // reads the same state from the log alone.
        EncryptedAppendLog clearedPending = new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME, keyRing.getSymmetricKey(), 1);
        assertTrue(clearedPending.readAllValidRecords().isEmpty(), "pending queue must be cleared after recovery");
        assertEquals(List.of("b"), ids(newStore().load()));
    }

    @Test
    public void testShouldCompactThresholds() {
        assertFalse(ClosedTradesStore.shouldCompact(512, 1), "at the floor, do not compact");
        assertTrue(ClosedTradesStore.shouldCompact(513, 1), "just past the floor, compact");
        assertFalse(ClosedTradesStore.shouldCompact(1000, 600), "ratio not exceeded -> no compaction");
        assertTrue(ClosedTradesStore.shouldCompact(1300, 600), "ratio exceeded -> compaction");
    }
}
