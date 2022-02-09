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

package bisq.daemon.grpc;

import bisq.core.app.HavenoHeadlessApp;

import bisq.common.UserThread;

import bisq.proto.grpc.ShutdownServerGrpc;
import bisq.proto.grpc.StopReply;
import bisq.proto.grpc.StopRequest;

import io.grpc.stub.StreamObserver;

import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Slf4j
class GrpcShutdownService extends ShutdownServerGrpc.ShutdownServerImplBase {

    private final GrpcExceptionHandler exceptionHandler;

    @Inject
    public GrpcShutdownService(GrpcExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void stop(StopRequest req,
                     StreamObserver<StopReply> responseObserver) {
        try {
            log.info("Shutdown request received.");
            HavenoHeadlessApp.setOnGracefulShutDownHandler(new Runnable() {
                @Override
                public void run() {
                    var reply = StopReply.newBuilder().build();
                    responseObserver.onNext(reply);
                    responseObserver.onCompleted();
                }
            });
            UserThread.runAfter(HavenoHeadlessApp.getShutDownHandler(), 500, MILLISECONDS);
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }
}
