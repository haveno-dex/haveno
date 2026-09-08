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
import haveno.common.ClockWatcher;
import haveno.common.ThreadUtils;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.network.p2p.network.CloseConnectionReason;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.network.MessageListener;
import haveno.network.p2p.network.NetworkNode;
import haveno.network.p2p.peers.PeerManager;
import haveno.network.p2p.peers.keepalive.messages.Ping;
import haveno.network.p2p.peers.keepalive.messages.Pong;
import haveno.network.utils.EventThrottler;
import haveno.network.utils.EventThrottler.ThrottleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.time.Clock;
import java.util.Random;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

class KeepAliveHandler implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(KeepAliveHandler.class);
    private static final int DELAY_MS = 10_000;
    private static final int TIMEOUT_SEC = 240;
    private static final int TIMEOUT_CHECK_INTERVAL_SEC = 10;
    private static final long LOG_THROTTLE_INTERVAL_MS = 60000; // throttle logging warnings to once every 60 seconds
    private static EventThrottler throttler = new EventThrottler(LOG_THROTTLE_INTERVAL_MS, TimeUnit.MILLISECONDS);


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Listener
    ///////////////////////////////////////////////////////////////////////////////////////////

    public interface Listener {
        void onComplete(KeepAliveHandler handler);

        @SuppressWarnings("UnusedParameters")
        void onFault(String errorMessage, KeepAliveHandler handler);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Class fields
    ///////////////////////////////////////////////////////////////////////////////////////////

    private final NetworkNode networkNode;
    private final PeerManager peerManager;
    private final Listener listener;
    private final Clock clock;
    private final int nonce = new Random().nextInt();
    // Handler state is confined to the connection thread.
    @Nullable
    private Connection connection;
    private boolean stopped;
    private Timer delayTimer;
    private Timer timeoutTimer;
    private long sendTs;
    private long lastReadTimestamp;
    private long timeoutDeadline;
    private long lastTimeoutCheck;
    private int timeoutEpoch;
    private boolean pingSent;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public KeepAliveHandler(NetworkNode networkNode, PeerManager peerManager, Listener listener, Clock clock) {
        this.networkNode = networkNode;
        this.peerManager = peerManager;
        this.listener = listener;
        this.clock = clock;
    }

    public void cancel() {
        ThreadUtils.execute(this::cleanup, Connection.THREAD_ID);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void sendPingAfterRandomDelay(Connection connection) {
        ThreadUtils.execute(() -> {
            if (!stopped) {
                delayTimer = UserThread.runAfterRandomDelay(() ->
                        ThreadUtils.execute(() -> sendPing(connection), Connection.THREAD_ID), 1, DELAY_MS, TimeUnit.MILLISECONDS);
            }
        }, Connection.THREAD_ID);
    }

    private void sendPing(Connection connection) {
        if (stopped) return;
        this.connection = connection;
        Ping ping = new Ping(nonce, connection.getStatistic().roundTripTimeProperty().get());
        sendTs = clock.millis();
        lastReadTimestamp = connection.getLastReadTimestamp();

        // Register before sending, as the pong can arrive before the send callback.
        connection.addMessageListener(this);
        startTimeout(TimeUnit.SECONDS.toMillis(TIMEOUT_SEC));
        try {
            SettableFuture<Connection> future = networkNode.sendMessage(connection, ping);
            Futures.addCallback(future, new FutureCallback<>() {
                @Override
                public void onSuccess(Connection result) {
                    ThreadUtils.execute(() -> {
                        if (stopped) return;
                        pingSent = true;
                        lastReadTimestamp = connection.getLastReadTimestamp();
                        // Give the peer a full reply window after the queued write completes.
                        startTimeout(TimeUnit.SECONDS.toMillis(TIMEOUT_SEC));
                    }, Connection.THREAD_ID);
                }

                @Override
                public void onFailure(Throwable throwable) {
                    ThreadUtils.execute(() -> handleSendFailure(throwable), Connection.THREAD_ID);
                }
            }, MoreExecutors.directExecutor());
        } catch (RuntimeException e) {
            handleSendFailure(e);
        }
    }

    private void handleSendFailure(Throwable throwable) {
        if (stopped) return;
        cleanup();
        String errorMessage = "Sending ping to " + connection + " failed: " + throwable.getMessage();
        if (throwable instanceof RejectedExecutionException) log.debug(errorMessage);
        else log.info(errorMessage);
        // Connection handles socket failures; a rejected local send does not prove a peer fault.
        listener.onFault(errorMessage, this);
    }

    private void startTimeout(long delayMs) {
        long now = clock.millis();
        timeoutDeadline = now + delayMs;
        lastTimeoutCheck = now;
        scheduleTimeoutCheck();
    }

    private void scheduleTimeoutCheck() {
        if (timeoutTimer != null) timeoutTimer.stop();
        int epoch = ++timeoutEpoch;
        long delayMs = Math.min(TimeUnit.SECONDS.toMillis(TIMEOUT_CHECK_INTERVAL_SEC),
                Math.max(1, timeoutDeadline - clock.millis()));
        timeoutTimer = UserThread.runAfter(() -> ThreadUtils.execute(() -> {
            if (!stopped && epoch == timeoutEpoch) onTimeout();
        }, Connection.THREAD_ID), delayMs, TimeUnit.MILLISECONDS);
    }

    private void onTimeout() {
        long now = clock.millis();
        long elapsed = now - lastTimeoutCheck;
        // Retire pre-suspension probes even if this callback runs before the wake listener.
        if (connection.isStopped() || elapsed < 0 || elapsed > ClockWatcher.IDLE_TOLERANCE_MS) {
            cleanup();
            listener.onComplete(this);
            return;
        }
        lastTimeoutCheck = now;
        if (now < timeoutDeadline) {
            scheduleTimeoutCheck();
            return;
        }

        // A pong can be queued behind a large envelope that is still being received.
        long currentReadTimestamp = connection.getLastReadTimestamp();
        if (currentReadTimestamp > lastReadTimestamp) {
            lastReadTimestamp = currentReadTimestamp;
            long remainingMs = TimeUnit.SECONDS.toMillis(TIMEOUT_SEC) - (now - currentReadTimestamp);
            if (remainingMs > 0) {
                startTimeout(remainingMs);
                return;
            }
        }
        handleFault((pingSent ? "Peer did not answer ping" : "Sending ping did not complete") +
                        " and no data was received for " + TIMEOUT_SEC + " seconds: " + connection,
                pingSent ? CloseConnectionReason.SOCKET_TIMEOUT : CloseConnectionReason.SEND_MSG_TIMEOUT);
    }

    private void handleFault(String errorMessage, CloseConnectionReason closeConnectionReason) {
        if (stopped) return;
        cleanup();
        log.info(errorMessage);
        peerManager.handleConnectionFault(connection);
        listener.onFault(errorMessage, this);
        if (!connection.isStopped()) connection.shutDown(closeConnectionReason);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // MessageListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onMessage(NetworkEnvelope networkEnvelope, Connection connection) {
        if (networkEnvelope instanceof Pong) {
            ThreadUtils.execute(() -> {
                if (stopped || connection != this.connection) return;
                Pong pong = (Pong) networkEnvelope;
                if (pong.getRequestNonce() == nonce) {
                    int roundTripTime = (int) (clock.millis() - sendTs);
                    connection.getStatistic().setRoundTripTime(roundTripTime);
                    cleanup();
                    listener.onComplete(this);
                } else {
                    throttleWarn("Nonce not matching. That should never happen.\n" +
                            "\tWe drop that message. nonce=" + nonce + ", requestNonce=" + pong.getRequestNonce() + ", peerNodeAddress=" + connection.getPeersNodeAddressOptional().orElse(null));
                }
            }, Connection.THREAD_ID);
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
        if (timeoutTimer != null) {
            timeoutTimer.stop();
            timeoutTimer = null;
        }
    }

    private void throttleWarn(String msg) {
        ThrottleResult throttleResult = throttler.onEvent();
        if (!throttleResult.throttled) {
            log.warn(msg);
            if (throttleResult.throttledCount > 0) log.warn("We received {} throttled warnings since the last log entry" + (throttleResult.throttledCount >= Connection.POSSIBLE_DOS_THRESHOLD ? ". " + Connection.POSSIBLE_DOS_MESSAGE : ""), throttleResult.throttledCount);
        }
    }
}
