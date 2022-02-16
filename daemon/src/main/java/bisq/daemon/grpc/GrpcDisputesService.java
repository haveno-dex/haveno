package bisq.daemon.grpc;

import bisq.core.api.CoreApi;
import bisq.core.support.dispute.Attachment;
import bisq.core.support.dispute.DisputeResult;
import bisq.core.util.ParsingUtils;

import bisq.common.proto.ProtoUtil;

import bisq.proto.grpc.DisputesGrpc.DisputesImplBase;
import bisq.proto.grpc.GetDisputeReply;
import bisq.proto.grpc.GetDisputeRequest;
import bisq.proto.grpc.GetDisputesReply;
import bisq.proto.grpc.GetDisputesRequest;
import bisq.proto.grpc.OpenDisputeReply;
import bisq.proto.grpc.OpenDisputeRequest;
import bisq.proto.grpc.ResolveDisputeReply;
import bisq.proto.grpc.ResolveDisputeRequest;
import bisq.proto.grpc.SendDisputeChatMessageReply;
import bisq.proto.grpc.SendDisputeChatMessageRequest;

import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import static bisq.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import static bisq.proto.grpc.DisputesGrpc.getGetDisputeMethod;
import static bisq.proto.grpc.DisputesGrpc.getGetDisputesMethod;
import static bisq.proto.grpc.DisputesGrpc.getOpenDisputeMethod;
import static bisq.proto.grpc.DisputesGrpc.getResolveDisputeMethod;
import static bisq.proto.grpc.DisputesGrpc.getSendDisputeChatMessageMethod;
import static java.util.concurrent.TimeUnit.SECONDS;

import bisq.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import bisq.daemon.grpc.interceptor.GrpcCallRateMeter;

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
                        exceptionHandler.handleException(log, throwable, responseObserver);
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
            exceptionHandler.handleException(log, cause, responseObserver);
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
            // scale atomic unit to centineros for consistency TODO switch base to atomic units?
            var customPayoutAmount = ParsingUtils.atomicUnitsToCentineros(req.getCustomPayoutAmount());
            coreApi.resolveDispute(req.getTradeId(), winner, reason, req.getSummaryNotes(), customPayoutAmount);
            var reply = ResolveDisputeReply.newBuilder().build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
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
                            put(getGetDisputeMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getGetDisputesMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getResolveDisputeMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getOpenDisputeMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getSendDisputeChatMessageMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                        }}
                )));
    }
}
