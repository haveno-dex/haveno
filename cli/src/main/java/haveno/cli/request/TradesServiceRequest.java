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
import haveno.proto.grpc.CompleteTradeRequest;
import haveno.proto.grpc.ConfirmPaymentReceivedRequest;
import haveno.proto.grpc.ConfirmPaymentSentRequest;
import haveno.proto.grpc.GetChatMessagesRequest;
import haveno.proto.grpc.GetTradeRequest;
import haveno.proto.grpc.GetTradesRequest;
import haveno.proto.grpc.SendChatMessageRequest;
import haveno.proto.grpc.TakeOfferReply;
import haveno.proto.grpc.TakeOfferRequest;
import haveno.proto.grpc.TradeInfo;
import haveno.proto.grpc.WithdrawFundsRequest;
import protobuf.ChatMessage;

import java.util.List;

import static haveno.proto.grpc.GetTradesRequest.Category.CLOSED;
import static haveno.proto.grpc.GetTradesRequest.Category.FAILED;

public class TradesServiceRequest {

    private final GrpcStubs grpcStubs;

    public TradesServiceRequest(GrpcStubs grpcStubs) {
        this.grpcStubs = grpcStubs;
    }

    public TakeOfferReply getTakeOfferReply(String offerId, String paymentAccountId, long amount, String challenge) {
        var request = TakeOfferRequest.newBuilder()
                .setOfferId(offerId)
                .setPaymentAccountId(paymentAccountId)
                .setAmount(amount)
                .setChallenge(challenge)
                .build();
        return grpcStubs.tradesService.takeOffer(request);
    }

    public TradeInfo takeOffer(String offerId, String paymentAccountId) {
        return takeOffer(offerId, paymentAccountId, 0 /* take full offer amount */, "");
    }

    public TradeInfo takeOffer(String offerId, String paymentAccountId, long amount, String challenge) {
        var reply = getTakeOfferReply(offerId, paymentAccountId, amount, challenge);
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

    public void completeTrade(String tradeId) {
        var request = CompleteTradeRequest.newBuilder()
                .setTradeId(tradeId)
                .build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.tradesService.completeTrade(request);
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

    public List<ChatMessage> getChatMessages(String tradeId) {
        var request = GetChatMessagesRequest.newBuilder()
                .setTradeId(tradeId)
                .build();
        return grpcStubs.tradesService.getChatMessages(request).getMessageList();
    }

    public void sendChatMessage(String tradeId, String message) {
        var request = SendChatMessageRequest.newBuilder()
                .setTradeId(tradeId)
                .setMessage(message)
                .build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.tradesService.sendChatMessage(request);
    }
}
