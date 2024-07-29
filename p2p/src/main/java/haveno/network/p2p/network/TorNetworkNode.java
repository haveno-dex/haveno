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

import haveno.common.proto.network.NetworkProtoResolver;
import haveno.common.util.SingleThreadExecutorUtils;

import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;

import java.net.Socket;

import java.io.IOException;

import java.util.concurrent.ExecutorService;

import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.Nullable;

@Slf4j
public abstract class TorNetworkNode extends NetworkNode {

    protected final ExecutorService executor;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public TorNetworkNode(String hiddenServiceAddress, int servicePort,
            NetworkProtoResolver networkProtoResolver,
            @Nullable BanFilter banFilter,
            int maxConnections) {
        super(servicePort, networkProtoResolver, banFilter, maxConnections);
        executor = SingleThreadExecutorUtils.getSingleThreadExecutor("StartTor");
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void start(@Nullable SetupListener setupListener) {
        if (setupListener != null)
            addSetupListener(setupListener);

        createTorAndHiddenService();
    }

    public void shutDown(@Nullable Runnable shutDownCompleteHandler) {
        super.shutDown(shutDownCompleteHandler);
    }

    public abstract Socks5Proxy getSocksProxy();

    protected abstract Socket createSocket(NodeAddress peerNodeAddress) throws IOException;

    protected abstract void createTorAndHiddenService();
}
