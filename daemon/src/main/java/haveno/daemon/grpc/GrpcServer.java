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
import com.google.inject.Singleton;
import haveno.common.config.Config;
import haveno.core.api.CoreContext;
import haveno.daemon.grpc.interceptor.PasswordAuthInterceptor;
import static io.grpc.ServerInterceptors.interceptForward;
import java.io.IOException;
import java.io.UncheckedIOException;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class GrpcServer {

    private final Server server;

    @Inject
    public GrpcServer(CoreContext coreContext,
                      Config config,
                      PasswordAuthInterceptor passwordAuthInterceptor,
                      GrpcAccountService accountService,
                      GrpcDisputeAgentsService disputeAgentsService,
                      GrpcDisputesService disputesService,
                      GrpcHelpService helpService,
                      GrpcOffersService offersService,
                      GrpcPaymentAccountsService paymentAccountsService,
                      GrpcPriceService priceService,
                      GrpcShutdownService shutdownService,
                      GrpcVersionService versionService,
                      GrpcGetTradeStatisticsService tradeStatisticsService,
                      GrpcTradesService tradesService,
                      GrpcWalletsService walletsService,
                      GrpcNotificationsService notificationsService,
                      GrpcXmrConnectionService moneroConnectionsService,
                      GrpcXmrNodeService moneroNodeService) {
        this.server = ServerBuilder.forPort(config.apiPort)
                .addService(shutdownService)
                .intercept(passwordAuthInterceptor)
                .addService(interceptForward(accountService, config.disableRateLimits ? interceptors() : accountService.interceptors()))
                .addService(interceptForward(disputeAgentsService, config.disableRateLimits ? interceptors() : disputeAgentsService.interceptors()))
                .addService(interceptForward(disputesService, config.disableRateLimits ? interceptors() : disputesService.interceptors()))
                .addService(interceptForward(helpService, config.disableRateLimits ? interceptors() : helpService.interceptors()))
                .addService(interceptForward(offersService, config.disableRateLimits ? interceptors() : offersService.interceptors()))
                .addService(interceptForward(paymentAccountsService, config.disableRateLimits ? interceptors() : paymentAccountsService.interceptors()))
                .addService(interceptForward(priceService, config.disableRateLimits ? interceptors() : priceService.interceptors()))
                .addService(interceptForward(tradeStatisticsService, config.disableRateLimits ? interceptors() : tradeStatisticsService.interceptors()))
                .addService(interceptForward(tradesService, config.disableRateLimits ? interceptors() : tradesService.interceptors()))
                .addService(interceptForward(versionService, config.disableRateLimits ? interceptors() :  versionService.interceptors()))
                .addService(interceptForward(walletsService, config.disableRateLimits ? interceptors() : walletsService.interceptors()))
                .addService(interceptForward(notificationsService, config.disableRateLimits ? interceptors() : notificationsService.interceptors()))
                .addService(interceptForward(moneroConnectionsService, config.disableRateLimits ? interceptors() : moneroConnectionsService.interceptors()))
                .addService(interceptForward(moneroNodeService, config.disableRateLimits ? interceptors() :  moneroNodeService.interceptors()))
                .build();

        coreContext.setApiUser(true);
    }

    private ServerInterceptor[] interceptors() {
        return new ServerInterceptor[]{callLoggingInterceptor()};
    }

    private ServerInterceptor callLoggingInterceptor() {
        return new ServerInterceptor() {
            @Override
            public <RequestT, ResponseT> ServerCall.Listener<RequestT> interceptCall(ServerCall<RequestT, ResponseT> call, Metadata headers, ServerCallHandler<RequestT, ResponseT> next) {
                log.debug("GRPC endpoint called: " + call.getMethodDescriptor().getFullMethodName());
                return next.startCall(call, headers);
            }
        };
    }

    public void start() {
        try {
            server.start();
            log.info("listening on port {}", server.getPort());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public void shutdown() {
        log.info("Server shutdown started");
        server.shutdown();
        log.info("Server shutdown complete");
    }
}
