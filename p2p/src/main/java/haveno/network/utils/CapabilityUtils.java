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

package haveno.network.utils;

import haveno.common.app.Capabilities;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.peers.PeerManager;
import haveno.network.p2p.storage.payload.CapabilityRequiringPayload;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
public class CapabilityUtils {
    public static boolean capabilityRequiredAndCapabilityNotSupported(NodeAddress peersNodeAddress,
                                                                      NetworkEnvelope message,
                                                                      PeerManager peerManager) {
        if (!(message instanceof CapabilityRequiringPayload))
            return false;

        // We might have multiple entries of the same peer without the supportedCapabilities field set if we received
        // it from old versions, so we filter those.
        Optional<Capabilities> optionalCapabilities = peerManager.findPeersCapabilities(peersNodeAddress);
        if (optionalCapabilities.isPresent()) {
            boolean result = optionalCapabilities.get().containsAll(((CapabilityRequiringPayload) message).getRequiredCapabilities());

            if (!result)
                log.warn("We don't send the message because the peer does not support the required capability. " +
                        "peersNodeAddress={}", peersNodeAddress);

            return !result;
        }

        log.warn("We don't have the peer in our persisted peers so we don't know their capabilities. " +
                "We decide to not sent the msg. peersNodeAddress={}", peersNodeAddress);
        return true;

    }
}
