package haveno.core.api.model;

import haveno.core.api.model.builder.PeerInfoBuilder;
import haveno.network.p2p.peers.peerexchange.Peer;
import haveno.common.Payload;

import java.util.List;


public class PeerInfo implements Payload {

    private final String nodeAddress;
    private final List<Integer> capabilities;

    public static PeerInfo toPeerInfo(Peer peer) {
        return getBuilder(peer)
                .build();
    }

    public PeerInfo(PeerInfoBuilder builder) {
        this.nodeAddress = builder.getNodeAddress();
        this.capabilities = builder.getCapabilities();
    }

    private static PeerInfoBuilder getBuilder(Peer peer) {
        return new PeerInfoBuilder()
        .withNodeAddress(peer.getNodeAddress().getFullAddress());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    public static PeerInfo fromProto(haveno.proto.grpc.PeerInfo proto) {
        return new PeerInfoBuilder()
        .withNodeAddress(proto.getNodeAddress())
        .build();
    }

    @Override
    public haveno.proto.grpc.PeerInfo toProtoMessage() {
        haveno.proto.grpc.PeerInfo.Builder builder = haveno.proto.grpc.PeerInfo.newBuilder()
            .setNodeAddress(nodeAddress);
        
        return builder.build();
    }

    @Override
    public String toString() {
        return "PeerInfo{" + "\n" +
                " " + nodeAddress.toString() + "\n" +
                '}';
    }

    public String getNodeAddress() {
        return nodeAddress;
    }

    public List<Integer> getCapabilities() {
        return capabilities;
    }
}
