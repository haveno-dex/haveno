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

package haveno.network;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;
import haveno.common.config.Config;
import haveno.network.p2p.network.NetworkNode;
import java.net.UnknownHostException;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides Socks5Proxies for the monero network and http requests
 * <p/>
 * By default there is only used the haveno internal Tor proxy, which is used for the P2P network, xmr network
 * (if Tor for xmr is enabled) and http requests (if Tor for http requests is enabled).
 * If the user provides a socks5ProxyHttpAddress it will be used for http requests.
 * If the user provides a socks5ProxyXmrAddress, this will be used for the xmr network.
 * If socks5ProxyXmrAddress is present but no socks5ProxyHttpAddress the socks5ProxyXmrAddress will be used for http
 * requests.
 * If no socks5ProxyXmrAddress and no socks5ProxyHttpAddress is defined (default) we use socks5ProxyInternal.
 */
public class Socks5ProxyProvider {
    private static final Logger log = LoggerFactory.getLogger(Socks5ProxyProvider.class);

    @Nullable
    private NetworkNode socks5ProxyInternalFactory;

    // proxy used for btc network
    @Nullable
    private final Socks5Proxy socks5ProxyXmr;

    // if defined proxy used for http requests
    @Nullable
    private final Socks5Proxy socks5ProxyHttp;

    @Inject
    public Socks5ProxyProvider(@Named(Config.SOCKS_5_PROXY_XMR_ADDRESS) String socks5ProxyXmrAddress,
                               @Named(Config.SOCKS_5_PROXY_HTTP_ADDRESS) String socks5ProxyHttpAddress) {
        socks5ProxyXmr = getProxyFromAddress(socks5ProxyXmrAddress);
        socks5ProxyHttp = getProxyFromAddress(socks5ProxyHttpAddress);
    }

    @Nullable
    public Socks5Proxy getSocks5Proxy() {
        if (socks5ProxyXmr != null)
            return socks5ProxyXmr;
        else if (socks5ProxyInternalFactory != null)
            return getSocks5ProxyInternal();
        else
            return null;
    }

    @Nullable
    public Socks5Proxy getSocks5ProxyXmr() {
        return socks5ProxyXmr;
    }

    @Nullable
    public Socks5Proxy getSocks5ProxyHttp() {
        return socks5ProxyHttp;
    }

    @Nullable
    public Socks5Proxy getSocks5ProxyInternal() {
        return socks5ProxyInternalFactory.getSocksProxy();
    }

    public void setSocks5ProxyInternal(@Nullable NetworkNode havenoSocks5ProxyFactory) {
        this.socks5ProxyInternalFactory = havenoSocks5ProxyFactory;
    }

    @Nullable
    private Socks5Proxy getProxyFromAddress(String socks5ProxyAddress) {
        if (!socks5ProxyAddress.isEmpty()) {
            String[] tokens = socks5ProxyAddress.split(":");
            if (tokens.length == 2) {
                try {
                    return new Socks5Proxy(tokens[0], Integer.valueOf(tokens[1]));
                } catch (UnknownHostException e) {
                    log.error(e.getMessage());
                    e.printStackTrace();
                }
            } else {
                log.error("Incorrect format for socks5ProxyAddress. Should be: host:port.\n" +
                        "socks5ProxyAddress=" + socks5ProxyAddress);
            }
        }
        return null;
    }
}
