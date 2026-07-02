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
import com.google.inject.name.Named;
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
 * <p>Replaces the old "serialize the whole {@code TradableList} and atomically rewrite the {@code
 * ClosedTrades} file on every change" model — which is O(n) per closed trade and was the root cause
 * of the OOM-on-write that froze stores (issue #2383) — with an {@link EncryptedAppendLog}. Closing a
 * trade appends one small record (O(1)); the full history is only re-encrypted during infrequent
 * compaction. The in-memory {@code ObservableList} that the GUI binds to is unchanged.
 *
 * <p>The log holds {@link protobuf.TradableLogEntry} records: an {@code upsert} adds or replaces the
 * tradable with the matching id, a {@code delete_id} tombstones one. Replaying in order
 * (latest-wins per id, first-seen insertion order preserved) reconstructs the equivalent list.
 *
 * <p>Writes never throw into callers (trade completion and offer cancellation must not fail because
 * a history write failed): a failed batch is kept in memory and retried in order before the next
 * write. Callers are responsible for ordering: appends must be issued in the same order as the list
 * mutations they mirror (see {@code ClosedTradableManager}'s persist lock).
 */
@Slf4j
public class ClosedTradesStore {

    static final String LEGACY_FILE_NAME = "ClosedTrades";          // old monolithic store
    static final String LOG_FILE_NAME = "ClosedTrades.log";         // new append-only log
    static final String LEGACY_BACKUP_NAME = "ClosedTrades.legacy-backup";

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
    // Entries whose write failed (e.g. disk full); retried in order before the next write so a
    // transient failure delays durability instead of dropping history or breaking the caller.
    private final List<byte[]> failedEntries = new ArrayList<>();

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

    private EncryptedAppendLog appendLog() {
        if (appendLog == null) {
            SecretKey symmetricKey = keyRing.getSymmetricKey();
            if (symmetricKey == null) throw new IllegalStateException("Cannot use ClosedTradesStore before the key ring is unlocked");
            appendLog = new EncryptedAppendLog(dir, LOG_FILE_NAME, symmetricKey, NUM_MAX_BACKUP_FILES);
        }
        return appendLog;
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
     * single fsync. Never throws (a failed batch is kept and retried before the next write) except
     * for {@link OutOfMemoryError}, which is rethrown so heap exhaustion is never mistaken for a
     * write failure.
     */
    public void appendEntries(List<byte[]> entries) {
        synchronized (failedEntries) {
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
            } catch (OutOfMemoryError e) {
                throw e;
            } catch (Throwable t) {
                log.error("Could not append {} record(s) to {}; keeping them in memory and retrying on the next write.",
                        batch.size(), LOG_FILE_NAME, t);
                failedEntries.clear();
                failedEntries.addAll(batch);
            }
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
     * Loads the closed trades, merging in any legacy monolithic store that has not been migrated
     * yet. Returns the reconstructed list in original insertion order. Must be called with the key
     * ring unlocked.
     *
     * <p>Records that are authenticated but cannot be decoded (schema from a newer version, or a
     * decode fault) are skipped rather than failing the whole load; when that happens the log is
     * reported via {@link CorruptedStorageFileHandler} so the user is notified, and compaction is
     * suppressed so the skipped records stay on disk for a fixed build to recover.
     */
    public List<Tradable> load() {
        List<byte[]> records = appendLog().readAllValidRecords();

        // Replay keyed by id: upsert replaces in place (LinkedHashMap.put keeps the original position),
        // delete removes. Iteration order is therefore the first-seen insertion order. seenIds tracks
        // every id the log has ever mentioned (including tombstoned ones) for the legacy merge below.
        LinkedHashMap<String, Tradable> byId = new LinkedHashMap<>();
        Set<String> seenIds = new HashSet<>();
        int skipped = 0;
        for (byte[] record : records) {
            try {
                protobuf.TradableLogEntry entry = protobuf.TradableLogEntry.parseFrom(record);
                switch (entry.getEntryCase()) {
                    case UPSERT:
                        Tradable tradable = TradableList.tradableFromProto(entry.getUpsert(), protoResolver, xmrWalletService.get());
                        byId.put(tradable.getId(), tradable);
                        seenIds.add(tradable.getId());
                        break;
                    case DELETE_ID:
                        byId.remove(entry.getDeleteId());
                        seenIds.add(entry.getDeleteId());
                        break;
                    default:
                        // An authenticated record with no known entry set is most likely a new entry
                        // type written by a newer version; keep it on disk and continue.
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

        maybeMergeLegacy(byId, seenIds);

        List<Tradable> result = new ArrayList<>(byId.values());
        // Never compact while undecodable records exist - a rewrite from the decoded trades would
        // permanently drop them.
        if (skipped == 0) maybeCompact(records.size(), result);
        return result;
    }

    private void maybeCompact(int recordsRead, List<Tradable> liveTrades) {
        int live = liveTrades.size();
        if (!shouldCompact(recordsRead, live)) return;
        log.info("Compacting {}: {} records -> {} live trades", LOG_FILE_NAME, recordsRead, live);
        List<byte[]> compacted = new ArrayList<>(live);
        for (Tradable tradable : liveTrades) compacted.add(upsertBytes(tradable));
        appendLog().rewrite(compacted);
    }

    // Compact once the log carries enough superseded/tombstoned records that a rewrite is worth it,
    // but never on every small change. Package-private for testing.
    static boolean shouldCompact(int recordsRead, int liveTrades) {
        return recordsRead > Math.max((long) liveTrades * COMPACTION_RATIO, MIN_RECORDS_FOR_COMPACTION);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Migration
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Merges a legacy monolithic ClosedTrades file into the log whenever one is present - not only
    // on first run. Gating on the log's existence would permanently orphan legacy history whenever a
    // legacy file (re)appears after the log was created, e.g. after a downgrade/upgrade cycle (the
    // old build recreates "ClosedTrades" for trades closed while downgraded) or after a deferred
    // first migration in a session that already appended. Only trades whose id the log has never
    // mentioned are merged, so tombstoned or updated trades are not resurrected. The legacy file is
    // renamed to a backup only after the merge is durably appended; a failure defers to a later start.
    private void maybeMergeLegacy(LinkedHashMap<String, Tradable> byId, Set<String> seenIds) {
        File legacyFile = new File(dir, LEGACY_FILE_NAME);
        if (!legacyFile.exists()) return;

        log.info("Merging legacy monolithic {} into append-only {}", LEGACY_FILE_NAME, LOG_FILE_NAME);
        TradableList<Tradable> legacy = legacyPersistenceManager.getPersisted(LEGACY_FILE_NAME);
        if (legacy == null) {
            // The read failed. Either it was transient (key ring not ready / shutting down) - in which
            // case we must retry on a later start - or the file was genuinely corrupt, in which case
            // getPersisted has already moved it to backup_of_corrupted_data (so legacyFile no longer
            // exists and the next start skips the merge). In neither case may we rename the legacy
            // file: that would strand real history and block the retry.
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
