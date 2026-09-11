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
import haveno.common.UserThread;
import haveno.common.crypto.AuthenticatedEncryption;
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
import haveno.core.trade.failed.FailedTradesManager;
import haveno.core.trade.protocol.ProcessModel;
import java.math.BigInteger;
import java.util.function.Consumer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import haveno.core.user.Preferences;
import haveno.core.xmr.wallet.BtcWalletService;
import haveno.core.xmr.wallet.XmrWalletService;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import javafx.collections.FXCollections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
                new PersistenceManager<>(dir, resolver, corruptedStorageFileHandler, keyRing));
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
        ClosedTradesStore store = newStore();
        assertTrue(store.isHistoryIncomplete());
        assertTrue(store.load().isEmpty());
        assertFalse(store.isHistoryIncomplete());
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
    public void testUnreadableLegacyFileDoesNotHideExistingLogHistory() throws Exception {
        newStore().appendUpsert(openOffer("recent", 0));
        File legacy = new File(dir, ClosedTradesStore.LEGACY_FILE_NAME);
        byte[] damaged = {0, 1, 2, 3};
        Files.write(legacy.toPath(), damaged);
        ClosedTradesStore store = newStore();
        assertEquals(List.of("recent"), ids(store.load()));
        assertTrue(store.isHistoryIncomplete());
        org.junit.jupiter.api.Assertions.assertArrayEquals(damaged, Files.readAllBytes(legacy.toPath()));
        assertTrue(corruptedStorageFileHandler.getPreservedFiles().orElseThrow().contains(ClosedTradesStore.LEGACY_FILE_NAME));
        assertTrue(corruptedStorageFileHandler.getFiles().isEmpty(), "preserved history must not trigger the quarantine warning");
    }

    @Test
    public void testMigrationFromLegacyMonolithicFile() throws Exception {
        writeLegacyFile(openOffer("legacy-1", 0), openOffer("legacy-2", 0));
        byte[] original = Files.readAllBytes(new File(dir, ClosedTradesStore.LEGACY_FILE_NAME).toPath());

        List<Tradable> loaded = newStore().load();

        assertEquals(List.of("legacy-1", "legacy-2"), ids(loaded));
        assertTrue(new File(dir, ClosedTradesStore.LOG_FILE_NAME).exists(), "log should be created");
        assertTrue(new File(dir, ClosedTradesStore.LEGACY_BACKUP_NAME).exists(), "legacy file should be frozen as backup");
        assertFalse(new File(dir, ClosedTradesStore.LEGACY_FILE_NAME).exists(), "legacy file should be moved");
        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(new File(dir, ClosedTradesStore.LEGACY_BACKUP_NAME).toPath()));
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

        ClosedTradesStore reader = newStore();
        List<Tradable> loaded = reader.load();

        assertTrue(reader.isHistoryIncomplete());
        assertEquals(List.of("a", "b"), ids(loaded), "decodable records must survive an undecodable one");
        assertTrue(corruptedStorageFileHandler.getPreservedFiles().isPresent(), "user must be notified of skipped records");
        assertTrue(corruptedStorageFileHandler.getPreservedFiles().get().contains(ClosedTradesStore.LOG_FILE_NAME));
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
    public void testPendingQueueSurvivesMainLogAuthenticationFailureRestartsAndRetries() throws Exception {
        // Run retries explicitly so timers from simulated sessions cannot outlive the test.
        try (var userThread = mockStatic(UserThread.class)) {
            newStore().appendUpsert(openOffer("base", 0));
            File logFile = new File(dir, ClosedTradesStore.LOG_FILE_NAME);
            byte[] original = Files.readAllBytes(logFile.toPath());
            byte[] damaged = corruptFile(logFile);

            ClosedTradesStore first = newStore();
            assertThrows(IllegalStateException.class, first::load);
            assertTrue(first.isHistoryIncomplete());
            OpenOffer firstOffer = openOffer("first", 7);
            byte[] firstOfferBytes = ClosedTradesStore.upsertBytes(firstOffer);
            first.appendEntries(List.of(firstOfferBytes));

            ClosedTradesStore restarted = newStore();
            assertThrows(IllegalStateException.class, restarted::load);
            assertThrows(IllegalStateException.class, restarted::load);
            OpenOffer secondOffer = openOffer("second", 9);
            byte[] secondOfferBytes = ClosedTradesStore.upsertBytes(secondOffer);
            restarted.appendEntries(List.of(secondOfferBytes));
            restarted.flushFailedEntries();
            restarted.flushFailedEntries();

            EncryptedAppendLog pending = new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                    keyRing.getSymmetricKey(), 1);
            List<byte[]> records = pending.readAllValidRecords();
            assertEquals(2, records.size(), "retries must retain both sessions without duplicating records");
            assertArrayEquals(firstOfferBytes, records.get(0));
            assertArrayEquals(secondOfferBytes, records.get(1));
            assertArrayEquals(damaged, Files.readAllBytes(logFile.toPath()));

            Files.write(logFile.toPath(), original);
            assertEquals(List.of("base", "first", "second"), ids(newStore().load()));
            assertTrue(pending.readAllValidRecords().isEmpty());
            assertEquals(List.of("base", "first", "second"), ids(newStore().load()));
        }
    }

    @Test
    public void testAppendBeforeLoadPreservesPendingRecordOrder() throws Exception {
        newStore().appendUpsert(openOffer("same", 1));
        EncryptedAppendLog pending = new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                keyRing.getSymmetricKey(), 1);
        pending.appendAll(List.of(ClosedTradesStore.deleteBytes("same"),
                ClosedTradesStore.upsertBytes(openOffer("older", 2))));

        newStore().appendUpsert(openOffer("same", 3));

        List<Tradable> loaded = newStore().load();
        assertEquals(List.of("older", "same"), ids(loaded), "older pending records must precede new appends");
        assertEquals(3, ((OpenOffer) loaded.get(1)).getTriggerPrice());
        assertTrue(pending.readAllValidRecords().isEmpty());
    }

    @Test
    public void testUnreadablePendingQueueIsPreservedUntilRecovery() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            newStore().appendUpsert(openOffer("base", 0));
            File logFile = new File(dir, ClosedTradesStore.LOG_FILE_NAME);
            byte[] originalLog = Files.readAllBytes(logFile.toPath());
            corruptFile(logFile);
            EncryptedAppendLog pending = new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                    keyRing.getSymmetricKey(), 1);
            pending.append(ClosedTradesStore.upsertBytes(openOffer("older", 1)));
            File pendingFile = new File(dir, ClosedTradesStore.PENDING_FILE_NAME);
            byte[] originalPending = Files.readAllBytes(pendingFile.toPath());
            byte[] damagedPending = corruptFile(pendingFile);

            ClosedTradesStore store = newStore();
            assertThrows(IllegalStateException.class, store::load);
            store.appendUpsert(openOffer("newer", 2));
            store.flushFailedEntries();
            assertArrayEquals(damagedPending, Files.readAllBytes(pendingFile.toPath()));

            Files.write(pendingFile.toPath(), originalPending);
            store.flushFailedEntries();
            assertEquals(2, pending.readAllValidRecords().size());
            Files.write(logFile.toPath(), originalLog);
            store.flushFailedEntries();
            assertEquals(List.of("base", "older", "newer"), ids(newStore().load()));
            assertTrue(pending.readAllValidRecords().isEmpty());
        }
    }

    @Test
    public void testUnreadablePendingQueueDoesNotBlockHealthyMainLogAppends() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            newStore().appendUpsert(openOffer("base", 0));
            File logFile = new File(dir, ClosedTradesStore.LOG_FILE_NAME);
            byte[] originalLog = Files.readAllBytes(logFile.toPath());
            corruptFile(logFile);
            EncryptedAppendLog pending = new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                    keyRing.getSymmetricKey(), 1);
            pending.append(ClosedTradesStore.upsertBytes(openOffer("older", 1)));
            File pendingFile = new File(dir, ClosedTradesStore.PENDING_FILE_NAME);
            byte[] damagedPending = corruptFile(pendingFile);

            ClosedTradesStore store = newStore();
            assertThrows(IllegalStateException.class, store::load);
            OpenOffer newer = openOffer("newer", 2);
            byte[] newerBytes = ClosedTradesStore.upsertBytes(newer);
            store.appendEntries(List.of(newerBytes));
            // Recover only the main log: a successful retry must leave the unreadable queue intact.
            Files.write(logFile.toPath(), originalLog);
            store.flushFailedEntries();
            OpenOffer latest = openOffer("latest", 3);
            byte[] latestBytes = ClosedTradesStore.upsertBytes(latest);
            newStore().appendEntries(List.of(latestBytes));

            EncryptedAppendLog main = new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME,
                    keyRing.getSymmetricKey(), 3);
            List<byte[]> records = main.readAllValidRecords();
            assertEquals(3, records.size());
            assertArrayEquals(newerBytes, records.get(1));
            assertArrayEquals(latestBytes, records.get(2));
            assertArrayEquals(damagedPending, Files.readAllBytes(pendingFile.toPath()));
        }
    }

    @Test
    public void testUnreadablePendingQueueDoesNotHideMainHistoryOrEnableCleanup() throws Exception {
        newStore().appendUpsert(openOffer("base", 0));
        EncryptedAppendLog pending = new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                keyRing.getSymmetricKey(), 1);
        pending.append(ClosedTradesStore.upsertBytes(openOffer("pending", 1)));
        File pendingFile = new File(dir, ClosedTradesStore.PENDING_FILE_NAME);
        byte[] original = Files.readAllBytes(pendingFile.toPath());
        byte[] damaged = corruptFile(pendingFile);

        ClosedTradesStore store = newStore();
        store.appendUpsert(openOffer("newer", 2));
        assertEquals(List.of("base", "newer"), ids(store.load()));
        assertTrue(store.isHistoryIncomplete());
        assertArrayEquals(damaged, Files.readAllBytes(pendingFile.toPath()));
        assertEquals(List.of(ClosedTradesStore.PENDING_FILE_NAME), corruptedStorageFileHandler.getPreservedFiles().orElseThrow());

        CleanupMailboxMessagesService cleanup = mock(CleanupMailboxMessagesService.class);
        ClosedTradableManager manager = new ClosedTradableManager(keyRing, null, mock(Preferences.class), null,
                store, corruptedStorageFileHandler, cleanup);
        Trade closed = mock(Trade.class);
        when(closed.getId()).thenReturn("closed");
        when(closed.getDate()).thenReturn(new Date(0));
        manager.getObservableList().add(closed);
        manager.onAllServicesInitialized();
        manager.maybeClearSensitiveData();
        manager.add(openOffer("another", 3));
        manager.add(openOffer("another", 4));
        assertEquals(1, manager.getObservableList().stream().filter(entry -> entry.getId().equals("another")).count());
        assertEquals(4, ((OpenOffer) manager.getObservableList().get(1)).getTriggerPrice());
        assertFalse(manager.canTradeHaveSensitiveDataCleared("closed"));
        verify(cleanup, never()).handleTrades(any());
        verify(closed, never()).maybeClearSensitiveData();

        Files.write(pendingFile.toPath(), original);
        store.flushFailedEntries();
        assertTrue(pending.readAllValidRecords().isEmpty());
        assertTrue(store.isHistoryIncomplete(), "a write retry does not replay the displayed history");
        ClosedTradesStore recovered = newStore();
        assertEquals(List.of("base", "newer", "another", "pending"), ids(recovered.load()));
        assertFalse(recovered.isHistoryIncomplete());
        ClosedTradableManager recoveredManager = new ClosedTradableManager(keyRing, null, mock(Preferences.class), null,
                recovered, corruptedStorageFileHandler, cleanup);
        recoveredManager.onAllServicesInitialized();
        verify(cleanup).handleTrades(List.of());
    }

    @Test
    public void testRecoveredUnreadableQueueCannotReplayCommittedUpdateAfterNewWrites() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            ClosedTradesStore writer = newStore();
            writer.appendUpsert(openOffer("same", 1));
            EncryptedAppendLog main = new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME,
                    keyRing.getSymmetricKey(), 3);
            byte[] committed = main.readAllValidRecords().get(0);
            EncryptedAppendLog pending = new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                    keyRing.getSymmetricKey(), 1);
            pending.rewrite(List.of(committed));
            File pendingFile = new File(dir, ClosedTradesStore.PENDING_FILE_NAME);
            byte[] originalPending = Files.readAllBytes(pendingFile.toPath());
            byte[] damagedPending = corruptFile(pendingFile);
            ClosedTradesStore store = newStore();
            assertEquals(List.of("same"), ids(store.load()));
            assertTrue(store.isHistoryIncomplete());

            for (int i = 2; i <= 600; i++) store.appendUpsert(openOffer("same", i));
            assertEquals(600, ((OpenOffer) store.load().get(0)).getTriggerPrice());
            assertEquals(600, main.readAllValidRecords().size(), "unreadable pending records must retain commit receipts");
            assertArrayEquals(damagedPending, Files.readAllBytes(pendingFile.toPath()));

            Files.write(pendingFile.toPath(), originalPending);
            store.flushFailedEntries();

            assertTrue(pending.readAllRecords().isEmpty(), "recovery must run even when new writes left no memory queue");
            assertEquals(600, main.readAllValidRecords().size(), "the committed pending update must not be appended again");
            assertTrue(store.isHistoryIncomplete(), "a write retry does not replay the displayed history");
            ClosedTradesStore recovered = newStore();
            assertEquals(600, ((OpenOffer) recovered.load().get(0)).getTriggerPrice());
            assertFalse(recovered.isHistoryIncomplete());
            assertEquals(1, main.readAllValidRecords().size());
        }
    }

    @Test
    public void testIncompleteHistoryDefersCompactionAndLegacyMerge() throws Exception {
        ClosedTradesStore writer = newStore();
        for (int i = 0; i < 600; i++) writer.appendUpsert(openOffer("keep", i));
        writeLegacyFile(openOffer("deleted", 0), openOffer("legacy", 0));
        File legacyFile = new File(dir, ClosedTradesStore.LEGACY_FILE_NAME);
        byte[] originalLegacy = Files.readAllBytes(legacyFile.toPath());
        File mainFile = new File(dir, ClosedTradesStore.LOG_FILE_NAME);
        byte[] originalMain = Files.readAllBytes(mainFile.toPath());
        EncryptedAppendLog pending = new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                keyRing.getSymmetricKey(), 1);
        pending.append(ClosedTradesStore.deleteBytes("deleted"));
        File pendingFile = new File(dir, ClosedTradesStore.PENDING_FILE_NAME);
        byte[] originalPending = Files.readAllBytes(pendingFile.toPath());
        byte[] damagedPending = corruptFile(pendingFile);

        assertEquals(List.of("keep"), ids(newStore().load()));
        assertArrayEquals(originalMain, Files.readAllBytes(mainFile.toPath()));
        assertArrayEquals(originalLegacy, Files.readAllBytes(legacyFile.toPath()));
        assertArrayEquals(damagedPending, Files.readAllBytes(pendingFile.toPath()));
        assertFalse(new File(dir, ClosedTradesStore.LEGACY_BACKUP_NAME).exists());

        Files.write(pendingFile.toPath(), originalPending);
        ClosedTradesStore recovered = newStore();
        assertEquals(List.of("keep", "legacy"), ids(recovered.load()));
        assertFalse(recovered.isHistoryIncomplete());
        assertFalse(legacyFile.exists());
        assertEquals(3, new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME,
                keyRing.getSymmetricKey(), 3).readAllValidRecords().size());
    }

    @Test
    public void testDamagedPendingFramesAreNeverTruncatedOrCleared() throws Exception {
        newStore().appendUpsert(openOffer("base", 0));
        EncryptedAppendLog pending = new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                keyRing.getSymmetricKey(), 1);
        pending.append(ClosedTradesStore.upsertBytes(openOffer("pending", 1)));
        File pendingFile = new File(dir, ClosedTradesStore.PENDING_FILE_NAME);
        byte[] original = Files.readAllBytes(pendingFile.toPath());
        byte[] invalidLength = original.clone();
        java.util.Arrays.fill(invalidLength, 0, 4, (byte) 0);
        byte[] oversizedLength = original.clone();
        oversizedLength[0] = 0x7f;
        for (byte[] damaged : List.of(invalidLength, oversizedLength, new byte[]{1},
                java.util.Arrays.copyOf(original, original.length - 1))) {
            Files.write(pendingFile.toPath(), damaged);
            ClosedTradesStore store = newStore();
            assertEquals(List.of("base"), ids(store.load()));
            assertTrue(store.isHistoryIncomplete());
            store.appendUpsert(openOffer("base", 2));
            store.flushFailedEntries();
            assertArrayEquals(damaged, Files.readAllBytes(pendingFile.toPath()));
            assertEquals(List.of("base"), ids(newStore().load()));
            assertArrayEquals(damaged, Files.readAllBytes(pendingFile.toPath()));
        }
    }

    @Test
    public void testSuccessfulRetryClearsFirstPendingRewriteTemp() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            for (boolean partial : new boolean[]{true, false}) {
                File mainFile = new File(dir, ClosedTradesStore.LOG_FILE_NAME);
                File pendingFile = new File(dir, ClosedTradesStore.PENDING_FILE_NAME);
                File pendingTemp = new File(dir, ClosedTradesStore.PENDING_FILE_NAME + ".tmp");
                Files.deleteIfExists(pendingFile.toPath());
                new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME, keyRing.getSymmetricKey(), 3)
                        .rewrite(List.of(ClosedTradesStore.upsertBytes(openOffer("base", 0))));
                byte[] original = Files.readAllBytes(mainFile.toPath());
                corruptFile(mainFile);
                OpenOffer queued = openOffer("queued", 1);
                byte[] encrypted = AuthenticatedEncryption.encrypt(ClosedTradesStore.upsertBytes(queued),
                        keyRing.getSymmetricKey(), "append-log/" + ClosedTradesStore.PENDING_FILE_NAME);
                byte[] tempBytes = partial ? new byte[]{1} : ByteBuffer.allocate(4 + encrypted.length)
                        .putInt(encrypted.length).put(encrypted).array();
                EncryptedAppendLog pending = spy(new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                        keyRing.getSymmetricKey(), 1));
                doAnswer(invocation -> {
                    Files.write(pendingTemp.toPath(), tempBytes);
                    throw new IllegalStateException("Simulated failed first pending rewrite");
                }).doCallRealMethod().when(pending).rewrite(any());
                ClosedTradesStore store = newStore();
                var pendingField = ClosedTradesStore.class.getDeclaredField("pendingLog");
                pendingField.setAccessible(true);
                pendingField.set(store, pending);

                store.appendUpsert(queued);
                assertFalse(pendingFile.exists());
                assertArrayEquals(tempBytes, Files.readAllBytes(pendingTemp.toPath()));
                Files.write(mainFile.toPath(), original);
                store.flushFailedEntries();
                store.appendUpsert(openOffer("queued", 2));

                assertFalse(pendingTemp.exists());
                ClosedTradesStore restarted = newStore();
                List<Tradable> loaded = restarted.load();
                assertEquals(List.of("base", "queued"), ids(loaded));
                assertEquals(2, ((OpenOffer) loaded.get(1)).getTriggerPrice());
                assertFalse(restarted.isHistoryIncomplete());
            }
        }
    }

    @Test
    public void testPartialPendingRewriteIsPreservedAfterRestart() throws Exception {
        newStore().appendUpsert(openOffer("base", 0));
        File pendingTemp = new File(dir, ClosedTradesStore.PENDING_FILE_NAME + ".tmp");
        byte[] partial = {1, 2};
        Files.write(pendingTemp.toPath(), partial);
        ClosedTradesStore restarted = newStore();

        assertEquals(List.of("base"), ids(restarted.load()));
        assertTrue(restarted.isHistoryIncomplete());
        assertArrayEquals(partial, Files.readAllBytes(new File(dir, ClosedTradesStore.PENDING_FILE_NAME).toPath()));
        assertTrue(corruptedStorageFileHandler.getPreservedFiles().orElseThrow().contains(ClosedTradesStore.PENDING_FILE_NAME));
    }

    @Test
    public void testFailedQueueClearRetriesOnAppendAndWithAnEmptyMemoryQueue() throws Exception {
        for (boolean useTimer : new boolean[]{false, true}) {
            try (var userThread = mockStatic(UserThread.class)) {
                new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME, keyRing.getSymmetricKey(), 3)
                        .rewrite(List.of(ClosedTradesStore.upsertBytes(openOffer("base", 0))));
                EncryptedAppendLog pending = spy(new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                        keyRing.getSymmetricKey(), 1));
                pending.rewrite(List.of(ClosedTradesStore.upsertBytes(openOffer("queued", 1))));
                var failures = doThrow(new IllegalStateException("Simulated queue clear failure"));
                if (useTimer) failures = failures.doThrow(new IllegalStateException("Simulated repeated clear failure"));
                failures.doCallRealMethod().when(pending).rewrite(List.of());
                ClosedTradesStore store = newStore();
                var pendingField = ClosedTradesStore.class.getDeclaredField("pendingLog");
                pendingField.setAccessible(true);
                pendingField.set(store, pending);

                assertEquals(List.of("base", "queued"), ids(store.load()));
                assertEquals(1, pending.readAllRecords().size());
                store.appendUpsert(openOffer("queued", 2));
                if (useTimer) assertEquals(1, pending.readAllRecords().size());
                else assertTrue(pending.readAllRecords().isEmpty());
                ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
                userThread.verify(() -> UserThread.runAfter(retry.capture(), eq(30L)));
                retry.getValue().run();

                assertTrue(pending.readAllRecords().isEmpty());
                verify(pending, times(useTimer ? 3 : 2)).rewrite(List.of());
                List<Tradable> loaded = newStore().load();
                assertEquals(List.of("base", "queued"), ids(loaded));
                assertEquals(2, ((OpenOffer) loaded.get(1)).getTriggerPrice());
            }
        }
    }

    @Test
    public void testMainLogTruncationDefersCleanupAndReportsRecoveryCopy() throws Exception {
        ClosedTradesStore writer = newStore();
        for (int i = 0; i < 600; i++) writer.appendUpsert(openOffer("keep", i));
        File mainFile = new File(dir, ClosedTradesStore.LOG_FILE_NAME);
        byte[] original = Files.readAllBytes(mainFile.toPath());
        Files.write(mainFile.toPath(), new byte[]{1}, StandardOpenOption.APPEND);
        byte[] damaged = Files.readAllBytes(mainFile.toPath());
        ClosedTradesStore reader = newStore();

        assertEquals(List.of("keep"), ids(reader.load()));
        assertTrue(reader.isHistoryIncomplete());
        assertArrayEquals(original, Files.readAllBytes(mainFile.toPath()), "the valid prefix must not be compacted");
        String recovery = corruptedStorageFileHandler.getPreservedFiles().orElseThrow().get(0);
        assertTrue(recovery.startsWith(FileUtil.CORRUPTED_BACKUP_FOLDER + File.separator));
        assertArrayEquals(damaged, Files.readAllBytes(new File(dir, recovery).toPath()));
        reader.flushFailedEntries();
        assertTrue(reader.isHistoryIncomplete());
        for (int restart = 0; restart < 2; restart++) {
            ClosedTradesStore restarted = newStore();
            assertEquals(List.of("keep"), ids(restarted.load()));
            assertTrue(restarted.isHistoryIncomplete());
            assertArrayEquals(original, Files.readAllBytes(mainFile.toPath()));
        }
    }

    @Test
    public void testDuplicateResolutionUsesReopenCountAndPreservesIncompleteStores() throws Exception {
        for (boolean incomplete : new boolean[]{true, false}) {
            for (long[] counts : List.of(new long[]{0, 0}, new long[]{1, 0}, new long[]{0, 1}, new long[]{2, 2})) {
                for (boolean reversed : new boolean[]{false, true}) {
                    ClosedTradableManager closedManager = mock(ClosedTradableManager.class);
                    FailedTradesManager failedManager = mock(FailedTradesManager.class);
                    Trade pending = mock(Trade.class);
                    Trade closed = mock(Trade.class);
                    when(pending.getUid()).thenReturn("same-uid");
                    when(closed.getUid()).thenReturn("same-uid");
                    when(pending.getReopenCount()).thenReturn(counts[0]);
                    when(closed.getReopenCount()).thenReturn(counts[1]);
                    when(closedManager.getClosedTrades()).thenReturn(List.of(closed));
                    when(closedManager.isHistoryIncomplete()).thenReturn(incomplete);
                    when(failedManager.getObservableList()).thenReturn(FXCollections.observableArrayList());
                    TradeManager manager = mock(TradeManager.class, CALLS_REAL_METHODS);
                    doNothing().when(manager).removeTrade(any());
                    setManagerField(manager, "closedTradableManager", closedManager);
                    setManagerField(manager, "failedTradesManager", failedManager);
                    var removeDuplicates = TradeManager.class.getDeclaredMethod("removeDuplicateTrades", List.class);
                    removeDuplicates.setAccessible(true);
                    List<Trade> trades = new ArrayList<>(reversed ? List.of(closed, pending) : List.of(pending, closed));
                    boolean keepPending = counts[0] > counts[1] || counts[0] == counts[1] && incomplete;

                    removeDuplicates.invoke(manager, trades);

                    assertEquals(List.of(keepPending ? pending : closed), trades);
                    verify(failedManager, never()).removeTrade(any());
                    if (!incomplete && keepPending) verify(closedManager).removeTrade(closed);
                    else verify(closedManager, never()).removeTrade(any());
                    if (!incomplete && !keepPending) verify(manager).removeTrade(pending);
                    else verify(manager, never()).removeTrade(any());
                }
            }
        }
    }

    @Test
    public void testReopenCountRoundTripsAndRejectsInvalidValues() throws Exception {
        Trade trade = storedTrade("roundtrip");
        for (long count = 0; count < 3; count++) {
            protobuf.Tradable encoded = (protobuf.Tradable) trade.toProtoMessage();
            Trade restored = (Trade) TradableList.tradableFromProto(encoded, resolver, mock(XmrWalletService.class));
            assertEquals(count, restored.getReopenCount());
            trade.incrementReopenCount();
        }
        protobuf.BuyerAsMakerTrade invalid = ((protobuf.Tradable) trade.toProtoMessage()).getBuyerAsMakerTrade();
        invalid = invalid.toBuilder().setTrade(invalid.getTrade().toBuilder().setReopenCount(-1)).build();
        protobuf.BuyerAsMakerTrade invalidCount = invalid;
        assertThrows(IllegalArgumentException.class, () -> BuyerAsMakerTrade.fromProto(invalidCount, mock(XmrWalletService.class), resolver));
        var field = Trade.class.getDeclaredField("reopenCount");
        field.setAccessible(true);
        field.setLong(trade, Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, trade::incrementReopenCount);
        assertEquals(Long.MAX_VALUE, trade.getReopenCount());
    }

    @Test
    public void testReopenFailureAndLateCallbacksPreserveClosedHistory() throws Exception {
        ClosedTradesStore store = newTradeStore();
        store.load();
        Preferences preferences = mock(Preferences.class);
        when(preferences.getClearDataAfterDays()).thenReturn(36500);
        ClosedTradableManager closed = new ClosedTradableManager(keyRing, null, preferences, null,
                store, corruptedStorageFileHandler, mock(CleanupMailboxMessagesService.class));
        Trade trade = spy(storedTrade("reopen"));
        doReturn(true).when(trade).isInitialized();
        trade.setCompleted(true);
        closed.add(trade);
        TradableList<Trade> pending = new TradableList<>();
        PersistenceManager<TradableList<Trade>> persistence = mock(PersistenceManager.class);
        List<Runnable> successes = new ArrayList<>();
        List<Consumer<Throwable>> failures = new ArrayList<>();
        List<protobuf.PersistableEnvelope> snapshots = new ArrayList<>();
        doAnswer(invocation -> {
            successes.add(invocation.getArgument(0));
            failures.add(invocation.getArgument(1));
            snapshots.add((protobuf.PersistableEnvelope) pending.toProtoMessage());
            return null;
        }).when(persistence).forcePersistNow(any(), any());
        TradeManager manager = mock(TradeManager.class, CALLS_REAL_METHODS);
        setManagerField(manager, "closedTradableManager", closed);
        setManagerField(manager, "tradableList", pending);
        setManagerField(manager, "persistenceManager", persistence);
        setManagerField(manager, "xmrWalletService", mock(XmrWalletService.class));
        FailedTradesManager failed = mock(FailedTradesManager.class);
        when(failed.getObservableList()).thenReturn(FXCollections.observableArrayList());
        setManagerField(manager, "failedTradesManager", failed);

        manager.onMoveClosedTradeToPendingTrades(trade);
        assertEquals(1, snapshots.get(0).getTradableList().getTradable(0).getBuyerAsMakerTrade().getTrade().getReopenCount());
        failures.get(0).accept(new IOException("Simulated pending-store failure"));
        assertEquals(0, ((Trade) newTradeStore().load().get(0)).getReopenCount());
        assertFalse(closed.canTradeHaveSensitiveDataCleared(trade.getId()));
        clearInvocations(persistence);
        manager.requestPersistence(trade);
        verify(persistence).requestPersistence();
        assertFalse(closed.persistClosedTrade(trade));
        assertEquals(0, ((Trade) newTradeStore().load().get(0)).getReopenCount(), "an uncommitted reopen must not overwrite closed state");
        manager.onMoveClosedTradeToPendingTrades(trade);

        // Re-close before the first write's callback, while the instance is still in both lists.
        manager.onTradeCompleted(trade);
        Trade reclosed = (Trade) newTradeStore().load().get(0);
        assertTrue(reclosed.isCompleted());
        assertEquals(2, reclosed.getReopenCount());
        assertTrue(pending.isEmpty());
        successes.get(1).run();
        assertEquals(1, newTradeStore().load().size());

        manager.onMoveClosedTradeToPendingTrades(trade);
        assertEquals(3, trade.getReopenCount());
        successes.get(1).run();
        assertEquals(1, newTradeStore().load().size(), "an older reopen callback must not tombstone the current transition");
        successes.get(2).run();
        assertTrue(newTradeStore().load().isEmpty());
        assertEquals(List.of(trade), pending.getList());
        verify(persistence, never()).persistNow(any());
    }

    @Test
    public void testDurableReopenSurvivesUnwrittenAndTornTombstonesAcrossRestarts() throws Exception {
        Executor previousExecutor = UserThread.getExecutor();
        UserThread.setExecutor(Runnable::run);
        try {
            for (boolean torn : new boolean[]{false, true}) {
                File scenario = new File(dir, torn ? "torn" : "unwritten");
                assertTrue(scenario.mkdir());
                Trade trade = storedTrade("reopened");
                trade.setCompleted(true);
                ClosedTradesStore writer = newTradeStore(scenario);
                writer.appendUpsert(trade);
                trade.incrementReopenCount();
                trade.setCompleted(false);
                TradableList<Trade> pending = new TradableList<>();
                pending.add(trade);
                CorePersistenceProtoResolver tradeResolver = new CorePersistenceProtoResolver(() -> null,
                        () -> mock(XmrWalletService.class), null);
                PersistenceManager<TradableList<Trade>> persistence = new PersistenceManager<>(scenario, tradeResolver,
                        corruptedStorageFileHandler, keyRing);
                persistence.initialize(pending, "PendingTrades", PersistenceManager.Source.PRIVATE);
                CountDownLatch written = new CountDownLatch(1);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                try {
                    persistence.forcePersistNow(written::countDown, error -> { failure.set(error); written.countDown(); });
                    assertTrue(written.await(15, TimeUnit.SECONDS));
                    assertNull(failure.get());
                } finally {
                    persistence.shutdown();
                }
                if (torn) {
                    writer.appendDelete(trade.getId());
                    File file = new File(scenario, ClosedTradesStore.LOG_FILE_NAME);
                    byte[] bytes = Files.readAllBytes(file.toPath());
                    Files.write(file.toPath(), java.util.Arrays.copyOf(bytes, bytes.length - 1));
                }
                for (int restart = 0; restart < 2; restart++) {
                    ClosedTradesStore store = newTradeStore(scenario);
                    List<Tradable> closedEntries = store.load();
                    assertEquals(torn, store.isHistoryIncomplete());
                    ClosedTradableManager closed = new ClosedTradableManager(keyRing, null, mock(Preferences.class), null,
                            store, corruptedStorageFileHandler, mock(CleanupMailboxMessagesService.class));
                    closed.getObservableList().addAll(closedEntries);
                    TradableList<Trade> active = new TradableList<>();
                    PersistenceManager<TradableList<Trade>> restarted = new PersistenceManager<>(scenario, tradeResolver,
                            corruptedStorageFileHandler, keyRing);
                    restarted.initialize(active, "PendingTrades", PersistenceManager.Source.PRIVATE);
                    try {
                        active.setAll(restarted.getPersisted().getList());
                        Trade pendingTrade = active.getList().get(0);
                        assertEquals(1, pendingTrade.getReopenCount());
                        FailedTradesManager failed = mock(FailedTradesManager.class);
                        when(failed.getObservableList()).thenReturn(FXCollections.observableArrayList());
                        TradeManager manager = mock(TradeManager.class, CALLS_REAL_METHODS);
                        setManagerField(manager, "closedTradableManager", closed);
                        setManagerField(manager, "failedTradesManager", failed);
                        setManagerField(manager, "tradableList", active);
                        setManagerField(manager, "persistenceManager", restarted);
                        List<Trade> trades = new ArrayList<>(closed.getClosedTrades());
                        trades.add(pendingTrade);
                        var deduplicate = TradeManager.class.getDeclaredMethod("removeDuplicateTrades", List.class);
                        deduplicate.setAccessible(true);
                        deduplicate.invoke(manager, trades);
                        assertEquals(List.of(pendingTrade), trades);
                        assertEquals(List.of(pendingTrade), active.getList());
                        assertEquals(torn ? 1 : 0, closed.getClosedTrades().size());
                    } finally {
                        restarted.shutdown();
                    }
                }
            }
        } finally {
            UserThread.setExecutor(previousExecutor);
        }
    }

    private Trade storedTrade(String id) {
        return new BuyerAsMakerTrade(openOffer(id, 0).getOffer(), BigInteger.ONE, 100000,
                mock(XmrWalletService.class), new ProcessModel(id, "account", keyRing.getPubKeyRing()), "uid-" + id,
                null, null, null, null);
    }

    private ClosedTradesStore newTradeStore() {
        return newTradeStore(dir);
    }

    private ClosedTradesStore newTradeStore(File directory) {
        return new ClosedTradesStore(directory, keyRing, resolver, () -> mock(XmrWalletService.class), corruptedStorageFileHandler,
                new PersistenceManager<>(directory, resolver, corruptedStorageFileHandler, keyRing));
    }

    private static void setManagerField(TradeManager manager, String name, Object value) throws Exception {
        var field = TradeManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(manager, value);
    }

    private byte[] corruptFile(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        bytes[bytes.length - 1] ^= 1;
        Files.write(file.toPath(), bytes);
        return bytes;
    }

    @Test
    public void testCommittedPendingUpdateCannotOverwriteNewerUpdateOnRestart() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            ClosedTradesStore store = newStore();
            EncryptedAppendLog main = spy(new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME,
                    keyRing.getSymmetricKey(), 3));
            EncryptedAppendLog pending = spy(new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                    keyRing.getSymmetricKey(), 1));
            setLog(store, "appendLog", main);
            setLog(store, "pendingLog", pending);
            doThrow(new IllegalStateException("Simulated append failure"))
                    .doCallRealMethod().when(main).appendAll(anyList());
            doThrow(new IllegalStateException("Simulated queue clear failure"))
                    .when(pending).rewrite(List.of());

            store.appendUpsert(openOffer("same", 1));
            assertEquals(1, pending.readAllValidRecords().size());
            store.appendUpsert(openOffer("same", 2));

            List<Tradable> loaded = newStore().load();
            assertEquals(List.of("same"), ids(loaded));
            assertEquals(2, ((OpenOffer) loaded.get(0)).getTriggerPrice());
            assertTrue(pending.readAllValidRecords().isEmpty());
        }
    }

    @Test
    public void testCommittedPendingDeleteCannotRemoveReaddedTradeOnRestart() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            ClosedTradesStore store = newStore();
            store.appendUpsert(openOffer("same", 1));
            EncryptedAppendLog main = spy(new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME,
                    keyRing.getSymmetricKey(), 3));
            EncryptedAppendLog pending = spy(new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                    keyRing.getSymmetricKey(), 1));
            setLog(store, "appendLog", main);
            setLog(store, "pendingLog", pending);
            doThrow(new IllegalStateException("Simulated append failure"))
                    .doCallRealMethod().when(main).appendAll(anyList());
            doThrow(new IllegalStateException("Simulated queue clear failure"))
                    .when(pending).rewrite(List.of());

            store.appendDelete("same");
            store.flushFailedEntries();
            store.appendUpsert(openOffer("same", 2));

            List<Tradable> loaded = newStore().load();
            assertEquals(List.of("same"), ids(loaded));
            assertEquals(2, ((OpenOffer) loaded.get(0)).getTriggerPrice());
        }
    }

    @Test
    public void testCommittedReceiptsAreSyncedBeforeQueueIsCleared() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            newStore().appendUpsert(openOffer("same", 1));
            byte[] committed = new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME,
                    keyRing.getSymmetricKey(), 3).readAllValidRecords().get(0);
            new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME, keyRing.getSymmetricKey(), 1)
                    .rewrite(List.of(committed));
            EncryptedAppendLog main = spy(new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME,
                    keyRing.getSymmetricKey(), 3));
            EncryptedAppendLog pending = spy(new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                    keyRing.getSymmetricKey(), 1));
            ClosedTradesStore store = newStore();
            setLog(store, "appendLog", main);
            setLog(store, "pendingLog", pending);

            assertEquals(List.of("same"), ids(store.load()));

            InOrder inOrder = inOrder(main, pending);
            inOrder.verify(main).sync();
            inOrder.verify(pending).rewrite(List.of());
            verify(main, never()).appendAll(anyList());
            assertTrue(pending.readAllValidRecords().isEmpty());
        }
    }

    @Test
    public void testCommittedReceiptsAreSyncedBeforePendingSnapshotIsReplaced() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            newStore().appendUpsert(openOffer("a", 1));
            byte[] committed = new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME,
                    keyRing.getSymmetricKey(), 3).readAllValidRecords().get(0);
            new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME, keyRing.getSymmetricKey(), 1)
                    .rewrite(List.of(committed, ClosedTradesStore.upsertBytes(openOffer("b", 1))));
            EncryptedAppendLog main = spy(new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME,
                    keyRing.getSymmetricKey(), 3));
            EncryptedAppendLog pending = spy(new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                    keyRing.getSymmetricKey(), 1));
            doThrow(new IllegalStateException("Simulated append failure")).when(main).appendAll(anyList());
            ClosedTradesStore store = newStore();
            setLog(store, "appendLog", main);
            setLog(store, "pendingLog", pending);

            assertEquals(List.of("a", "b"), ids(store.load()));

            InOrder inOrder = inOrder(main, pending);
            inOrder.verify(main).sync();
            inOrder.verify(pending).rewrite(anyList());
            assertEquals(1, pending.readAllValidRecords().size());
        }
    }

    private static void setLog(ClosedTradesStore store, String name, EncryptedAppendLog log) throws Exception {
        var field = ClosedTradesStore.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(store, log);
    }

    @Test
    public void testLaterIdenticalDeleteIsStillRecovered() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            ClosedTradesStore store = newStore();
            store.appendUpsert(openOffer("same", 1));
            store.appendDelete("same");
            store.appendUpsert(openOffer("same", 2));
            EncryptedAppendLog main = spy(new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME,
                    keyRing.getSymmetricKey(), 3));
            setLog(store, "appendLog", main);
            doThrow(new IllegalStateException("Simulated append failure")).when(main).appendAll(anyList());

            store.appendDelete("same");

            assertTrue(newStore().load().isEmpty(), "equal payloads can represent separate mutations");
        }
    }

    @Test
    public void testPartialBatchRestartPreservesReaddedTradeOrder() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            ClosedTradesStore store = newStore();
            store.appendUpsert(openOffer("a", 0));
            store.appendUpsert(openOffer("b", 0));
            EncryptedAppendLog main = spy(new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME,
                    keyRing.getSymmetricKey(), 3));
            setLog(store, "appendLog", main);
            doThrow(new IllegalStateException("Simulated append failure")).when(main).appendAll(anyList());
            store.appendEntries(List.of(ClosedTradesStore.deleteBytes("a"),
                    ClosedTradesStore.upsertBytes(openOffer("a", 1)),
                    ClosedTradesStore.upsertBytes(openOffer("c", 0))));
            doAnswer(invocation -> {
                List<byte[]> batch = invocation.getArgument(0);
                new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME, keyRing.getSymmetricKey(), 3)
                        .appendAll(batch.subList(0, 2));
                throw new OutOfMemoryError("Simulated process termination during batch append");
            }).when(main).appendAll(anyList());
            assertThrows(OutOfMemoryError.class, store::flushFailedEntries);

            assertEquals(List.of("b", "a", "c"), ids(newStore().load()));
            assertEquals(List.of("b", "a", "c"), ids(newStore().load()));
            assertEquals(5, new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME,
                    keyRing.getSymmetricKey(), 3).readAllValidRecords().size());
        }
    }

    @Test
    public void testFailedPendingClearDefersCompactionUntilRestartRecovery() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            ClosedTradesStore store = newStore();
            EncryptedAppendLog main = spy(new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME,
                    keyRing.getSymmetricKey(), 3));
            EncryptedAppendLog pending = spy(new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                    keyRing.getSymmetricKey(), 1));
            setLog(store, "appendLog", main);
            setLog(store, "pendingLog", pending);
            doThrow(new IllegalStateException("Simulated append failure"))
                    .doCallRealMethod().when(main).appendAll(anyList());
            doThrow(new IllegalStateException("Simulated queue clear failure"))
                    .when(pending).rewrite(List.of());
            store.appendUpsert(openOffer("same", 0));
            for (int i = 1; i <= 600; i++) store.appendUpsert(openOffer("same", i));

            ClosedTradesStore reader = newStore();
            setLog(reader, "appendLog", main);
            setLog(reader, "pendingLog", pending);
            List<Tradable> loaded = reader.load();
            assertEquals(600, ((OpenOffer) loaded.get(0)).getTriggerPrice());
            verify(main, never()).rewrite(anyList());

            assertEquals(600, ((OpenOffer) newStore().load().get(0)).getTriggerPrice());
            assertEquals(1, new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME,
                    keyRing.getSymmetricKey(), 3).readAllValidRecords().size());
            assertEquals(600, ((OpenOffer) newStore().load().get(0)).getTriggerPrice());
        }
    }

    @Test
    public void testLegacyPendingIdentitiesAreDurableBeforeAppend() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            EncryptedAppendLog pending = spy(new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                    keyRing.getSymmetricKey(), 1));
            pending.rewrite(List.of(protobuf.TradableLogEntry.newBuilder()
                    .setUpsert((protobuf.Tradable) openOffer("same", 1).toProtoMessage()).build().toByteArray()));
            EncryptedAppendLog main = spy(new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME,
                    keyRing.getSymmetricKey(), 3));
            doAnswer(invocation -> {
                List<byte[]> batch = invocation.getArgument(0);
                String durableId = protobuf.TradableLogEntry.parseFrom(pending.readAllValidRecords().get(0)).getMutationId();
                assertFalse(durableId.isEmpty());
                assertEquals(durableId, protobuf.TradableLogEntry.parseFrom(batch.get(0)).getMutationId());
                return invocation.callRealMethod();
            }).when(main).appendAll(anyList());
            doThrow(new OutOfMemoryError("Simulated process termination before queue clear"))
                    .when(pending).rewrite(List.of());
            ClosedTradesStore store = newStore();
            setLog(store, "appendLog", main);
            setLog(store, "pendingLog", pending);

            assertThrows(OutOfMemoryError.class, store::load);

            ClosedTradesStore restarted = newStore();
            restarted.appendUpsert(openOffer("same", 2));
            assertEquals(2, ((OpenOffer) newStore().load().get(0)).getTriggerPrice());
        }
    }

    @Test
    public void testFailedLegacyPendingMigrationDefersAppendWithoutHidingHistory() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            newStore().appendUpsert(openOffer("main", 0));
            EncryptedAppendLog pending = spy(new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                    keyRing.getSymmetricKey(), 1));
            pending.rewrite(List.of(protobuf.TradableLogEntry.newBuilder()
                    .setUpsert((protobuf.Tradable) openOffer("queued", 1).toProtoMessage()).build().toByteArray()));
            byte[] originalPending = Files.readAllBytes(new File(dir, ClosedTradesStore.PENDING_FILE_NAME).toPath());
            doThrow(new IllegalStateException("Simulated legacy queue rewrite failure"))
                    .when(pending).rewrite(anyList());
            ClosedTradesStore store = newStore();
            setLog(store, "pendingLog", pending);

            assertEquals(List.of("main", "queued"), ids(store.load()));
            assertEquals(1, new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME,
                    keyRing.getSymmetricKey(), 3).readAllValidRecords().size());
            assertArrayEquals(originalPending,
                    Files.readAllBytes(new File(dir, ClosedTradesStore.PENDING_FILE_NAME).toPath()));
            doCallRealMethod().when(pending).rewrite(anyList());
            store.appendUpsert(openOffer("queued", 2));

            assertEquals(2, ((OpenOffer) newStore().load().get(1)).getTriggerPrice());
        }
    }

    @Test
    public void testAppendBeforeLoadRecoversOlderPendingFirst() throws Exception {
        new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME, keyRing.getSymmetricKey(), 1)
                .rewrite(List.of(ClosedTradesStore.upsertBytes(openOffer("same", 1))));

        newStore().appendUpsert(openOffer("same", 2));

        assertEquals(2, ((OpenOffer) newStore().load().get(0)).getTriggerPrice());
    }

    @Test
    public void testFailedClearIsRetriedWithEmptyMemoryQueue() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            EncryptedAppendLog pending = spy(new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                    keyRing.getSymmetricKey(), 1));
            pending.rewrite(List.of(ClosedTradesStore.upsertBytes(openOffer("queued", 1))));
            doThrow(new IllegalStateException("Simulated queue clear failure"))
                    .doCallRealMethod().when(pending).rewrite(List.of());
            ClosedTradesStore store = newStore();
            setLog(store, "pendingLog", pending);
            store.load();
            assertEquals(1, pending.readAllValidRecords().size());
            ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
            userThread.verify(() -> UserThread.runAfter(retry.capture(), eq(30L)));

            retry.getValue().run();

            assertTrue(pending.readAllValidRecords().isEmpty());
            assertEquals(List.of("queued"), ids(newStore().load()));
        }
    }

    @Test
    public void testOlderPendingSnapshotCannotOverwriteNewerBatchOnRestart() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            ClosedTradesStore store = newStore();
            EncryptedAppendLog main = spy(new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME,
                    keyRing.getSymmetricKey(), 3));
            EncryptedAppendLog pending = spy(new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                    keyRing.getSymmetricKey(), 1));
            setLog(store, "appendLog", main);
            setLog(store, "pendingLog", pending);
            doThrow(new IllegalStateException("Simulated append failure"))
                    .doThrow(new IllegalStateException("Simulated repeated append failure"))
                    .doCallRealMethod().when(main).appendAll(anyList());
            store.appendUpsert(openOffer("same", 1));
            doThrow(new IllegalStateException("Simulated pending rewrite failure")).when(pending).rewrite(anyList());
            store.appendUpsert(openOffer("same", 2));
            store.appendUpsert(openOffer("same", 3));

            assertEquals(3, ((OpenOffer) newStore().load().get(0)).getTriggerPrice());
        }
    }

    @Test
    public void testMainReadFailureStillDurablyQueuesNewWritesAlongsideOldPending() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            ClosedTradesStore writer = newStore();
            writer.appendUpsert(openOffer("committed", 1));
            EncryptedAppendLog main = spy(new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME,
                    keyRing.getSymmetricKey(), 3));
            byte[] committed = main.readAllValidRecords().get(0);
            writer.appendUpsert(openOffer("committed", 2));
            EncryptedAppendLog pending = new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                    keyRing.getSymmetricKey(), 1);
            pending.rewrite(List.of(committed, protobuf.TradableLogEntry.newBuilder()
                    .setUpsert((protobuf.Tradable) openOffer("queued", 1).toProtoMessage()).build().toByteArray()));
            doThrow(new IllegalStateException("Simulated main-log read failure")).when(main).readAllValidRecords();
            ClosedTradesStore store = newStore();
            setLog(store, "appendLog", main);

            store.appendUpsert(openOffer("new", 3));

            assertEquals(3, pending.readAllValidRecords().size());
            assertFalse(protobuf.TradableLogEntry.parseFrom(pending.readAllValidRecords().get(1)).getMutationId().isEmpty());
            doCallRealMethod().when(main).readAllValidRecords();
            store.appendUpsert(openOffer("next", 4));
            List<Tradable> loaded = newStore().load();
            assertEquals(List.of("committed", "queued", "new", "next"), ids(loaded));
            assertEquals(2, ((OpenOffer) loaded.get(0)).getTriggerPrice());
            assertEquals(3, ((OpenOffer) loaded.get(2)).getTriggerPrice());
        }
    }

    @Test
    public void testMainReadFailureCanCreateFirstDurableQueue() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            ClosedTradesStore store = newStore();
            EncryptedAppendLog main = mock(EncryptedAppendLog.class);
            doThrow(new IllegalStateException("Simulated main-log read failure")).when(main).readAllValidRecords();
            setLog(store, "appendLog", main);

            store.appendUpsert(openOffer("new", 3));

            assertEquals(List.of("new"), ids(newStore().load()));
        }
    }

    @Test
    public void testEmptyPendingFileDoesNotNeedClearingOnStartup() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            EncryptedAppendLog pending = spy(new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                    keyRing.getSymmetricKey(), 1));
            new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME, keyRing.getSymmetricKey(), 1).rewrite(List.of());
            ClosedTradesStore store = newStore();
            setLog(store, "pendingLog", pending);

            assertTrue(store.load().isEmpty());

            verify(pending, never()).rewrite(anyList());
            userThread.verifyNoInteractions();
        }
    }

    @Test
    public void testIdleShutdownFlushDoesNotInitializeLockedStore() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            ClosedTradesStore store = newStore();
            EncryptedAppendLog main = mock(EncryptedAppendLog.class);
            EncryptedAppendLog pending = mock(EncryptedAppendLog.class);
            setLog(store, "appendLog", main);
            setLog(store, "pendingLog", pending);
            keyRing.lockKeys();

            store.flushFailedEntries();

            verifyNoInteractions(main, pending);
            userThread.verifyNoInteractions();
        }
    }

    @Test
    public void testShouldCompactThresholds() {
        assertFalse(ClosedTradesStore.shouldCompact(512, 1), "at the floor, do not compact");
        assertTrue(ClosedTradesStore.shouldCompact(513, 1), "just past the floor, compact");
        assertFalse(ClosedTradesStore.shouldCompact(1000, 600), "ratio not exceeded -> no compaction");
        assertTrue(ClosedTradesStore.shouldCompact(1300, 600), "ratio exceeded -> compaction");
    }
}
