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

package haveno.cli.request;

import haveno.cli.GrpcStubs;
import haveno.proto.grpc.GetDisputeRequest;
import haveno.proto.grpc.GetDisputesRequest;
import haveno.proto.grpc.OpenDisputeRequest;
import haveno.proto.grpc.ResolveDisputeRequest;
import haveno.proto.grpc.SendDisputeChatMessageRequest;
import protobuf.Dispute;
import protobuf.DisputeResult;

import java.util.List;

public class DisputesServiceRequest {

    private final GrpcStubs grpcStubs;

    public DisputesServiceRequest(GrpcStubs grpcStubs) {
        this.grpcStubs = grpcStubs;
    }

    public Dispute getDispute(String tradeId) {
        var request = GetDisputeRequest.newBuilder()
                .setTradeId(tradeId)
                .build();
        return grpcStubs.disputesService.getDispute(request).getDispute();
    }

    public List<Dispute> getDisputes() {
        var request = GetDisputesRequest.newBuilder().build();
        return grpcStubs.disputesService.getDisputes(request).getDisputesList();
    }

    public void openDispute(String tradeId) {
        var request = OpenDisputeRequest.newBuilder()
                .setTradeId(tradeId)
                .build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.disputesService.openDispute(request);
    }

    public void resolveDispute(String tradeId,
                               DisputeResult.Winner winner,
                               DisputeResult.Reason reason,
                               String summaryNotes,
                               long customPayoutAmount) {
        var request = ResolveDisputeRequest.newBuilder()
                .setTradeId(tradeId)
                .setWinner(winner)
                .setReason(reason)
                .setSummaryNotes(summaryNotes)
                .setCustomPayoutAmount(customPayoutAmount)
                .build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.disputesService.resolveDispute(request);
    }

    public void sendDisputeChatMessage(String disputeId, String message) {
        var request = SendDisputeChatMessageRequest.newBuilder()
                .setDisputeId(disputeId)
                .setMessage(message)
                .build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.disputesService.sendDisputeChatMessage(request);
    }
}
