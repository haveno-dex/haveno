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

package haveno.daemon.grpc;

import com.google.inject.Inject;
import haveno.common.config.Config;
import haveno.core.api.CoreApi;
import haveno.core.api.model.OfferInfo;
import haveno.core.offer.Offer;
import haveno.core.offer.OpenOffer;
import haveno.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import haveno.daemon.grpc.interceptor.GrpcCallRateMeter;
import static haveno.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import haveno.proto.grpc.CancelOfferReply;
import haveno.proto.grpc.CancelOfferRequest;
import haveno.proto.grpc.GetMyOfferReply;
import haveno.proto.grpc.GetMyOfferRequest;
import haveno.proto.grpc.GetMyOffersReply;
import haveno.proto.grpc.GetMyOffersRequest;
import haveno.proto.grpc.GetOfferReply;
import haveno.proto.grpc.GetOfferRequest;
import haveno.proto.grpc.GetOffersReply;
import haveno.proto.grpc.GetOffersRequest;
import static haveno.proto.grpc.OffersGrpc.OffersImplBase;
import static haveno.proto.grpc.OffersGrpc.getCancelOfferMethod;
import static haveno.proto.grpc.OffersGrpc.getGetMyOfferMethod;
import static haveno.proto.grpc.OffersGrpc.getGetMyOffersMethod;
import static haveno.proto.grpc.OffersGrpc.getGetOfferMethod;
import static haveno.proto.grpc.OffersGrpc.getGetOffersMethod;
import static haveno.proto.grpc.OffersGrpc.getPostOfferMethod;
import haveno.proto.grpc.PostOfferReply;
import haveno.proto.grpc.PostOfferRequest;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class GrpcOffersService extends OffersImplBase {

    private final CoreApi coreApi;
    private final GrpcExceptionHandler exceptionHandler;

    @Inject
    public GrpcOffersService(CoreApi coreApi, GrpcExceptionHandler exceptionHandler) {
        this.coreApi = coreApi;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void getOffer(GetOfferRequest req,
                         StreamObserver<GetOfferReply> responseObserver) {
        try {
            Offer offer = coreApi.getOffer(req.getId());
            var reply = GetOfferReply.newBuilder()
                    .setOffer(OfferInfo.toOfferInfo(offer).toProtoMessage())
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void getMyOffer(GetMyOfferRequest req,
                           StreamObserver<GetMyOfferReply> responseObserver) {
        try {
            OpenOffer openOffer = coreApi.getMyOffer(req.getId());
            var reply = GetMyOfferReply.newBuilder()
                    .setOffer(OfferInfo.toMyOfferInfo(openOffer).toProtoMessage())
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void getOffers(GetOffersRequest req,
                          StreamObserver<GetOffersReply> responseObserver) {
        try {
            List<OfferInfo> result = coreApi.getOffers(req.getDirection(), req.getCurrencyCode())
                    .stream().map(OfferInfo::toOfferInfo)
                    .collect(Collectors.toList());
            var reply = GetOffersReply.newBuilder()
                    .addAllOffers(result.stream()
                            .map(OfferInfo::toProtoMessage)
                            .collect(Collectors.toList()))
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void getMyOffers(GetMyOffersRequest req,
                            StreamObserver<GetMyOffersReply> responseObserver) {
        try {
            List<OfferInfo> result = new ArrayList<OfferInfo>();
            for (OpenOffer offer : coreApi.getMyOffers(req.getDirection(), req.getCurrencyCode())) {
                result.add(OfferInfo.toMyOfferInfo(offer));
            }
            var reply = GetMyOffersReply.newBuilder()
                    .addAllOffers(result.stream()
                            .map(OfferInfo::toProtoMessage)
                            .collect(Collectors.toList()))
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void postOffer(PostOfferRequest req,
                            StreamObserver<PostOfferReply> responseObserver) {
        GrpcErrorMessageHandler errorMessageHandler =
                new GrpcErrorMessageHandler(getPostOfferMethod().getFullMethodName(),
                        responseObserver,
                        exceptionHandler,
                        log);
        try {
            coreApi.postOffer(
                    req.getCurrencyCode(),
                    req.getDirection(),
                    req.getPrice(),
                    req.getUseMarketBasedPrice(),
                    req.getMarketPriceMarginPct(),
                    req.getAmount(),
                    req.getMinAmount(),
                    req.getBuyerSecurityDepositPct(),
                    req.getTriggerPrice(),
                    req.getReserveExactAmount(),
                    req.getPaymentAccountId(),
                    offer -> {
                        // This result handling consumer's accept operation will return
                        // the new offer to the gRPC client after async placement is done.
                        OpenOffer openOffer = coreApi.getMyOffer(offer.getId());
                        OfferInfo offerInfo = OfferInfo.toMyOfferInfo(openOffer);
                        PostOfferReply reply = PostOfferReply.newBuilder()
                                .setOffer(offerInfo.toProtoMessage())
                                .build();
                        responseObserver.onNext(reply);
                        responseObserver.onCompleted();
                    },
                    errorMessage -> {
                        if (!errorMessageHandler.isErrorHandled())
                            errorMessageHandler.handleErrorMessage(errorMessage);
                    });
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void cancelOffer(CancelOfferRequest req,
                            StreamObserver<CancelOfferReply> responseObserver) {
        try {
            coreApi.cancelOffer(req.getId());
            var reply = CancelOfferReply.newBuilder().build();
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
                            put(getGetOfferMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 10 : 1, SECONDS));
                            put(getGetMyOfferMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 10 : 1, SECONDS));
                            put(getGetOffersMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 20 : 1, SECONDS));
                            put(getGetMyOffersMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 20 : 3, Config.baseCurrencyNetwork().isTestnet() ? SECONDS : MINUTES));
                            put(getPostOfferMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 20 : 3, Config.baseCurrencyNetwork().isTestnet() ? SECONDS : MINUTES));
                            put(getCancelOfferMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 10 : 3, Config.baseCurrencyNetwork().isTestnet() ? SECONDS : MINUTES));
                        }}
                )));
    }
}
