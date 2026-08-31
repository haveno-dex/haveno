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

package haveno.network.p2p.network;

import haveno.network.p2p.CloseConnectionMessage;
import haveno.network.p2p.peers.keepalive.messages.KeepAliveMessage;

import haveno.common.proto.network.NetworkEnvelope;

import java.io.IOException;
import java.io.OutputStream;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
class ProtoOutputStream {
    private static final Logger log = LoggerFactory.getLogger(ProtoOutputStream.class);
    private static final long WRITE_LOCK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(120);

    private final OutputStream outputStream;
    private final Statistic statistic;
    private final BooleanSupplier shutDownStarted;

    private final AtomicBoolean isConnectionActive = new AtomicBoolean(true);
    private final Lock lock = new ReentrantLock();

    ProtoOutputStream(OutputStream outputStream, Statistic statistic, BooleanSupplier shutDownStarted) {
        this.outputStream = outputStream;
        this.statistic = statistic;
        this.shutDownStarted = shutDownStarted;
    }

    // Returns false when nothing was written because the connection shut down.
    boolean writeEnvelope(NetworkEnvelope envelope, protobuf.NetworkEnvelope proto) {
        // Bound the lock wait so senders cannot pile up behind a write stalled on a dead socket.
        if (!tryToAcquireLock(WRITE_LOCK_TIMEOUT_MS)) {
            if (!isConnectionActive.get()) {
                return false;
            }
            // report a shutdown instead of a stall, so the caller retries on a fresh connection
            if (shutDownStarted.getAsBoolean() && !(envelope instanceof CloseConnectionMessage)) {
                return false;
            }
            throw new HavenoRuntimeException("Timed out waiting to write " + envelope.getClass().getSimpleName() + ", connection write side appears stalled");
        }

        try {
            if (!isConnectionActive.get()) {
                return false;
            }
            // recheck after the lock wait in case a shutdown started meanwhile; the close frame is exempt
            if (shutDownStarted.getAsBoolean() && !(envelope instanceof CloseConnectionMessage)) {
                return false;
            }
            try {
                writeEnvelopeOrThrow(envelope, proto);
            } finally {
                // the close frame is the final frame, so deactivate to reject later writes behind the lock
                if (envelope instanceof CloseConnectionMessage) {
                    isConnectionActive.set(false);
                }
            }
            return true;
        } catch (IOException e) {
            if (!isConnectionActive.get()) {
                // Connection was closed by us.
                return false;
            }

            log.error("Failed to write envelope", e);
            throw new HavenoRuntimeException("Failed to write envelope", e);

        } finally {
            lock.unlock();
        }
    }

    void onConnectionShutdown() {
        isConnectionActive.set(false);

        boolean acquiredLock = tryToAcquireLock(Connection.getShutdownTimeout());
        if (!acquiredLock) {
            return;
        }

        try {
            outputStream.close();
        } catch (Throwable t) {
            log.error("Failed to close connection", t);

        } finally {
            lock.unlock();
        }
    }

    // The caller already converted the envelope for its size metrics, so we take the proto to avoid a second conversion.
    private void writeEnvelopeOrThrow(NetworkEnvelope envelope, protobuf.NetworkEnvelope proto) throws IOException {
        long ts = System.currentTimeMillis();
        proto.writeDelimitedTo(outputStream);
        outputStream.flush();
        long duration = System.currentTimeMillis() - ts;
        if (duration > 10000) {
            log.info("Sending {} to peer took {} sec.", envelope.getClass().getSimpleName(), duration / 1000d);
        }
        statistic.addSentBytes(proto.getSerializedSize());
        statistic.addSentMessage(envelope);

        if (!(envelope instanceof KeepAliveMessage)) {
            statistic.updateLastActivityTimestamp();
        }
    }

    private boolean tryToAcquireLock(long timeoutMs) {
        try {
            return lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
