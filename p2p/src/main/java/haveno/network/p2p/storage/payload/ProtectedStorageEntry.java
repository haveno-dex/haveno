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
        return !(protectedStoragePayload instanceof MailboxStoragePayload) &&
                isSequenceNumberValid(true) &&
                ownerPubKey.equals(protectedStoragePayload.getOwnerPubKey()) &&
                isSignatureValid();
    }

    /*
     * Only the payload owner can remove a plain entry, using a remove-specific signature.
     * Mailbox payloads require the receiver checks in ProtectedMailboxStorageEntry.
     */
    public boolean isValidForRemoveOperation() {
        return !(protectedStoragePayload instanceof MailboxStoragePayload) &&
                isSequenceNumberValid(false) &&
                ownerPubKey.equals(protectedStoragePayload.getOwnerPubKey()) &&
                isSignatureValid(P2PDataStorage.getRemoveHash(protectedStoragePayload, sequenceNumber));
    }

    protected boolean isSequenceNumberValid(boolean isAddOperation) {
        // Reserve the final sequence number for removal, including receiver removal of mailbox entries.
        return sequenceNumber >= 0 && (!isAddOperation || sequenceNumber < Integer.MAX_VALUE);
    }

    /*
     * Returns true if the signature for the Entry is valid for the payload, sequence number, and ownerPubKey
     */
    boolean isSignatureValid() {
        return isSignatureValid(P2PDataStorage.get32ByteHash(
                new P2PDataStorage.DataAndSeqNrPair(protectedStoragePayload, sequenceNumber)));
    }

    private boolean isSignatureValid(byte[] hash) {
        try {
            boolean result = Sig.verify(ownerPubKey, hash, signature);

            if (!result)
                log.debug("Invalid storage signature for {} at sequence number {}",
                        protectedStoragePayload.getClass().getSimpleName(), sequenceNumber);

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
