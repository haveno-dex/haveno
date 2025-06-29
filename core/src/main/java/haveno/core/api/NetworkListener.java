package haveno.core.api;

import haveno.proto.grpc.NetworkMessage;

import lombok.NonNull;

public interface NetworkListener {
    void onMessage(@NonNull NetworkMessage network_message);
}