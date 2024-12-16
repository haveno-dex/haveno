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
import haveno.core.api.CoreApi;
import haveno.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import haveno.daemon.grpc.interceptor.GrpcCallRateMeter;
import static haveno.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import haveno.proto.grpc.GetMethodHelpReply;
import haveno.proto.grpc.GetMethodHelpRequest;
import static haveno.proto.grpc.HelpGrpc.HelpImplBase;
import static haveno.proto.grpc.HelpGrpc.getGetMethodHelpMethod;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import java.util.HashMap;
import java.util.Optional;
import static java.util.concurrent.TimeUnit.SECONDS;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class GrpcHelpService extends HelpImplBase {

    private final CoreApi coreApi;
    private final GrpcExceptionHandler exceptionHandler;

    @Inject
    public GrpcHelpService(CoreApi coreApi, GrpcExceptionHandler exceptionHandler) {
        this.coreApi = coreApi;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void getMethodHelp(GetMethodHelpRequest req,
                              StreamObserver<GetMethodHelpReply> responseObserver) {
        try {
            String helpText = coreApi.getMethodHelp(req.getMethodName());
            var reply = GetMethodHelpReply.newBuilder().setMethodHelp(helpText).build();
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
                            put(getGetMethodHelpMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));
                        }}
                )));
    }
}
