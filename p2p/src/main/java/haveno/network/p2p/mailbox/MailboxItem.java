/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.network.p2p.mailbox;

import haveno.common.proto.ProtobufferException;
import haveno.common.proto.network.NetworkProtoResolver;
import haveno.common.proto.persistable.PersistablePayload;
import haveno.network.p2p.DecryptedMessageWithPubKey;
import haveno.network.p2p.storage.payload.ProtectedMailboxStorageEntry;
import lombok.Value;

import javax.annotation.Nullable;
import java.time.Clock;
import java.util.Optional;

@Value
public class MailboxItem implements PersistablePayload {
    private final ProtectedMailboxStorageEntry protectedMailboxStorageEntry;
    @Nullable
    private final DecryptedMessageWithPubKey decryptedMessageWithPubKey;

    public MailboxItem(ProtectedMailboxStorageEntry protectedMailboxStorageEntry,
                       @Nullable DecryptedMessageWithPubKey decryptedMessageWithPubKey) {
        this.protectedMailboxStorageEntry = protectedMailboxStorageEntry;
        this.decryptedMessageWithPubKey = decryptedMessageWithPubKey;
    }

    @Override
    public protobuf.MailboxItem toProtoMessage() {
        protobuf.MailboxItem.Builder builder = protobuf.MailboxItem.newBuilder()
                .setProtectedMailboxStorageEntry(protectedMailboxStorageEntry.toProtoMessage());

        Optional.ofNullable(decryptedMessageWithPubKey).ifPresent(decryptedMessageWithPubKey ->
                builder.setDecryptedMessageWithPubKey(decryptedMessageWithPubKey.toProtoMessage()));

        return builder
                .build();
    }

    public static MailboxItem fromProto(protobuf.MailboxItem proto, NetworkProtoResolver networkProtoResolver)
            throws ProtobufferException {

        DecryptedMessageWithPubKey decryptedMessageWithPubKey = proto.hasDecryptedMessageWithPubKey() ?
                DecryptedMessageWithPubKey.fromProto(proto.getDecryptedMessageWithPubKey(), networkProtoResolver) :
                null;

        return new MailboxItem(ProtectedMailboxStorageEntry.fromProto(proto.getProtectedMailboxStorageEntry(),
                networkProtoResolver),
                decryptedMessageWithPubKey);
    }

    public boolean isMine() {
        return decryptedMessageWithPubKey != null;
    }

    public String getUid() {
        if (decryptedMessageWithPubKey != null) {
            // We use uid from mailboxMessage in case its ours as we have the at removeMailboxMsg only the
            // decryptedMessageWithPubKey available which contains the mailboxMessage.
            MailboxMessage mailboxMessage = (MailboxMessage) decryptedMessageWithPubKey.getNetworkEnvelope();
            return mailboxMessage.getUid();
        } else {
            // If its not our mailbox msg we take the uid from the prefixedSealedAndSignedMessage instead.
            // Those will never be removed via removeMailboxMsg but we clean up expired entries at startup.
            return protectedMailboxStorageEntry.getMailboxStoragePayload().getPrefixedSealedAndSignedMessage().getUid();
        }
    }

    public boolean isExpired(Clock clock) {
        return protectedMailboxStorageEntry.isExpired(clock);
    }
}
