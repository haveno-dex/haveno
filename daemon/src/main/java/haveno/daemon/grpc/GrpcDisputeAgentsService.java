package haveno.daemon.grpc;

import com.google.inject.Inject;
import haveno.core.api.CoreApi;
import haveno.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import haveno.daemon.grpc.interceptor.GrpcCallRateMeter;
import static haveno.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import static haveno.proto.grpc.DisputeAgentsGrpc.DisputeAgentsImplBase;
import static haveno.proto.grpc.DisputeAgentsGrpc.getRegisterDisputeAgentMethod;
import static haveno.proto.grpc.DisputeAgentsGrpc.getUnregisterDisputeAgentMethod;
import haveno.proto.grpc.RegisterDisputeAgentReply;
import haveno.proto.grpc.RegisterDisputeAgentRequest;
import haveno.proto.grpc.UnregisterDisputeAgentReply;
import haveno.proto.grpc.UnregisterDisputeAgentRequest;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import java.util.HashMap;
import java.util.Optional;
import static java.util.concurrent.TimeUnit.SECONDS;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class GrpcDisputeAgentsService extends DisputeAgentsImplBase {

    private final CoreApi coreApi;
    private final GrpcExceptionHandler exceptionHandler;

    @Inject
    public GrpcDisputeAgentsService(CoreApi coreApi, GrpcExceptionHandler exceptionHandler) {
        this.coreApi = coreApi;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void registerDisputeAgent(RegisterDisputeAgentRequest req,
                                     StreamObserver<RegisterDisputeAgentReply> responseObserver) {
        try {
            GrpcErrorMessageHandler errorMessageHandler = new GrpcErrorMessageHandler(getRegisterDisputeAgentMethod().getFullMethodName(), responseObserver, exceptionHandler, log);
            coreApi.registerDisputeAgent(
                    req.getDisputeAgentType(),
                    req.getRegistrationKey(),
                    () -> {
                        var reply = RegisterDisputeAgentReply.newBuilder().build();
                        responseObserver.onNext(reply);
                        responseObserver.onCompleted();
                    },
                    errorMessage -> {
                        if (!errorMessageHandler.isErrorHandled()) errorMessageHandler.handleErrorMessage(errorMessage);
                    });

        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void unregisterDisputeAgent(UnregisterDisputeAgentRequest req,
                                     StreamObserver<UnregisterDisputeAgentReply> responseObserver) {
        try {
            GrpcErrorMessageHandler errorMessageHandler = new GrpcErrorMessageHandler(getUnregisterDisputeAgentMethod().getFullMethodName(), responseObserver, exceptionHandler, log);
            coreApi.unregisterDisputeAgent(
                    req.getDisputeAgentType(),
                    () -> {
                        var reply = UnregisterDisputeAgentReply.newBuilder().build();
                        responseObserver.onNext(reply);
                        responseObserver.onCompleted();
                    },
                    errorMessage -> {
                        if (!errorMessageHandler.isErrorHandled()) errorMessageHandler.handleErrorMessage(errorMessage);
                    });
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
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
                            // Do not limit devs' ability to test agent registration
                            // and call validation in regtest arbitration daemons.
                            put(getRegisterDisputeAgentMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                        }}
                )));
    }
}
