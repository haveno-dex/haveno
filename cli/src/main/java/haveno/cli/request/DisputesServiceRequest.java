package haveno.cli.request;

import haveno.cli.GrpcStubs;
import protobuf.Dispute;
import haveno.proto.grpc.GetDisputeRequest;
import haveno.proto.grpc.GetDisputesRequest;
import haveno.proto.grpc.OpenDisputeRequest;
import haveno.proto.grpc.ResolveDisputeRequest;
import haveno.proto.grpc.SendDisputeChatMessageRequest;
import protobuf.DisputeResult;

import java.util.List;

public class DisputesServiceRequest {

    private final GrpcStubs grpcStubs;

    public DisputesServiceRequest(GrpcStubs grpcStubs) {
        this.grpcStubs = grpcStubs;
    }

    public Dispute getDispute(String tradeId) {
        GetDisputeRequest request = GetDisputeRequest.newBuilder()
                .setTradeId(tradeId)
                .build();
        return grpcStubs.disputesService.getDispute(request).getDispute();
    }

    public List<Dispute> getDisputes() {
        GetDisputesRequest request = GetDisputesRequest.newBuilder().build();
        return grpcStubs.disputesService.getDisputes(request).getDisputesList();
    }

    public void openDispute(String tradeId) {
        OpenDisputeRequest request = OpenDisputeRequest.newBuilder()
                .setTradeId(tradeId)
                .build();
        grpcStubs.disputesService.openDispute(request);
    }

    public void resolveDispute(String tradeId, String winner, String reason, String summaryNotes, long customPayoutAmount) {
        DisputeResult.Winner winnerEnum;
        switch (winner.toLowerCase()) {
            case "buyer":
                winnerEnum = DisputeResult.Winner.BUYER;
                break;
            case "seller":
                winnerEnum = DisputeResult.Winner.SELLER;
                break;
            default:
                throw new IllegalArgumentException("winner must be 'buyer' or 'seller'");
        }
        
        DisputeResult.Reason reasonEnum;
        switch (reason.toLowerCase()) {
            case "other":
                reasonEnum = DisputeResult.Reason.OTHER;
                break;
            case "bug":
                reasonEnum = DisputeResult.Reason.BUG;
                break;
            case "usability":
                reasonEnum = DisputeResult.Reason.USABILITY;
                break;
            case "scam":
                reasonEnum = DisputeResult.Reason.SCAM;
                break;
            case "protocol_violation":
                reasonEnum = DisputeResult.Reason.PROTOCOL_VIOLATION;
                break;
            case "no_reply":
                reasonEnum = DisputeResult.Reason.NO_REPLY;
                break;
            case "bank_problems":
                reasonEnum = DisputeResult.Reason.BANK_PROBLEMS;
                break;
            case "option_trade":
                reasonEnum = DisputeResult.Reason.OPTION_TRADE;
                break;
            case "seller_not_responding":
                reasonEnum = DisputeResult.Reason.SELLER_NOT_RESPONDING;
                break;
            case "wrong_sender_account":
                reasonEnum = DisputeResult.Reason.WRONG_SENDER_ACCOUNT;
                break;
            case "trade_already_settled":
                reasonEnum = DisputeResult.Reason.TRADE_ALREADY_SETTLED;
                break;
            case "peer_was_late":
                reasonEnum = DisputeResult.Reason.PEER_WAS_LATE;
                break;
            default:
                reasonEnum = DisputeResult.Reason.OTHER;
                break;
        }

        ResolveDisputeRequest request = ResolveDisputeRequest.newBuilder()
                .setTradeId(tradeId)
                .setWinner(winnerEnum)
                .setReason(reasonEnum)
                .setSummaryNotes(summaryNotes)
                .setCustomPayoutAmount(customPayoutAmount)
                .build();
        grpcStubs.disputesService.resolveDispute(request);
    }

    public void sendDisputeChatMessage(String disputeId, String message) {
        SendDisputeChatMessageRequest request = SendDisputeChatMessageRequest.newBuilder()
                .setDisputeId(disputeId)
                .setMessage(message)
                .build();
        grpcStubs.disputesService.sendDisputeChatMessage(request);
    }
}