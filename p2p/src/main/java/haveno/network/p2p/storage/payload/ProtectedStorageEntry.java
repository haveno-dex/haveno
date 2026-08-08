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

package haveno.network.p2p.storage.payload;

import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import haveno.common.crypto.CryptoException;
import haveno.common.crypto.Sig;
import haveno.common.proto.network.GetDataResponsePriority;
import haveno.common.proto.network.NetworkPayload;
import haveno.common.proto.network.NetworkProtoResolver;
import haveno.common.proto.persistable.PersistablePayload;
import haveno.common.util.Utilities;
import haveno.network.p2p.storage.P2PDataStorage;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.security.PublicKey;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

@Getter
@EqualsAndHashCode
@Slf4j
public class ProtectedStorageEntry implements NetworkPayload, PersistablePayload {
    private final ProtectedStoragePayload protectedStoragePayload;
    private final byte[] ownerPubKeyBytes;
    transient private final PublicKey ownerPubKey;
    private final int sequenceNumber;
    private final byte[] signature;
    private long creationTimeStamp;

    // Throttle for the remove-signature-failure warning (a failed remove signature is expected from peers on
    // an older version during a mandatory update).
    private static final long REMOVE_SIG_WARN_INTERVAL_MS = 60_000;
    private static final AtomicLong lastRemoveSigWarnMs = new AtomicLong(0);
    private static final AtomicLong suppressedRemoveSigWarnCount = new AtomicLong(0);

    public ProtectedStorageEntry(@NotNull ProtectedStoragePayload protectedStoragePayload,
                                 @NotNull PublicKey ownerPubKey,
                                 int sequenceNumber,
                                 byte[] signature,
                                 Clock clock) {
        this(protectedStoragePayload,
                Sig.getPublicKeyBytes(ownerPubKey),
                ownerPubKey,
                sequenceNumber,
                signature,
                clock.millis(),
                clock);
    }

    protected ProtectedStorageEntry(@NotNull ProtectedStoragePayload protectedStoragePayload,
                                    byte[] ownerPubKeyBytes,
                                    @NotNull PublicKey ownerPubKey,
                                    int sequenceNumber,
                                    byte[] signature,
                                    long creationTimeStamp,
                                    Clock clock) {

        Preconditions.checkArgument(!(protectedStoragePayload instanceof PersistableNetworkPayload));

        this.protectedStoragePayload = protectedStoragePayload;
        this.ownerPubKeyBytes = ownerPubKeyBytes;
        this.ownerPubKey = ownerPubKey;

        this.sequenceNumber = sequenceNumber;
        this.signature = signature;

        // We don't allow creation date in the future, but we cannot be too strict as clocks are not synced
        this.creationTimeStamp = Math.min(creationTimeStamp, clock.millis());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private ProtectedStorageEntry(@NotNull ProtectedStoragePayload protectedStoragePayload,
                                  byte[] ownerPubKeyBytes,
                                  int sequenceNumber,
                                  byte[] signature,
                                  long creationTimeStamp,
                                  Clock clock) {
        this(protectedStoragePayload,
                ownerPubKeyBytes,
                Sig.getPublicKeyFromBytes(ownerPubKeyBytes),
                sequenceNumber,
                signature,
                creationTimeStamp,
                clock);
    }

    public Message toProtoMessage() {
        return protobuf.ProtectedStorageEntry.newBuilder()
                .setStoragePayload((protobuf.StoragePayload) protectedStoragePayload.toProtoMessage())
                .setOwnerPubKeyBytes(ByteString.copyFrom(ownerPubKeyBytes))
                .setSequenceNumber(sequenceNumber)
                .setSignature(ByteString.copyFrom(signature))
                .setCreationTimeStamp(creationTimeStamp)
                .build();
    }

    public protobuf.ProtectedStorageEntry toProtectedStorageEntry() {
        return (protobuf.ProtectedStorageEntry) toProtoMessage();

    }

    public static ProtectedStorageEntry fromProto(protobuf.ProtectedStorageEntry proto,
                                                  NetworkProtoResolver resolver) {
        return new ProtectedStorageEntry(
                ProtectedStoragePayload.fromProto(proto.getStoragePayload(), resolver),
                proto.getOwnerPubKeyBytes().toByteArray(),
                proto.getSequenceNumber(),
                proto.getSignature().toByteArray(),
                proto.getCreationTimeStamp(),
                resolver.getClock());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void backDate() {
        if (protectedStoragePayload instanceof ExpirablePayload)
            creationTimeStamp -= ((ExpirablePayload) protectedStoragePayload).getTTL() / 2;
    }

    public boolean isExpired(Clock clock) {
        return protectedStoragePayload instanceof ExpirablePayload &&
                (clock.millis() - creationTimeStamp) > ((ExpirablePayload) protectedStoragePayload).getTTL();
    }

    public GetDataResponsePriority getGetDataResponsePriority() {
        return protectedStoragePayload.getGetDataResponsePriority();
    }

    /*
     * Returns true if the Entry is valid for an add operation. For non-mailbox Entrys, the entry owner must
     * match the payload owner.
     */
    public boolean isValidForAddOperation() {

        // Reject a sequence number that can no longer be superseded, so an entry cannot permanently lock its
        // payload out of future add/refresh/remove updates via integer overflow.
        if (sequenceNumber < 0 || sequenceNumber == Integer.MAX_VALUE) {
            log.warn("ProtectedStorageEntry::isValidForAddOperation() rejected out-of-range sequenceNumber {}", sequenceNumber);
            return false;
        }

        if (!this.isSignatureValid())
            return false;

        // TODO: The code currently supports MailboxStoragePayload objects inside ProtectedStorageEntry. Fix this.
        if (protectedStoragePayload instanceof MailboxStoragePayload) {
            MailboxStoragePayload mailboxStoragePayload = (MailboxStoragePayload) this.getProtectedStoragePayload();
            return mailboxStoragePayload.getSenderPubKeyForAddOperation().equals(this.getOwnerPubKey());

        } else {
            boolean result = this.ownerPubKey.equals(protectedStoragePayload.getOwnerPubKey());

            if (!result) {
                String res1 = this.toString();
                String res2 = "null";
                if (protectedStoragePayload.getOwnerPubKey() != null)
                    res2 = Utilities.encodeToHex(protectedStoragePayload.getOwnerPubKey().getEncoded(), true);

                log.warn("ProtectedStorageEntry::isValidForAddOperation() failed. Entry owner does not match Payload owner:\n" +
                        "ProtectedStorageEntry={}\nPayloadOwner={}", res1, res2);
            }

            return result;
        }
    }

    /*
     * Returns true if the Entry is valid for a remove operation. For non-mailbox Entrys, the entry owner must
     * match the payload owner.
     */
    public boolean isValidForRemoveOperation() {

        // A MailboxStoragePayload must be carried by a ProtectedMailboxStorageEntry, which overrides this method
        // and enforces receiver-only removal. Reject one smuggled into a plain entry, otherwise a captured
        // mailbox add could be replayed as a plain remove to suppress a victim's mailbox message.
        if (protectedStoragePayload instanceof MailboxStoragePayload) {
            log.warn("ProtectedStorageEntry::isValidForRemoveOperation() rejected a MailboxStoragePayload carried by a plain entry");
            return false;
        }

        // Reject a non-superseding sequence number (as in isValidForAddOperation).
        if (sequenceNumber < 0 || sequenceNumber == Integer.MAX_VALUE) {
            log.warn("ProtectedStorageEntry::isValidForRemoveOperation() rejected out-of-range sequenceNumber {}", sequenceNumber);
            return false;
        }

        // The entry owner must match the payload owner.
        if (!this.ownerPubKey.equals(protectedStoragePayload.getOwnerPubKey())) {
            log.warn("ProtectedStorageEntry::isValidForRemoveOperation() failed. Entry owner does not match Payload owner.\n{}", this);
            return false;
        }

        // The signature must be bound to the remove operation (over getRemoveHash), so a captured add/refresh
        // signature cannot be replayed as a remove to force-cancel a maker's live order.
        return isSignatureValidForRemove();
    }

    /*
     * Returns true if the signature is valid for a remove of the payload at this sequence number and ownerPubKey.
     */
    boolean isSignatureValidForRemove() {
        try {
            byte[] removeHash = P2PDataStorage.getRemoveHash(this.protectedStoragePayload, this.sequenceNumber);

            boolean result = Sig.verify(this.ownerPubKey, removeHash, this.signature);

            if (!result)
                warnRemoveSigFailureThrottled();

            return result;
        } catch (CryptoException e) {
            log.error("ProtectedStorageEntry::isSignatureValidForRemove() exception {}", e.toString());
            return false;
        }
    }

    // Keep the warning visible so a genuine replayed/corrupt removal is never silently ignored, but throttle it
    // so removals from peers on an older version during a mandatory update cannot flood the log.
    private void warnRemoveSigFailureThrottled() {
        long now = System.currentTimeMillis();
        long last = lastRemoveSigWarnMs.get();
        if (now - last >= REMOVE_SIG_WARN_INTERVAL_MS && lastRemoveSigWarnMs.compareAndSet(last, now)) {
            long suppressed = suppressedRemoveSigWarnCount.getAndSet(0);
            log.warn("Rejected a removal with an invalid remove-operation signature ({}){}. Expected from peers on an older version during a mandatory update; otherwise a replayed or corrupt removal.",
                    protectedStoragePayload.getClass().getSimpleName(),
                    suppressed > 0 ? " (+" + suppressed + " more suppressed since last warning)" : "");
        } else {
            suppressedRemoveSigWarnCount.incrementAndGet();
            log.debug("ProtectedStorageEntry::isSignatureValidForRemove() failed for {} seqNr {}",
                    protectedStoragePayload.getClass().getSimpleName(), sequenceNumber);
        }
    }

    /*
     * Returns true if the signature for the Entry is valid for the payload, sequence number, and ownerPubKey
     */
    boolean isSignatureValid() {
        try {
            byte[] hashOfDataAndSeqNr = P2PDataStorage.get32ByteHash(
                    new P2PDataStorage.DataAndSeqNrPair(this.protectedStoragePayload, this.sequenceNumber));

            boolean result = Sig.verify(this.ownerPubKey, hashOfDataAndSeqNr, this.signature);

            if (!result)
                log.warn("ProtectedStorageEntry::isSignatureValid() failed.\n{}}", this);

            return result;
        } catch (CryptoException e) {
            log.error("ProtectedStorageEntry::isSignatureValid() exception {}", e.toString());
            return false;
        }
    }

    /*
     * Returns true if the Entry metadata that is expected to stay constant between different versions of the same object
     * matches.
     */
    public boolean matchesRelevantPubKey(ProtectedStorageEntry protectedStorageEntry) {
        boolean result = protectedStorageEntry.getOwnerPubKey().equals(this.ownerPubKey);

        if (!result) {
            log.warn("New data entry does not match our stored data. storedData.ownerPubKey={}, ownerPubKey={}}",
                    protectedStorageEntry.getOwnerPubKey().toString(), this.ownerPubKey);
        }

        return result;
    }

    @Override
    public String toString() {
        return "ProtectedStorageEntry {" +
                "\n\tPayload:                 " + protectedStoragePayload +
                "\n\tOwner Public Key:        " + Utilities.bytesAsHexString(this.ownerPubKeyBytes) +
                "\n\tSequence Number:         " + this.sequenceNumber +
                "\n\tSignature:               " + Utilities.bytesAsHexString(this.signature) +
                "\n\tTimestamp:               " + this.creationTimeStamp +
                "\n} ";
    }
}
