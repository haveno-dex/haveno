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

package haveno.daemon.grpc;

import com.google.inject.Inject;
import haveno.common.config.Config;
import haveno.core.api.CoreApi;
import haveno.core.api.model.TradeInfo;
import static haveno.core.api.model.TradeInfo.toTradeInfo;
import haveno.core.trade.Trade;
import haveno.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import haveno.daemon.grpc.interceptor.GrpcCallRateMeter;
import static haveno.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import haveno.proto.grpc.CompleteTradeReply;
import haveno.proto.grpc.CompleteTradeRequest;
import haveno.proto.grpc.ConfirmPaymentReceivedReply;
import haveno.proto.grpc.ConfirmPaymentReceivedRequest;
import haveno.proto.grpc.ConfirmPaymentSentReply;
import haveno.proto.grpc.ConfirmPaymentSentRequest;
import haveno.proto.grpc.GetChatMessagesReply;
import haveno.proto.grpc.GetChatMessagesRequest;
import haveno.proto.grpc.GetTradeReply;
import haveno.proto.grpc.GetTradeRequest;
import haveno.proto.grpc.GetTradesReply;
import haveno.proto.grpc.GetTradesRequest;
import haveno.proto.grpc.SendChatMessageReply;
import haveno.proto.grpc.SendChatMessageRequest;
import haveno.proto.grpc.TakeOfferReply;
import haveno.proto.grpc.TakeOfferRequest;
import haveno.proto.grpc.TradesGrpc.TradesImplBase;
import static haveno.proto.grpc.TradesGrpc.getCompleteTradeMethod;
import static haveno.proto.grpc.TradesGrpc.getConfirmPaymentReceivedMethod;
import static haveno.proto.grpc.TradesGrpc.getConfirmPaymentSentMethod;
import static haveno.proto.grpc.TradesGrpc.getGetChatMessagesMethod;
import static haveno.proto.grpc.TradesGrpc.getGetTradeMethod;
import static haveno.proto.grpc.TradesGrpc.getGetTradesMethod;
import static haveno.proto.grpc.TradesGrpc.getSendChatMessageMethod;
import static haveno.proto.grpc.TradesGrpc.getTakeOfferMethod;
import static haveno.proto.grpc.TradesGrpc.getWithdrawFundsMethod;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class GrpcTradesService extends TradesImplBase {

    private final CoreApi coreApi;
    private final GrpcExceptionHandler exceptionHandler;

    @Inject
    public GrpcTradesService(CoreApi coreApi, GrpcExceptionHandler exceptionHandler) {
        this.coreApi = coreApi;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void getTrade(GetTradeRequest req,
                         StreamObserver<GetTradeReply> responseObserver) {
        try {
            Trade trade = coreApi.getTrade(req.getTradeId());
            String role = coreApi.getTradeRole(req.getTradeId());
            var reply = GetTradeReply.newBuilder()
                    .setTrade(toTradeInfo(trade, role).toProtoMessage())
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException cause) {
            // Offer makers may call 'gettrade' many times before a trade exists.
            // Log a 'trade not found' warning instead of a full stack trace.
            cause.printStackTrace();
            exceptionHandler.handleExceptionAsWarning(log, "getTrade", cause, responseObserver);
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void getTrades(GetTradesRequest req,
                         StreamObserver<GetTradesReply> responseObserver) {
        try {
            List<TradeInfo> trades = coreApi.getTrades()
                    .stream().map(TradeInfo::toTradeInfo)
                    .collect(Collectors.toList());
            var reply = GetTradesReply.newBuilder()
                    .addAllTrades(trades.stream()
                            .map(TradeInfo::toProtoMessage)
                            .collect(Collectors.toList()))
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void takeOffer(TakeOfferRequest req,
                          StreamObserver<TakeOfferReply> responseObserver) {
        GrpcErrorMessageHandler errorMessageHandler = new GrpcErrorMessageHandler(getTakeOfferMethod().getFullMethodName(), responseObserver, exceptionHandler, log);
        try {
            coreApi.takeOffer(req.getOfferId(),
                    req.getPaymentAccountId(),
                    req.getAmount(),
                    trade -> {
                        TradeInfo tradeInfo = toTradeInfo(trade);
                        var reply = TakeOfferReply.newBuilder()
                                .setTrade(tradeInfo.toProtoMessage())
                                .build();
                        responseObserver.onNext(reply);
                        responseObserver.onCompleted();
                    },
                    errorMessage -> {
                        if (!errorMessageHandler.isErrorHandled()) errorMessageHandler.handleErrorMessage(errorMessage);
                    });
        } catch (Throwable cause) {
            cause.printStackTrace();
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void confirmPaymentSent(ConfirmPaymentSentRequest req,
                                      StreamObserver<ConfirmPaymentSentReply> responseObserver) {
        GrpcErrorMessageHandler errorMessageHandler = new GrpcErrorMessageHandler(getConfirmPaymentSentMethod().getFullMethodName(), responseObserver, exceptionHandler, log);
        try {
            coreApi.confirmPaymentSent(req.getTradeId(),
                    () -> {
                        var reply = ConfirmPaymentSentReply.newBuilder().build();
                        responseObserver.onNext(reply);
                        responseObserver.onCompleted();
                    },
                    errorMessage -> {
                        if (!errorMessageHandler.isErrorHandled()) errorMessageHandler.handleErrorMessage(errorMessage);
                    });
        } catch (Throwable cause) {
            cause.printStackTrace();
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void confirmPaymentReceived(ConfirmPaymentReceivedRequest req,
                                       StreamObserver<ConfirmPaymentReceivedReply> responseObserver) {
        GrpcErrorMessageHandler errorMessageHandler = new GrpcErrorMessageHandler(getConfirmPaymentReceivedMethod().getFullMethodName(), responseObserver, exceptionHandler, log);
        try {
            coreApi.confirmPaymentReceived(req.getTradeId(),
                    () -> {
                        var reply = ConfirmPaymentReceivedReply.newBuilder().build();
                        responseObserver.onNext(reply);
                        responseObserver.onCompleted();
                    },
                    errorMessage -> {
                        if (!errorMessageHandler.isErrorHandled()) errorMessageHandler.handleErrorMessage(errorMessage);
                    });
        } catch (Throwable cause) {
            cause.printStackTrace();
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    // TODO: rename CompleteTradeRequest to CloseTradeRequest
    @Override
    public void completeTrade(CompleteTradeRequest req,
                          StreamObserver<CompleteTradeReply> responseObserver) {
        try {
            coreApi.closeTrade(req.getTradeId());
            var reply = CompleteTradeReply.newBuilder().build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void getChatMessages(GetChatMessagesRequest req,
                                StreamObserver<GetChatMessagesReply> responseObserver) {
        try {
            var tradeChats = coreApi.getChatMessages(req.getTradeId())
                    .stream()
                    .map(msg -> msg.toProtoNetworkEnvelope().getChatMessage())
                    .collect(Collectors.toList());
            var reply = GetChatMessagesReply.newBuilder()
                    .addAllMessage(tradeChats)
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void sendChatMessage(SendChatMessageRequest req,
                                StreamObserver<SendChatMessageReply> responseObserver) {
        try {
            coreApi.sendChatMessage(req.getTradeId(), req.getMessage());
            var reply = SendChatMessageReply.newBuilder().build();
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
                            put(getGetTradeMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 30 : 1, SECONDS));
                            put(getGetTradesMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 10 : 1, SECONDS));
                            put(getTakeOfferMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 20 : 3, Config.baseCurrencyNetwork().isTestnet() ? SECONDS : MINUTES));
                            put(getConfirmPaymentSentMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 10 : 3, Config.baseCurrencyNetwork().isTestnet() ? SECONDS : MINUTES));
                            put(getConfirmPaymentReceivedMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 10 : 3, Config.baseCurrencyNetwork().isTestnet() ? SECONDS : MINUTES));
                            put(getCompleteTradeMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 10 : 3, Config.baseCurrencyNetwork().isTestnet() ? SECONDS : MINUTES));
                            put(getWithdrawFundsMethod().getFullMethodName(), new GrpcCallRateMeter(3, MINUTES));
                            put(getGetChatMessagesMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 10 : 1, Config.baseCurrencyNetwork().isTestnet() ? SECONDS : MINUTES));
                            put(getSendChatMessageMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 10 : 1, Config.baseCurrencyNetwork().isTestnet() ? SECONDS : MINUTES));
                        }}
                )));
    }
}
