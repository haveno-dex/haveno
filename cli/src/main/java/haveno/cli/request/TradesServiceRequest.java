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

package haveno.cli.request;

import haveno.cli.GrpcStubs;
import protobuf.ChatMessage;
import haveno.proto.grpc.CompleteTradeRequest;
import haveno.proto.grpc.ConfirmPaymentReceivedRequest;
import haveno.proto.grpc.ConfirmPaymentSentRequest;
import haveno.proto.grpc.GetChatMessagesReply;
import haveno.proto.grpc.GetChatMessagesRequest;
import haveno.proto.grpc.GetTradeReply;
import haveno.proto.grpc.GetTradeRequest;
import haveno.proto.grpc.GetTradesReply;
import haveno.proto.grpc.GetTradesRequest;

import haveno.proto.grpc.SendChatMessageRequest;
import haveno.proto.grpc.TakeOfferReply;
import haveno.proto.grpc.TakeOfferRequest;
import haveno.proto.grpc.TradeInfo;

import haveno.proto.grpc.WithdrawFundsRequest;

import java.util.List;

import static haveno.proto.grpc.GetTradesRequest.Category.CLOSED;
import static haveno.proto.grpc.GetTradesRequest.Category.FAILED;
import static haveno.proto.grpc.GetTradesRequest.Category.OPEN;

public class TradesServiceRequest {

    private final GrpcStubs grpcStubs;

    public TradesServiceRequest(GrpcStubs grpcStubs) {
        this.grpcStubs = grpcStubs;
    }

    public TakeOfferReply getTakeOfferReply(String offerId, String paymentAccountId, long amount, String challenge) {
        TakeOfferRequest request = TakeOfferRequest.newBuilder()
                .setOfferId(offerId)
                .setPaymentAccountId(paymentAccountId)
                .setAmount(amount)
                .setChallenge(challenge)
                .build();
        return grpcStubs.tradesService.takeOffer(request);
    }

    public TradeInfo takeOffer(String offerId, String paymentAccountId, long amount, String challenge) {
        TakeOfferReply reply = getTakeOfferReply(offerId, paymentAccountId, amount, challenge);
        if (reply.hasTrade()) {
            return reply.getTrade();
        } else {
            throw new IllegalStateException(reply.getFailureReason().getDescription());
        }
    }

    public TradeInfo getTrade(String tradeId) {
        GetTradeRequest request = GetTradeRequest.newBuilder()
                .setTradeId(tradeId)
                .build();
        GetTradeReply reply = grpcStubs.tradesService.getTrade(request);
        return reply.getTrade();
    }

    public List<TradeInfo> getOpenTrades() {
        GetTradesRequest request = GetTradesRequest.newBuilder()
                .setCategory(OPEN)
                .build();
        GetTradesReply reply = grpcStubs.tradesService.getTrades(request);
        return reply.getTradesList();
    }

    public List<TradeInfo> getTradeHistory(GetTradesRequest.Category category) {
        if (!category.equals(CLOSED) && !category.equals(FAILED) && !category.equals(OPEN)) {
            throw new IllegalStateException("Unrecognized getTrades category parameter " + category.name());
        }

        GetTradesRequest request = GetTradesRequest.newBuilder()
                .setCategory(category)
                .build();
        GetTradesReply reply = grpcStubs.tradesService.getTrades(request);
        return reply.getTradesList();
    }

    public void confirmPaymentSent(String tradeId) {
        ConfirmPaymentSentRequest request = ConfirmPaymentSentRequest.newBuilder()
                .setTradeId(tradeId)
                .build();
        grpcStubs.tradesService.confirmPaymentSent(request);
    }

    public void confirmPaymentReceived(String tradeId) {
        ConfirmPaymentReceivedRequest request = ConfirmPaymentReceivedRequest.newBuilder()
                .setTradeId(tradeId)
                .build();
        grpcStubs.tradesService.confirmPaymentReceived(request);
    }

    public void withdrawFunds(String tradeId, String address, String memo) {
        WithdrawFundsRequest request = WithdrawFundsRequest.newBuilder()
                .setTradeId(tradeId)
                .setAddress(address)
                .setMemo(memo)
                .build();
        grpcStubs.tradesService.withdrawFunds(request);
    }

    public List<ChatMessage> getChatMessages(String tradeId) {
        GetChatMessagesRequest request = GetChatMessagesRequest.newBuilder()
                .setTradeId(tradeId)
                .build();
        GetChatMessagesReply reply = grpcStubs.tradesService.getChatMessages(request);
        return reply.getMessageList();
    }

    public void sendChatMessage(String tradeId, String message) {
        SendChatMessageRequest request = SendChatMessageRequest.newBuilder()
                .setTradeId(tradeId)
                .setMessage(message)
                .build();
        grpcStubs.tradesService.sendChatMessage(request);
    }

    public void completeTrade(String tradeId) {
        CompleteTradeRequest request = CompleteTradeRequest.newBuilder()
                .setTradeId(tradeId)
                .build();
        grpcStubs.tradesService.completeTrade(request);
    }
}
