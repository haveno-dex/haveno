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
import haveno.network.p2p.P2PService;
import haveno.network.p2p.network.SetupListener;
import static io.grpc.ServerInterceptors.interceptForward;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class GrpcServer {

    private static final String HIDDEN_SERVICE_NAME = "api";

    private final Config config;
    private final P2PService p2PService;
    private final Server server;

    @Inject
    public GrpcServer(CoreContext coreContext,
                      Config config,
                      P2PService p2PService,
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
        this.config = config;
        this.p2PService = p2PService;
        this.server = serverBuilder(config)
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

    // bind to localhost when the api is reached through the hidden service
    private static ServerBuilder<?> serverBuilder(Config config) {
        if (!config.apiHiddenService) return ServerBuilder.forPort(config.apiPort);
        return NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), config.apiPort));
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
            if (config.apiHiddenService) publishHiddenService();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    // publish the api onion once tor is running, reusing the address of previous runs
    private void publishHiddenService() {
        p2PService.getNetworkNode().addSetupListener(new SetupListener() {
            @Override
            public void onTorNodeReady() {
                try {
                    int port = config.apiHiddenServicePort;
                    String onionAddress = p2PService.getNetworkNode().publishHiddenService(HIDDEN_SERVICE_NAME, port, port);
                    log.info("\n################################################################\n" +
                            "API hidden service published: {}:{}\n" +
                            "################################################################",
                            onionAddress, port);
                } catch (Exception e) {
                    log.error("Could not publish API hidden service", e);
                }
            }

            @Override
            public void onHiddenServicePublished() {
                // nothing to do
            }
        });
    }

    public void shutdown() {
        log.info("Server shutdown started");
        server.shutdown();
        log.info("Server shutdown complete");
    }
}
