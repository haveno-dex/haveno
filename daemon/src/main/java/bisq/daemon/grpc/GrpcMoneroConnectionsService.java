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

package bisq.daemon.grpc;

import bisq.core.api.CoreApi;
import bisq.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import bisq.daemon.grpc.interceptor.GrpcCallRateMeter;
import bisq.proto.grpc.AddConnectionReply;
import bisq.proto.grpc.AddConnectionRequest;
import bisq.proto.grpc.CheckConnectionReply;
import bisq.proto.grpc.CheckConnectionRequest;
import bisq.proto.grpc.CheckConnectionsReply;
import bisq.proto.grpc.CheckConnectionsRequest;
import bisq.proto.grpc.GetBestAvailableConnectionReply;
import bisq.proto.grpc.GetBestAvailableConnectionRequest;
import bisq.proto.grpc.GetConnectionReply;
import bisq.proto.grpc.GetConnectionRequest;
import bisq.proto.grpc.GetConnectionsReply;
import bisq.proto.grpc.GetConnectionsRequest;
import bisq.proto.grpc.RemoveConnectionReply;
import bisq.proto.grpc.RemoveConnectionRequest;
import bisq.proto.grpc.SetAutoSwitchReply;
import bisq.proto.grpc.SetAutoSwitchRequest;
import bisq.proto.grpc.SetConnectionReply;
import bisq.proto.grpc.SetConnectionRequest;
import bisq.proto.grpc.StartCheckingConnectionsReply;
import bisq.proto.grpc.StartCheckingConnectionsRequest;
import bisq.proto.grpc.StopCheckingConnectionsReply;
import bisq.proto.grpc.StopCheckingConnectionsRequest;
import bisq.proto.grpc.UrlConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.inject.Inject;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroRpcConnection;

import static bisq.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import static bisq.proto.grpc.MoneroConnectionsGrpc.*;
import static java.util.concurrent.TimeUnit.SECONDS;

@Slf4j
class GrpcMoneroConnectionsService extends MoneroConnectionsImplBase {

    private final CoreApi coreApi;
    private final GrpcExceptionHandler exceptionHandler;

    @Inject
    public GrpcMoneroConnectionsService(CoreApi coreApi, GrpcExceptionHandler exceptionHandler) {
        this.coreApi = coreApi;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void addConnection(AddConnectionRequest request,
                              StreamObserver<AddConnectionReply> responseObserver) {
        handleRequest(responseObserver, () -> {
            coreApi.addMoneroConnection(toMoneroRpcConnection(request.getConnection()));
            return AddConnectionReply.newBuilder().build();
        });
    }

    @Override
    public void removeConnection(RemoveConnectionRequest request,
                                 StreamObserver<RemoveConnectionReply> responseObserver) {
        handleRequest(responseObserver, () -> {
            coreApi.removeMoneroConnection(validateUri(request.getUrl()));
            return RemoveConnectionReply.newBuilder().build();
        });
    }

    @Override
    public void getConnection(GetConnectionRequest request,
                              StreamObserver<GetConnectionReply> responseObserver) {
        handleRequest(responseObserver, () -> {
            UrlConnection replyConnection = toUrlConnection(coreApi.getMoneroConnection());
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
            List<MoneroRpcConnection> connections = coreApi.getMoneroConnections();
            List<UrlConnection> replyConnections = connections.stream()
                    .map(GrpcMoneroConnectionsService::toUrlConnection).collect(Collectors.toList());
            return GetConnectionsReply.newBuilder().addAllConnections(replyConnections).build();
        });
    }

    @Override
    public void setConnection(SetConnectionRequest request,
                              StreamObserver<SetConnectionReply> responseObserver) {
        handleRequest(responseObserver, () -> {
            if (request.getUrl() != null && !request.getUrl().isEmpty())
                coreApi.setMoneroConnection(validateUri(request.getUrl()));
            else if (request.hasConnection())
                coreApi.setMoneroConnection(toMoneroRpcConnection(request.getConnection()));
            else coreApi.setMoneroConnection((MoneroRpcConnection) null); // disconnect from client
            return SetConnectionReply.newBuilder().build();
        });
    }

    @Override
    public void checkConnection(CheckConnectionRequest request,
                                StreamObserver<CheckConnectionReply> responseObserver) {
        handleRequest(responseObserver, () -> {
            MoneroRpcConnection connection = coreApi.checkMoneroConnection();
            UrlConnection replyConnection = toUrlConnection(connection);
            CheckConnectionReply.Builder builder = CheckConnectionReply.newBuilder();
            if (replyConnection != null) {
                builder.setConnection(replyConnection);
            }
            return builder.build();
        });
    }

    @Override
    public void checkConnections(CheckConnectionsRequest request,
                                 StreamObserver<CheckConnectionsReply> responseObserver) {
        handleRequest(responseObserver, () -> {
            List<MoneroRpcConnection> connections = coreApi.checkMoneroConnections();
            List<UrlConnection> replyConnections = connections.stream()
                    .map(GrpcMoneroConnectionsService::toUrlConnection).collect(Collectors.toList());
            return CheckConnectionsReply.newBuilder().addAllConnections(replyConnections).build();
        });
    }

    @Override
    public void startCheckingConnections(StartCheckingConnectionsRequest request,
                                         StreamObserver<StartCheckingConnectionsReply> responseObserver) {
        handleRequest(responseObserver, () -> {
            int refreshMillis = request.getRefreshPeriod();
            Long refreshPeriod = refreshMillis == 0 ? null : (long) refreshMillis;
            coreApi.startCheckingMoneroConnection(refreshPeriod);
            return StartCheckingConnectionsReply.newBuilder().build();
        });
    }

    @Override
    public void stopCheckingConnections(StopCheckingConnectionsRequest request,
                                        StreamObserver<StopCheckingConnectionsReply> responseObserver) {
        handleRequest(responseObserver, () -> {
            coreApi.stopCheckingMoneroConnection();
            return StopCheckingConnectionsReply.newBuilder().build();
        });
    }

    @Override
    public void getBestAvailableConnection(GetBestAvailableConnectionRequest request,
                                           StreamObserver<GetBestAvailableConnectionReply> responseObserver) {
        handleRequest(responseObserver, () -> {
            MoneroRpcConnection connection = coreApi.getBestAvailableMoneroConnection();
            UrlConnection replyConnection = toUrlConnection(connection);
            GetBestAvailableConnectionReply.Builder builder = GetBestAvailableConnectionReply.newBuilder();
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
            coreApi.setMoneroConnectionAutoSwitch(request.getAutoSwitch());
            return SetAutoSwitchReply.newBuilder().build();
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
                            put(getCheckConnectionsMethod().getFullMethodName(), new GrpcCallRateMeter(allowedCallsPerTimeWindow, SECONDS));
                            put(getStartCheckingConnectionsMethod().getFullMethodName(), new GrpcCallRateMeter(allowedCallsPerTimeWindow, SECONDS));
                            put(getStopCheckingConnectionsMethod().getFullMethodName(), new GrpcCallRateMeter(allowedCallsPerTimeWindow, SECONDS));
                            put(getGetBestAvailableConnectionMethod().getFullMethodName(), new GrpcCallRateMeter(allowedCallsPerTimeWindow, SECONDS));
                            put(getSetAutoSwitchMethod().getFullMethodName(), new GrpcCallRateMeter(allowedCallsPerTimeWindow, SECONDS));
                        }}
                )));
    }
}
