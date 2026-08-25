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
import haveno.common.ThreadUtils;
import haveno.common.config.Config;
import haveno.core.api.CoreAccountService;
import haveno.core.api.CoreContext;
import haveno.daemon.grpc.interceptor.PasswordAuthInterceptor;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.network.SetupListener;
import static io.grpc.ServerInterceptors.interceptForward;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final long PUBLISH_RETRY_PERIOD_MS = 60000;

    private final Config config;
    private final P2PService p2PService;
    private final CoreAccountService coreAccountService;
    private final Server server;
    private String publishedOnionAddress;
    private volatile boolean isShutDownStarted;
    private volatile boolean publishRequested;
    private final AtomicBoolean isPublishing = new AtomicBoolean();

    @Inject
    public GrpcServer(CoreContext coreContext,
                      Config config,
                      P2PService p2PService,
                      CoreAccountService coreAccountService,
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
        this.coreAccountService = coreAccountService;
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

    // bind to 127.0.0.1 when the api is reached through the hidden service, which tor targets, except on a whonix
    // workstation where the gateway tor reaches the directly targeted api over the workstation interface; cap
    // concurrent calls per connection and reap idle connections so remote clients cannot hold unbounded resources
    private static ServerBuilder<?> serverBuilder(Config config) {
        if (!config.apiHiddenService) return ServerBuilder.forPort(config.apiPort);
        String host = isWhonixWorkstation() && config.apiHiddenServicePort == config.apiPort ? "0.0.0.0" : "127.0.0.1";
        return NettyServerBuilder.forAddress(new InetSocketAddress(host, config.apiPort))
                .maxConcurrentCallsPerConnection(256)
                .maxConnectionIdle(5, TimeUnit.MINUTES);
    }

    // same detection as netlayer, which binds its hidden service sockets externally on whonix
    private static boolean isWhonixWorkstation() {
        return new File("/usr/share/whonix/marker").exists() && new File("/usr/share/anon-ws-base-files/workstation").exists();
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

    // publish the api onion before login if the account cannot open automatically, so remote clients
    // can open it; otherwise wait for tor to start after login with all settings applied (e.g. bridges)
    private void publishHiddenService() {
        if (!coreAccountService.isAccountOpen()) publishHiddenServiceWithRetry();
        p2PService.getNetworkNode().addSetupListener(new SetupListener() {
            @Override
            public void onTorNodeReady() {
                publishHiddenServiceWithRetry(); // publish or republish on the started tor
            }

            @Override
            public void onHiddenServicePublished() {
                // nothing to do
            }

            @Override
            public void onSetupFailed(Throwable throwable) {
                publishHiddenServiceWithRetry(); // republish after a failed p2p setup, e.g. tor restarted for bridges but failed
            }
        });
    }

    // retry until published, with at most one active retry task; tor start and control calls block, so run off the user thread
    private void publishHiddenServiceWithRetry() {
        publishRequested = true;
        if (!isPublishing.compareAndSet(false, true)) return;
        ThreadUtils.submitToPool(() -> {
            boolean interrupted = false;
            try {
                while (!isShutDownStarted && publishRequested) {
                    publishRequested = false;
                    if (!tryPublishHiddenService()) {
                        publishRequested = true;
                        Thread.sleep(PUBLISH_RETRY_PERIOD_MS);
                    }
                }
            } catch (InterruptedException e) {
                interrupted = true; // stop retrying
            } finally {
                isPublishing.set(false);
                if (!interrupted && publishRequested && !isShutDownStarted) publishHiddenServiceWithRetry(); // a publish was requested while releasing
            }
        });
    }

    private synchronized boolean tryPublishHiddenService() {
        if (isShutDownStarted) return true; // do not publish an onion whose target server is closed
        try {
            int port = config.apiHiddenServicePort;
            String onionAddress = p2PService.getNetworkNode().publishHiddenService(HIDDEN_SERVICE_NAME, port, port);
            if (!onionAddress.equals(publishedOnionAddress)) {
                publishedOnionAddress = onionAddress;
                log.info("\n################################################################\n" +
                        "API hidden service created: {}:{}\n" +
                        "################################################################",
                        onionAddress, port);
            }
            return true;
        } catch (UnsupportedOperationException e) {
            log.error("Cannot publish API hidden service: {}", e.getMessage());
            return true; // the network node does not support hidden services, so do not retry
        } catch (Exception e) {
            log.error("Could not publish API hidden service", e);
            return false;
        }
    }

    public void shutdown() {
        log.info("Server shutdown started");
        isShutDownStarted = true;
        server.shutdown();
        log.info("Server shutdown complete");
    }
}
