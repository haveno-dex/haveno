package haveno.core.api;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.Set;

import haveno.network.p2p.peers.peerexchange.Peer;
import haveno.core.filter.Filter;
import haveno.core.filter.FilterManager;
import haveno.network.p2p.peers.PeerManager;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class CoreNetworkService {

    private final PeerManager peerManager;
    private final FilterManager filterManager;

    @Inject
    public CoreNetworkService(PeerManager peerManager
            , FilterManager filterManager) {
        this.peerManager = peerManager;
        this.filterManager = filterManager;
    }

    //public void addListener(@NonNull NetworkListener listener) {
    //    synchronized (lock) {
    //        listeners.add(listener);
    //    }
    //}

    Set<Peer> getOnlinePeers() {
        return peerManager.getLivePeers();
    }

    Set<Peer> getOnlineSeedNodePeers() {
        return peerManager.getLiveSeedNodePeers();
    }

    Filter getFilter() {
        return filterManager.getFilter();
    }

    //public void sendNetworMessage(@NonNull NetworkMessage network_message) {
    //    synchronized (lock) {
    //        for (Iterator<NetworkListener> iter = listeners.iterator(); iter.hasNext(); ) {
    //            NetworkListener listener = iter.next();
    //            try {
    //                listener.onMessage(network_message);
    //            } catch (RuntimeException e) {
    //                log.warn("Failed to send network envelope to listener {}: {}", listener, e.getMessage());
    //                iter.remove();
    //            }
    //        }
    //    }
   // }

}
