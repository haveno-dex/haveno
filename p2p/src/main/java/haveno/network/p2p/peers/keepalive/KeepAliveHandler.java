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

package haveno.network.p2p.peers.keepalive;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.network.MessageListener;
import haveno.network.p2p.network.NetworkNode;
import haveno.network.p2p.peers.PeerManager;
import haveno.network.p2p.peers.keepalive.messages.Ping;
import haveno.network.p2p.peers.keepalive.messages.Pong;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.Random;
import java.util.concurrent.TimeUnit;

class KeepAliveHandler implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(KeepAliveHandler.class);
    private static final int DELAY_MS = 10_000;
    private static final long LOG_THROTTLE_INTERVAL_MS = 60000; // throttle logging warnings to once every 60 seconds
    private static long lastLoggedWarningTs = 0;
    private static int numThrottledWarnings = 0;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Listener
    ///////////////////////////////////////////////////////////////////////////////////////////

    public interface Listener {
        void onComplete();

        @SuppressWarnings("UnusedParameters")
        void onFault(String errorMessage);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Class fields
    ///////////////////////////////////////////////////////////////////////////////////////////

    private final NetworkNode networkNode;
    private final PeerManager peerManager;
    private final Listener listener;
    private final int nonce = new Random().nextInt();
    @Nullable
    private Connection connection;
    private boolean stopped;
    private Timer delayTimer;
    private long sendTs;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public KeepAliveHandler(NetworkNode networkNode, PeerManager peerManager, Listener listener) {
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

    public void sendPingAfterRandomDelay(Connection connection) {
        delayTimer = UserThread.runAfterRandomDelay(() -> sendPing(connection), 1, DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void sendPing(Connection connection) {
        if (!stopped) {
            Ping ping = new Ping(nonce, connection.getStatistic().roundTripTimeProperty().get());
            sendTs = System.currentTimeMillis();
            SettableFuture<Connection> future = networkNode.sendMessage(connection, ping);
            Futures.addCallback(future, new FutureCallback<Connection>() {
                @Override
                public void onSuccess(Connection connection) {
                    if (!stopped) {
                        KeepAliveHandler.this.connection = connection;
                        connection.addMessageListener(KeepAliveHandler.this);
                    } else {
                        log.trace("We have stopped already. We ignore that networkNode.sendMessage.onSuccess call.");
                    }
                }

                @Override
                public void onFailure(@NotNull Throwable throwable) {
                    if (!stopped) {
                        String errorMessage = "Sending ping to " + connection +
                                " failed. That is expected if the peer is offline.\n\tping=" + ping +
                                ".\n\tException=" + throwable.getMessage();
                        cleanup();
                        //peerManager.shutDownConnection(connection, CloseConnectionReason.SEND_MSG_FAILURE);
                        log.info(errorMessage);
                        peerManager.handleConnectionFault(connection);
                        listener.onFault(errorMessage);
                    } else {
                        log.trace("We have stopped already. We ignore that networkNode.sendMessage.onFailure call.");
                    }
                }
            }, MoreExecutors.directExecutor());
        } else {
            log.trace("We have stopped already. We ignore that sendPing call.");
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // MessageListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onMessage(NetworkEnvelope networkEnvelope, Connection connection) {
        if (networkEnvelope instanceof Pong) {
            if (!stopped) {
                Pong pong = (Pong) networkEnvelope;
                if (pong.getRequestNonce() == nonce) {
                    int roundTripTime = (int) (System.currentTimeMillis() - sendTs);
                    connection.getStatistic().setRoundTripTime(roundTripTime);
                    cleanup();
                    listener.onComplete();
                } else {
                    throttleWarn("Nonce not matching. That should never happen.\n" + 
                            "\tWe drop that message. nonce=" + nonce + ", requestNonce=" + pong.getRequestNonce() + ", peerNodeAddress=" + connection.getPeersNodeAddressOptional().orElseGet(null));
                }
            } else {
                log.trace("We have stopped already. We ignore that onMessage call.");
            }
        }
    }

    private void cleanup() {
        stopped = true;
        if (connection != null)
            connection.removeMessageListener(this);

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
