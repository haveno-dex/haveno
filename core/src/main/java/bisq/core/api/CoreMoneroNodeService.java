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

import bisq.core.user.Preferences;
import bisq.core.xmr.MoneroNodeSettings;

import bisq.common.config.Config;

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

/**
 * Manages a Monero node instance or connection to an instance.
 */
@Slf4j
@Singleton
public class CoreMoneroNodeService {

    private static final String MONERO_NETWORK_TYPE = Config.baseCurrencyNetwork().getNetwork().toLowerCase();
    private static final String MONEROD_PATH = System.getProperty("user.dir") + File.separator + ".localnet" + File.separator + "monerod";
    private static final String MONEROD_DATADIR =  System.getProperty("user.dir") + File.separator + ".localnet" + File.separator + MONERO_NETWORK_TYPE;

    private final Preferences preferences;
    private final List<MoneroNodeServiceListener> listeners = new ArrayList<>();

    private static final List<String> DevArgs = Arrays.asList(
            MONEROD_PATH,
            "--" + MONERO_NETWORK_TYPE,
            "--no-igd",
            "--hide-my-port"
    );

    @Getter
    private MoneroDaemon daemon;

    @Inject
    public CoreMoneroNodeService(Preferences preferences) {
        this.daemon = null;
        this.preferences = preferences;
    }

    public void addListener(MoneroNodeServiceListener listener) {
        listeners.add(listener);
    }

    public boolean removeListener(MoneroNodeServiceListener listener) {
        return listeners.remove(listener);
    }

    /**
     * Returns whether the local monero node is running or an external monero node is running
     */
    public boolean isMoneroNodeStarted() {
        // todo: check if node is running at 18081, 28081, or 38081 for mainnet, testnet, and stagenet respectively
        return daemon != null;
    }

    public MoneroNodeSettings getMoneroNodeSettings() {
        return preferences.getMoneroNodeSettings();
    }

    /**
     * Starts a local monero node. Throws MoneroError if the node cannot be started.
     * Persists the settings to preferences if the node started successfully.
     */
    public void startMoneroNode(MoneroNodeSettings settings) throws IOException {
        if (daemon != null) throw new IllegalStateException("Monero node already running");

        var args = new ArrayList<>(DevArgs);

        var dataDir = settings.getBlockchainPath();
        if (dataDir == null || dataDir.isEmpty()) {
            dataDir = MONEROD_DATADIR;
        }
        args.add("--data-dir=" + dataDir);

        var bootstrapUrl = settings.getBootstrapUrl();
        if (bootstrapUrl != null && !bootstrapUrl.isEmpty()) {
            args.add("--bootstrap-daemon-address=" + bootstrapUrl);
        }

        var flags = settings.getStartupFlags();
        if (flags != null) {
            args.addAll(flags);
        }

        daemon = new MoneroDaemonRpc(args);
        preferences.setMoneroNodeSettings(settings);
        for (var listener : listeners) listener.onNodeStarted(daemon);
    }

    /**
     * Stops the current local monero node if the node was started by this process.
     * Does not remove the last MoneroNodeSettings.
     */
    public void stopMoneroNode() {
        if (daemon == null) throw new IllegalStateException("Monero node is not running");
        daemon.stop();
        daemon = null;
        for (var listener : listeners) listener.onNodeStopped();
    }
}
