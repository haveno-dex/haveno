package haveno.daemon.grpc;

import com.google.inject.Inject;

import haveno.core.api.CoreApi;
import haveno.core.api.NetworkListener;
import haveno.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import haveno.daemon.grpc.interceptor.GrpcCallRateMeter;
import static haveno.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import haveno.proto.grpc.NetworkGrpc.NetworkImplBase;
import haveno.proto.grpc.NetworkMessage;
import static haveno.proto.grpc.NetworkGrpc.getRegisterNetworkListenerMethod;
import haveno.proto.grpc.RegisterNetworkListenerRequest;
import io.grpc.Context;
import io.grpc.ServerInterceptor;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

import java.util.HashMap;
import java.util.Optional;
import static java.util.concurrent.TimeUnit.SECONDS;

import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class GrpcNetworkService extends NetworkImplBase {

    private final CoreApi coreApi;
    private final GrpcExceptionHandler exceptionHandler;

    @Inject
    public GrpcNetworkService(CoreApi coreApi, GrpcExceptionHandler exceptionHandler) {
        this.coreApi = coreApi;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void registerNetworkListener(RegisterNetworkListenerRequest request,
                                             StreamObserver<NetworkMessage> responseObserver) {
        Context ctx = Context.current().fork(); // context is independent for long-lived request
        ctx.run(() -> {
            try {
                coreApi.addNetworkListener(new GrpcNetworkListener(responseObserver));
                // No onNext / onCompleted, as the response observer should be kept open
            } catch (Throwable t) {
                exceptionHandler.handleException(log, t, responseObserver);
            }
        });
    }

    @Value
    private static class GrpcNetworkListener implements NetworkListener {

        @NonNull
        StreamObserver<NetworkMessage> responseObserver;

        @Override
        public void onMessage(@NonNull NetworkMessage network_message) {
            if (!((ServerCallStreamObserver<NetworkMessage>) responseObserver).isCancelled()) {
                responseObserver.onNext(network_message);
            }
        }
    }

    final ServerInterceptor[] interceptors() {
        Optional<ServerInterceptor> rateMeteringInterceptor = rateMeteringInterceptor();
        return rateMeteringInterceptor.map(serverInterceptor ->
                new ServerInterceptor[]{serverInterceptor}).orElseGet(() -> new ServerInterceptor[0]);
    }

    final Optional<ServerInterceptor> rateMeteringInterceptor() {
        return getCustomRateMeteringInterceptor(coreApi.getConfig().appDataDir, this.getClass())
                .or(() -> Optional.of(CallRateMeteringInterceptor.valueOf(
                        new HashMap<>() {{
                            put(getRegisterNetworkListenerMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                        }}
                )));
    }
}
