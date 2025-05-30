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

package haveno.network.p2p.peers.peerexchange;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.network.CloseConnectionReason;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.network.MessageListener;
import haveno.network.p2p.network.NetworkNode;
import haveno.network.p2p.peers.PeerManager;
import haveno.network.p2p.peers.peerexchange.messages.GetPeersRequest;
import haveno.network.p2p.peers.peerexchange.messages.GetPeersResponse;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@Slf4j
class PeerExchangeHandler implements MessageListener {
    // We want to keep timeout short here
    private static final long TIMEOUT = 90;
    private static final int DELAY_MS = 500;
    private static final long LOG_THROTTLE_INTERVAL_MS = 60000; // throttle logging warnings to once every 60 seconds
    private static long lastLoggedWarningTs = 0;
    private static int numThrottledWarnings = 0;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Listener
    ///////////////////////////////////////////////////////////////////////////////////////////

    public interface Listener {
        void onComplete();

        @SuppressWarnings("UnusedParameters")
        void onFault(String errorMessage, @Nullable Connection connection);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Class fields
    ///////////////////////////////////////////////////////////////////////////////////////////

    private final NetworkNode networkNode;
    private final PeerManager peerManager;
    private final Listener listener;
    private final int nonce = new Random().nextInt();
    private Timer timeoutTimer;
    private Connection connection;
    private boolean stopped;
    private Timer delayTimer;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public PeerExchangeHandler(NetworkNode networkNode, PeerManager peerManager, Listener listener) {
        this.networkNode = networkNode;
        this.peerManager = peerManager;
        this.listener = listener;
    }

    public void cancel() {
        cleanup();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void sendGetPeersRequestAfterRandomDelay(NodeAddress nodeAddress) {
        delayTimer = UserThread.runAfterRandomDelay(() -> sendGetPeersRequest(nodeAddress), 1, DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void sendGetPeersRequest(NodeAddress nodeAddress) {
        log.debug("sendGetPeersRequest to nodeAddress={}", nodeAddress);
        if (!stopped) {
            if (networkNode.getNodeAddress() != null) {
                GetPeersRequest getPeersRequest = new GetPeersRequest(networkNode.getNodeAddress(),
                        nonce,
                        new HashSet<>(peerManager.getLivePeers(nodeAddress)));
                if (timeoutTimer == null) {
                    timeoutTimer = UserThread.runAfter(() -> {  // setup before sending to avoid race conditions
                                if (!stopped) {
                                    String errorMessage = "A timeout occurred at sending getPeersRequest. nodeAddress=" + nodeAddress;
                                    handleFault(errorMessage, CloseConnectionReason.SEND_MSG_TIMEOUT, nodeAddress);
                                } else {
                                    log.trace("We have stopped that handler already. We ignore that timeoutTimer.run call.");
                                }
                            },
                            TIMEOUT, TimeUnit.SECONDS);
                }

                try {
                    SettableFuture<Connection> future = networkNode.sendMessage(nodeAddress, getPeersRequest);
                    Futures.addCallback(future, new FutureCallback<Connection>() {
                        @Override
                        public void onSuccess(Connection connection) {
                            if (!stopped) {
                                //TODO
                                /*if (!connection.getPeersNodeAddressOptional().isPresent()) {
                                    connection.setPeersNodeAddress(nodeAddress);
                                    log.warn("sendGetPeersRequest: !connection.getPeersNodeAddressOptional().isPresent()");
                                }*/
    
                                PeerExchangeHandler.this.connection = connection;
                                connection.addMessageListener(PeerExchangeHandler.this);
                            } else {
                                log.trace("We have stopped that handler already. We ignore that sendGetPeersRequest.onSuccess call.");
                            }
                        }
    
                        @Override
                        public void onFailure(@NotNull Throwable throwable) {
                            if (!stopped) {
                                String errorMessage = "Sending getPeersRequest to " + nodeAddress +
                                        " failed. That is expected if the peer is offline. Exception=" + throwable.getMessage();
                                handleFault(errorMessage, CloseConnectionReason.SEND_MSG_FAILURE, nodeAddress);
                            } else {
                                log.trace("We have stopped that handler already. We ignore that sendGetPeersRequest.onFailure call.");
                            }
                        }
                    }, MoreExecutors.directExecutor());
                } catch (Exception e) {
                    if (!networkNode.isShutDownStarted()) throw e;
                }
            } else {
                log.debug("My node address is still null at sendGetPeersRequest. We ignore that call.");
            }
        } else {
            log.trace("We have stopped that handler already. We ignore that sendGetPeersRequest call.");
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // MessageListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onMessage(NetworkEnvelope networkEnvelope, Connection connection) {
        if (networkEnvelope instanceof GetPeersResponse) {
            if (!stopped) {
                GetPeersResponse getPeersResponse = (GetPeersResponse) networkEnvelope;
                // Check if the response is for our request
                if (getPeersResponse.getRequestNonce() == nonce) {
                    peerManager.addToReportedPeers(getPeersResponse.getReportedPeers(),
                            connection,
                            getPeersResponse.getSupportedCapabilities());
                    cleanup();
                    listener.onComplete();
                } else {
                    throttleWarn("Nonce not matching. That should never happen.\n" + 
                            "\tWe drop that message. nonce=" + nonce + ", requestNonce=" + getPeersResponse.getRequestNonce() + ", peerNodeAddress=" + connection.getPeersNodeAddressOptional().orElseGet(null));
                }
            } else {
                log.trace("We have stopped that handler already. We ignore that onMessage call.");
            }
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    @SuppressWarnings("UnusedParameters")
    private void handleFault(String errorMessage, CloseConnectionReason closeConnectionReason, NodeAddress nodeAddress) {
        cleanup();
       /* if (connection == null)
            peerManager.shutDownConnection(nodeAddress, closeConnectionReason);
        else
            peerManager.shutDownConnection(connection, closeConnectionReason);*/

        peerManager.handleConnectionFault(nodeAddress, connection);
        listener.onFault(errorMessage, connection);
    }

    private void cleanup() {
        stopped = true;
        if (connection != null)
            connection.removeMessageListener(this);

        if (timeoutTimer != null) {
            timeoutTimer.stop();
            timeoutTimer = null;
        }

        if (delayTimer != null) {
            delayTimer.stop();
            delayTimer = null;
        }
    }

    private synchronized void throttleWarn(String msg) {
        boolean logWarning = System.currentTimeMillis() - lastLoggedWarningTs > LOG_THROTTLE_INTERVAL_MS;
        if (logWarning) {
            log.warn(msg);
            if (numThrottledWarnings > 0) log.warn("Possible DoS attack detected. {} warnings were throttled since the last log entry", numThrottledWarnings);
            numThrottledWarnings = 0;
            lastLoggedWarningTs = System.currentTimeMillis();
        } else {
            numThrottledWarnings++;
        }
    }
}
