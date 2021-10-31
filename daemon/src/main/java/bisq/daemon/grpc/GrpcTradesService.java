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

import haveno.core.api.CoreApi;
import haveno.core.api.model.TradeInfo;
import haveno.core.trade.Trade;

import haveno.proto.grpc.ConfirmPaymentReceivedReply;
import haveno.proto.grpc.ConfirmPaymentReceivedRequest;
import haveno.proto.grpc.ConfirmPaymentStartedReply;
import haveno.proto.grpc.ConfirmPaymentStartedRequest;
import haveno.proto.grpc.GetTradeReply;
import haveno.proto.grpc.GetTradeRequest;
import haveno.proto.grpc.KeepFundsReply;
import haveno.proto.grpc.KeepFundsRequest;
import haveno.proto.grpc.TakeOfferReply;
import haveno.proto.grpc.TakeOfferRequest;
import haveno.proto.grpc.WithdrawFundsReply;
import haveno.proto.grpc.WithdrawFundsRequest;

import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;

import javax.inject.Inject;

import java.util.HashMap;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import static haveno.core.api.model.TradeInfo.toTradeInfo;
import static haveno.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import static haveno.proto.grpc.TradesGrpc.*;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;



import haveno.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import haveno.daemon.grpc.interceptor.GrpcCallRateMeter;

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
            exceptionHandler.handleExceptionAsWarning(log, "getTrade", cause, responseObserver);
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void takeOffer(TakeOfferRequest req,
                          StreamObserver<TakeOfferReply> responseObserver) {
        GrpcErrorMessageHandler errorMessageHandler =
                new GrpcErrorMessageHandler(getTakeOfferMethod().getFullMethodName(),
                        responseObserver,
                        exceptionHandler,
                        log);
        coreApi.takeOffer(req.getOfferId(),
                req.getPaymentAccountId(),
                trade -> {
                    TradeInfo tradeInfo = toTradeInfo(trade);
                    var reply = TakeOfferReply.newBuilder()
                            .setTrade(tradeInfo.toProtoMessage())
                            .build();
                    responseObserver.onNext(reply);
                    responseObserver.onCompleted();
                },
                errorMessage -> {
                    if (!errorMessageHandler.isErrorHandled())
                        errorMessageHandler.handleErrorMessage(errorMessage);
                });
    }

    @Override
    public void confirmPaymentStarted(ConfirmPaymentStartedRequest req,
                                      StreamObserver<ConfirmPaymentStartedReply> responseObserver) {
        try {
            coreApi.confirmPaymentStarted(req.getTradeId());
            var reply = ConfirmPaymentStartedReply.newBuilder().build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void confirmPaymentReceived(ConfirmPaymentReceivedRequest req,
                                       StreamObserver<ConfirmPaymentReceivedReply> responseObserver) {
        try {
            coreApi.confirmPaymentReceived(req.getTradeId());
            var reply = ConfirmPaymentReceivedReply.newBuilder().build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void keepFunds(KeepFundsRequest req,
                          StreamObserver<KeepFundsReply> responseObserver) {
        try {
            coreApi.keepFunds(req.getTradeId());
            var reply = KeepFundsReply.newBuilder().build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void withdrawFunds(WithdrawFundsRequest req,
                              StreamObserver<WithdrawFundsReply> responseObserver) {
        try {
            coreApi.withdrawFunds(req.getTradeId(), req.getAddress(), req.getMemo());
            var reply = WithdrawFundsReply.newBuilder().build();
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
                            put(getGetTradeMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));
                            put(getTakeOfferMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));
                            put(getConfirmPaymentStartedMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));
                            put(getConfirmPaymentReceivedMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));
                            put(getKeepFundsMethod().getFullMethodName(), new GrpcCallRateMeter(1, MINUTES));
                            put(getWithdrawFundsMethod().getFullMethodName(), new GrpcCallRateMeter(1, MINUTES));
                        }}
                )));
    }
}
