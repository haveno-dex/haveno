package haveno.network.p2p.network;

import haveno.common.util.Hex;
import haveno.network.p2p.NodeAddress;

import haveno.common.UserThread;
import haveno.common.proto.network.NetworkProtoResolver;

import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;

import java.net.Socket;
import java.net.InetAddress;
import java.net.ServerSocket;

import java.io.IOException;

import java.nio.charset.StandardCharsets;

import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.Nullable;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
public class TorNetworkNodeDirectBind extends TorNetworkNode {

    private static final int TOR_DATA_PORT = 9050;  // TODO: config option?
    private final String serviceAddress;

    public TorNetworkNodeDirectBind(int servicePort,
                                    NetworkProtoResolver networkProtoResolver,
                                    @Nullable BanFilter banFilter,
                                    int maxConnections,
                                    String hiddenServiceAddress) {
        super(servicePort, networkProtoResolver, banFilter, maxConnections);
        this.serviceAddress = hiddenServiceAddress;
    }

    @Override
    public void shutDown(@Nullable Runnable shutDownCompleteHandler) {
        super.shutDown(() -> {
            log.info("TorNetworkNodeDirectBind shutdown completed");
            if (shutDownCompleteHandler != null) shutDownCompleteHandler.run();
        });
    }

    @Override
    public Socks5Proxy getSocksProxy() {
        Socks5Proxy proxy = new Socks5Proxy(InetAddress.getLoopbackAddress(), TOR_DATA_PORT);
        proxy.resolveAddrLocally(false);
        return proxy;
    }

    @Override
    protected Socket createSocket(NodeAddress peerNodeAddress) throws IOException {
        // https://datatracker.ietf.org/doc/html/rfc1928 SOCKS5 Protocol
        try {
            checkArgument(peerNodeAddress.getHostName().endsWith(".onion"), "PeerAddress is not an onion address");
            Socket sock = new Socket(InetAddress.getLoopbackAddress(), TOR_DATA_PORT);
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
            } catch (IOException e) {
                log.error("Could not connect to external Tor", e);
                UserThread.execute(() -> setupListeners.forEach(s -> s.onSetupFailed(new RuntimeException(e.getMessage()))));
            }
            return null;
        });
    }
}
