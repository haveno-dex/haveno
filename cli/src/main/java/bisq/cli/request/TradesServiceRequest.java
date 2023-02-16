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

package bisq.cli.request;

import bisq.proto.grpc.ConfirmPaymentReceivedRequest;
import bisq.proto.grpc.ConfirmPaymentSentRequest;
import bisq.proto.grpc.GetTradeRequest;
import bisq.proto.grpc.GetTradesRequest;
import bisq.proto.grpc.TakeOfferReply;
import bisq.proto.grpc.TakeOfferRequest;
import bisq.proto.grpc.TradeInfo;
import bisq.proto.grpc.WithdrawFundsRequest;

import java.util.List;

import static bisq.proto.grpc.GetTradesRequest.Category.CLOSED;
import static bisq.proto.grpc.GetTradesRequest.Category.FAILED;



import bisq.cli.GrpcStubs;

public class TradesServiceRequest {

    private final GrpcStubs grpcStubs;

    public TradesServiceRequest(GrpcStubs grpcStubs) {
        this.grpcStubs = grpcStubs;
    }

    public TakeOfferReply getTakeOfferReply(String offerId, String paymentAccountId) {
        var request = TakeOfferRequest.newBuilder()
                .setOfferId(offerId)
                .setPaymentAccountId(paymentAccountId)
                .build();
        return grpcStubs.tradesService.takeOffer(request);
    }

    public TradeInfo takeOffer(String offerId, String paymentAccountId) {
        var reply = getTakeOfferReply(offerId, paymentAccountId);
        if (reply.hasTrade())
            return reply.getTrade();
        else
            throw new IllegalStateException(reply.getFailureReason().getDescription());
    }

    public TradeInfo getTrade(String tradeId) {
        var request = GetTradeRequest.newBuilder()
                .setTradeId(tradeId)
                .build();
        return grpcStubs.tradesService.getTrade(request).getTrade();
    }

    public List<TradeInfo> getOpenTrades() {
        var request = GetTradesRequest.newBuilder()
                .build();
        return grpcStubs.tradesService.getTrades(request).getTradesList();
    }

    public List<TradeInfo> getTradeHistory(GetTradesRequest.Category category) {
        if (!category.equals(CLOSED) && !category.equals(FAILED))
            throw new IllegalStateException("unrecognized gettrades category parameter " + category.name());

        var request = GetTradesRequest.newBuilder()
                .setCategory(category)
                .build();
        return grpcStubs.tradesService.getTrades(request).getTradesList();
    }

    public void confirmPaymentSent(String tradeId) {
        var request = ConfirmPaymentSentRequest.newBuilder()
                .setTradeId(tradeId)
                .build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.tradesService.confirmPaymentSent(request);
    }

    public void confirmPaymentReceived(String tradeId) {
        var request = ConfirmPaymentReceivedRequest.newBuilder()
                .setTradeId(tradeId)
                .build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.tradesService.confirmPaymentReceived(request);
    }

    public void withdrawFunds(String tradeId, String address, String memo) {
        var request = WithdrawFundsRequest.newBuilder()
                .setTradeId(tradeId)
                .setAddress(address)
                .setMemo(memo)
                .build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.tradesService.withdrawFunds(request);
    }
}
