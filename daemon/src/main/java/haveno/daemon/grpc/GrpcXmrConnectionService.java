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
import haveno.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import haveno.daemon.grpc.interceptor.GrpcCallRateMeter;
import static haveno.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import haveno.proto.grpc.AddConnectionReply;
import haveno.proto.grpc.AddConnectionRequest;
import haveno.proto.grpc.CheckConnectionReply;
import haveno.proto.grpc.CheckConnectionRequest;
import haveno.proto.grpc.GetAutoSwitchReply;
import haveno.proto.grpc.GetAutoSwitchRequest;
import haveno.proto.grpc.GetBestConnectionReply;
import haveno.proto.grpc.GetBestConnectionRequest;
import haveno.proto.grpc.GetConnectionReply;
import haveno.proto.grpc.GetConnectionRequest;
import haveno.proto.grpc.GetConnectionsReply;
import haveno.proto.grpc.GetConnectionsRequest;
import haveno.proto.grpc.RemoveConnectionReply;
import haveno.proto.grpc.RemoveConnectionRequest;
import haveno.proto.grpc.SetAutoSwitchReply;
import haveno.proto.grpc.SetAutoSwitchRequest;
import haveno.proto.grpc.SetConnectionReply;
import haveno.proto.grpc.SetConnectionRequest;
import haveno.proto.grpc.UrlConnection;
import static haveno.proto.grpc.XmrConnectionsGrpc.XmrConnectionsImplBase;
import static haveno.proto.grpc.XmrConnectionsGrpc.getAddConnectionMethod;
import static haveno.proto.grpc.XmrConnectionsGrpc.getCheckConnectionMethod;
import static haveno.proto.grpc.XmrConnectionsGrpc.getGetBestConnectionMethod;
import static haveno.proto.grpc.XmrConnectionsGrpc.getGetConnectionMethod;
import static haveno.proto.grpc.XmrConnectionsGrpc.getGetConnectionsMethod;
import static haveno.proto.grpc.XmrConnectionsGrpc.getRemoveConnectionMethod;
import static haveno.proto.grpc.XmrConnectionsGrpc.getSetAutoSwitchMethod;
import static haveno.proto.grpc.XmrConnectionsGrpc.getSetConnectionMethod;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import static java.util.concurrent.TimeUnit.SECONDS;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroRpcConnection;

@Slf4j
class GrpcXmrConnectionService extends XmrConnectionsImplBase {

    private final CoreApi coreApi;
    private final GrpcExceptionHandler exceptionHandler;

    @Inject
    public GrpcXmrConnectionService(CoreApi coreApi, GrpcExceptionHandler exceptionHandler) {
        this.coreApi = coreApi;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void addConnection(AddConnectionRequest request,
                              StreamObserver<AddConnectionReply> responseObserver) {
        handleRequest(responseObserver, () -> {
            coreApi.addXmrConnection(toMoneroRpcConnection(request.getConnection()));
            return AddConnectionReply.newBuilder().build();
        });
    }

    @Override
    public void removeConnection(RemoveConnectionRequest request,
                                 StreamObserver<RemoveConnectionReply> responseObserver) {
        handleRequest(responseObserver, () -> {
            coreApi.removeXmrConnection(validateUri(request.getUrl()));
            return RemoveConnectionReply.newBuilder().build();
        });
    }

    @Override
    public void getConnection(GetConnectionRequest request,
                              StreamObserver<GetConnectionReply> responseObserver) {
        handleRequest(responseObserver, () -> {
            UrlConnection replyConnection = toUrlConnection(coreApi.getXmrConnection());
            GetConnectionReply.Builder builder = GetConnectionReply.newBuilder();
            if (replyConnection != null) {
                builder.setConnection(replyConnection);
            }
            return builder.build();
        });
    }

    @Override
    public void getConnections(GetConnectionsRequest request,
                               StreamObserver<GetConnectionsReply> responseObserver) {
        handleRequest(responseObserver, () -> {
            List<MoneroRpcConnection> connections = coreApi.getXmrConnections();
            List<UrlConnection> replyConnections = connections.stream()
                    .map(GrpcXmrConnectionService::toUrlConnection).collect(Collectors.toList());
            return GetConnectionsReply.newBuilder().addAllConnections(replyConnections).build();
        });
    }

    @Override
    public void setConnection(SetConnectionRequest request,
                              StreamObserver<SetConnectionReply> responseObserver) {
        handleRequest(responseObserver, () -> {
            if (request.getUrl() != null && !request.getUrl().isEmpty())
                coreApi.setXmrConnection(validateUri(request.getUrl()));
            else if (request.hasConnection())
                coreApi.setXmrConnection(toMoneroRpcConnection(request.getConnection()));
            else coreApi.setXmrConnection((MoneroRpcConnection) null); // disconnect from client
            return SetConnectionReply.newBuilder().build();
        });
    }

    @Override
    public void checkConnection(CheckConnectionRequest request,
                                StreamObserver<CheckConnectionReply> responseObserver) {
        handleRequest(responseObserver, () -> {
            MoneroRpcConnection connection = coreApi.checkXmrConnection();
            UrlConnection replyConnection = toUrlConnection(connection);
            CheckConnectionReply.Builder builder = CheckConnectionReply.newBuilder();
            if (replyConnection != null) {
                builder.setConnection(replyConnection);
            }
            return builder.build();
        });
    }

    @Override
    public void getBestConnection(GetBestConnectionRequest request,
                                           StreamObserver<GetBestConnectionReply> responseObserver) {
        handleRequest(responseObserver, () -> {
            MoneroRpcConnection connection = coreApi.getBestXmrConnection();
            UrlConnection replyConnection = toUrlConnection(connection);
            GetBestConnectionReply.Builder builder = GetBestConnectionReply.newBuilder();
            if (replyConnection != null) {
                builder.setConnection(replyConnection);
            }
            return builder.build();
        });
    }

    @Override
    public void setAutoSwitch(SetAutoSwitchRequest request,
                              StreamObserver<SetAutoSwitchReply> responseObserver) {
        handleRequest(responseObserver, () -> {
            coreApi.setXmrConnectionAutoSwitch(request.getAutoSwitch());
            return SetAutoSwitchReply.newBuilder().build();
        });
    }

    @Override
    public void getAutoSwitch(GetAutoSwitchRequest request,
                              StreamObserver<GetAutoSwitchReply> responseObserver) {
        handleRequest(responseObserver, () -> {
            GetAutoSwitchReply.Builder builder = GetAutoSwitchReply.newBuilder();
            builder.setAutoSwitch(coreApi.getXmrConnectionAutoSwitch());
            return builder.build();
        });
    }

    private <_Reply> void handleRequest(StreamObserver<_Reply> responseObserver,
                                        RpcRequestHandler<_Reply> handler) {
        try {
            _Reply reply = handler.handleRequest();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @FunctionalInterface
    private interface RpcRequestHandler<_Reply> {
        _Reply handleRequest() throws Exception;
    }


    private static UrlConnection toUrlConnection(MoneroRpcConnection rpcConnection) {
        if (rpcConnection == null) return null;
        return UrlConnection.newBuilder()
                .setUrl(rpcConnection.getUri())
                .setPriority(rpcConnection.getPriority())
                .setOnlineStatus(toOnlineStatus(rpcConnection.isOnline()))
                .setAuthenticationStatus(toAuthenticationStatus(rpcConnection.isAuthenticated()))
                .build();
    }

    private static UrlConnection.AuthenticationStatus toAuthenticationStatus(Boolean authenticated) {
        if (authenticated == null) return UrlConnection.AuthenticationStatus.NO_AUTHENTICATION;
        else if (authenticated) return UrlConnection.AuthenticationStatus.AUTHENTICATED;
        else return UrlConnection.AuthenticationStatus.NOT_AUTHENTICATED;
    }

    private static UrlConnection.OnlineStatus toOnlineStatus(Boolean online) {
        if (online == null) return UrlConnection.OnlineStatus.UNKNOWN;
        else if (online) return UrlConnection.OnlineStatus.ONLINE;
        else return UrlConnection.OnlineStatus.OFFLINE;
    }

    private static MoneroRpcConnection toMoneroRpcConnection(UrlConnection uriConnection) throws MalformedURLException {
        if (uriConnection == null) return null;
        return new MoneroRpcConnection(
                validateUri(uriConnection.getUrl()),
                nullIfEmpty(uriConnection.getUsername()),
                nullIfEmpty(uriConnection.getPassword()))
                .setPriority(uriConnection.getPriority());
    }

    private static String validateUri(String url) throws MalformedURLException {
        if (url.isEmpty()) throw new IllegalArgumentException("URL is required");
        return new URL(url).toString(); // validate and return
    }

    private static String nullIfEmpty(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value;
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
                            put(getAddConnectionMethod().getFullMethodName(), new GrpcCallRateMeter(allowedCallsPerTimeWindow, SECONDS));
                            put(getRemoveConnectionMethod().getFullMethodName(), new GrpcCallRateMeter(allowedCallsPerTimeWindow, SECONDS));
                            put(getGetConnectionMethod().getFullMethodName(), new GrpcCallRateMeter(allowedCallsPerTimeWindow, SECONDS));
                            put(getGetConnectionsMethod().getFullMethodName(), new GrpcCallRateMeter(allowedCallsPerTimeWindow, SECONDS));
                            put(getSetConnectionMethod().getFullMethodName(), new GrpcCallRateMeter(allowedCallsPerTimeWindow, SECONDS));
                            put(getCheckConnectionMethod().getFullMethodName(), new GrpcCallRateMeter(allowedCallsPerTimeWindow, SECONDS));
                            put(getGetBestConnectionMethod().getFullMethodName(), new GrpcCallRateMeter(allowedCallsPerTimeWindow, SECONDS));
                            put(getSetAutoSwitchMethod().getFullMethodName(), new GrpcCallRateMeter(allowedCallsPerTimeWindow, SECONDS));
                        }}
                )));
    }
}
