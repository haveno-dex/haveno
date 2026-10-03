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
import com.google.protobuf.UnknownFieldSet;
import haveno.common.UserThread;
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
import haveno.core.payment.ZelleAccount;
import haveno.core.proto.persistable.CorePersistenceProtoResolver;
import haveno.core.trade.protocol.ProcessModel;
import haveno.core.trade.failed.FailedTradesManager;
import haveno.core.user.Preferences;
import haveno.core.xmr.wallet.BtcWalletService;
import haveno.core.xmr.wallet.XmrWalletService;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
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
        return newStore(null);
    }

    private ClosedTradesStore newStore(XmrWalletService walletService) {
        Provider<XmrWalletService> xmr = () -> walletService;
        CorePersistenceProtoResolver storeResolver = new CorePersistenceProtoResolver(() -> null, xmr, null);
        return new ClosedTradesStore(dir, keyRing, storeResolver, xmr, corruptedStorageFileHandler,
                new PersistenceManager<>(dir, storeResolver, null, keyRing));
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
    private void writeLegacyFile(Tradable... tradables) throws Exception {
        TradableList<Tradable> legacy = new TradableList<>();
        for (Tradable tradable : tradables) legacy.add(tradable);
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
    public void testMigrationPreservesCanceledOfferAndTradeWithSameId() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            OpenOffer offer = openOffer("handoff", 0);
            offer.setState(OpenOffer.State.CANCELED);
            Trade trade = handoffTrade();
            trade.setCompleted(true);
            writeLegacyFile(offer, trade);
            XmrWalletService walletService = mock(XmrWalletService.class);
            assertEquals(List.of(OpenOffer.class, BuyerAsMakerTrade.class),
                    newStore(walletService).load().stream().map(Object::getClass).toList());
            assertEquals(List.of(OpenOffer.class, BuyerAsMakerTrade.class),
                    newStore(walletService).load().stream().map(Object::getClass).toList());
        }
    }

    @Test
    public void testLogPreservesCanceledOfferAndTradeWithSameId() {
        try (var userThread = mockStatic(UserThread.class)) {
            XmrWalletService walletService = mock(XmrWalletService.class);
            ClosedTradesStore store = newStore(walletService);
            OpenOffer offer = openOffer("handoff", 0);
            offer.setState(OpenOffer.State.CANCELED);
            store.appendUpsert(offer);
            Trade trade = handoffTrade();
            trade.setCompleted(true);
            store.appendUpsert(trade);
            for (int i = 0; i < 600; i++) store.appendUpsert(offer);
            assertEquals(List.of(OpenOffer.class, BuyerAsMakerTrade.class),
                    newStore(walletService).load().stream().map(Object::getClass).toList());
            assertEquals(List.of(OpenOffer.class, BuyerAsMakerTrade.class),
                    newStore(walletService).load().stream().map(Object::getClass).toList());
            assertEquals(2, rawLog().readAllValidRecords().size());
        }
    }

    @Test
    public void testRemovingTradeKeepsCanceledOfferWithSameId() throws Exception {
        assertTypedRemovalPreservesCounterpart(true);
    }

    @Test
    public void testRemovingCanceledOfferKeepsTradeWithSameId() throws Exception {
        assertTypedRemovalPreservesCounterpart(false);
    }

    private void assertTypedRemovalPreservesCounterpart(boolean removeTrade) throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            XmrWalletService walletService = mock(XmrWalletService.class);
            ClosedTradesStore store = newStore(walletService);
            store.load();
            ClosedTradableManager manager = new ClosedTradableManager(null, null, mock(Preferences.class), null,
                    store, corruptedStorageFileHandler, null);
            OpenOffer offer = openOffer("handoff", 0);
            offer.setState(OpenOffer.State.CANCELED);
            Trade trade = handoffTrade();
            trade.setCompleted(true);
            manager.add(offer);
            manager.add(trade);
            manager.remove(removeTrade ? trade : offer);
            store.requestRedaction();
            store.flushRedaction();
            writeLegacyFile(offer, trade);
            for (int i = 0; i < 2; i++) {
                List<Tradable> loaded = newStore(walletService).load();
                assertEquals(1, loaded.size());
                assertEquals(removeTrade ? OpenOffer.class : BuyerAsMakerTrade.class, loaded.getFirst().getClass());
            }
            assertEquals(2, rawLog().readAllValidRecords().size());
        }
    }

    @Test
    public void testLegacyDeletionPrecedesReaddedTradeAfterCompaction() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            XmrWalletService walletService = mock(XmrWalletService.class);
            ClosedTradesStore store = newStore(walletService);
            store.load();
            OpenOffer offer = openOffer("handoff", 0);
            Trade first = handoffTrade();
            Trade second = handoffTrade("second-attempt");
            store.appendUpsert(offer);
            store.appendUpsert(first);
            store.appendUpsert(second);
            store.appendDelete("handoff");
            store.appendUpsert(second);
            store.requestRedaction();
            store.flushRedaction();
            assertEquals("handoff", protobuf.TradableLogEntry.parseFrom(rawLog().readAllValidRecords().getFirst()).getDeleteId());
            writeLegacyFile(offer, first, second);
            List<Tradable> loaded = newStore(walletService).load();
            assertEquals(1, loaded.size());
            assertEquals(second.getUid(), ((Trade) loaded.getFirst()).getUid());
            assertEquals(2, rawLog().readAllValidRecords().size());
        }
    }

    @Test
    public void testTradeAttemptsWithSameOfferIdKeepSeparateHistory() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            XmrWalletService walletService = mock(XmrWalletService.class);
            Trade first = handoffTrade();
            Trade second = handoffTrade("second-attempt");
            writeLegacyFile(first, second);
            ClosedTradesStore store = newStore(walletService);
            assertEquals(List.of(first.getUid(), second.getUid()),
                    store.load().stream().map(value -> ((Trade) value).getUid()).toList());
            store.appendUpsert(first);
            store.requestRedaction();
            store.flushRedaction();
            assertEquals(List.of(first.getUid(), second.getUid()),
                    newStore(walletService).load().stream().map(value -> ((Trade) value).getUid()).toList());
            store.appendEntries(List.of(ClosedTradesStore.deleteBytes(first)));
            store.requestRedaction();
            store.flushRedaction();
            writeLegacyFile(first, second);
            List<Tradable> loaded = newStore(walletService).load();
            assertEquals(1, loaded.size());
            assertEquals(second.getUid(), ((Trade) loaded.getFirst()).getUid());
        }
    }

    @Test
    public void testPendingTypedDeletionKeepsOtherTradeAttempt() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            XmrWalletService walletService = mock(XmrWalletService.class);
            ClosedTradesStore store = newStore(walletService);
            Trade first = handoffTrade();
            Trade second = handoffTrade("second-attempt");
            store.appendUpsert(first);
            store.appendUpsert(second);
            EncryptedAppendLog pending = new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME, keyRing.getSymmetricKey(), 1);
            pending.appendAll(List.of(ClosedTradesStore.deleteBytes(first),
                    protobuf.TradableLogEntry.newBuilder().setDeleteId("unrelated").build().toByteArray()));
            List<Tradable> loaded = newStore(walletService).load();
            assertEquals(1, loaded.size());
            assertEquals(second.getUid(), ((Trade) loaded.getFirst()).getUid());
            assertTrue(pending.readAllValidRecords().isEmpty());
            assertEquals(second.getUid(), ((Trade) newStore(walletService).load().getFirst()).getUid());
        }
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
    public void testManagerClearsPaymentDetailsFromPersistedTradeHistory() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            XmrWalletService walletService = mock(XmrWalletService.class);
            ClosedTradesStore store = new ClosedTradesStore(dir, keyRing, resolver, () -> walletService,
                    corruptedStorageFileHandler, new PersistenceManager<>(dir, resolver, null, keyRing));
            store.load();
            ZelleAccount account = new ZelleAccount();
            account.init();
            account.setHolderName("Sensitive account holder");
            account.setEmailOrMobileNr("sensitive-payment-detail@example.com");
            ProcessModel processModel = new ProcessModel("trade", "account", keyRing.getPubKeyRing());
            BuyerAsMakerTrade trade = new BuyerAsMakerTrade(openOffer("trade", 0).getOffer(), BigInteger.ONE, 100,
                    walletService, processModel, "trade-uid", null, null, null, null);
            trade.setTakeOfferDate(0);
            trade.getMaker().setPaymentAccountPayload(account.getPaymentAccountPayload());
            store.appendUpsert(trade);
            assertTrue(protobuf.TradableLogEntry.parseFrom(rawLog().readAllValidRecords().get(0))
                    .getUpsert().getBuyerAsMakerTrade().getTrade().getProcessModel().getMaker().hasPaymentAccountPayload());
            ClosedTradableManager manager = new ClosedTradableManager(null, null, mock(Preferences.class), null,
                    store, corruptedStorageFileHandler, null);
            manager.getObservableList().add(trade);

            manager.maybeClearSensitiveData();
            manager.shutDown();

            assertNull(trade.getMaker().getPaymentAccountPayload());
            List<byte[]> records = rawLog().readAllValidRecords();
            assertEquals(1, records.size());
            assertFalse(protobuf.TradableLogEntry.parseFrom(records.get(0))
                    .getUpsert().getBuyerAsMakerTrade().getTrade().getProcessModel().getMaker().hasPaymentAccountPayload());
            ClosedTradesStore restarted = new ClosedTradesStore(dir, keyRing, resolver, () -> walletService,
                    corruptedStorageFileHandler, new PersistenceManager<>(dir, resolver, null, keyRing));
            Trade restored = (Trade) restarted.load().get(0);
            assertNull(restored.getMaker().getPaymentAccountPayload());
        }
    }

    @Test
    public void testRedactionKeepsLatestRawRecordsAndTombstones() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            ClosedTradesStore store = newStore();
            store.load();
            store.appendUpsert(openOffer("deleted", 99));
            store.appendUpsert(openOffer("keep", 99));
            store.appendDelete("deleted");
            byte[] cleared = protobuf.TradableLogEntry.parseFrom(ClosedTradesStore.upsertBytes(openOffer("keep", 0)))
                    .toBuilder().setUnknownFields(UnknownFieldSet.newBuilder()
                            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(123).build()).build())
                    .build().toByteArray();
            store.appendEntries(List.of(cleared));

            store.requestRedaction();
            store.flushRedaction();

            List<byte[]> records = rawLog().readAllValidRecords();
            assertEquals(2, records.size());
            assertArrayEquals(cleared, records.get(1), "retain raw fields and mutation identity");
            assertEquals("deleted", protobuf.TradableLogEntry.parseFrom(records.get(0)).getDeleteId());
            assertEquals(1, FileUtil.getBackupFiles(dir, ClosedTradesStore.LOG_FILE_NAME).size());
            writeLegacyFile(openOffer("deleted", 99));
            assertEquals(List.of("keep"), ids(newStore().load()), "redaction must retain deletion markers");
        }
    }

    @Test
    public void testManagerDoesNotRewriteWithoutClearedData() {
        try (var userThread = mockStatic(UserThread.class)) {
            ClosedTradesStore store = newStore();
            store.appendUpsert(openOffer("keep", 99));
            store.appendUpsert(openOffer("keep", 0));
            ClosedTradesStore restarted = newStore();
            restarted.load();
            ClosedTradableManager manager = new ClosedTradableManager(null, null, mock(Preferences.class), null,
                    restarted, corruptedStorageFileHandler, null);

            manager.maybeClearSensitiveData();
            manager.shutDown();

            assertEquals(2, rawLog().readAllValidRecords().size());
        }
    }

    @Test
    public void testRedactionWaitsForDurablePendingClear() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            ClosedTradesStore store = newStore();
            store.load();
            store.appendUpsert(openOffer("same", 99));
            EncryptedAppendLog main = spy(rawLog());
            EncryptedAppendLog pending = spy(new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME,
                    keyRing.getSymmetricKey(), 1));
            setLog(store, "appendLog", main);
            setLog(store, "pendingLog", pending);
            doThrow(new IllegalStateException("Simulated append failure")).doCallRealMethod().when(main).appendAll(anyList());
            doThrow(new IllegalStateException("Simulated pending clear failure")).when(pending).rewrite(List.of());
            store.appendUpsert(openOffer("same", 0));
            store.requestRedaction();
            store.flushFailedEntries();
            store.flushRedaction();
            assertEquals(2, rawLog().readAllValidRecords().size());
            verify(main, never()).rewrite(anyList());

            doCallRealMethod().when(pending).rewrite(List.of());
            store.flushFailedEntries();
            store.flushRedaction();
            assertEquals(1, rawLog().readAllValidRecords().size());
            assertEquals(0, ((OpenOffer) newStore().load().get(0)).getTriggerPrice());
        }
    }

    @Test
    public void testRedactionRetriesTransientFailureWithoutAnotherWrite() throws Exception {
        try (var userThread = mockStatic(UserThread.class);
             var threads = mockConstruction(Thread.class, (thread, context) ->
                     doAnswer(invocation -> {
                         ((Runnable) context.arguments().get(0)).run();
                         return null;
                     }).when(thread).start())) {
            ClosedTradesStore store = newStore();
            store.load();
            store.appendUpsert(openOffer("same", 99));
            store.appendUpsert(openOffer("same", 0));
            EncryptedAppendLog main = spy(rawLog());
            setLog(store, "appendLog", main);
            doThrow(new IllegalStateException("Simulated rewrite failure")).doCallRealMethod().when(main).rewrite(anyList());
            store.requestRedaction();
            ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
            userThread.verify(() -> UserThread.runAfter(retry.capture(), eq(30L)));

            retry.getValue().run();

            assertEquals(2, rawLog().readAllValidRecords().size());
            userThread.verify(() -> UserThread.runAfter(retry.capture(), eq(30L)), times(2));
            retry.getValue().run();
            assertEquals(1, rawLog().readAllValidRecords().size());
            assertEquals(0, ((OpenOffer) newStore().load().get(0)).getTriggerPrice());
            userThread.verify(() -> UserThread.runAfter(any(Runnable.class), eq(30L)), times(2));
        }
    }

    @Test
    public void testRedactionSkipsUndecodableHistory() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            ClosedTradesStore store = newStore();
            store.appendUpsert(openOffer("keep", 99));
            rawLog().append(new byte[]{0x0a});
            store = newStore();
            store.load();
            store.appendUpsert(openOffer("keep", 0));
            store.requestRedaction();
            store.flushRedaction();
            assertEquals(3, rawLog().readAllValidRecords().size());
        }
    }

    @Test
    public void testRedactionDoesNotRunWithoutLoad() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            ClosedTradesStore store = newStore();
            EncryptedAppendLog main = mock(EncryptedAppendLog.class);
            setLog(store, "appendLog", main);
            doThrow(new OutOfMemoryError("Simulated load failure")).when(main).readAllValidRecords();
            assertThrows(OutOfMemoryError.class, store::load);
            store.requestRedaction();
            store.flushRedaction();
            verify(main).readAllValidRecords();
            verify(main, never()).rewrite(anyList());
            userThread.verifyNoInteractions();
        }
    }

    @Test
    public void testLoadRedactsSupersededTradeRecords() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            XmrWalletService walletService = mock(XmrWalletService.class);
            BuyerAsMakerTrade trade = new BuyerAsMakerTrade(openOffer("trade", 0).getOffer(), BigInteger.ONE, 100,
                    walletService, new ProcessModel("trade", "account", keyRing.getPubKeyRing()), "trade-uid", null, null, null, null);
            ClosedTradesStore store = newStore(walletService);
            store.appendUpsert(trade);
            store.appendUpsert(trade); // e.g. a cleared snapshot whose redaction was interrupted
            ClosedTradesStore restarted = newStore(walletService);

            restarted.load();
            userThread.verify(() -> UserThread.runAfter(any(Runnable.class), eq(30L)));
            assertEquals(2, rawLog().readAllValidRecords().size());
            restarted.flushRedaction();

            assertEquals(1, rawLog().readAllValidRecords().size());
            assertEquals(List.of("trade"), ids(newStore(walletService).load()));
        }
    }

    private EncryptedAppendLog rawLog() {
        return new EncryptedAppendLog(dir, ClosedTradesStore.LOG_FILE_NAME, keyRing.getSymmetricKey(), 0);
    }

    @Test
    public void testShouldCompactThresholds() {
        assertFalse(ClosedTradesStore.shouldCompact(512, 1), "at the floor, do not compact");
        assertTrue(ClosedTradesStore.shouldCompact(513, 1), "just past the floor, compact");
        assertFalse(ClosedTradesStore.shouldCompact(1000, 600), "ratio not exceeded -> no compaction");
        assertTrue(ClosedTradesStore.shouldCompact(1300, 600), "ratio exceeded -> compaction");
    }

    @Test
    public void testCompletionRevisionRoundTripsAndLegacyRecordsRemainUnmarked() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            Trade trade = handoffTrade();
            trade.setCompleted(true);
            trade.setCompleted(false);
            assertEquals(2, trade.getCompletedRevision());
            protobuf.BuyerAsMakerTrade proto = ((protobuf.Tradable) trade.toProtoMessage()).getBuyerAsMakerTrade();
            Trade restored = (Trade) BuyerAsMakerTrade.fromProto(proto, mock(XmrWalletService.class), resolver);
            assertFalse(restored.isCompleted());
            assertEquals(2, restored.getCompletedRevision());
            restored.setCompleted(true);
            assertEquals(3, restored.getCompletedRevision());

            Trade legacy = (Trade) BuyerAsMakerTrade.fromProto(proto.toBuilder()
                    .setTrade(proto.getTrade().toBuilder().clearCompletedRevision()).build(), mock(XmrWalletService.class), resolver);
            assertEquals(0, legacy.getCompletedRevision());
            legacy.setCompleted(false);
            assertEquals(1, legacy.getCompletedRevision());
        }
    }

    @Test
    public void testDuplicateResolutionKeepsNewestCloseOrReopenInEitherOrder() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            for (boolean pendingFirst : List.of(false, true)) {
                for (int scenario = 0; scenario < 4; scenario++) {
                    Trade closed = handoffTrade();
                    Trade pending = handoffTrade();
                    if (scenario != 3) closed.setCompleted(true);
                    if (scenario == 1 || scenario == 2) {
                        pending.setCompleted(true);
                        pending.setCompleted(false);
                    }
                    if (scenario == 2) closed.setCompleted(false);
                    ClosedTradableManager closedManager = handoffClosedManager(closed);
                    TradeManager manager = handoffManager(closedManager, mock(PersistenceManager.class));
                    manager.getObservableList().add(pending);
                    List<Trade> trades = new ArrayList<>(pendingFirst ? List.of(pending, closed) : List.of(closed, pending));
                    var deduplicate = TradeManager.class.getDeclaredMethod("removeDuplicateTrades", List.class);
                    deduplicate.setAccessible(true);
                    deduplicate.invoke(manager, trades);
                    Trade expected = scenario == 1 || scenario == 2 ? pending : closed;
                    assertEquals(1, trades.size());
                    assertSame(expected, trades.get(0));
                    assertEquals(expected == pending, manager.getObservableList().contains(pending));
                    assertEquals(expected == closed, closedManager.getClosedTrades().contains(closed));
                }
            }
        }
    }

    @Test
    public void testStartupResumesMarkedReopenWithoutMovingLegacyOrCompletedTrades() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            Trade reopened = handoffTrade();
            reopened.setCompleted(true);
            reopened.setCompleted(false);
            Trade legacy = handoffTrade();
            Trade completed = handoffTrade();
            completed.setCompleted(true);
            ClosedTradableManager closed = handoffClosedManager(reopened, legacy, completed);
            PersistenceManager<TradableList<Trade>> persistence = mock(PersistenceManager.class);
            TradeManager manager = handoffManager(closed, persistence);
            resumeTradeMoves(manager);
            assertEquals(List.of(reopened), manager.getObservableList());
            assertEquals(List.of(legacy, completed), closed.getClosedTrades());
            InOrder order = inOrder(persistence, closed);
            order.verify(persistence).persistNowAndWait();
            order.verify(closed).removeTrade(reopened);
        }
    }

    @Test
    public void testFailedPendingWriteRetainsClosedCopyUntilSuccessfulRetry() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            Trade trade = spy(handoffTrade());
            trade.setCompleted(true);
            doReturn(true).when(trade).isInitialized();
            ClosedTradableManager closed = handoffClosedManager(trade);
            PersistenceManager<TradableList<Trade>> persistence = mock(PersistenceManager.class);
            doThrow(new IllegalStateException("write failed")).when(persistence).persistNowAndWait();
            TradeManager manager = handoffManager(closed, persistence);

            manager.onMoveClosedTradeToPendingTrades(trade);

            assertFalse(trade.isCompleted());
            assertEquals(2, trade.getCompletedRevision());
            assertTrue(manager.getObservableList().contains(trade));
            verify(closed, never()).removeTrade(trade);
            InOrder order = inOrder(closed, persistence);
            order.verify(closed).persistClosedTrade(trade);
            order.verify(persistence).persistNowAndWait();

            doNothing().when(persistence).persistNowAndWait();
            userThread.verify(() -> UserThread.runAfter(any(Runnable.class), eq(30L)));
            manager.retryReopenedTrade(trade);
            assertTrue(closed.getClosedTrades().isEmpty());
            assertEquals(List.of(trade), manager.getObservableList());
        }
    }

    @Test
    public void testFailedReopenRoutesUpdatesToActiveStoreAndCompletionBackToClosed() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            Trade trade = spy(handoffTrade());
            trade.setCompleted(true);
            doReturn(true).when(trade).isInitialized();
            ClosedTradableManager closed = handoffClosedManager(trade);
            PersistenceManager<TradableList<Trade>> persistence = mock(PersistenceManager.class);
            doThrow(new IllegalStateException("write failed")).when(persistence).persistNowAndWait();
            TradeManager manager = handoffManager(closed, persistence);
            manager.onMoveClosedTradeToPendingTrades(trade);

            manager.requestPersistence(trade);
            Runnable callback = mock(Runnable.class);
            manager.persistNow(trade, callback);
            verify(persistence).persistNow(callback);
            verify(closed).persistClosedTrade(trade); // only the recovery marker

            manager.onTradeCompleted(trade);
            manager.requestPersistence(trade);
            manager.persistNow(trade, callback);
            verify(closed, times(3)).persistClosedTrade(trade);
            verify(callback).run();
            manager.retryReopenedTrade(trade);
            verify(closed, never()).removeTrade(trade);
            verify(persistence).persistNowAndWait(); // the delayed retry must not undo completion
        }
    }

    @Test
    public void testFailedReopenKeepsLatestDurableStateAfterRestart() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            XmrWalletService walletService = mock(XmrWalletService.class);
            CorePersistenceProtoResolver tradeResolver = new CorePersistenceProtoResolver(() -> null, () -> walletService, null);
            ClosedTradesStore store = newStore(walletService);
            store.load();
            Preferences preferences = mock(Preferences.class);
            when(preferences.getClearDataAfterDays()).thenReturn(Integer.MAX_VALUE);
            ClosedTradableManager closed = new ClosedTradableManager(null, null, preferences, null,
                    store, corruptedStorageFileHandler, null);
            Trade trade = spy(handoffTrade());
            trade.setState(Trade.State.SELLER_CONFIRMED_PAYMENT_RECEIPT);
            trade.setPayoutTxId("original-payout");
            trade.setCompleted(true);
            doReturn(true).when(trade).isInitialized();
            closed.add(trade);
            PersistenceManager<TradableList<Trade>> persistence = spy(new PersistenceManager<>(dir, tradeResolver, null, keyRing));
            TradeManager manager = handoffManager(closed, persistence);
            var list = TradeManager.class.getDeclaredField("tradableList");
            list.setAccessible(true);
            persistence.initialize((TradableList<Trade>) list.get(manager), "PendingTrades", PersistenceManager.Source.PRIVATE);
            File destination = new File(dir, "PendingTrades");
            assertTrue(destination.mkdir()); // inject a real failure replacing the pending file

            manager.onMoveClosedTradeToPendingTrades(trade);
            assertTrue(closed.getClosedTrades().contains(trade));
            assertTrue(destination.delete());
            persistence.persistNowAndWait(); // ordinary pending persistence recovers before handoff cleanup
            doAnswer(invocation -> {
                persistence.persistNowAndWait(); // wait for the routed write before simulating a restart
                return null;
            }).when(persistence).persistNow(null);
            trade.setState(Trade.State.SELLER_SENT_PAYMENT_RECEIVED_MSG);
            trade.setPayoutTxId("replacement-payout");
            manager.persistNow(trade, null);

            Trade pending = persistence.getPersisted().getList().get(0);
            Trade marker = (Trade) newStore(walletService).load().get(0);
            assertEquals(pending.getCompletedRevision(), marker.getCompletedRevision());
            ClosedTradableManager restoredClosed = handoffClosedManager(marker);
            TradeManager restored = handoffManager(restoredClosed, mock(PersistenceManager.class));
            restored.getObservableList().add(pending);
            List<Trade> trades = new ArrayList<>(List.of(marker, pending));
            var deduplicate = TradeManager.class.getDeclaredMethod("removeDuplicateTrades", List.class);
            deduplicate.setAccessible(true);
            deduplicate.invoke(restored, trades);
            assertEquals(1, trades.size());
            assertEquals(Trade.State.SELLER_SENT_PAYMENT_RECEIVED_MSG, trades.get(0).getState());
            assertEquals("replacement-payout", trades.get(0).getPayoutTxId());
        }
    }

    @Test
    public void testFailedStoreOutranksReopenMarkerButNotPendingOnRestart() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            for (boolean failedFirst : List.of(false, true)) {
                for (boolean activePending : List.of(false, true)) {
                    Trade failedTrade = handoffTrade();
                    failedTrade.setCompleted(true);
                    failedTrade.setCompleted(false);
                    Trade other = handoffTrade();
                    other.setCompleted(true);
                    other.setCompleted(false);
                    ClosedTradableManager closed = handoffClosedManager(activePending ? new Trade[0] : new Trade[] {other});
                    PersistenceManager<TradableList<Trade>> pendingPersistence = mock(PersistenceManager.class);
                    PersistenceManager<TradableList<Trade>> failedPersistence = mock(PersistenceManager.class);
                    FailedTradesManager failed = new FailedTradesManager(null, null, null, failedPersistence, null, null);
                    failed.add(failedTrade);
                    TradeManager manager = handoffManager(closed, pendingPersistence, failed);
                    if (activePending) manager.getObservableList().add(other);
                    List<Trade> trades = new ArrayList<>(failedFirst ? List.of(failedTrade, other) : List.of(other, failedTrade));
                    var deduplicate = TradeManager.class.getDeclaredMethod("removeDuplicateTrades", List.class);
                    deduplicate.setAccessible(true);
                    deduplicate.invoke(manager, trades);
                    assertEquals(List.of(activePending ? other : failedTrade), trades);
                    if (!activePending) {
                        Runnable callback = mock(Runnable.class);
                        manager.persistNow(failedTrade, callback);
                        verify(failedPersistence).persistNow(callback);
                        verify(pendingPersistence, never()).persistNow(any());
                    }
                }
            }
        }
    }

    @Test
    public void testReopenRetryDoesNotRemoveRecoveryCopyAfterLeavingPending() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            Trade trade = handoffTrade();
            trade.setCompleted(true);
            trade.setCompleted(false);
            ClosedTradableManager closed = handoffClosedManager(trade);
            PersistenceManager<TradableList<Trade>> persistence = mock(PersistenceManager.class);
            TradeManager manager = handoffManager(closed, persistence);
            manager.retryReopenedTrade(trade);
            verifyNoInteractions(persistence);
            verify(closed, never()).removeTrade(trade);
        }
    }

    @Test
    public void testSensitiveDataClearingSkipsReopenMarkersAndRetainsLegacyBehavior() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            for (boolean reopened : List.of(false, true)) {
                Preferences preferences = mock(Preferences.class);
                when(preferences.getClearDataAfterDays()).thenReturn(30);
                ClosedTradableManager closed = new ClosedTradableManager(null, null, preferences, null,
                        mock(ClosedTradesStore.class), corruptedStorageFileHandler, null);
                Trade trade = handoffTrade();
                trade.setTakeOfferDate(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2));
                ZelleAccount account = new ZelleAccount();
                account.init();
                account.setEmailOrMobileNr("payment@example.com");
                trade.getMaker().setPaymentAccountPayload(account.getPaymentAccountPayload());
                if (reopened) {
                    trade.setCompleted(true);
                    trade.setCompleted(false);
                }
                closed.add(trade);
                when(preferences.getClearDataAfterDays()).thenReturn(1);
                closed.maybeClearSensitiveData();
                assertEquals(reopened, trade.getMaker().getPaymentAccountPayload() != null);
            }
        }
    }

    @Test
    public void testSensitiveDataClearingUsesEachTradesOwnDate() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            Preferences preferences = mock(Preferences.class);
            when(preferences.getClearDataAfterDays()).thenReturn(1);
            ClosedTradableManager closed = new ClosedTradableManager(null, null, preferences, null,
                    mock(ClosedTradesStore.class), corruptedStorageFileHandler, null);
            Trade oldTrade = handoffTrade("old-attempt");
            oldTrade.setTakeOfferDate(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2));
            Trade recentTrade = handoffTrade("recent-attempt");
            recentTrade.setTakeOfferDate(System.currentTimeMillis());
            ZelleAccount account = new ZelleAccount();
            account.init();
            account.setEmailOrMobileNr("payment@example.com");
            oldTrade.getMaker().setPaymentAccountPayload(account.getPaymentAccountPayload());
            recentTrade.getMaker().setPaymentAccountPayload(account.getPaymentAccountPayload());
            closed.add(openOffer("handoff", 0));
            closed.add(oldTrade);
            closed.add(recentTrade);
            closed.maybeClearSensitiveData();
            assertNull(oldTrade.getMaker().getPaymentAccountPayload());
            assertSame(account.getPaymentAccountPayload(), recentTrade.getMaker().getPaymentAccountPayload());
        }
    }

    @Test
    public void testReopenCannotDeleteConcurrentCompletion() throws Exception {
        assertConcurrentCompletion(false);
        assertConcurrentCompletion(true);
    }

    private void assertConcurrentCompletion(boolean startup) throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            Trade trade = spy(handoffTrade());
            trade.setCompleted(true);
            if (startup) trade.setCompleted(false);
            doReturn(true).when(trade).isInitialized();
            ClosedTradableManager closed = handoffClosedManager(trade);
            PersistenceManager<TradableList<Trade>> persistence = mock(PersistenceManager.class);
            CountDownLatch saving = new CountDownLatch(1);
            CountDownLatch finishSaving = new CountDownLatch(1);
            doAnswer(invocation -> {
                saving.countDown();
                assertTrue(finishSaving.await(10, TimeUnit.SECONDS));
                return null;
            }).when(persistence).persistNowAndWait();
            TradeManager manager = handoffManager(closed, persistence);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var reopen = executor.submit(() -> {
                    if (startup) resumeTradeMoves(manager);
                    else manager.onMoveClosedTradeToPendingTrades(trade);
                    return null;
                });
                try {
                    assertTrue(saving.await(5, TimeUnit.SECONDS));
                    CountDownLatch completing = new CountDownLatch(1);
                    var complete = executor.submit(() -> {
                        completing.countDown();
                        manager.onTradeCompleted(trade);
                    });
                    assertTrue(completing.await(5, TimeUnit.SECONDS));
                    try {
                        assertThrows(TimeoutException.class, () -> complete.get(100, TimeUnit.MILLISECONDS));
                    } finally {
                        finishSaving.countDown();
                    }
                    reopen.get(5, TimeUnit.SECONDS);
                    complete.get(5, TimeUnit.SECONDS);
                    assertTrue(trade.isCompleted());
                    assertEquals(List.of(trade), closed.getClosedTrades());
                    assertTrue(manager.getObservableList().isEmpty());
                } finally {
                    finishSaving.countDown();
                }
            }
        }
    }

    @Test
    public void testRecompletionPersistsWhenFailedReopenLeftTradeInClosedList() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            XmrWalletService walletService = mock(XmrWalletService.class);
            ClosedTradesStore store = new ClosedTradesStore(dir, keyRing, resolver, () -> walletService,
                    corruptedStorageFileHandler, new PersistenceManager<>(dir, resolver, null, keyRing));
            store.load();
            ClosedTradableManager closed = new ClosedTradableManager(null, null, mock(Preferences.class), null,
                    store, corruptedStorageFileHandler, null);
            Trade trade = handoffTrade();
            trade.setCompleted(true);
            closed.add(trade);
            trade.setCompleted(false);
            closed.persistClosedTrade(trade);
            trade.setCompleted(true);
            closed.add(trade);
            ClosedTradesStore restarted = new ClosedTradesStore(dir, keyRing, resolver, () -> walletService,
                    corruptedStorageFileHandler, new PersistenceManager<>(dir, resolver, null, keyRing));
            Trade restored = (Trade) restarted.load().get(0);
            assertTrue(restored.isCompleted());
            assertEquals(3, restored.getCompletedRevision());
        }
    }

    @Test
    public void testRetainedWalletDoesNotRepersistUnchangedProcessData() throws Exception {
        Trade trade = spy(handoffTrade());
        doReturn(true).when(trade).walletExists();
        doReturn(true).when(trade).isPayoutFinalized();
        doNothing().when(trade).deleteWallet();
        trade.setPayoutTxHex("payout");
        trade.getMaker().setUpdatedMultisigHex("multisig");
        trade.getTaker().setUnsignedPayoutTxHex("unsigned");
        var clear = Trade.class.getDeclaredMethod("clearProcessData");
        clear.setAccessible(true);
        assertEquals(true, clear.invoke(trade));
        assertNull(trade.getPayoutTxHex());
        assertNull(trade.getMaker().getUpdatedMultisigHex());
        assertNull(trade.getTaker().getUnsignedPayoutTxHex());
        assertEquals(false, clear.invoke(trade));
        assertTrue(trade.walletExists());
    }

    private Trade handoffTrade() {
        return handoffTrade("handoff-uid");
    }

    private Trade handoffTrade(String uid) {
        return new BuyerAsMakerTrade(openOffer("handoff", 0).getOffer(), BigInteger.ONE, 100,
                mock(XmrWalletService.class), new ProcessModel("handoff", "account", keyRing.getPubKeyRing()),
                uid, null, null, null, null);
    }

    private ClosedTradableManager handoffClosedManager(Trade... trades) {
        ClosedTradableManager manager = mock(ClosedTradableManager.class);
        List<Trade> closed = new ArrayList<>(List.of(trades));
        when(manager.getClosedTrades()).thenAnswer(invocation -> List.copyOf(closed));
        doAnswer(invocation -> { closed.remove(invocation.getArgument(0)); return null; }).when(manager).removeTrade(any(Trade.class));
        doAnswer(invocation -> {
            Trade trade = invocation.getArgument(0);
            if (!closed.contains(trade)) closed.add(trade);
            return null;
        }).when(manager).add(any(Tradable.class));
        return manager;
    }

    private TradeManager handoffManager(ClosedTradableManager closed, PersistenceManager<TradableList<Trade>> persistence) throws Exception {
        FailedTradesManager failed = mock(FailedTradesManager.class);
        when(failed.getObservableList()).thenReturn(FXCollections.observableArrayList());
        return handoffManager(closed, persistence, failed);
    }

    private TradeManager handoffManager(ClosedTradableManager closed, PersistenceManager<TradableList<Trade>> persistence,
                                      FailedTradesManager failed) throws Exception {
        TradeManager manager = mock(TradeManager.class, CALLS_REAL_METHODS);
        String[] names = { "closedTradableManager", "persistenceManager", "failedTradesManager", "tradableList", "xmrWalletService" };
        Object[] values = { closed, persistence, failed, new TradableList<Trade>(), mock(XmrWalletService.class) };
        for (int i = 0; i < names.length; i++) {
            var field = TradeManager.class.getDeclaredField(names[i]);
            field.setAccessible(true);
            field.set(manager, values[i]);
        }
        return manager;
    }

    private void resumeTradeMoves(TradeManager manager) throws Exception {
        var resume = TradeManager.class.getDeclaredMethod("resumeInterruptedTradeMoves");
        resume.setAccessible(true);
        resume.invoke(manager);
    }
}
