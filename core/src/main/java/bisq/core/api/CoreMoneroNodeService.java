/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */
package bisq.core.api;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.io.File;
import java.io.IOException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import monero.daemon.MoneroDaemon;
import monero.daemon.MoneroDaemonRpc;
import monero.daemon.model.MoneroNetworkType;

/**
 * Manages a Monero node instance or connection to an instance.
 */
@Slf4j
@Singleton
public class CoreMoneroNodeService {

    // Monero configuration
    // TODO: don't hard code configuration, inject into classes?
    private static final MoneroNetworkType MONERO_NETWORK_TYPE = MoneroNetworkType.STAGENET;
    private static final String MONEROD_PATH = System.getProperty("user.dir") + File.separator + ".localnet" + File.separator + "monerod";
    private static final String MONEROD_DATADIR =  System.getProperty("user.dir") + File.separator + ".localnet" + File.separator + MONERO_NETWORK_TYPE.toString().toLowerCase();
    private static final String MONEROD_P2PPORT = "58080";
    private static final String MONEROD_RPCPORT = "58081";
    private static final String MONEROD_ZMQPORT = "58082";

    private List<MoneroNodeServiceListener> listeners = new ArrayList<>();

    private static final List<String> DevArgs = Arrays.asList(
            MONEROD_PATH,
            "--" + MONERO_NETWORK_TYPE.toString().toLowerCase(),
            "--no-igd",
            "--hide-my-port",
            "--data-dir", MONEROD_DATADIR,
            "--p2p-bind-port", MONEROD_P2PPORT,
            "--rpc-bind-port", MONEROD_RPCPORT,
            "--zmq-rpc-bind-port", MONEROD_ZMQPORT
    );

    @Getter
    private MoneroDaemon daemon;

    @Inject
    public CoreMoneroNodeService() {
        this.daemon = null;
    }

    public void addListener(MoneroNodeServiceListener listener) {
        listeners.add(listener);
    }

    public boolean removeListener(MoneroNodeServiceListener listener) {
        return listeners.remove(listener);
    }

    public boolean isMoneroNodeStarted() {
        return daemon != null;
    }

    /**
     * Starts a local monero node. Throws MoneroError if the node cannot be started.
     */
    public void startMoneroNode(String rpcUsername, String rpcPassword) throws IOException {
        if (daemon != null) throw new IllegalStateException("Monero node already running");
        var args = new ArrayList<>(DevArgs);
        args.add("--rpc-login");
        args.add(rpcUsername + ":" + rpcPassword);
        daemon = new MoneroDaemonRpc(args);
        for (var listener : listeners) listener.onNodeStarted(daemon);
    }

    public void stopMoneroNode() {
        if (daemon == null) throw new IllegalStateException("Monero node is not running");
        daemon.stop();
        daemon = null;
        for (var listener : listeners) listener.onNodeStopped();
    }
}
