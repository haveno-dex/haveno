package haveno.daemon.grpc;

import haveno.common.proto.ProtoUtil;
import haveno.core.api.CoreApi;
import haveno.core.support.dispute.Attachment;
import haveno.core.support.dispute.DisputeResult;
import haveno.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import haveno.daemon.grpc.interceptor.GrpcCallRateMeter;
import haveno.proto.grpc.DisputesGrpc.DisputesImplBase;
import haveno.proto.grpc.GetDisputeReply;
import haveno.proto.grpc.GetDisputeRequest;
import haveno.proto.grpc.GetDisputesReply;
import haveno.proto.grpc.GetDisputesRequest;
import haveno.proto.grpc.OpenDisputeReply;
import haveno.proto.grpc.OpenDisputeRequest;
import haveno.proto.grpc.ResolveDisputeReply;
import haveno.proto.grpc.ResolveDisputeRequest;
import haveno.proto.grpc.SendDisputeChatMessageReply;
import haveno.proto.grpc.SendDisputeChatMessageRequest;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.stream.Collectors;

import static haveno.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import static haveno.proto.grpc.DisputesGrpc.getGetDisputeMethod;
import static haveno.proto.grpc.DisputesGrpc.getGetDisputesMethod;
import static haveno.proto.grpc.DisputesGrpc.getOpenDisputeMethod;
import static haveno.proto.grpc.DisputesGrpc.getResolveDisputeMethod;
import static haveno.proto.grpc.DisputesGrpc.getSendDisputeChatMessageMethod;
import static java.util.concurrent.TimeUnit.SECONDS;

@Slf4j
public class GrpcDisputesService extends DisputesImplBase {

    private final CoreApi coreApi;
    private final GrpcExceptionHandler exceptionHandler;

    @Inject
    public GrpcDisputesService(CoreApi coreApi, GrpcExceptionHandler exceptionHandler) {
        this.coreApi = coreApi;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void openDispute(OpenDisputeRequest req, StreamObserver<OpenDisputeReply> responseObserver) {
        try {
            coreApi.openDispute(req.getTradeId(),
                    () -> {
                        var reply = OpenDisputeReply.newBuilder().build();
                        responseObserver.onNext(reply);
                        responseObserver.onCompleted();
                    },
                    (errorMessage, throwable) -> {
                        log.info("Error in openDispute" + errorMessage);
                        exceptionHandler.handleErrorMessage(log, errorMessage, responseObserver);
                    });
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void getDispute(GetDisputeRequest req, StreamObserver<GetDisputeReply> responseObserver) {
        try {
            var dispute = coreApi.getDispute(req.getTradeId());
            var reply = GetDisputeReply.newBuilder()
                    .setDispute(dispute.toProtoMessage())
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleExceptionAsWarning(log, getClass().getName() + ".getDispute", cause, responseObserver);
        }
    }

    @Override
    public void getDisputes(GetDisputesRequest req, StreamObserver<GetDisputesReply> responseObserver) {
        try {
            var disputes = coreApi.getDisputes();
            var disputesProtobuf = disputes.stream()
                    .map(d -> d.toProtoMessage())
                    .collect(Collectors.toList());
            var reply = GetDisputesReply.newBuilder()
                    .addAllDisputes(disputesProtobuf)
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void resolveDispute(ResolveDisputeRequest req, StreamObserver<ResolveDisputeReply> responseObserver) {
        try {
            var winner = ProtoUtil.enumFromProto(DisputeResult.Winner.class, req.getWinner().name());
            var reason = ProtoUtil.enumFromProto(DisputeResult.Reason.class, req.getReason().name());
            coreApi.resolveDispute(req.getTradeId(), winner, reason, req.getSummaryNotes(), req.getCustomPayoutAmount());
            var reply = ResolveDisputeReply.newBuilder().build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            cause.printStackTrace();
            exceptionHandler.handleExceptionAsWarning(log, getClass().getName() + ".resolveDispute", cause, responseObserver);
        }
    }

    @Override
    public void sendDisputeChatMessage(SendDisputeChatMessageRequest req,
                                       StreamObserver<SendDisputeChatMessageReply> responseObserver) {
        try {
            var attachmentsProto = req.getAttachmentsList();
            var attachments = attachmentsProto.stream().map(a -> Attachment.fromProto(a))
                    .collect(Collectors.toList());
            coreApi.sendDisputeChatMessage(req.getDisputeId(), req.getMessage(), new ArrayList(attachments));
            var reply = SendDisputeChatMessageReply.newBuilder().build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
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
                            put(getGetDisputeMethod().getFullMethodName(), new GrpcCallRateMeter(20, SECONDS));
                            put(getGetDisputesMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getResolveDisputeMethod().getFullMethodName(), new GrpcCallRateMeter(20, SECONDS));
                            put(getOpenDisputeMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getSendDisputeChatMessageMethod().getFullMethodName(), new GrpcCallRateMeter(20, SECONDS));
                        }}
                )));
    }
}
