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

import haveno.common.config.Config;
import com.google.inject.Inject;
import haveno.core.api.CoreApi;
import haveno.core.api.model.MarketDepthInfo;
import haveno.core.api.model.MarketPriceInfo;
import haveno.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import haveno.daemon.grpc.interceptor.GrpcCallRateMeter;
import static haveno.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import haveno.proto.grpc.MarketDepthReply;
import haveno.proto.grpc.MarketDepthRequest;
import haveno.proto.grpc.MarketPriceReply;
import haveno.proto.grpc.MarketPriceRequest;
import haveno.proto.grpc.MarketPricesReply;
import haveno.proto.grpc.MarketPricesRequest;
import static haveno.proto.grpc.PriceGrpc.PriceImplBase;
import static haveno.proto.grpc.PriceGrpc.getGetMarketPriceMethod;
import haveno.proto.grpc.PriceGrpc.PriceImplBase;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static haveno.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import static haveno.proto.grpc.PriceGrpc.getGetMarketPriceMethod;
import static java.util.concurrent.TimeUnit.SECONDS;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class GrpcPriceService extends PriceImplBase {

    private final CoreApi coreApi;
    private final GrpcExceptionHandler exceptionHandler;

    @Inject
    public GrpcPriceService(CoreApi coreApi, GrpcExceptionHandler exceptionHandler) {
        this.coreApi = coreApi;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void getMarketPrice(MarketPriceRequest req,
                               StreamObserver<MarketPriceReply> responseObserver) {
        try {
            double marketPrice = coreApi.getMarketPrice(req.getCurrencyCode());
            responseObserver.onNext(MarketPriceReply.newBuilder().setPrice(marketPrice).build());
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void getMarketPrices(MarketPricesRequest request,
                                StreamObserver<MarketPricesReply> responseObserver) {
        try {
            responseObserver.onNext(mapMarketPricesReply(coreApi.getMarketPrices()));
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void getMarketDepth(MarketDepthRequest req,
                               StreamObserver<MarketDepthReply> responseObserver) {
        try {
            responseObserver.onNext(mapMarketDepthReply(coreApi.getMarketDepth(req.getCurrencyCode())));
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    private MarketPricesReply mapMarketPricesReply(List<MarketPriceInfo> marketPrices) {
        MarketPricesReply.Builder builder = MarketPricesReply.newBuilder();
        marketPrices.stream()
                .map(MarketPriceInfo::toProtoMessage)
                .forEach(builder::addMarketPrice);
        return builder.build();
    }

    private MarketDepthReply mapMarketDepthReply(MarketDepthInfo marketDepth) {
        return MarketDepthReply.newBuilder().setMarketDepth(marketDepth.toProtoMessage()).build();
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
                            put(getGetMarketPriceMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 20 : 1, SECONDS));
                        }}
                )));
    }
}
