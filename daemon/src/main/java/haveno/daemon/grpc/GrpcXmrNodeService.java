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

package haveno.daemon.grpc;

import com.google.inject.Inject;
import haveno.core.api.CoreApi;
import haveno.core.xmr.XmrNodeSettings;
import haveno.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import haveno.daemon.grpc.interceptor.GrpcCallRateMeter;
import static haveno.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import haveno.proto.grpc.GetXmrNodeSettingsReply;
import haveno.proto.grpc.GetXmrNodeSettingsRequest;
import haveno.proto.grpc.IsXmrNodeOnlineReply;
import haveno.proto.grpc.IsXmrNodeOnlineRequest;
import haveno.proto.grpc.StartXmrNodeReply;
import haveno.proto.grpc.StartXmrNodeRequest;
import haveno.proto.grpc.StopXmrNodeReply;
import haveno.proto.grpc.StopXmrNodeRequest;
import haveno.proto.grpc.XmrNodeGrpc.XmrNodeImplBase;
import static haveno.proto.grpc.XmrNodeGrpc.getGetXmrNodeSettingsMethod;
import static haveno.proto.grpc.XmrNodeGrpc.getIsXmrNodeOnlineMethod;
import static haveno.proto.grpc.XmrNodeGrpc.getStartXmrNodeMethod;
import static haveno.proto.grpc.XmrNodeGrpc.getStopXmrNodeMethod;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import java.util.HashMap;
import java.util.Optional;
import static java.util.concurrent.TimeUnit.SECONDS;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroError;

@Slf4j
public class GrpcXmrNodeService extends XmrNodeImplBase {

    private final CoreApi coreApi;
    private final GrpcExceptionHandler exceptionHandler;

    @Inject
    public GrpcXmrNodeService(CoreApi coreApi, GrpcExceptionHandler exceptionHandler) {
        this.coreApi = coreApi;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void isXmrNodeOnline(IsXmrNodeOnlineRequest request,
                                    StreamObserver<IsXmrNodeOnlineReply> responseObserver) {
        try {
            var reply = IsXmrNodeOnlineReply.newBuilder()
                    .setIsRunning(coreApi.isXmrNodeOnline())
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void getXmrNodeSettings(GetXmrNodeSettingsRequest request,
                                      StreamObserver<GetXmrNodeSettingsReply> responseObserver) {
        try {
            var settings = coreApi.getXmrNodeSettings();
            var builder = GetXmrNodeSettingsReply.newBuilder();
            if (settings != null) {
                builder.setSettings(settings.toProtoMessage());
            }
            var reply = builder.build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void startXmrNode(StartXmrNodeRequest request,
                                StreamObserver<StartXmrNodeReply> responseObserver) {
        try {
            var settings = request.getSettings();
            coreApi.startXmrNode(XmrNodeSettings.fromProto(settings));
            var reply = StartXmrNodeReply.newBuilder().build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (MoneroError me) {
            handleMoneroError(me, responseObserver);
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void stopXmrNode(StopXmrNodeRequest request,
                               StreamObserver<StopXmrNodeReply> responseObserver) {
        try {
            coreApi.stopXmrNode();
            var reply = StopXmrNodeReply.newBuilder().build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (MoneroError me) {
            handleMoneroError(me, responseObserver);
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    private void handleMoneroError(MoneroError me, StreamObserver<?> responseObserver) {
        // MoneroError is caused by the node startup failing, don't treat as unknown server error
        // by wrapping with a handled exception type.
        var headerLengthLimit = 8192; // MoneroErrors may print the entire monerod help text which causes a header overflow in grpc
        if (me.getMessage().length() > headerLengthLimit) {
            exceptionHandler.handleException(log, new IllegalStateException(me.getMessage().substring(0, headerLengthLimit - 1)), responseObserver);
        } else {
            exceptionHandler.handleException(log, new IllegalStateException(me), responseObserver);
        }
    }

    final ServerInterceptor[] interceptors() {
        Optional<ServerInterceptor> rateMeteringInterceptor = rateMeteringInterceptor();
        return rateMeteringInterceptor.map(serverInterceptor ->
                new ServerInterceptor[]{serverInterceptor}).orElseGet(() -> new ServerInterceptor[0]);
    }

    private Optional<ServerInterceptor> rateMeteringInterceptor() {
        return getCustomRateMeteringInterceptor(coreApi.getConfig().appDataDir, this.getClass())
                .or(() -> Optional.of(CallRateMeteringInterceptor.valueOf(
                        new HashMap<>() {{
                            int allowedCallsPerTimeWindow = 10;
                            put(getIsXmrNodeOnlineMethod().getFullMethodName(), new GrpcCallRateMeter(allowedCallsPerTimeWindow, SECONDS));
                            put(getGetXmrNodeSettingsMethod().getFullMethodName(), new GrpcCallRateMeter(allowedCallsPerTimeWindow, SECONDS));
                            put(getStartXmrNodeMethod().getFullMethodName(), new GrpcCallRateMeter(allowedCallsPerTimeWindow, SECONDS));
                            put(getStopXmrNodeMethod().getFullMethodName(), new GrpcCallRateMeter(allowedCallsPerTimeWindow, SECONDS));
                        }}
                )));
    }
}
