package haveno.core.api;

import com.google.inject.Singleton;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import haveno.proto.grpc.NetworkMessage;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class CoreNetworkService {

    private final Object lock = new Object();
    private final List<NetworkListener> listeners = new LinkedList<>();

    public void addListener(@NonNull NetworkListener listener) {
        synchronized (lock) {
            listeners.add(listener);
        }
    }

    public void sendNetworMessage(@NonNull NetworkMessage network_message) {
        synchronized (lock) {
            for (Iterator<NetworkListener> iter = listeners.iterator(); iter.hasNext(); ) {
                NetworkListener listener = iter.next();
                try {
                    listener.onMessage(network_message);
                } catch (RuntimeException e) {
                    log.warn("Failed to send network envelope to listener {}: {}", listener, e.getMessage());
                    iter.remove();
                }
            }
        }
    }

}
