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

import com.google.common.annotations.VisibleForTesting;

import haveno.common.config.Config;
import com.google.inject.Inject;
import haveno.core.api.CoreApi;
import haveno.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import haveno.daemon.grpc.interceptor.GrpcCallRateMeter;
import static haveno.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import static haveno.proto.grpc.GetVersionGrpc.GetVersionImplBase;
import static haveno.proto.grpc.GetVersionGrpc.getGetVersionMethod;
import haveno.proto.grpc.GetVersionGrpc.GetVersionImplBase;
import haveno.proto.grpc.GetVersionReply;
import haveno.proto.grpc.GetVersionRequest;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import java.util.HashMap;
import java.util.Optional;

import static haveno.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import static haveno.proto.grpc.GetVersionGrpc.getGetVersionMethod;
import static java.util.concurrent.TimeUnit.SECONDS;
import lombok.extern.slf4j.Slf4j;

@VisibleForTesting
@Slf4j
public class GrpcVersionService extends GetVersionImplBase {

    private final CoreApi coreApi;
    private final GrpcExceptionHandler exceptionHandler;

    @Inject
    public GrpcVersionService(CoreApi coreApi, GrpcExceptionHandler exceptionHandler) {
        this.coreApi = coreApi;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void getVersion(GetVersionRequest req, StreamObserver<GetVersionReply> responseObserver) {
        try {
            var reply = GetVersionReply.newBuilder().setVersion(coreApi.getVersion()).build();
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
                            put(getGetVersionMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 5 : 1, SECONDS));
                        }}
                )));
    }
}
