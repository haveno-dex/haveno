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

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
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
import haveno.common.proto.network.NetworkEnvelope;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferPayload;
import haveno.core.offer.OpenOffer;
import haveno.core.payment.ZelleAccount;
import haveno.core.proto.persistable.CorePersistenceProtoResolver;
import haveno.core.trade.protocol.ProcessModel;
import haveno.core.trade.statistics.TradeStatistics3;
import haveno.core.trade.statistics.TradeStatistics3StorageService;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.trade.failed.FailedTradesManager;
import haveno.core.user.Preferences;
import haveno.core.xmr.wallet.BtcWalletService;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.network.NetworkNode;
import haveno.network.p2p.peers.BroadcastHandler;
import haveno.network.p2p.peers.Broadcaster;
import haveno.network.p2p.peers.PeerManager;
import haveno.network.p2p.peers.getdata.RequestDataManager;
import haveno.network.p2p.peers.peerexchange.PeerExchangeManager;
import haveno.network.p2p.storage.P2PDataStorage;
import haveno.network.p2p.storage.messages.AddPersistableNetworkPayloadMessage;
import haveno.network.p2p.storage.payload.PersistableNetworkPayload;
import haveno.network.p2p.storage.persistence.AppendOnlyDataStoreService;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import static org.mockito.ArgumentMatchers.anyLong;
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
        writeLegacyCopies();

        List<Tradable> loaded = newStore().load();

        assertEquals(List.of("legacy-1", "legacy-2"), ids(loaded));
        assertTrue(new File(dir, ClosedTradesStore.STORE_FILE_NAME).exists(), "log should be created");
        assertFalse(new File(dir, ClosedTradesStore.LEGACY_BACKUP_NAME).exists(), "migration must not keep a legacy copy");
        assertFalse(new File(dir, ClosedTradesStore.LEGACY_FILE_NAME).exists(), "legacy file should be deleted");
        assertOnlyMonolithicCopiesRemoved();
        // A second start does not re-migrate (log already present) and reads identically.
        assertEquals(List.of("legacy-1", "legacy-2"), ids(newStore().load()));
    }

    private List<File> legacyCopies() {
        return List.of(new File(dir, ClosedTradesStore.LEGACY_BACKUP_NAME),
                new File(dir, ClosedTradesStore.LEGACY_BACKUP_NAME + ".123"),
                new File(dir, "backup/backups_ClosedTrades"),
                new File(dir, "backup/backups_ClosedTrades_log"),
                new File(dir, "backup/backups_ClosedTrades_log_pending"));
    }

    private void writeLegacyCopies() throws IOException {
        for (File file : legacyCopies()) {
            if (file.getParentFile().equals(dir)) {
                Files.write(file.toPath(), new byte[]{1});
            } else {
                Files.createDirectories(file.toPath());
                Files.write(new File(file, "snapshot").toPath(), new byte[]{1});
            }
        }
    }

    private void assertOnlyMonolithicCopiesRemoved() {
        for (File file : legacyCopies()) {
            boolean unmigratedLogBackup = file.getName().startsWith("backups_ClosedTrades_log");
            assertEquals(unmigratedLogBackup, file.exists(), file.toString());
        }
    }

    private EncryptedAppendLog oldLog(String suffix) {
        return new EncryptedAppendLog(dir, ClosedTradesStore.OLD_LOG_FILE_NAME + suffix, keyRing.getSymmetricKey(), 0);
    }

    @Test
    public void testOldLogAndPendingMigrateAndRemoveLegacyCopies() throws Exception {
        oldLog("").append(ClosedTradesStore.upsertBytes(openOffer("main", 1)));
        oldLog(".pending").append(ClosedTradesStore.upsertBytes(openOffer("pending", 2)));
        writeLegacyCopies();
        File unrelated = new File(dir, ClosedTradesStore.LEGACY_BACKUP_NAME + "-unrelated");
        Files.write(unrelated.toPath(), new byte[]{1});
        File corruptBackup = new File(dir, FileUtil.CORRUPTED_BACKUP_FOLDER + "/saved_UnrelatedStore");
        Files.createDirectories(corruptBackup.getParentFile().toPath());
        Files.write(corruptBackup.toPath(), new byte[]{1});

        assertEquals(List.of("main", "pending"), ids(newStore().load()));
        assertTrue(new File(dir, ClosedTradesStore.STORE_FILE_NAME).exists());
        assertFalse(new File(dir, ClosedTradesStore.OLD_LOG_FILE_NAME).exists());
        assertFalse(new File(dir, ClosedTradesStore.OLD_LOG_FILE_NAME + ".pending").exists());
        assertTrue(new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME, keyRing.getSymmetricKey(), 0)
                .readAllValidRecords().isEmpty());
        for (File file : legacyCopies()) assertFalse(file.exists(), file.toString());
        assertTrue(unrelated.exists());
        assertTrue(corruptBackup.exists());
        assertEquals(List.of("main", "pending"), ids(newStore().load()));
    }

    @Test
    public void testBothLogNamesMergeWithDowngradedWritesLast() throws Exception {
        rawLog().appendAll(List.of(ClosedTradesStore.upsertBytes(openOffer("original", 1)),
                ClosedTradesStore.upsertBytes(openOffer("shared", 1)),
                ClosedTradesStore.upsertBytes(openOffer("deleted", 1))));
        byte[] committed = ClosedTradesStore.upsertBytes(openOffer("shared", 2));
        oldLog("").appendAll(List.of(committed, ClosedTradesStore.upsertBytes(openOffer("downgraded", 3)),
                ClosedTradesStore.deleteBytes(openOffer("deleted", 1)),
                ClosedTradesStore.upsertBytes(openOffer("shared", 4))));
        // A committed pending receipt must not overwrite the later update when both names merge.
        oldLog(".pending").appendAll(List.of(committed, ClosedTradesStore.upsertBytes(openOffer("queued-old", 5))));
        new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME, keyRing.getSymmetricKey(), 0)
                .append(ClosedTradesStore.upsertBytes(openOffer("queued-new", 6)));

        List<Tradable> loaded = newStore().load();
        assertEquals(List.of("original", "shared", "downgraded", "queued-new", "queued-old"), ids(loaded));
        assertEquals(4, ((OpenOffer) loaded.get(1)).getTriggerPrice());
        assertFalse(new File(dir, ClosedTradesStore.OLD_LOG_FILE_NAME).exists());
        assertFalse(new File(dir, ClosedTradesStore.OLD_LOG_FILE_NAME + ".pending").exists());
        assertEquals(ids(loaded), ids(newStore().load()));
    }

    @Test
    public void testMigrationRecoversOldTempsAndPreservesNewTemp() throws Exception {
        rawLog().append(ClosedTradesStore.upsertBytes(openOffer("new-temp", 1)));
        Files.move(new File(dir, ClosedTradesStore.STORE_FILE_NAME).toPath(),
                new File(dir, ClosedTradesStore.STORE_FILE_NAME + ".tmp").toPath());
        oldLog(".tmp").append(ClosedTradesStore.upsertBytes(openOffer("old-temp", 2)));
        oldLog(".pending.tmp").append(ClosedTradesStore.upsertBytes(openOffer("pending-temp", 3)));

        assertEquals(List.of("new-temp", "old-temp", "pending-temp"), ids(newStore().load()));
        assertFalse(new File(dir, ClosedTradesStore.OLD_LOG_FILE_NAME + ".tmp").exists());
        assertFalse(new File(dir, ClosedTradesStore.OLD_LOG_FILE_NAME + ".pending.tmp").exists());
        assertFalse(new File(dir, ClosedTradesStore.STORE_FILE_NAME + ".tmp").exists());
        assertEquals(List.of("new-temp", "old-temp", "pending-temp"), ids(newStore().load()));
    }

    @Test
    public void testStaleOldTempIsDeletedBeforeLiveFileMoves() throws Exception {
        oldLog("").append(ClosedTradesStore.upsertBytes(openOffer("current", 1)));
        oldLog(".tmp").append(ClosedTradesStore.upsertBytes(openOffer("stale", 2)));
        File source = new File(dir, ClosedTradesStore.OLD_LOG_FILE_NAME);
        try (var files = mockStatic(FileUtil.class, invocation -> {
            if (invocation.getMethod().getName().equals("atomicReplace") && source.equals(invocation.getArgument(0))) {
                assertFalse(new File(dir, ClosedTradesStore.OLD_LOG_FILE_NAME + ".tmp").exists());
                invocation.callRealMethod();
                throw new OutOfMemoryError("Simulated termination immediately after rename");
            }
            return invocation.callRealMethod();
        })) {
            assertThrows(OutOfMemoryError.class, () -> newStore().load());
        }
        assertEquals(List.of("current"), ids(newStore().load()));
    }

    @Test
    public void testStaleTempDeletionSyncIsRetriedBeforeRename() throws Exception {
        oldLog("").append(ClosedTradesStore.upsertBytes(openOffer("current", 1)));
        oldLog(".tmp").append(ClosedTradesStore.upsertBytes(openOffer("stale", 2)));
        File source = new File(dir, ClosedTradesStore.OLD_LOG_FILE_NAME);
        ClosedTradesStore store = newStore();
        int[] syncAttempts = {0};
        try (var files = mockStatic(FileUtil.class, invocation -> {
            if (invocation.getMethod().getName().equals("syncDirectory") && source.exists()) {
                if (++syncAttempts[0] == 1) throw new IOException("Simulated stale-temp deletion sync failure");
            }
            if (invocation.getMethod().getName().equals("atomicReplace") && source.equals(invocation.getArgument(0))) {
                assertEquals(2, syncAttempts[0], "the temp deletion must be durable before renaming on retry");
            }
            return invocation.callRealMethod();
        })) {
            assertThrows(RuntimeException.class, store::load);
            assertTrue(source.exists());
            assertFalse(new File(dir, ClosedTradesStore.OLD_LOG_FILE_NAME + ".tmp").exists());
            assertEquals(List.of("current"), ids(store.load()));
        }
        assertEquals(List.of("current"), ids(newStore().load()));
    }

    @Test
    public void testAppendBeforeLoadMigratesOldHistoryAndQueue() throws Exception {
        oldLog("").append(ClosedTradesStore.upsertBytes(openOffer("main", 1)));
        oldLog(".pending").append(ClosedTradesStore.upsertBytes(openOffer("queued", 2)));

        newStore().appendUpsert(openOffer("queued", 3));

        List<Tradable> loaded = newStore().load();
        assertEquals(List.of("main", "queued"), ids(loaded));
        assertEquals(3, ((OpenOffer) loaded.get(1)).getTriggerPrice());
        assertFalse(new File(dir, ClosedTradesStore.OLD_LOG_FILE_NAME).exists());
    }

    @Test
    public void testFailedMainRenameAfterPendingMigrationKeepsDurableQueue() throws Exception {
        oldLog("").append(ClosedTradesStore.upsertBytes(openOffer("main", 1)));
        oldLog(".pending").append(ClosedTradesStore.upsertBytes(openOffer("queued", 2)));
        writeLegacyCopies();
        ClosedTradesStore store = newStore();
        File source = new File(dir, ClosedTradesStore.OLD_LOG_FILE_NAME);
        File target = new File(dir, ClosedTradesStore.STORE_FILE_NAME);
        try (var userThread = mockStatic(UserThread.class);
             var files = mockStatic(FileUtil.class, invocation -> {
                 if (invocation.getMethod().getName().equals("atomicReplace") && source.equals(invocation.getArgument(0))) {
                     throw new IOException("Simulated rename failure");
                 }
                 return invocation.callRealMethod();
             })) {
            assertThrows(RuntimeException.class, store::load);
            store.appendUpsert(openOffer("later", 3));
            assertTrue(source.exists());
            assertFalse(target.exists());
            assertFalse(new File(dir, ClosedTradesStore.OLD_LOG_FILE_NAME + ".pending").exists());
            for (File file : legacyCopies()) assertTrue(file.exists(), file.toString());
            assertEquals(2, new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME, keyRing.getSymmetricKey(), 0)
                    .readAllValidRecords().size());
        }
        assertEquals(List.of("main", "queued", "later"), ids(newStore().load()));
    }

    @Test
    public void testFailedSourceDeletionBlocksNewWritesUntilMergeCanFinish() throws Exception {
        rawLog().append(ClosedTradesStore.upsertBytes(openOffer("shared", 1)));
        oldLog("").append(ClosedTradesStore.upsertBytes(openOffer("shared", 2)));
        File source = new File(dir, ClosedTradesStore.OLD_LOG_FILE_NAME);
        ClosedTradesStore store = newStore();
        try (var userThread = mockStatic(UserThread.class);
             var files = mockStatic(FileUtil.class, invocation -> {
                 if (invocation.getMethod().getName().equals("deleteFileIfExists") && source.equals(invocation.getArgument(0))) {
                     throw new IOException("Simulated source deletion failure");
                 }
                 return invocation.callRealMethod();
             })) {
            assertThrows(RuntimeException.class, store::load);
            long mergedLength = new File(dir, ClosedTradesStore.STORE_FILE_NAME).length();
            store.appendUpsert(openOffer("shared", 3));
            store.flushFailedEntries();
            assertEquals(mergedLength, new File(dir, ClosedTradesStore.STORE_FILE_NAME).length());
            assertTrue(source.exists());
        }
        List<Tradable> loaded = newStore().load();
        assertEquals(List.of("shared"), ids(loaded));
        assertEquals(3, ((OpenOffer) loaded.getFirst()).getTriggerPrice());
        assertFalse(source.exists());
        assertEquals(3, ((OpenOffer) newStore().load().getFirst()).getTriggerPrice());
    }

    @Test
    public void testRepairedMigrationRetainsLegacyCopiesAcrossRestarts() throws Exception {
        rawLog().append(ClosedTradesStore.upsertBytes(openOffer("main", 1)));
        oldLog("").append(ClosedTradesStore.upsertBytes(openOffer("old", 2)));
        Files.write(new File(dir, ClosedTradesStore.OLD_LOG_FILE_NAME).toPath(), new byte[]{1},
                StandardOpenOption.APPEND);
        writeLegacyFile(openOffer("legacy", 3));
        writeLegacyCopies();
        ClosedTradesStore store = newStore();

        assertEquals(List.of("main", "old", "legacy"), ids(store.load()));
        assertTrue(new File(dir, ClosedTradesStore.LEGACY_FILE_NAME).exists());
        for (File file : legacyCopies()) assertTrue(file.exists(), file.toString());
        store.load();
        for (File file : legacyCopies()) assertTrue(file.exists(), file.toString());
        assertEquals(List.of("main", "old", "legacy"), ids(newStore().load()));
        assertFalse(new File(dir, ClosedTradesStore.LEGACY_FILE_NAME).exists());
        for (File file : legacyCopies()) assertTrue(file.exists(), file.toString());
    }

    @Test
    public void testRepairedMainLogRetainsCopies() throws Exception {
        assertRepairedLogRetainsCopies(ClosedTradesStore.STORE_FILE_NAME);
    }

    @Test
    public void testRepairedPendingLogRetainsCopies() throws Exception {
        assertRepairedLogRetainsCopies(ClosedTradesStore.PENDING_FILE_NAME);
    }

    @Test
    public void testRepairedOldPendingLogRetainsCopies() throws Exception {
        assertRepairedLogRetainsCopies(ClosedTradesStore.OLD_LOG_FILE_NAME + ".pending");
    }

    private void assertRepairedLogRetainsCopies(String fileName) throws Exception {
        rawLog().append(ClosedTradesStore.upsertBytes(openOffer("main", 1)));
        new EncryptedAppendLog(dir, fileName, keyRing.getSymmetricKey(), 0)
                .append(ClosedTradesStore.upsertBytes(openOffer("recovered", 2)));
        Files.write(new File(dir, fileName).toPath(), new byte[]{1}, StandardOpenOption.APPEND);
        writeLegacyCopies();

        assertEquals(List.of("main", "recovered"), ids(newStore().load()));
        for (File file : legacyCopies()) assertTrue(file.exists(), file.toString());
        assertEquals(List.of("main", "recovered"), ids(newStore().load()));
        for (File file : legacyCopies()) assertTrue(file.exists(), file.toString());
    }

    @Test
    public void testEarlierMidLogCorruptionRetainsCopiesAcrossRestarts() throws Exception {
        rawLog().appendAll(List.of(ClosedTradesStore.upsertBytes(openOffer("kept", 1)),
                ClosedTradesStore.upsertBytes(openOffer("damaged", 2)),
                ClosedTradesStore.upsertBytes(openOffer("lost", 3))));
        corruptFrame(new File(dir, ClosedTradesStore.STORE_FILE_NAME), 1);
        writeLegacyCopies();

        assertEquals(List.of("kept"), ids(newStore().load()));
        File[] marked = new File(dir, FileUtil.CORRUPTED_BACKUP_FOLDER).listFiles((d, name) ->
                name.endsWith("_" + ClosedTradesStore.STORE_FILE_NAME + EncryptedAppendLog.CORRUPT_BACKUP_SUFFIX));
        assertEquals(1, marked.length, "mid-log damage must leave a marked copy");
        for (File file : legacyCopies()) assertTrue(file.exists(), file.toString());
        // The repair copy keeps recovery snapshots available after a clean restart.
        assertEquals(List.of("kept"), ids(newStore().load()));
        for (File file : legacyCopies()) assertTrue(file.exists(), file.toString());
    }

    // Flips a byte inside the given frame's ciphertext, so replay sees mid-log damage rather than a torn tail.
    private static void corruptFrame(File file, int frame) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        int offset = 0;
        for (int i = 0; i < frame; i++) offset += 4 + ByteBuffer.wrap(bytes, offset, 4).getInt();
        int len = ByteBuffer.wrap(bytes, offset, 4).getInt();
        bytes[offset + 4 + len / 2] ^= 0x5a;
        Files.write(file.toPath(), bytes);
    }

    @Test
    public void testMissingOrEmptyStoreRetainsRecoveryCopies() throws Exception {
        writeLegacyCopies();
        assertTrue(newStore().load().isEmpty());
        for (File file : legacyCopies()) assertTrue(file.exists(), file.toString());
        rawLog().rewrite(List.of());
        assertTrue(newStore().load().isEmpty());
        for (File file : legacyCopies()) assertTrue(file.exists(), file.toString());

        newStore().appendUpsert(openOffer("recent", 1));
        assertEquals(List.of("recent"), ids(newStore().load()));
        for (File file : legacyCopies()) assertTrue(file.exists(), file.toString());
    }

    @Test
    public void testNewTradesRetainReadableSnapshotsOfMissingHistory() throws Exception {
        writeLegacyFile(openOffer("historical", 1));
        oldLog("").append(ClosedTradesStore.upsertBytes(openOffer("historical", 1)));
        oldLog(".pending").append(ClosedTradesStore.upsertBytes(openOffer("queued", 2)));
        List<File> backups = new ArrayList<>();
        for (String fileName : List.of(ClosedTradesStore.LEGACY_FILE_NAME,
                ClosedTradesStore.OLD_LOG_FILE_NAME, ClosedTradesStore.OLD_PENDING_FILE_NAME)) {
            assertTrue(FileUtil.rollingBackup(dir, fileName, 1));
            backups.addAll(FileUtil.getBackupFiles(dir, fileName));
            Files.delete(new File(dir, fileName).toPath());
        }
        assertEquals(3, backups.size());

        ClosedTradesStore store = newStore();
        assertTrue(store.load().isEmpty());
        store.appendUpsert(openOffer("recent", 3));

        assertEquals(List.of("recent"), ids(newStore().load()));
        for (File backup : backups) assertTrue(backup.exists(), backup.toString());
    }

    @Test
    public void testUnmarkedEarlierRepairRetainsReadableBackup() throws Exception {
        assertEarlierRepairRetainsBackup(ClosedTradesStore.OLD_LOG_FILE_NAME, false);
    }

    @Test
    public void testEarlierPendingRepairRetainsReadableBackup() throws Exception {
        assertEarlierRepairRetainsBackup(ClosedTradesStore.OLD_PENDING_FILE_NAME, false);
    }

    @Test
    public void testDamagedLengthRetainsReadableBackup() throws Exception {
        assertEarlierRepairRetainsBackup(ClosedTradesStore.OLD_LOG_FILE_NAME, true);
    }

    private void assertEarlierRepairRetainsBackup(String fileName, boolean damageLength) throws Exception {
        rawLog().append(ClosedTradesStore.upsertBytes(openOffer("main", 1)));
        EncryptedAppendLog log = new EncryptedAppendLog(dir, fileName, keyRing.getSymmetricKey(), 0);
        log.appendAll(List.of(ClosedTradesStore.upsertBytes(openOffer("kept", 1)),
                ClosedTradesStore.upsertBytes(openOffer("damaged", 2)),
                ClosedTradesStore.upsertBytes(openOffer("lost", 3))));
        assertTrue(FileUtil.rollingBackup(dir, fileName, 1));
        File backup = FileUtil.getBackupFiles(dir, fileName).getFirst();
        File source = new File(dir, fileName);
        if (damageLength) {
            byte[] bytes = Files.readAllBytes(source.toPath());
            int secondFrame = 4 + ByteBuffer.wrap(bytes, 0, 4).getInt();
            ByteBuffer.wrap(bytes, secondFrame, 4).putInt(Integer.MAX_VALUE);
            Files.write(source.toPath(), bytes);
        } else {
            corruptFrame(source, 1);
        }
        assertEquals(1, log.readAllValidRecords().size());
        if (!damageLength && fileName.equals(ClosedTradesStore.OLD_LOG_FILE_NAME)) {
            // Earlier builds saved corruption copies without the suffix.
            File corruptedDir = new File(dir, FileUtil.CORRUPTED_BACKUP_FOLDER);
            File[] copies = corruptedDir.listFiles();
            assertEquals(1, copies.length);
            Files.move(copies[0].toPath(), new File(corruptedDir, "earlier_" + fileName).toPath());
        }

        assertEquals(List.of("main", "kept"), ids(newStore().load()));
        assertEquals(List.of("main", "kept"), ids(newStore().load()));
        assertTrue(backup.exists());
        assertEquals(3, new EncryptedAppendLog(backup.getParentFile(), backup.getName(), keyRing.getSymmetricKey(), 0)
                .readAllValidRecords().size());
    }

    @Test
    public void testFailedLegacyAppendKeepsSourceAndCopies() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            rawLog().append(ClosedTradesStore.upsertBytes(openOffer("main", 1)));
            writeLegacyFile(openOffer("legacy", 2));
            writeLegacyCopies();
            EncryptedAppendLog main = spy(rawLog());
            doThrow(new IllegalStateException("Simulated legacy append failure")).when(main).appendAll(anyList());
            ClosedTradesStore store = newStore();
            setLog(store, "appendLog", main);

            assertEquals(List.of("main", "legacy"), ids(store.load()));
            assertEquals(1, rawLog().readAllValidRecords().size());
            assertTrue(new File(dir, ClosedTradesStore.LEGACY_FILE_NAME).exists());
            for (File file : legacyCopies()) assertTrue(file.exists(), file.toString());
            assertEquals(List.of("main", "legacy"), ids(newStore().load()));
            assertFalse(new File(dir, ClosedTradesStore.LEGACY_FILE_NAME).exists());
            assertOnlyMonolithicCopiesRemoved();
        }
    }

    @Test
    public void testEarlierFailedLegacyImportKeepsReadableRollingBackup() throws Exception {
        writeLegacyFile(openOffer("original", 1));
        assertTrue(FileUtil.rollingBackup(dir, ClosedTradesStore.LEGACY_FILE_NAME, 1));
        Files.write(new File(dir, ClosedTradesStore.LEGACY_FILE_NAME).toPath(), new byte[]{1});
        PersistenceManager<TradableList<Tradable>> legacy = new PersistenceManager<>(dir, resolver, null, keyRing);
        assertNull(legacy.getPersisted(ClosedTradesStore.LEGACY_FILE_NAME));
        rawLog().append(ClosedTradesStore.upsertBytes(openOffer("recent", 2)));

        assertEquals(List.of("recent"), ids(newStore().load()));
        assertEquals(1, FileUtil.getBackupFiles(dir, ClosedTradesStore.LEGACY_FILE_NAME).size());
        File corrupted = new File(dir, FileUtil.CORRUPTED_BACKUP_FOLDER + "/" + ClosedTradesStore.LEGACY_FILE_NAME);
        Files.move(corrupted.toPath(), new File(corrupted.getParentFile(), ClosedTradesStore.LEGACY_FILE_NAME + ".earlier").toPath());
        assertEquals(List.of("recent"), ids(newStore().load()));
        assertEquals(1, FileUtil.getBackupFiles(dir, ClosedTradesStore.LEGACY_FILE_NAME).size());
    }

    @Test
    public void testEarlierCorruptionKeepsRecoveryCopiesAfterMerge() throws Exception {
        writeLegacyFile(openOffer("recreated", 1));
        writeLegacyCopies();
        File corrupted = new File(dir, FileUtil.CORRUPTED_BACKUP_FOLDER + "/" + ClosedTradesStore.LEGACY_FILE_NAME);
        Files.createDirectories(corrupted.getParentFile().toPath());
        Files.write(corrupted.toPath(), new byte[]{1});

        assertEquals(List.of("recreated"), ids(newStore().load()));
        assertFalse(new File(dir, ClosedTradesStore.LEGACY_FILE_NAME).exists());
        for (File file : legacyCopies()) assertTrue(file.exists(), file.toString());
        assertTrue(corrupted.exists());
        assertEquals(List.of("recreated"), ids(newStore().load()));
    }

    @Test
    public void testExistingStoreRemainsReadableWhenCleanupCannotSync() throws Exception {
        rawLog().append(ClosedTradesStore.upsertBytes(openOffer("main", 1)));
        writeLegacyFile(openOffer("main", 1));
        try (var files = mockStatic(FileUtil.class, invocation -> {
            if (invocation.getMethod().getName().equals("syncFileAndDirectory")) {
                throw new IOException("Simulated read-only store");
            }
            return invocation.callRealMethod();
        })) {
            assertEquals(List.of("main"), ids(newStore().load()));
            assertFalse(corruptedStorageFileHandler.getFiles().isPresent());
            assertTrue(new File(dir, ClosedTradesStore.LEGACY_FILE_NAME).exists());
        }
    }

    @Test
    public void testAlreadyMergedLegacyIsKeptWhenSyncFails() throws Exception {
        rawLog().append(ClosedTradesStore.upsertBytes(openOffer("same", 1)));
        writeLegacyFile(openOffer("same", 0));
        writeLegacyCopies();
        ClosedTradesStore store = newStore();
        setLog(store, "appendLog", rawLog());
        try (var files = mockStatic(FileUtil.class, invocation -> {
            if (invocation.getMethod().getName().equals("syncFileAndDirectory")) {
                throw new IOException("Simulated sync failure before legacy cleanup");
            }
            return invocation.callRealMethod();
        })) {
            assertEquals(1, ((OpenOffer) store.load().getFirst()).getTriggerPrice());
            assertTrue(new File(dir, ClosedTradesStore.LEGACY_FILE_NAME).exists());
            for (File file : legacyCopies()) assertTrue(file.exists(), file.toString());
        }
        assertEquals(1, ((OpenOffer) newStore().load().getFirst()).getTriggerPrice());
        assertFalse(new File(dir, ClosedTradesStore.LEGACY_FILE_NAME).exists());
        assertOnlyMonolithicCopiesRemoved();
    }

    @Test
    public void testSourceDeletionSyncFailureStillBlocksWritesOnRetry() throws Exception {
        rawLog().append(ClosedTradesStore.upsertBytes(openOffer("shared", 1)));
        oldLog("").append(ClosedTradesStore.upsertBytes(openOffer("shared", 2)));
        File source = new File(dir, ClosedTradesStore.OLD_LOG_FILE_NAME);
        ClosedTradesStore store = newStore();
        try (var userThread = mockStatic(UserThread.class);
             var files = mockStatic(FileUtil.class, invocation -> {
                 if (invocation.getMethod().getName().equals("syncDirectory") && !source.exists()) {
                     throw new IOException("Simulated directory sync failure after deletion");
                 }
                 return invocation.callRealMethod();
             })) {
            assertThrows(RuntimeException.class, store::load);
            assertFalse(source.exists());
            long mergedLength = new File(dir, ClosedTradesStore.STORE_FILE_NAME).length();
            store.appendUpsert(openOffer("shared", 3));
            assertEquals(mergedLength, new File(dir, ClosedTradesStore.STORE_FILE_NAME).length());
        }
        store.flushFailedEntries();
        assertEquals(3, ((OpenOffer) newStore().load().getFirst()).getTriggerPrice());
    }

    @Test
    public void testUnreadableLegacyRetainsCopies() throws Exception {
        rawLog().append(ClosedTradesStore.upsertBytes(openOffer("main", 1)));
        writeLegacyFile(openOffer("legacy", 2));
        writeLegacyCopies();
        PersistenceManager<TradableList<Tradable>> legacy = spy(new PersistenceManager<>(dir, resolver, null, keyRing));
        doReturn(null).when(legacy).getPersisted(ClosedTradesStore.LEGACY_FILE_NAME);
        ClosedTradesStore store = new ClosedTradesStore(dir, keyRing, resolver, () -> null, corruptedStorageFileHandler, legacy);

        assertEquals(List.of("main"), ids(store.load()));
        assertTrue(new File(dir, ClosedTradesStore.LEGACY_FILE_NAME).exists());
        for (File file : legacyCopies()) assertTrue(file.exists(), file.toString());
        assertEquals(List.of("main", "legacy"), ids(newStore().load()));
        assertOnlyMonolithicCopiesRemoved();
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
        assertFalse(new File(dir, ClosedTradesStore.LEGACY_FILE_NAME).exists(), "legacy file should be deleted after merge");
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
    public void testSecondLegacyMergeLeavesNoBackup() throws Exception {
        writeLegacyFile(openOffer("first", 0));
        newStore().load();
        assertFalse(new File(dir, ClosedTradesStore.LEGACY_BACKUP_NAME).exists());

        writeLegacyFile(openOffer("second", 0));
        newStore().load();

        File[] backups = dir.listFiles((d, name) -> name.startsWith(ClosedTradesStore.LEGACY_BACKUP_NAME));
        assertEquals(0, backups.length, "legacy migration must not retain sensitive copies");
    }

    @Test
    public void testUndecodableRecordIsSkippedAndSurfaced() throws Exception {
        writeLegacyFile(openOffer("a", 99));
        writeLegacyCopies();
        ClosedTradesStore store = newStore();
        store.appendUpsert(openOffer("a", 0));
        store.appendUpsert(openOffer("b", 0));

        // Append two authenticated but undecodable records directly to the log: one that is not a
        // valid TradableLogEntry at all, and one whose upsert has no known tradable type (as a newer
        // version would write). Neither may hide the rest of the history.
        EncryptedAppendLog rawLog = new EncryptedAppendLog(dir, ClosedTradesStore.STORE_FILE_NAME, keyRing.getSymmetricKey(), 3);
        rawLog.append(new byte[]{0x0a}); // truncated protobuf
        rawLog.append(protobuf.TradableLogEntry.newBuilder()
                .setUpsert(protobuf.Tradable.newBuilder().build())
                .build()
                .toByteArray());

        List<Tradable> loaded = newStore().load();

        assertEquals(List.of("a", "b"), ids(loaded), "decodable records must survive an undecodable one");
        assertTrue(corruptedStorageFileHandler.getFiles().isPresent(), "user must be notified of skipped records");
        assertTrue(corruptedStorageFileHandler.getFiles().get().contains(ClosedTradesStore.STORE_FILE_NAME));
        // The undecodable records stay on disk (no compaction while they exist): a later read with a
        // build that understands them would still see them; here we just prove the load is stable.
        assertEquals(List.of("a", "b"), ids(newStore().load()));
        assertTrue(new File(dir, ClosedTradesStore.LEGACY_FILE_NAME).exists());
        for (File file : legacyCopies()) assertTrue(file.exists(), file.toString());
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
        byte[] pendingBeforeClear = Files.readAllBytes(new File(dir, ClosedTradesStore.PENDING_FILE_NAME).toPath());

        List<Tradable> loaded = newStore().load();

        assertEquals(List.of("b"), ids(loaded), "pending records must replay after the log");
        assertEquals(7, ((OpenOffer) loaded.get(0)).getTriggerPrice());
        // The recovery re-appended the records to the log and cleared the queue, so a later start
        // reads the same state from the log alone.
        EncryptedAppendLog clearedPending = new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME, keyRing.getSymmetricKey(), 1);
        assertTrue(clearedPending.readAllValidRecords().isEmpty(), "pending queue must be cleared after recovery");
        List<File> backups = FileUtil.getBackupFiles(dir, ClosedTradesStore.PENDING_FILE_NAME);
        assertEquals(1, backups.size());
        assertArrayEquals(pendingBeforeClear, Files.readAllBytes(backups.get(0).toPath()));
        assertEquals(List.of("b"), ids(newStore().load()));
        assertTrue(backups.get(0).exists(), "legacy cleanup must preserve current rolling backups");
    }

    @Test
    public void testCommittedPendingUpdateCannotOverwriteNewerUpdateOnRestart() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            ClosedTradesStore store = newStore();
            EncryptedAppendLog main = spy(new EncryptedAppendLog(dir, ClosedTradesStore.STORE_FILE_NAME,
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
            EncryptedAppendLog main = spy(new EncryptedAppendLog(dir, ClosedTradesStore.STORE_FILE_NAME,
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
            byte[] committed = new EncryptedAppendLog(dir, ClosedTradesStore.STORE_FILE_NAME,
                    keyRing.getSymmetricKey(), 3).readAllValidRecords().get(0);
            new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME, keyRing.getSymmetricKey(), 1)
                    .rewrite(List.of(committed));
            EncryptedAppendLog main = spy(new EncryptedAppendLog(dir, ClosedTradesStore.STORE_FILE_NAME,
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
            byte[] committed = new EncryptedAppendLog(dir, ClosedTradesStore.STORE_FILE_NAME,
                    keyRing.getSymmetricKey(), 3).readAllValidRecords().get(0);
            new EncryptedAppendLog(dir, ClosedTradesStore.PENDING_FILE_NAME, keyRing.getSymmetricKey(), 1)
                    .rewrite(List.of(committed, ClosedTradesStore.upsertBytes(openOffer("b", 1))));
            EncryptedAppendLog main = spy(new EncryptedAppendLog(dir, ClosedTradesStore.STORE_FILE_NAME,
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
            EncryptedAppendLog main = spy(new EncryptedAppendLog(dir, ClosedTradesStore.STORE_FILE_NAME,
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
            EncryptedAppendLog main = spy(new EncryptedAppendLog(dir, ClosedTradesStore.STORE_FILE_NAME,
                    keyRing.getSymmetricKey(), 3));
            setLog(store, "appendLog", main);
            doThrow(new IllegalStateException("Simulated append failure")).when(main).appendAll(anyList());
            store.appendEntries(List.of(ClosedTradesStore.deleteBytes("a"),
                    ClosedTradesStore.upsertBytes(openOffer("a", 1)),
                    ClosedTradesStore.upsertBytes(openOffer("c", 0))));
            doAnswer(invocation -> {
                List<byte[]> batch = invocation.getArgument(0);
                new EncryptedAppendLog(dir, ClosedTradesStore.STORE_FILE_NAME, keyRing.getSymmetricKey(), 3)
                        .appendAll(batch.subList(0, 2));
                throw new OutOfMemoryError("Simulated process termination during batch append");
            }).when(main).appendAll(anyList());
            assertThrows(OutOfMemoryError.class, store::flushFailedEntries);

            assertEquals(List.of("b", "a", "c"), ids(newStore().load()));
            assertEquals(List.of("b", "a", "c"), ids(newStore().load()));
            assertEquals(5, new EncryptedAppendLog(dir, ClosedTradesStore.STORE_FILE_NAME,
                    keyRing.getSymmetricKey(), 3).readAllValidRecords().size());
        }
    }

    @Test
    public void testFailedPendingClearDefersCompactionUntilRestartRecovery() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            ClosedTradesStore store = newStore();
            EncryptedAppendLog main = spy(new EncryptedAppendLog(dir, ClosedTradesStore.STORE_FILE_NAME,
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
            assertEquals(1, new EncryptedAppendLog(dir, ClosedTradesStore.STORE_FILE_NAME,
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
            EncryptedAppendLog main = spy(new EncryptedAppendLog(dir, ClosedTradesStore.STORE_FILE_NAME,
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
            writeLegacyFile(openOffer("queued", 0));
            writeLegacyCopies();
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
            assertTrue(new File(dir, ClosedTradesStore.LEGACY_FILE_NAME).exists());
            for (File file : legacyCopies()) assertTrue(file.exists(), file.toString());
            assertEquals(1, new EncryptedAppendLog(dir, ClosedTradesStore.STORE_FILE_NAME,
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
            EncryptedAppendLog main = spy(new EncryptedAppendLog(dir, ClosedTradesStore.STORE_FILE_NAME,
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
            EncryptedAppendLog main = spy(new EncryptedAppendLog(dir, ClosedTradesStore.STORE_FILE_NAME,
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
            byte[] beforeRedaction = Files.readAllBytes(new File(dir, ClosedTradesStore.STORE_FILE_NAME).toPath());

            store.requestRedaction();
            store.flushRedaction();

            List<byte[]> records = rawLog().readAllValidRecords();
            assertEquals(2, records.size());
            assertArrayEquals(cleared, records.get(1), "retain raw fields and mutation identity");
            assertEquals("deleted", protobuf.TradableLogEntry.parseFrom(records.get(0)).getDeleteId());
            List<File> backups = FileUtil.getBackupFiles(dir, ClosedTradesStore.STORE_FILE_NAME);
            assertEquals(1, backups.size());
            assertArrayEquals(beforeRedaction, Files.readAllBytes(backups.get(0).toPath()));
            writeLegacyFile(openOffer("deleted", 99));
            assertEquals(List.of("keep"), ids(newStore().load()), "redaction must retain deletion markers");
            assertTrue(backups.get(0).exists(), "legacy cleanup must preserve current rolling backups");
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
        return new EncryptedAppendLog(dir, ClosedTradesStore.STORE_FILE_NAME, keyRing.getSymmetricKey(), 0);
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
    public void testPayoutHeightLoadsOnlyWithConfirmedPayout() throws Exception {
        try (var userThread = mockStatic(UserThread.class)) {
            protobuf.BuyerAsMakerTrade proto = ((protobuf.Tradable) handoffTrade().toProtoMessage()).getBuyerAsMakerTrade();
            for (Trade.PayoutState state : Trade.PayoutState.values()) {
                Trade restored = (Trade) BuyerAsMakerTrade.fromProto(proto.toBuilder().setTrade(proto.getTrade().toBuilder()
                        .setPayoutState(Trade.PayoutState.toProtoMessage(state)).setPayoutHeight(100)).build(), mock(XmrWalletService.class), resolver);
                assertEquals(restored.isPayoutConfirmed() ? 100 : 0,
                        ((protobuf.Tradable) restored.toProtoMessage()).getBuyerAsMakerTrade().getTrade().getPayoutHeight(), state.name());
            }
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

    @Test
    public void testLegacyTradeStatisticsCanRecoverAfterRestart() {
        TradeStatisticsManager previousManager = HavenoUtils.tradeStatisticsManager;
        try (var userThread = mockStatic(UserThread.class)) {
            P2PService p2p = mock(P2PService.class);
            TradeStatistics3StorageService storage = mock(TradeStatistics3StorageService.class);
            Map<P2PDataStorage.ByteArray, PersistableNetworkPayload> statistics = new HashMap<>();
            when(storage.getMapOfAllData()).thenReturn(statistics);
            TradeStatisticsManager manager = new TradeStatisticsManager(p2p, null, storage,
                    mock(AppendOnlyDataStoreService.class), dir, false);
            Trade trade = statisticsTrade();
            TradeStatistics3 payload = TradeStatistics3.fromV2(trade, null, false);
            statistics.put(new P2PDataStorage.ByteArray(payload.getHash()), payload);

            manager.maybePublishTradeStatistics(trade, null, false);
            verifyNoInteractions(p2p);
            assertEquals(0, trade.getTradeStatisticsPublishTime());

            // An unrelated save must not turn a local legacy statistic into a confirmed broadcast.
            ClosedTradesStore store = newStore(mock(XmrWalletService.class));
            store.appendUpsert(trade);
            Trade restored = spy((Trade) store.load().get(0));
            doReturn(true).when(restored).shouldPublishTradeStatistics();
            statistics.clear();
            long earliest = System.currentTimeMillis();
            manager.maybePublishTradeStatistics(restored, null, false);
            long scheduledTime = restored.getTradeStatisticsPublishTime();
            assertTrue(scheduledTime >= earliest);
            assertTrue(scheduledTime < System.currentTimeMillis() + TimeUnit.HOURS.toMillis(24));
            manager.maybePublishTradeStatistics(restored, null, false);
            assertEquals(scheduledTime, restored.getTradeStatisticsPublishTime());
            verify(restored).requestPersistence();
            store.appendUpsert(restored);
            assertEquals(scheduledTime, ((Trade) store.load().get(0)).getTradeStatisticsPublishTime());
        } finally {
            HavenoUtils.tradeStatisticsManager = previousManager;
        }
    }

    @Test
    public void testPersistedTradeStatisticsScheduleRetriesUntilConfirmed() {
        TradeStatisticsManager previousManager = HavenoUtils.tradeStatisticsManager;
        try (var userThread = mockStatic(UserThread.class)) {
            userThread.when(() -> UserThread.execute(any(Runnable.class))).thenAnswer(invocation -> {
                invocation.getArgument(0, Runnable.class).run();
                return null;
            });
            P2PService p2p = mock(P2PService.class);
            when(p2p.isNetworkReady()).thenReturn(true);
            TradeStatistics3StorageService storage = mock(TradeStatistics3StorageService.class);
            when(storage.getMapOfAllData()).thenReturn(Map.of());
            TradeStatisticsManager manager = new TradeStatisticsManager(p2p, null, storage,
                    mock(AppendOnlyDataStoreService.class), dir, false);
            ClosedTradesStore store = newStore(mock(XmrWalletService.class));
            Trade trade = statisticsTrade();
            trade.setTradeStatisticsPublishTime(1); // an overdue schedule publishes after a short random delay
            store.appendUpsert(trade);
            Trade restored = spy((Trade) store.load().get(0));
            doReturn(true).when(restored).shouldPublishTradeStatistics();
            doAnswer(invocation -> { store.appendUpsert(restored); return null; }).when(restored).requestPersistence();

            manager.maybePublishTradeStatistics(restored, null, false);
            manager.maybePublishTradeStatistics(restored, null, false);
            ArgumentCaptor<Runnable> publish = ArgumentCaptor.forClass(Runnable.class);
            ArgumentCaptor<Long> delay = ArgumentCaptor.forClass(Long.class);
            userThread.verify(() -> UserThread.runAfter(publish.capture(), delay.capture(), eq(TimeUnit.MILLISECONDS)));
            assertTrue(delay.getValue() >= 1 && delay.getValue() <= TimeUnit.MINUTES.toMillis(60));
            publish.getValue().run();
            ArgumentCaptor<BroadcastHandler.Listener> listeners = ArgumentCaptor.forClass(BroadcastHandler.Listener.class);
            verify(p2p).addPersistableNetworkPayload(any(TradeStatistics3.class), eq(true), listeners.capture());
            assertEquals(1, restored.getTradeStatisticsPublishTime());
            assertFalse(restored.isTradeStatisticsPublished());
            ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
            userThread.verify(() -> UserThread.runAfter(retry.capture(), eq(60L), eq(TimeUnit.MINUTES)));

            // A silent broadcast, such as losing all peers, must still be retried.
            retry.getValue().run();
            verify(p2p, times(2)).addPersistableNetworkPayload(any(TradeStatistics3.class), eq(true), listeners.capture());
            listeners.getValue().onNotSufficientlyBroadcast(0, 1);
            assertFalse(restored.isTradeStatisticsPublished());
            listeners.getValue().onSufficientlyBroadcast(List.of());
            listeners.getValue().onSufficientlyBroadcast(List.of());
            verify(restored).requestPersistence();
            assertTrue(((Trade) store.load().get(0)).isTradeStatisticsPublished());
            userThread.verify(() -> UserThread.runAfter(retry.capture(), eq(60L), eq(TimeUnit.MINUTES)), times(2));
            retry.getValue().run();
            verify(p2p, times(2)).addPersistableNetworkPayload(any(TradeStatistics3.class), eq(true), any(BroadcastHandler.Listener.class));

            Trade confirmed = spy((Trade) store.load().get(0));
            doReturn(true).when(confirmed).shouldPublishTradeStatistics();
            manager.maybePublishTradeStatistics(confirmed, null, false);
            verify(p2p, times(2)).addPersistableNetworkPayload(any(TradeStatistics3.class), eq(true), any(BroadcastHandler.Listener.class));
            userThread.verify(() -> UserThread.runAfter(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS)), times(1));
        } finally {
            HavenoUtils.tradeStatisticsManager = previousManager;
        }
    }

    @Test
    public void testTradeStatisticsRetryUntilPeerKnowsOurAddress() {
        TradeStatisticsManager previousManager = HavenoUtils.tradeStatisticsManager;
        Broadcaster broadcaster = null;
        try (var userThread = mockStatic(UserThread.class)) {
            userThread.when(() -> UserThread.execute(any(Runnable.class))).thenAnswer(invocation -> {
                invocation.getArgument(0, Runnable.class).run();
                return null;
            });
            userThread.when(() -> UserThread.runAfterRandomDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                    .thenAnswer(invocation -> {
                        invocation.getArgument(0, Runnable.class).run();
                        return null;
                    });
            NetworkNode network = mock(NetworkNode.class);
            PeerManager peers = mock(PeerManager.class);
            NodeAddress local = new NodeAddress("local:9999");
            NodeAddress seed = new NodeAddress("seed:9999");
            when(network.getNodeAddress()).thenReturn(local);
            Connection connection = mock(Connection.class);
            when(connection.getPeersNodeAddressOptional()).thenReturn(Optional.of(seed));
            when(connection.testCapability(any())).thenReturn(true);
            when(network.getConfirmedConnections()).thenReturn(Set.of(connection));
            SettableFuture<Connection> send = SettableFuture.create();
            when(network.sendMessage(eq(connection), any(NetworkEnvelope.class), any(ListeningExecutorService.class))).thenReturn(send);
            broadcaster = new Broadcaster(network, peers, 12);
            Broadcaster sender = broadcaster;
            P2PDataStorage dataStorage = mock(P2PDataStorage.class);
            when(dataStorage.addPersistableNetworkPayload(any(), eq(local), eq(true), eq(true), any()))
                    .thenAnswer(invocation -> {
                        sender.broadcast(new AddPersistableNetworkPayloadMessage(invocation.getArgument(0)), local, invocation.getArgument(4));
                        return true;
                    });
            RequestDataManager requests = mock(RequestDataManager.class);
            when(requests.getNodeAddressOfPreliminaryDataRequest()).thenReturn(Optional.of(seed));
            P2PService p2p = new P2PService(network, peers, dataStorage, requests,
                    mock(PeerExchangeManager.class), null, broadcaster, null, null, null, null);
            TradeStatistics3StorageService storage = mock(TradeStatistics3StorageService.class);
            when(storage.getMapOfAllData()).thenReturn(Map.of());
            TradeStatisticsManager manager = new TradeStatisticsManager(p2p, null, storage,
                    mock(AppendOnlyDataStoreService.class), dir, false);
            Trade trade = statisticsTrade();
            trade.setTradeStatisticsPublishTime(1);

            // preliminary data alone must not allow publication before hidden service publication
            p2p.onPreliminaryDataReceived();
            manager.maybePublishTradeStatistics(trade, null, false);
            ArgumentCaptor<Runnable> publish = ArgumentCaptor.forClass(Runnable.class);
            userThread.verify(() -> UserThread.runAfter(publish.capture(), anyLong(), eq(TimeUnit.MILLISECONDS)));
            publish.getValue().run();
            verifyNoInteractions(dataStorage);
            ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
            userThread.verify(() -> UserThread.runAfter(retry.capture(), eq(60L), eq(TimeUnit.MINUTES)));
            assertFalse(trade.isTradeStatisticsPublished());

            // network readiness starts the address handshake; its pending connection must not confirm a broadcast
            p2p.onHiddenServicePublished();
            verify(requests).requestUpdateData();
            assertTrue(p2p.isNetworkReady());
            retry.getValue().run();
            broadcaster.flush();
            verify(network, never()).sendMessage(any(Connection.class), any(NetworkEnvelope.class), any(ListeningExecutorService.class));
            assertFalse(trade.isTradeStatisticsPublished());

            when(connection.isPeerReadyForBroadcast()).thenReturn(true);
            userThread.verify(() -> UserThread.runAfter(retry.capture(), eq(60L), eq(TimeUnit.MINUTES)), times(2));
            retry.getValue().run();
            broadcaster.flush();
            assertFalse(trade.isTradeStatisticsPublished());
            send.set(connection);
            assertTrue(trade.isTradeStatisticsPublished());
            verify(trade).requestPersistence();
            userThread.verify(() -> UserThread.runAfter(retry.capture(), eq(60L), eq(TimeUnit.MINUTES)), times(3));
            retry.getValue().run();
            verify(network).sendMessage(eq(connection), any(NetworkEnvelope.class), any(ListeningExecutorService.class));
        } finally {
            if (broadcaster != null) broadcaster.shutDown(() -> {});
            HavenoUtils.tradeStatisticsManager = previousManager;
        }
    }

    private Trade statisticsTrade() {
        Trade trade = spy(handoffTrade());
        trade.setAmount(BigInteger.valueOf(1_000_000_000_000L));
        trade.setTakeOfferDate(1_700_000_000_000L);
        trade.getArbitrator().setNodeAddress(new NodeAddress("arbitrator:9999"));
        doReturn(true).when(trade).shouldPublishTradeStatistics();
        return trade;
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
