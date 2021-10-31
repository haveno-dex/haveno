/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.network.p2p.network;

import haveno.common.proto.network.NetworkEnvelope;

import java.io.OutputStream;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
class SynchronizedProtoOutputStream extends ProtoOutputStream {
    private static final Logger log = LoggerFactory.getLogger(SynchronizedProtoOutputStream.class);

    private final ExecutorService executorService;

    SynchronizedProtoOutputStream(OutputStream delegate, Statistic statistic) {
        super(delegate, statistic);
        this.executorService = Executors.newSingleThreadExecutor();
    }

    @Override
    void writeEnvelope(NetworkEnvelope envelope) {
        Future<?> future = executorService.submit(() -> super.writeEnvelope(envelope));
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread currentThread = Thread.currentThread();
            currentThread.interrupt();
            String msg = "Thread " + currentThread + " was interrupted. InterruptedException=" + e;
            log.error(msg);
            throw new HavenoRuntimeException(msg, e);
        } catch (ExecutionException e) {
            String msg = "Failed to write envelope. ExecutionException " + e;
            log.error(msg);
            throw new HavenoRuntimeException(msg, e);
        }
    }

    void onConnectionShutdown() {
        try {
            executorService.shutdownNow();
            super.onConnectionShutdown();
        } catch (Throwable t) {
            log.error("Failed to handle connection shutdown. Throwable={}", t.toString());
        }
    }
}
