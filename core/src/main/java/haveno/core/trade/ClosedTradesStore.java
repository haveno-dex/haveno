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

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import haveno.common.UserThread;
import haveno.common.config.Config;
import haveno.common.crypto.KeyRing;
import haveno.common.file.CorruptedStorageFileHandler;
import haveno.common.file.FileUtil;
import haveno.common.persistence.EncryptedAppendLog;
import haveno.common.persistence.PersistenceManager;
import haveno.core.proto.persistable.CorePersistenceProtoResolver;
import haveno.core.xmr.wallet.XmrWalletService;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;

/**
 * Append-only, encrypted backing store for the closed-trade history.
 *
 * <p>Replaces the old "re-serialize the whole {@code TradableList} on every change" model - O(n)
 * per closed trade and the root cause of the OOM-on-write in issue #2383 - with an
 * {@link EncryptedAppendLog}: closing a trade appends one small record, and the full history is
 * only rewritten during infrequent compaction.
 *
 * <p>The log holds {@link protobuf.TradableLogEntry} records: an {@code upsert} adds or replaces
 * the tradable with the matching id, a {@code delete_id} tombstones one. Replaying in order
 * (latest-wins per id, first-seen position kept) reconstructs the equivalent list.
 *
 * <p>Writes never throw into callers: a failed batch is kept in memory, mirrored to a pending file
 * so it survives a process kill, and retried in order before the next write. Appends must be issued
 * in the same order as the list mutations they mirror (see {@code ClosedTradableManager}'s persist
 * lock).
 */
@Slf4j
@Singleton
public class ClosedTradesStore {

    static final String LEGACY_FILE_NAME = "ClosedTrades";          // old monolithic store
    static final String LOG_FILE_NAME = "ClosedTrades.log";         // new append-only log
    static final String LEGACY_BACKUP_NAME = "ClosedTrades.legacy-backup";
    static final String PENDING_FILE_NAME = "ClosedTrades.log.pending"; // durable copy of the failed-write queue

    private static final int NUM_MAX_BACKUP_FILES = 3;
    // Compact when the log holds a lot more records than live trades (mutations/tombstones accumulated)
    // and it is worth the rewrite. Keeps replay bounded without rewriting on every small change.
    private static final int MIN_RECORDS_FOR_COMPACTION = 512;
    private static final int COMPACTION_RATIO = 2;

    private final File dir;
    private final KeyRing keyRing;
    private final CorePersistenceProtoResolver protoResolver;
    private final Provider<XmrWalletService> xmrWalletService;
    private final CorruptedStorageFileHandler corruptedStorageFileHandler;
    // Used only for the one-time legacy read during migration; never initialize()d, so it is not
    // registered for shutdown flush and will not rewrite the (renamed) legacy file.
    private final PersistenceManager<TradableList<Tradable>> legacyPersistenceManager;

    private EncryptedAppendLog appendLog;
    private EncryptedAppendLog pendingLog;
    // Entries whose write failed (e.g. disk full); mirrored to the pending file (best effort) and
    // retried in order before the next write, on a timer, and at shutdown.
    private final List<byte[]> failedEntries = new ArrayList<>();
    private boolean retryScheduled = false; // guarded by failedEntries
    private static final long RETRY_DELAY_SEC = 30;

    @Inject
    public ClosedTradesStore(@Named(Config.STORAGE_DIR) File dir,
                             KeyRing keyRing,
                             CorePersistenceProtoResolver protoResolver,
                             Provider<XmrWalletService> xmrWalletService,
                             CorruptedStorageFileHandler corruptedStorageFileHandler,
                             PersistenceManager<TradableList<Tradable>> legacyPersistenceManager) {
        this.dir = dir;
        this.keyRing = keyRing;
        this.protoResolver = protoResolver;
        this.xmrWalletService = xmrWalletService;
        this.corruptedStorageFileHandler = corruptedStorageFileHandler;
        this.legacyPersistenceManager = legacyPersistenceManager;
    }

    private synchronized EncryptedAppendLog appendLog() {
        if (appendLog == null) {
            SecretKey symmetricKey = keyRing.getSymmetricKey();
            if (symmetricKey == null) throw new IllegalStateException("Cannot use ClosedTradesStore before the key ring is unlocked");
            appendLog = new EncryptedAppendLog(dir, LOG_FILE_NAME, symmetricKey, NUM_MAX_BACKUP_FILES);
        }
        return appendLog;
    }

    private synchronized EncryptedAppendLog pendingLog() {
        if (pendingLog == null) {
            SecretKey symmetricKey = keyRing.getSymmetricKey();
            if (symmetricKey == null) throw new IllegalStateException("Cannot use ClosedTradesStore before the key ring is unlocked");
            pendingLog = new EncryptedAppendLog(dir, PENDING_FILE_NAME, symmetricKey, 1);
        }
        return pendingLog;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Writes
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void appendUpsert(Tradable tradable) {
        appendEntries(List.of(upsertBytes(tradable)));
    }

    public void appendDelete(String id) {
        appendEntries(List.of(deleteBytes(id)));
    }

    /**
     * Appends the given pre-encoded {@link protobuf.TradableLogEntry} records as one batch with a
     * single fsync. Never throws (a failed batch is queued and retried) except OutOfMemoryError,
     * so heap exhaustion is never mistaken for a write failure.
     */
    public void appendEntries(List<byte[]> entries) {
        synchronized (failedEntries) {
            boolean hadFailed = !failedEntries.isEmpty();
            List<byte[]> batch;
            if (failedEntries.isEmpty()) {
                batch = entries;
            } else {
                batch = new ArrayList<>(failedEntries.size() + entries.size());
                batch.addAll(failedEntries);
                batch.addAll(entries);
            }
            if (batch.isEmpty()) return;
            try {
                appendLog().appendAll(batch);
                failedEntries.clear();
                if (hadFailed) clearPendingQueue(); // the recovered records are in the log now
            } catch (OutOfMemoryError e) {
                throw e;
            } catch (Throwable t) {
                log.error("Could not append {} record(s) to {}; queueing them for retry.",
                        batch.size(), LOG_FILE_NAME, t);
                failedEntries.clear();
                failedEntries.addAll(batch);
                persistPendingQueue(batch);
                if (!retryScheduled) {
                    retryScheduled = true;
                    UserThread.runAfter(() -> {
                        synchronized (failedEntries) {
                            retryScheduled = false;
                        }
                        flushFailedEntries();
                    }, RETRY_DELAY_SEC);
                }
            }
        }
    }

    /**
     * Retries any queued failed entries now; no-op when the queue is empty. Called on a timer after
     * a failed write and from the shutdown sequence, so queued mutations do not die with the process.
     */
    public void flushFailedEntries() {
        appendEntries(List.of());
    }

    // Best-effort durable copy of the failed-write queue, so a queued batch survives a process
    // kill. May fail like the append did (e.g. disk full); the queue is then memory-only until a
    // retry succeeds.
    private void persistPendingQueue(List<byte[]> records) {
        try {
            pendingLog().rewrite(records);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.warn("Could not persist the failed-write queue to {}", PENDING_FILE_NAME, t);
        }
    }

    private void clearPendingQueue() {
        try {
            if (pendingLog().exists()) pendingLog().rewrite(List.of());
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.warn("Could not clear the failed-write queue {}", PENDING_FILE_NAME, t);
        }
    }

    static byte[] upsertBytes(Tradable tradable) {
        return protobuf.TradableLogEntry.newBuilder()
                .setUpsert((protobuf.Tradable) tradable.toProtoMessage())
                .build()
                .toByteArray();
    }

    static byte[] deleteBytes(String id) {
        return protobuf.TradableLogEntry.newBuilder()
                .setDeleteId(id)
                .build()
                .toByteArray();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Read / replay
    ///////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Loads the closed trades, merging any not-yet-migrated legacy store and any pending records
     * from a previously failed write. Returns the list in original insertion order; must be called
     * with the key ring unlocked. Authenticated but undecodable records (e.g. from a newer version)
     * are skipped, surfaced via {@link CorruptedStorageFileHandler}, and left on disk with
     * compaction suppressed so a fixed build can recover them.
     */
    public List<Tradable> load() {
        List<byte[]> records = appendLog().readAllValidRecords();

        // Merge in records whose append failed in a previous session; they replay after the log
        // and are re-appended to it below.
        List<byte[]> pendingRecords = pendingLog().readAllValidRecords();
        if (!pendingRecords.isEmpty()) {
            log.warn("Recovering {} record(s) from {} after a failed write in a previous session.",
                    pendingRecords.size(), PENDING_FILE_NAME);
            List<byte[]> combined = new ArrayList<>(records.size() + pendingRecords.size());
            combined.addAll(records);
            combined.addAll(pendingRecords);
            records = combined;
        }

        // Replay keyed by id: upsert replaces in place (LinkedHashMap keeps the original position),
        // delete removes. seenIds tracks every id ever mentioned, for the legacy merge below.
        LinkedHashMap<String, Tradable> byId = new LinkedHashMap<>();
        Set<String> seenIds = new HashSet<>();
        Set<String> deletedIds = new HashSet<>();
        int skipped = 0;
        for (byte[] record : records) {
            try {
                protobuf.TradableLogEntry entry = protobuf.TradableLogEntry.parseFrom(record);
                switch (entry.getEntryCase()) {
                    case UPSERT:
                        Tradable tradable = TradableList.tradableFromProto(entry.getUpsert(), protoResolver, xmrWalletService.get());
                        byId.put(tradable.getId(), tradable);
                        seenIds.add(tradable.getId());
                        deletedIds.remove(tradable.getId());
                        break;
                    case DELETE_ID:
                        byId.remove(entry.getDeleteId());
                        seenIds.add(entry.getDeleteId());
                        deletedIds.add(entry.getDeleteId());
                        break;
                    default:
                        // An unknown entry type is most likely from a newer version; keep it on disk.
                        log.warn("Skipping {} record with unknown entry type", LOG_FILE_NAME);
                        skipped++;
                }
            } catch (OutOfMemoryError e) {
                throw e;
            } catch (Throwable t) {
                // Authenticated but undecodable (e.g. written by a newer version). Skip just this
                // record instead of hiding the whole history; it stays on disk for recovery.
                log.error("Skipping an undecodable record in {}", LOG_FILE_NAME, t);
                skipped++;
            }
        }
        if (skipped > 0) {
            log.error("Skipped {} undecodable record(s) while loading {}. They remain on disk; compaction is suppressed.",
                    skipped, LOG_FILE_NAME);
            corruptedStorageFileHandler.addFile(LOG_FILE_NAME);
        }

        // Re-append the recovered pending records to the log (clears the pending file on success),
        // so the on-disk log replays to this same state.
        if (!pendingRecords.isEmpty()) {
            synchronized (failedEntries) {
                failedEntries.addAll(0, pendingRecords);
            }
            flushFailedEntries();
        }

        maybeMergeLegacy(byId, seenIds);

        List<Tradable> result = new ArrayList<>(byId.values());
        // Never compact while undecodable records exist - a rewrite from the decoded trades would
        // permanently drop them.
        if (skipped == 0) maybeCompact(records.size(), result, deletedIds);
        return result;
    }

    // Compaction keeps one tombstone per deleted id: the legacy merge skips ids the log has ever
    // mentioned, so dropping tombstones could let a reappearing legacy file resurrect deleted
    // trades. Best effort - a failed rewrite never fails the load; the log on disk is intact.
    private void maybeCompact(int recordsRead, List<Tradable> liveTrades, Set<String> deletedIds) {
        int compactedSize = liveTrades.size() + deletedIds.size();
        if (!shouldCompact(recordsRead, compactedSize)) return;
        log.info("Compacting {}: {} records -> {} live trades + {} tombstones", LOG_FILE_NAME, recordsRead, liveTrades.size(), deletedIds.size());
        List<byte[]> compacted = new ArrayList<>(compactedSize);
        for (Tradable tradable : liveTrades) compacted.add(upsertBytes(tradable));
        for (String id : deletedIds) compacted.add(deleteBytes(id));
        try {
            appendLog().rewrite(compacted);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.error("Compaction of {} failed; keeping the existing log.", LOG_FILE_NAME, t);
        }
    }

    // Compact once the log carries enough superseded records to be worth a rewrite.
    // retainedRecords = live trades + tombstones. Package-private for testing.
    static boolean shouldCompact(int recordsRead, int retainedRecords) {
        return recordsRead > Math.max((long) retainedRecords * COMPACTION_RATIO, MIN_RECORDS_FOR_COMPACTION);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Migration
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Merges a legacy monolithic ClosedTrades file into the log whenever one is present (it can
    // reappear after a downgrade/upgrade cycle or a deferred first migration). Only ids the log
    // has never mentioned are merged, so tombstoned or updated trades are not resurrected. The
    // legacy file is renamed to a backup only after the merge is durably appended.
    private void maybeMergeLegacy(LinkedHashMap<String, Tradable> byId, Set<String> seenIds) {
        File legacyFile = new File(dir, LEGACY_FILE_NAME);
        if (!legacyFile.exists()) return;

        log.info("Merging legacy monolithic {} into append-only {}", LEGACY_FILE_NAME, LOG_FILE_NAME);
        TradableList<Tradable> legacy = legacyPersistenceManager.getPersisted(LEGACY_FILE_NAME);
        if (legacy == null) {
            // Transient failure (key ring not ready, shutting down) - retry on a later start - or
            // genuine corruption, in which case getPersisted already moved the file to backup.
            // Either way never rename the legacy file: that would strand real history.
            log.warn("Legacy {} present but could not be read; deferring migration", LEGACY_FILE_NAME);
            return;
        }
        List<byte[]> entries = new ArrayList<>();
        List<Tradable> merged = new ArrayList<>();
        synchronized (legacy.getList()) {
            for (Tradable tradable : legacy.getList()) {
                if (seenIds.contains(tradable.getId())) continue; // already migrated, superseded, or deliberately deleted
                entries.add(upsertBytes(tradable));
                merged.add(tradable);
            }
        }
        try {
            appendLog().appendAll(entries);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            log.warn("Could not append legacy {} trades to {}; deferring migration", LEGACY_FILE_NAME, LOG_FILE_NAME, t);
            return;
        }
        for (Tradable tradable : merged) byId.put(tradable.getId(), tradable);

        try {
            File backupFile = new File(dir, LEGACY_BACKUP_NAME);
            // Keep any earlier backup (it may hold the original pre-migration history).
            if (backupFile.exists()) backupFile = new File(dir, LEGACY_BACKUP_NAME + "." + System.currentTimeMillis());
            FileUtil.renameFile(legacyFile, backupFile);
        } catch (IOException e) {
            log.warn("Could not rename legacy {} to a backup; leaving it in place", LEGACY_FILE_NAME, e);
        }
    }
}
