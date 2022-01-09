package bisq.daemon.grpc;

import bisq.core.api.CoreApi;
import bisq.core.api.CoreApi.NotificationListener;

import bisq.proto.grpc.NotificationMessage;
import bisq.proto.grpc.NotificationsGrpc.NotificationsImplBase;
import bisq.proto.grpc.RegisterNotificationListenerRequest;
import bisq.proto.grpc.SendNotificationReply;
import bisq.proto.grpc.SendNotificationRequest;

import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;

import javax.inject.Inject;

import java.util.HashMap;
import java.util.Optional;

import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import static bisq.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import static bisq.proto.grpc.NotificationsGrpc.getRegisterNotificationListenerMethod;
import static bisq.proto.grpc.NotificationsGrpc.getSendNotificationMethod;
import static java.util.concurrent.TimeUnit.SECONDS;



import bisq.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import bisq.daemon.grpc.interceptor.GrpcCallRateMeter;

@Slf4j
class GrpcNotificationsService extends NotificationsImplBase {

    private final CoreApi coreApi;
    private final GrpcExceptionHandler exceptionHandler;

    @Inject
    public GrpcNotificationsService(CoreApi coreApi, GrpcExceptionHandler exceptionHandler) {
        this.coreApi = coreApi;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void registerNotificationListener(RegisterNotificationListenerRequest request,
                                             StreamObserver<NotificationMessage> responseObserver) {
        try {
            coreApi.addNotificationListener(new GrpcNotificationListener(responseObserver));
            // No onNext / onCompleted, as the response observer should be kept open
        } catch (Throwable t) {
            exceptionHandler.handleException(log, t, responseObserver);
        }
    }

    @Override
    public void sendNotification(SendNotificationRequest request,
                                 StreamObserver<SendNotificationReply> responseObserver) {
        try {
            coreApi.sendNotification(request.getNotification());
            responseObserver.onNext(SendNotificationReply.newBuilder().build());
            responseObserver.onCompleted();
        } catch (Throwable t) {
            exceptionHandler.handleException(log, t, responseObserver);
        }
    }

    @Value
    private static class GrpcNotificationListener implements NotificationListener {

        @NonNull
        StreamObserver<NotificationMessage> responseObserver;

        @Override
        public void onMessage(@NonNull NotificationMessage message) {
            responseObserver.onNext(message);
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
                            put(getRegisterNotificationListenerMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getSendNotificationMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                        }}
                )));
    }
}
