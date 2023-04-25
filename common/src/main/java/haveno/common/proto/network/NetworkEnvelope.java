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

package haveno.common.proto.network;

import com.google.protobuf.Message;
import haveno.common.Envelope;
import lombok.EqualsAndHashCode;

import static com.google.common.base.Preconditions.checkArgument;

@EqualsAndHashCode
public abstract class NetworkEnvelope implements Envelope {

    protected final String messageVersion;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected NetworkEnvelope(String messageVersion) {
        this.messageVersion = messageVersion;
    }

    public protobuf.NetworkEnvelope.Builder getNetworkEnvelopeBuilder() {
        return protobuf.NetworkEnvelope.newBuilder().setMessageVersion(messageVersion);
    }

    @Override
    public Message toProtoMessage() {
        return getNetworkEnvelopeBuilder().build();
    }

    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        return getNetworkEnvelopeBuilder().build();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public String getMessageVersion() {
        // -1 is used for the case that we use an envelope message as payload (mailbox)
        // so we check only against 0 which is the default value if not set
        checkArgument(!messageVersion.equals("0"), "messageVersion is not set (0).");
        return messageVersion;
    }

    @Override
    public String toString() {
        return "NetworkEnvelope{" +
                "\n     messageVersion=" + messageVersion +
                "\n}";
    }
}
