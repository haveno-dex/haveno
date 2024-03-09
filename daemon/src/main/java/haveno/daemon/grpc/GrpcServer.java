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
import io.grpc.Server;
import io.grpc.ServerBuilder;
import static io.grpc.ServerInterceptors.interceptForward;
import java.io.IOException;
import java.io.UncheckedIOException;
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
                .addService(interceptForward(accountService, accountService.interceptors()))
                .addService(interceptForward(disputeAgentsService, disputeAgentsService.interceptors()))
                .addService(interceptForward(disputesService, disputesService.interceptors()))
                .addService(interceptForward(helpService, helpService.interceptors()))
                .addService(interceptForward(offersService, offersService.interceptors()))
                .addService(interceptForward(paymentAccountsService, paymentAccountsService.interceptors()))
                .addService(interceptForward(priceService, priceService.interceptors()))
                .addService(shutdownService)
                .addService(interceptForward(tradeStatisticsService, tradeStatisticsService.interceptors()))
                .addService(interceptForward(tradesService, tradesService.interceptors()))
                .addService(interceptForward(versionService, versionService.interceptors()))
                .addService(interceptForward(walletsService, walletsService.interceptors()))
                .addService(interceptForward(notificationsService, notificationsService.interceptors()))
                .addService(interceptForward(moneroConnectionsService, moneroConnectionsService.interceptors()))
                .addService(interceptForward(moneroNodeService, moneroNodeService.interceptors()))
                .intercept(passwordAuthInterceptor)
                .build();
        coreContext.setApiUser(true);
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
