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
import haveno.core.api.AccountServiceListener;
import haveno.core.api.CoreAccountService;
import haveno.core.api.CoreContext;
import haveno.daemon.grpc.interceptor.PasswordAuthInterceptor;
import haveno.network.p2p.network.NetworkNode;
import haveno.network.p2p.network.SetupListener;
import haveno.network.p2p.network.TorMode;
import haveno.network.p2p.network.TorNetworkNodeNetlayer;
import static io.grpc.ServerInterceptors.interceptForward;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;
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

    private static final long PUBLISH_RETRY_SEC = 60;
    private static final long SHUTDOWN_TIMEOUT_SEC = 2;

    private final Config config;
    private final CoreAccountService coreAccountService;
    private final NetworkNode networkNode;
    private final Server server;
    private volatile boolean isShutDownStarted;

    @Inject
    public GrpcServer(CoreContext coreContext,
                      Config config,
                      CoreAccountService coreAccountService,
                      NetworkNode networkNode,
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

        if (config.apiPassword == null || config.apiPassword.isBlank())
            throw new IllegalStateException("Cannot start the gRPC API with an empty apiPassword; set --apiPassword to a strong secret");

        this.config = config;
        this.coreAccountService = coreAccountService;
        this.networkNode = networkNode;
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

        // stop serving once the account is deleted or restored, so the reply to that call is the last from this instance
        coreAccountService.addListener(new AccountServiceListener() {
            @Override public void onAccountDeleted(Runnable onShutdown) { shutdown(); }
            @Override public void onAccountRestored(Runnable onShutdown) { shutdown(); }
        });
        try {
            server.start();
            log.info("listening on port {}", server.getPort());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        if (config.apiHiddenService) publishHiddenService();
    }

    // publish before login so remote clients can open the account, else on the tor started after login with persisted settings applied (e.g. bridges)
    private void publishHiddenService() {
        if (!(networkNode instanceof TorNetworkNodeNetlayer)) {
            log.error("Cannot publish api hidden service without Haveno's tor");
            return;
        }
        if (!coreAccountService.isAccountOpen()) {
            publishHiddenServiceWithRetry();
        } else {
            networkNode.addSetupListener(new SetupListener() {
                @Override public void onTorNodeReady() { publishHiddenServiceWithRetry(); }
                @Override public void onSetupFailed(Throwable throwable) { publishHiddenServiceWithRetry(); } // retry until tor is available
                @Override public void onHiddenServicePublished() { }
            });
        }
    }

    private void publishHiddenServiceWithRetry() {
        if (isShutDownStarted || networkNode.isShutDownStarted()) return;
        int port = config.apiHiddenServicePort;
        ((TorNetworkNodeNetlayer) networkNode).publishHiddenService(TorMode.API_HIDDEN_SERVICE_NAME, port).whenComplete((hostname, e) -> {
            if (e == null) {
                log.info("API hidden service created at {}:{}", hostname, port);
            } else {
                log.error("Failed to publish api hidden service, retrying in {} seconds", PUBLISH_RETRY_SEC, e);
                ThreadUtils.runAfter(this::publishHiddenServiceWithRetry, PUBLISH_RETRY_SEC); // not the user thread, which blocks until login
            }
        });
    }

    public void shutdown() {
        if (isShutDownStarted) return;
        isShutDownStarted = true;
        log.info("Server shutdown started");
        server.shutdown();
        log.info("Server shutdown complete");
    }

    // waits for calls in flight to reply, then ends any still open (e.g. notification streams), so nothing is served past this point
    public void awaitTermination() {
        try {
            if (!server.awaitTermination(SHUTDOWN_TIMEOUT_SEC, TimeUnit.SECONDS)) server.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
