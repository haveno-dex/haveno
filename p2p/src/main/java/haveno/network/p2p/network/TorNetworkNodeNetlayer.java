package haveno.network.p2p.network;

import haveno.common.Timer;
import haveno.network.p2p.NodeAddress;

import haveno.common.UserThread;
import haveno.common.proto.network.NetworkProtoResolver;

import haveno.network.utils.Utils;
import org.berndpruenster.netlayer.tor.HiddenServiceSocket;
import org.berndpruenster.netlayer.tor.Tor;
import org.berndpruenster.netlayer.tor.TorCtlException;

import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;

import java.security.SecureRandom;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.Nullable;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
public class TorNetworkNodeNetlayer extends TorNetworkNode {

    private static final long SHUT_DOWN_TIMEOUT_SEC = 2;
    private final static boolean POW_ENABLED_DEFAULT = true;
    private final static int POW_QUEUE_RATE_DEFAULT = 10;
    private final static int POW_QUEUE_BURST_DEFAULT = 100;

    private HiddenServiceSocket hiddenServiceSocket;
    private boolean streamIsolation;
    private Socks5Proxy socksProxy;
    protected TorMode torMode;
    private final String hiddenServiceFlags;
    private final String hiddenServiceParams;
    private final String torControlHost;
    private Timer shutDownTimeoutTimer;
    private volatile boolean isShutDownStarted;
    private boolean isShutDownComplete;
    private final Object torStartLock = new Object();
    private final Object torStartThreadLock = new Object();
    private Thread torStartThread;
    private final Map<String, String> publishedHiddenServices = new HashMap<String, String>();
    private Tor publishedHiddenServicesTor;
    private Tor socksProxyTor;
    private final BridgeAddressProvider bridgeAddressProvider;
    private List<String> torBootBridges;

    public TorNetworkNodeNetlayer(int servicePort,
                                  NetworkProtoResolver networkProtoResolver,
                                  TorMode torMode,
                                  @Nullable BanFilter banFilter,
                                  int maxConnections,
                                  boolean useStreamIsolation,
                                  String hiddenServiceFlags,
                                  String hiddenServiceParams,
                                  String torControlHost,
                                  BridgeAddressProvider bridgeAddressProvider) {
        super(servicePort, networkProtoResolver, banFilter, maxConnections);
        this.hiddenServiceFlags = hiddenServiceFlags;
        this.hiddenServiceParams = hiddenServiceParams;
        this.torControlHost = torControlHost;
        this.streamIsolation = useStreamIsolation;
        this.torMode = torMode;
        this.bridgeAddressProvider = bridgeAddressProvider;
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

        // abort an in-flight tor start (e.g. for the api hidden service), so it cannot outlive the shutdown
        synchronized (torStartThreadLock) {
            if (torStartThread != null) torStartThread.interrupt();
        }

        shutDownTimeoutTimer = UserThread.runAfter(() -> {
            log.error("A timeout occurred at shutDown");
            isShutDownComplete = true;
            if (shutDownCompleteHandler != null) shutDownCompleteHandler.run();
            executor.shutdownNow();
        }, SHUT_DOWN_TIMEOUT_SEC);

        super.shutDown(() -> {
            try {
                Tor tor = Tor.getDefault();
                if (tor != null) {
                    Tor.setDefault(null);
                    tor.shutdown();
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
        Socks5Proxy proxy = getSocksProxy();
        if (proxy == null) throw new IOException("Tor socks proxy is not ready");

        // connect with the JDK socks5 client so the socks handshake honors a timeout, as netlayer's
        // TorSocket can block indefinitely (e.g. on requests left in-flight across OS standby)
        Socket socket = new Socket(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(proxy.getInetAddress(), proxy.getPort())));
        try {
            socket.connect(InetSocketAddress.createUnresolved(peerNodeAddress.getHostName(), peerNodeAddress.getPort()), CREATE_SOCKET_TIMEOUT);
            socket.setTcpNoDelay(true);
            return socket;
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
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

            // cache per tor instance, so a proxy of a restarted tor is never reused
            Tor tor = Tor.getDefault();
            if (socksProxy == null || socksProxyTor != tor || streamIsolation) {
                socksProxy = tor != null ? tor.getProxy(torControlHost, stream) : null;
                socksProxyTor = socksProxy == null ? null : tor;
            }
            return socksProxy;
        } catch (Throwable t) {
            log.error("Error at getSocksProxy", t);
            return null;
        }
    }

    @Override
    public String publishHiddenService(String name, int servicePort, int localPort) {
        synchronized (torStartLock) {
            try {
                Tor tor = getOrCreateTor();

                // republish on a new tor instance, otherwise reuse the published address
                if (tor != publishedHiddenServicesTor) {
                    publishedHiddenServices.clear();
                    publishedHiddenServicesTor = tor;
                }
                String hostname = publishedHiddenServices.get(name);
                if (hostname == null) {
                    hostname = tor.publishHiddenService(torMode.getHiddenServiceDirectory(name), servicePort, localPort, getHiddenServiceFlagsList(), getHiddenServiceParamsList()).getHostname();
                    if (isShutDownStarted) { // shutdown may have missed the service while it was publishing, so unpublish it
                        try {
                            tor.unpublishHiddenService(hostname);
                        } catch (Throwable e) { // TorCtlException extends Throwable, not Exception
                            log.warn("Error unpublishing hidden service " + name + " during shutdown", e);
                        }
                        throw new IllegalStateException("Cannot publish hidden service " + name + " because shutdown has started");
                    }
                    publishedHiddenServices.put(name, hostname);
                }
                return hostname;
            } catch (IOException | TorCtlException e) {
                // do not reuse a possibly dead instance, unless the p2p hidden service is established on it
                if (hiddenServiceSocket == null) restartTor("because publishing hidden service " + name + " failed");
                throw new RuntimeException("Could not publish hidden service " + name, e);
            }
        }
    }

    // start tor on demand, so hidden services can publish before the p2p service starts
    private Tor getOrCreateTor() throws IOException, TorCtlException {
        synchronized (torStartLock) {
            if (isShutDownStarted) throw new IllegalStateException("Cannot start tor because shutdown has started");
            Tor tor = Tor.getDefault();
            if (tor == null) {
                List<String> bridges = getConfiguredBridges();
                synchronized (torStartThreadLock) {
                    // re-check under the interrupt lock, so shutdown cannot miss both the flag and the registration
                    if (isShutDownStarted) throw new IllegalStateException("Cannot start tor because shutdown has started");
                    torStartThread = Thread.currentThread();
                }
                try {
                    tor = torMode.getTor();
                } finally {
                    synchronized (torStartThreadLock) {
                        torStartThread = null;
                        Thread.interrupted(); // consume a late shutdown interrupt, so it cannot leak to unrelated tasks
                    }
                }
                if (tor == null) throw new IOException("Could not connect to tor"); // e.g. RunningTor returns null when unreachable
                if (isShutDownStarted) { // shutdown started while tor was starting, so discard it
                    shutdownTorQuietly(tor);
                    throw new IllegalStateException("Cannot start tor because shutdown has started");
                }
                Tor.setDefault(tor); // install only after the shutdown check, so the default of a newer instance is never clobbered
                if (isShutDownStarted) { // shutdown started between the check and install, so it may have missed the new default
                    if (Tor.getDefault() == tor) Tor.setDefault(null);
                    shutdownTorQuietly(tor);
                    throw new IllegalStateException("Cannot start tor because shutdown has started");
                }
                torBootBridges = bridges;
            }
            return tor;
        }
    }

    // shut down the current tor so the next getOrCreateTor() starts a fresh instance
    private void restartTor(String reason) {
        synchronized (torStartLock) {
            if (isShutDownStarted) return; // shutdown owns the tor lifecycle, and the default may already belong to a newer instance
            Tor tor = Tor.getDefault();
            if (tor == null) return;
            log.info("Restarting tor {}", reason);
            Tor.setDefault(null);
            shutdownTorQuietly(tor);
            publishedHiddenServices.clear();
            publishedHiddenServicesTor = null;
            socksProxy = null; // belongs to the old tor instance
        }
    }

    private static void shutdownTorQuietly(Tor tor) {
        try {
            tor.shutdown();
        } catch (Throwable e) { // TorCtlException extends Throwable, not Exception
            log.warn("Error shutting down tor", e);
        }
    }

    // bridges configured at boot, or null if unavailable (e.g. before login) or not applicable
    private List<String> getConfiguredBridges() {
        if (!(torMode instanceof NewTor)) return null;
        Collection<String> bridges = bridgeAddressProvider.getBridgeAddresses();
        return bridges == null || bridges.isEmpty() ? null : new ArrayList<String>(bridges);
    }

    // use hidden service flags as given
    private List<String> getHiddenServiceFlagsList() {
        return hiddenServiceFlags == null || hiddenServiceFlags.isEmpty() ? null : Arrays.asList(hiddenServiceFlags.split(","));
    }

    private List<String> getHiddenServiceParamsList() {

        // set hidden service default parameter map
        Map<String, String> hiddenServiceParamsMap = new HashMap<String, String>();
        hiddenServiceParamsMap.put("PoWDefensesEnabled", POW_ENABLED_DEFAULT ? "1" : "0");
        hiddenServiceParamsMap.put("PoWQueueRate", String.valueOf(POW_QUEUE_RATE_DEFAULT));
        hiddenServiceParamsMap.put("PoWQueueBurst", String.valueOf(POW_QUEUE_BURST_DEFAULT));

        // override configured parameters
        if (hiddenServiceParams != null && !hiddenServiceParams.isEmpty()) {
            List<String> paramsList = Arrays.asList(hiddenServiceParams.split(","));
            for (String param : paramsList) {
                String[] keyValue = param.split("=");
                if (keyValue.length == 2) {
                    hiddenServiceParamsMap.put(keyValue[0], keyValue[1]);
                } else {
                    hiddenServiceParamsMap.put(keyValue[0], null);
                }
            }
        }

        // convert map to List<String> with format "key=value" or "key" if value is null
        return hiddenServiceParamsMap.isEmpty() ? null : hiddenServiceParamsMap.entrySet().stream()
                .map(entry -> entry.getValue() != null ? entry.getKey() + "=" + entry.getValue() : entry.getKey())
                .toList();
    }

    @Override
    protected void createTorAndHiddenService() {
        int localPort = Utils.findFreeSystemPort();
        executor.submit(() -> {
            try {
                List<String> hiddenServiceFlagsList = getHiddenServiceFlagsList();
                List<String> hiddenServiceParamsList = getHiddenServiceParamsList();

                long ts;
                synchronized (torStartLock) {

                    // restart tor if it started before bridges were configured, e.g. for the api hidden service
                    boolean reused = Tor.getDefault() != null;
                    if (reused && !Objects.equals(torBootBridges, getConfiguredBridges())) {
                        restartTor("to apply the configured bridges");
                        reused = false;
                    }

                    getOrCreateTor();
                    Socks5Proxy proxy = getSocksProxy();
                    if (proxy != null) log.info("Tor SOCKS proxy ready on {}:{} (auto-assigned, loopback only)", torControlHost, proxy.getPort());
                    ts = System.currentTimeMillis();
                    log.info("Starting tor hidden service with flags={}, params={}", hiddenServiceFlagsList, hiddenServiceParamsList);
                    try {
                        hiddenServiceSocket = new HiddenServiceSocket(localPort, torMode.getHiddenServiceDirectory(), servicePort, null, hiddenServiceFlagsList, hiddenServiceParamsList);
                    } catch (Throwable e) { // TorCtlException extends Throwable, not Exception
                        if (!reused) throw e;
                        log.warn("Could not create hidden service on the reused tor, restarting tor", e);
                        restartTor("because the reused instance failed");
                        getOrCreateTor();
                        // use a fresh local port in case the failure was a local bind conflict
                        hiddenServiceSocket = new HiddenServiceSocket(Utils.findFreeSystemPort(), torMode.getHiddenServiceDirectory(), servicePort, null, hiddenServiceFlagsList, hiddenServiceParamsList);
                    }
                }
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
            } catch (Throwable t) {

                // surface any other error, otherwise the bootstrapping thread dies silently
                boolean abortedByShutDown = isShutDownStarted || Thread.currentThread().isInterrupted() || t instanceof InterruptedException;
                if (t instanceof InterruptedException) Thread.currentThread().interrupt(); // preserve interrupt status
                if (abortedByShutDown) {
                    log.warn("Tor node startup was aborted because shut down has started");
                } else {
                    log.error("Starting tor node failed with unexpected error", t);
                    String errorMessage = t.getMessage() != null ? t.getMessage() : t.toString();
                    UserThread.execute(() -> setupListeners.forEach(s -> s.onSetupFailed(new RuntimeException(errorMessage))));
                }
            }
            return null;
        });
    }
}
