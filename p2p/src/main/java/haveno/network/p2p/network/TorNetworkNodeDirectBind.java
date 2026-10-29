package haveno.network.p2p.network;

import haveno.common.util.Hex;
import haveno.network.p2p.NodeAddress;

import haveno.common.UserThread;
import haveno.common.proto.network.NetworkProtoResolver;

import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;

import org.berndpruenster.netlayer.tor.Tor;
import org.berndpruenster.netlayer.tor.TorCtlException;

import java.net.Socket;
import java.net.ServerSocket;

import java.io.IOException;

import java.nio.charset.StandardCharsets;

import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.Nullable;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
public class TorNetworkNodeDirectBind extends TorNetworkNode {

    private static final int TOR_DATA_PORT = 9050;  // default SocksPort, used without a control port
    private final String serviceAddress;
    private final String torHost;
    @Nullable private final RunningTor runningTor;
    private volatile Socks5Proxy socksProxy;

    public TorNetworkNodeDirectBind(int servicePort,
                                    NetworkProtoResolver networkProtoResolver,
                                    @Nullable BanFilter banFilter,
                                    int maxConnections,
                                    String hiddenServiceAddress,
                                    String torHost,
                                    @Nullable RunningTor runningTor) {
        super(servicePort, networkProtoResolver, banFilter, maxConnections);
        this.serviceAddress = hiddenServiceAddress;
        this.torHost = torHost;
        this.runningTor = runningTor;
    }

    @Override
    public void shutDown(@Nullable Runnable shutDownCompleteHandler) {
        super.shutDown(() -> {
            log.info("TorNetworkNodeDirectBind shutdown completed");
            if (shutDownCompleteHandler != null) shutDownCompleteHandler.run();
        });
        executor.shutdownNow(); // abort a pending SOCKS port lookup
    }

    // null until the SOCKS port is resolved at startup
    @Override
    public Socks5Proxy getSocksProxy() {
        return socksProxy;
    }

    @Override
    protected Socket createSocket(NodeAddress peerNodeAddress) throws IOException {
        // https://datatracker.ietf.org/doc/html/rfc1928 SOCKS5 Protocol
        try {
            checkArgument(peerNodeAddress.getHostName().endsWith(".onion"), "PeerAddress is not an onion address");
            Socks5Proxy proxy = socksProxy;
            if (proxy == null) throw new IOException("Tor SOCKS proxy is not available");
            Socket sock = new Socket(proxy.getInetAddress(), proxy.getPort());
            sock.setSoTimeout(CREATE_SOCKET_TIMEOUT); // bound the handshake reads; Connection sets its own timeout later
            sock.getOutputStream().write(Hex.decode("050100"));
            String response = Hex.encode(sock.getInputStream().readNBytes(2));
            if (!response.equalsIgnoreCase("0500")) {
                return null;
            }
            String connect_details = "050100033E" + Hex.encode(peerNodeAddress.getHostName().getBytes(StandardCharsets.UTF_8));
            StringBuilder connect_port = new StringBuilder(Integer.toHexString(peerNodeAddress.getPort()));
            while (connect_port.length() < 4) connect_port.insert(0, "0");
            connect_details = connect_details + connect_port;
            sock.getOutputStream().write(Hex.decode(connect_details));
            response = Hex.encode(sock.getInputStream().readNBytes(10));
            if (response.substring(0, 2).equalsIgnoreCase("05") && response.substring(2, 4).equalsIgnoreCase("00")) {
                return sock;    // success
            }
            if (response.substring(2, 4).equalsIgnoreCase("04")) {
                log.warn("Host unreachable: {}", peerNodeAddress);
            } else {
                log.warn("SOCKS error code received {} expected 00", response.substring(2, 4));
            }
            if (!response.substring(0, 2).equalsIgnoreCase("05")) {
                log.warn("unexpected response, this isn't a SOCKS5 proxy?: {} {}", response, response.substring(0, 2));
            }
        } catch (Exception e) {
            log.warn(e.toString());
        }
        throw new IOException("createSocket failed");
    }

    @Override
    protected void createTorAndHiddenService() {
        executor.submit(() -> {
            try {
                socksProxy = resolveSocksProxy();
                if (isShutDownStarted()) return null;
                log.info("Using Tor SOCKS proxy at {}:{}", torHost, socksProxy.getPort());

                // listener for incoming messages at the hidden service
                ServerSocket socket = new ServerSocket(servicePort);
                nodeAddressProperty.set(new NodeAddress(serviceAddress + ":" + servicePort));
                log.info("\n################################################################\n" +
                                "Bound to Tor hidden service: {} Port: {}\n" +
                                "################################################################",
                        serviceAddress, servicePort);
                UserThread.execute(() -> setupListeners.forEach(SetupListener::onTorNodeReady));
                UserThread.runAfter(() -> {
                    nodeAddressProperty.set(new NodeAddress(serviceAddress + ":" + servicePort));
                    startServer(socket);
                    setupListeners.forEach(SetupListener::onHiddenServicePublished);
                }, 3);
                return null;
            } catch (IOException | TorCtlException e) {
                if (isShutDownStarted()) return null;
                log.error("Could not initialize external Tor", e);
                String errorMessage = e.getMessage() != null ? e.getMessage() : "Could not initialize external Tor; see logs for details";
                UserThread.execute(() -> setupListeners.forEach(s -> s.onSetupFailed(new RuntimeException(errorMessage, e))));
            }
            return null;
        });
    }

    // a running tor reports its SocksPort over the control port, otherwise the default port is assumed
    private Socks5Proxy resolveSocksProxy() throws IOException, TorCtlException {
        if (runningTor == null) return Tor.getProxy(torHost, TOR_DATA_PORT);
        Tor tor = runningTor.getTor();
        if (tor == null) throw new IOException("Could not initialize Tor through the control port; see logs for details");
        return tor.getProxy(torHost);
    }
}
