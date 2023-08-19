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

package haveno.core.xmr.nodes;

import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;
import haveno.common.config.Config;
import haveno.core.xmr.setup.WalletConfig;
import haveno.network.Socks5MultiDiscovery;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.params.MainNetParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.List;

public class XmrNetworkConfig {
    private static final Logger log = LoggerFactory.getLogger(XmrNetworkConfig.class);

    @Nullable
    private final Socks5Proxy proxy;
    private final WalletConfig delegate;
    private final NetworkParameters parameters;
    private final int socks5DiscoverMode;

    public XmrNetworkConfig(WalletConfig delegate, NetworkParameters parameters, int socks5DiscoverMode,
                            @Nullable Socks5Proxy proxy) {
        this.delegate = delegate;
        this.parameters = parameters;
        this.socks5DiscoverMode = socks5DiscoverMode;
        this.proxy = proxy;
    }

    public void proposePeers(List<PeerAddress> peers) {
        if (!peers.isEmpty()) {
            log.info("You connect with peerAddresses: {}", peers);
            PeerAddress[] peerAddresses = peers.toArray(new PeerAddress[peers.size()]);
            delegate.setPeerNodes(peerAddresses);
        } else if (proxy != null) {
            if (log.isWarnEnabled()) {
                MainNetParams mainNetParams = MainNetParams.get();
                if (parameters.equals(mainNetParams)) {
                    log.warn("You use the public Bitcoin network and are exposed to privacy issues " +
                            "caused by the broken bloom filters. See https://haveno.exchange/blog/privacy-in-bitsquare/ " +
                            "for more info. It is recommended to use the provided nodes.");
                }
            }
            // SeedPeers uses hard coded stable addresses (from MainNetParams). It should be updated from time to time.
            delegate.setDiscovery(new Socks5MultiDiscovery(proxy, parameters, socks5DiscoverMode));
        } else if (Config.baseCurrencyNetwork().isMainnet()) {
            log.warn("You don't use tor and use the public Bitcoin network and are exposed to privacy issues " +
                    "caused by the broken bloom filters. See https://haveno.exchange/blog/privacy-in-bitsquare/ " +
                    "for more info. It is recommended to use Tor and the provided nodes.");
        }
    }
}
