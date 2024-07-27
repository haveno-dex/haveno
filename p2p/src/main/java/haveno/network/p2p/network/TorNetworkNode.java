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

package haveno.network.p2p.network;

import haveno.network.p2p.NodeAddress;
import haveno.network.utils.Utils;

import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.proto.network.NetworkProtoResolver;
import haveno.common.util.SingleThreadExecutorUtils;

import org.berndpruenster.netlayer.tor.Tor;

import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;

import java.net.Socket;

import java.io.IOException;

import java.util.concurrent.ExecutorService;

import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.Nullable;

@Slf4j
public abstract class TorNetworkNode extends NetworkNode {
    private static final long SHUT_DOWN_TIMEOUT = 2;

    protected final String torControlHost;
    protected final String serviceAddress;

    private Timer shutDownTimeoutTimer;
    protected Tor tor;
    protected TorMode torMode;
    private boolean shutDownInProgress;
    private boolean shutDownComplete;
    protected final ExecutorService executor;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public TorNetworkNode(String hiddenServiceAddress, int servicePort,
            NetworkProtoResolver networkProtoResolver,
            boolean useStreamIsolation,
            TorMode torMode,
            @Nullable BanFilter banFilter,
            int maxConnections, String torControlHost) {
        super(servicePort, networkProtoResolver, banFilter, maxConnections);
        this.serviceAddress = hiddenServiceAddress;
        this.torMode = torMode;
        this.torControlHost = torControlHost;

        executor = SingleThreadExecutorUtils.getSingleThreadExecutor("StartTor");
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void start(@Nullable SetupListener setupListener) {
        torMode.doRollingBackup();

        if (setupListener != null)
            addSetupListener(setupListener);

        createTorAndHiddenService(Utils.findFreeSystemPort(), servicePort);
    }

    public abstract Socks5Proxy getSocksProxy();

    protected abstract Socket createSocket(NodeAddress peerNodeAddress) throws IOException;

    public void shutDown(@Nullable Runnable shutDownCompleteHandler) {
        log.info("TorNetworkNode shutdown started");
        if (shutDownComplete) {
            log.info("TorNetworkNode shutdown already completed");
            if (shutDownCompleteHandler != null) shutDownCompleteHandler.run();
            return;
        }
        if (shutDownInProgress) {
            log.warn("Ignoring request to shut down because shut down is in progress");
            return;
        }
        shutDownInProgress = true;

        shutDownTimeoutTimer = UserThread.runAfter(() -> {
            log.error("A timeout occurred at shutDown");
            shutDownComplete = true;
            if (shutDownCompleteHandler != null) shutDownCompleteHandler.run();
            executor.shutdownNow();
        }, SHUT_DOWN_TIMEOUT);

        super.shutDown(() -> {
            try {
                tor = Tor.getDefault();
                if (tor != null) {
                    tor.shutdown();
                    tor = null;
                    log.info("Tor shutdown completed");
                }
                executor.shutdownNow();
            } catch (Throwable e) {
                log.error("Shutdown torNetworkNode failed with exception", e);
            } finally {
                shutDownTimeoutTimer.stop();
                shutDownComplete = true;
                if (shutDownCompleteHandler != null) shutDownCompleteHandler.run();
            }
        });
    }

    protected abstract void createTorAndHiddenService(int localPort, int servicePort);
}
