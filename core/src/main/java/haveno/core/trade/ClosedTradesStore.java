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
import com.google.protobuf.InvalidProtocolBufferException;
import haveno.common.config.Config;
import haveno.common.crypto.CryptoException;
import haveno.common.crypto.KeyRing;
import haveno.common.file.FileUtil;
import haveno.common.persistence.EncryptedAppendLog;
import haveno.common.persistence.PersistenceManager;
import haveno.core.proto.persistable.CorePersistenceProtoResolver;
import haveno.core.xmr.wallet.XmrWalletService;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    // Used only for the one-time legacy read during migration; never initialize()d, so it is not
    // registered for shutdown flush and will not rewrite the (renamed) legacy file.
    private final PersistenceManager<TradableList<Tradable>> legacyPersistenceManager;

    private EncryptedAppendLog appendLog;

    @Inject
    public ClosedTradesStore(@Named(Config.STORAGE_DIR) File dir,
                             KeyRing keyRing,
                             CorePersistenceProtoResolver protoResolver,
                             Provider<XmrWalletService> xmrWalletService,
                             PersistenceManager<TradableList<Tradable>> legacyPersistenceManager) {
        this.dir = dir;
        this.keyRing = keyRing;
        this.protoResolver = protoResolver;
        this.xmrWalletService = xmrWalletService;
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
        append(protobuf.TradableLogEntry.newBuilder()
                .setUpsert((protobuf.Tradable) tradable.toProtoMessage())
                .build());
    }

    public void appendDelete(String id) {
        append(protobuf.TradableLogEntry.newBuilder()
                .setDeleteId(id)
                .build());
    }

    private void append(protobuf.TradableLogEntry entry) {
        try {
            appendLog().append(entry.toByteArray());
        } catch (CryptoException e) {
            throw new RuntimeException("Could not append to " + LOG_FILE_NAME, e);
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Read / replay
    ///////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Loads the closed trades, migrating a legacy monolithic store on first run. Returns the
     * reconstructed list in original insertion order. Must be called with the key ring unlocked.
     */
    public List<Tradable> load() {
        maybeMigrateLegacy();

        List<byte[]> records = appendLog().readAllValidRecords();
        // Replay keyed by id: upsert replaces in place (LinkedHashMap.put keeps the original position),
        // delete removes. Iteration order is therefore the first-seen insertion order.
        LinkedHashMap<String, Tradable> byId = new LinkedHashMap<>();
        for (byte[] record : records) {
            protobuf.TradableLogEntry entry;
            try {
                entry = protobuf.TradableLogEntry.parseFrom(record);
            } catch (InvalidProtocolBufferException e) {
                // Records are HMAC-authenticated, so a parse failure is a schema/version fault, not
                // corruption; fail loudly rather than silently drop a trade.
                throw new RuntimeException("Could not parse an authenticated TradableLogEntry from " + LOG_FILE_NAME, e);
            }
            switch (entry.getEntryCase()) {
                case UPSERT:
                    Tradable tradable = TradableList.tradableFromProto(entry.getUpsert(), protoResolver, xmrWalletService.get());
                    byId.put(tradable.getId(), tradable);
                    break;
                case DELETE_ID:
                    byId.remove(entry.getDeleteId());
                    break;
                default:
                    log.warn("Ignoring {} record with no entry set", LOG_FILE_NAME);
            }
        }

        List<Tradable> result = new ArrayList<>(byId.values());
        maybeCompact(records.size(), result);
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

    private static byte[] upsertBytes(Tradable tradable) {
        return protobuf.TradableLogEntry.newBuilder()
                .setUpsert((protobuf.Tradable) tradable.toProtoMessage())
                .build()
                .toByteArray();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Migration
    ///////////////////////////////////////////////////////////////////////////////////////////

    // One-time migration: if the legacy monolithic ClosedTrades exists and no log does yet, read the
    // legacy file once (reusing PersistenceManager's streaming decrypt), write its trades into a fresh
    // log, and freeze the legacy file as a backup. The log is created via an atomic rename, so its
    // existence means migration finished; a crash mid-migration simply re-runs it next start.
    private void maybeMigrateLegacy() {
        File logFile = new File(dir, LOG_FILE_NAME);
        File legacyFile = new File(dir, LEGACY_FILE_NAME);
        if (logFile.exists() || !legacyFile.exists()) return;

        log.info("Migrating legacy monolithic {} to append-only {}", LEGACY_FILE_NAME, LOG_FILE_NAME);
        TradableList<Tradable> legacy = legacyPersistenceManager.getPersisted(LEGACY_FILE_NAME);
        if (legacy == null) {
            // The read failed. Either it was transient (key ring not ready / shutting down) — in which
            // case we must retry on a later start — or the file was genuinely corrupt, in which case
            // getPersisted has already moved it to backup_of_corrupted_data (so legacyFile no longer
            // exists and the next start skips migration). In neither case may we create an empty log and
            // rename the legacy file to a backup: that would strand real history and block the retry.
            log.warn("Legacy {} present but could not be read; deferring migration", LEGACY_FILE_NAME);
            return;
        }
        List<byte[]> records = new ArrayList<>();
        synchronized (legacy.getList()) {
            for (Tradable tradable : legacy.getList()) records.add(upsertBytes(tradable));
        }
        appendLog().rewrite(records);

        if (legacyFile.exists()) {
            try {
                FileUtil.renameFile(legacyFile, new File(dir, LEGACY_BACKUP_NAME));
            } catch (IOException e) {
                log.warn("Could not rename legacy {} to {}; leaving it in place", LEGACY_FILE_NAME, LEGACY_BACKUP_NAME, e);
            }
        }
    }
}
