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

package haveno.network.p2p.peers.peerexchange.messages;

import haveno.common.app.Capabilities;
import haveno.common.app.Version;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.SupportedCapabilitiesMessage;
import haveno.network.p2p.peers.peerexchange.Peer;
import lombok.EqualsAndHashCode;
import lombok.Value;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@EqualsAndHashCode(callSuper = true)
@Value
public final class GetPeersResponse extends NetworkEnvelope implements PeerExchangeMessage, SupportedCapabilitiesMessage {
    private final int requestNonce;
    private final Set<Peer> reportedPeers;
    @Nullable
    private final Capabilities supportedCapabilities;

    public GetPeersResponse(int requestNonce,
                            Set<Peer> reportedPeers) {
        this(requestNonce,
                reportedPeers,
                Capabilities.app,
                Version.getP2PMessageVersion());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private GetPeersResponse(int requestNonce,
                             Set<Peer> reportedPeers,
                             @Nullable Capabilities supportedCapabilities,
                             String messageVersion) {
        super(messageVersion);
        this.requestNonce = requestNonce;
        this.reportedPeers = reportedPeers;
        this.supportedCapabilities = supportedCapabilities;
    }

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        // We clone to avoid ConcurrentModificationExceptions
        Set<Peer> clone = new HashSet<>(reportedPeers);
        protobuf.GetPeersResponse.Builder builder = protobuf.GetPeersResponse.newBuilder()
                .setRequestNonce(requestNonce)
                .addAllReportedPeers(clone.stream()
                        .map(Peer::toProtoMessage)
                        .collect(Collectors.toList()));

        Optional.ofNullable(supportedCapabilities).ifPresent(e -> builder.addAllSupportedCapabilities(Capabilities.toIntList(supportedCapabilities)));

        return getNetworkEnvelopeBuilder()
                .setGetPeersResponse(builder)
                .build();
    }

    public static GetPeersResponse fromProto(protobuf.GetPeersResponse proto, String messageVersion) {
        HashSet<Peer> reportedPeers = proto.getReportedPeersList()
                .stream()
                .map(peer -> {
                    NodeAddress nodeAddress = new NodeAddress(peer.getNodeAddress().getHostName(),
                            peer.getNodeAddress().getPort());
                    return new Peer(nodeAddress, Capabilities.fromIntList(peer.getSupportedCapabilitiesList()));
                })
                .collect(Collectors.toCollection(HashSet::new));
        return new GetPeersResponse(proto.getRequestNonce(),
                reportedPeers,
                Capabilities.fromIntList(proto.getSupportedCapabilitiesList()),
                messageVersion);
    }
}
