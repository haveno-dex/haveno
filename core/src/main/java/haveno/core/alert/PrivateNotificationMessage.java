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

package haveno.core.alert;

import haveno.common.app.Version;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.mailbox.MailboxMessage;
import lombok.EqualsAndHashCode;
import lombok.Value;

import java.util.concurrent.TimeUnit;

@EqualsAndHashCode(callSuper = true)
@Value
public class PrivateNotificationMessage extends NetworkEnvelope implements MailboxMessage {
    public static final long TTL = TimeUnit.DAYS.toMillis(30);

    private final PrivateNotificationPayload privateNotificationPayload;
    private final NodeAddress senderNodeAddress;
    private final String uid;

    public PrivateNotificationMessage(PrivateNotificationPayload privateNotificationPayload,
                                      NodeAddress senderNodeAddress,
                                      String uid) {
        this(privateNotificationPayload, senderNodeAddress, uid, Version.getP2PMessageVersion());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private PrivateNotificationMessage(PrivateNotificationPayload privateNotificationPayload,
                                       NodeAddress senderNodeAddress,
                                       String uid,
                                       String messageVersion) {
        super(messageVersion);
        this.privateNotificationPayload = privateNotificationPayload;
        this.senderNodeAddress = senderNodeAddress;
        this.uid = uid;
    }

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        return getNetworkEnvelopeBuilder()
                .setPrivateNotificationMessage(protobuf.PrivateNotificationMessage.newBuilder()
                        .setPrivateNotificationPayload(privateNotificationPayload.toProtoMessage())
                        .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                        .setUid(uid))
                .build();
    }

    public static PrivateNotificationMessage fromProto(protobuf.PrivateNotificationMessage proto, String messageVersion) {
        return new PrivateNotificationMessage(PrivateNotificationPayload.fromProto(proto.getPrivateNotificationPayload()),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                proto.getUid(),
                messageVersion);
    }

    @Override
    public long getTTL() {
        return TTL;
    }
}
