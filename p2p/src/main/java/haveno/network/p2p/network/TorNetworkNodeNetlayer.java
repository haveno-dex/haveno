package haveno.network.p2p.network;

import haveno.common.Timer;
import haveno.network.p2p.NodeAddress;

import haveno.common.UserThread;
import haveno.common.proto.network.NetworkProtoResolver;

import haveno.network.utils.Utils;
import org.berndpruenster.netlayer.tor.HiddenServiceSocket;
import org.berndpruenster.netlayer.tor.Tor;
import org.berndpruenster.netlayer.tor.TorCtlException;
import org.berndpruenster.netlayer.tor.TorSocket;

import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;

import java.security.SecureRandom;

import java.net.Socket;

import java.io.IOException;

import java.util.Base64;

import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.Nullable;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
public class TorNetworkNodeNetlayer extends TorNetworkNode {

    private static final long SHUT_DOWN_TIMEOUT = 2;

    private HiddenServiceSocket hiddenServiceSocket;
    private boolean streamIsolation;
    private Socks5Proxy socksProxy;
    protected TorMode torMode;
    private Tor tor;
    private final String torControlHost;
    private Timer shutDownTimeoutTimer;
    private boolean isShutDownStarted;
    private boolean isShutDownComplete;

    public TorNetworkNodeNetlayer(int servicePort,
                                  NetworkProtoResolver networkProtoResolver,
                                  TorMode torMode,
                                  @Nullable BanFilter banFilter,
                                  int maxConnections,
                                  boolean useStreamIsolation,
                                  String torControlHost) {
        super(servicePort, networkProtoResolver, banFilter, maxConnections);
        this.torControlHost = torControlHost;
        this.streamIsolation = useStreamIsolation;
        this.torMode = torMode;
    }

    @Override
    public void start(@Nullable SetupListener setupListener) {
        torMode.doRollingBackup();
        super.start(setupListener);
    }

    @Override
    public void shutDown(@Nullable Runnable shutDownCompleteHandler) {
        log.info("TorNetworkNodeNetlayer shutdown started");
        if (isShutDownComplete) {
            log.info("TorNetworkNodeNetlayer shutdown already completed");
            if (shutDownCompleteHandler != null) shutDownCompleteHandler.run();
            return;
        }
        if (isShutDownStarted) {
            log.warn("Ignoring request to shut down because shut down already started");
            return;
        }
        isShutDownStarted = true;

        shutDownTimeoutTimer = UserThread.runAfter(() -> {
            log.error("A timeout occurred at shutDown");
            isShutDownComplete = true;
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
                log.error("Shutdown TorNetworkNodeNetlayer failed with exception", e);
            } finally {
                shutDownTimeoutTimer.stop();
                isShutDownComplete = true;
                if (shutDownCompleteHandler != null) shutDownCompleteHandler.run();
            }
        });
    }

    @Override
    protected Socket createSocket(NodeAddress peerNodeAddress) throws IOException {
        checkArgument(peerNodeAddress.getHostName().endsWith(".onion"), "PeerAddress is not an onion address");
        // If streamId is null stream isolation gets deactivated.
        // Hidden services use stream isolation by default, so we pass null.
        return new TorSocket(peerNodeAddress.getHostName(), peerNodeAddress.getPort(), torControlHost, null);
    }

    @Override
    public Socks5Proxy getSocksProxy() {
        try {
            String stream = null;
            if (streamIsolation) {
                byte[] bytes = new byte[512]; // tor.getProxy creates a Sha256 hash
                new SecureRandom().nextBytes(bytes);
                stream = Base64.getEncoder().encodeToString(bytes);
            }

            if (socksProxy == null || streamIsolation) {
                tor = Tor.getDefault();
                socksProxy = tor != null ? tor.getProxy(torControlHost, stream) : null;
            }
            return socksProxy;
        } catch (Throwable t) {
            log.error("Error at getSocksProxy", t);
            return null;
        }
    }

    @Override
    protected void createTorAndHiddenService() {
        int localPort = Utils.findFreeSystemPort();
        executor.submit(() -> {
            try {
                Tor.setDefault(torMode.getTor());
                long ts = System.currentTimeMillis();
                hiddenServiceSocket = new HiddenServiceSocket(localPort, torMode.getHiddenServiceDirectory(), servicePort);
                nodeAddressProperty.set(new NodeAddress(hiddenServiceSocket.getServiceName() + ":" + hiddenServiceSocket.getHiddenServicePort()));
                UserThread.execute(() -> setupListeners.forEach(SetupListener::onTorNodeReady));
                hiddenServiceSocket.addReadyListener(socket -> {
                    log.info("\n################################################################\n" +
                                    "Tor hidden service published after {} ms. Socket={}\n" +
                                    "################################################################",
                            System.currentTimeMillis() - ts, socket);
                    UserThread.execute(() -> {
                        nodeAddressProperty.set(new NodeAddress(hiddenServiceSocket.getServiceName() + ":"
                                + hiddenServiceSocket.getHiddenServicePort()));
                        startServer(socket);
                        setupListeners.forEach(SetupListener::onHiddenServicePublished);
                    });
                    return null;
                });
            } catch (TorCtlException e) {
                log.error("Starting tor node failed", e);
                if (e.getCause() instanceof IOException) {
                    UserThread.execute(() -> setupListeners.forEach(s -> s.onSetupFailed(new RuntimeException(e.getMessage()))));
                } else {
                    UserThread.execute(() -> setupListeners.forEach(SetupListener::onRequestCustomBridges));
                    log.warn("We shutdown as starting tor with the default bridges failed. We request user to add custom bridges.");
                    shutDown(null);
                }
            } catch (IOException e) {
                log.error("Could not connect to running Tor", e);
                UserThread.execute(() -> setupListeners.forEach(s -> s.onSetupFailed(new RuntimeException(e.getMessage()))));
            } catch (Throwable ignore) {
            }
            return null;
        });
    }
}
