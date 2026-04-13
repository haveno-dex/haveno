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
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import javax.annotation.Nullable;
import org.apache.commons.lang3.exception.ExceptionUtils;
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
            try {
                HostAndPort hostAndPort = parseHostAndPort(socks5ProxyAddress);
                Socks5Proxy proxy = new Socks5Proxy(hostAndPort.host, hostAndPort.port);
                proxy.resolveAddrLocally(false);
                return proxy;
            } catch (IllegalArgumentException e) {
                log.error("Incorrect format for socks5ProxyAddress. Should be: host:port or [ipv6]:port.\n" +
                        "socks5ProxyAddress=" + socks5ProxyAddress, e);
            } catch (UnknownHostException e) {
                log.error(ExceptionUtils.getStackTrace(e));
            }
        }
        return null;
    }

    private static HostAndPort parseHostAndPort(String socks5ProxyAddress) {
        String trimmedAddress = socks5ProxyAddress.trim();
        if (trimmedAddress.startsWith("[")) {
            int closingBracketIndex = trimmedAddress.indexOf("]");
            if (closingBracketIndex <= 0) throw new IllegalArgumentException("Invalid bracketed IPv6 address");
            String host = trimmedAddress.substring(1, closingBracketIndex);
            if (!isIpv6Literal(host)) throw new IllegalArgumentException("Invalid bracketed IPv6 address");
            String remainder = trimmedAddress.substring(closingBracketIndex + 1);
            if (!remainder.startsWith(":") || remainder.length() == 1) throw new IllegalArgumentException("Missing port");
            return new HostAndPort(host, parsePort(remainder.substring(1)));
        }

        int lastColonIndex = trimmedAddress.lastIndexOf(":");
        if (lastColonIndex <= 0 || lastColonIndex == trimmedAddress.length() - 1) throw new IllegalArgumentException("Missing port");

        String host = trimmedAddress.substring(0, lastColonIndex);
        String port = trimmedAddress.substring(lastColonIndex + 1);
        if (host.contains(":") && !(isIpv6Literal(host))) throw new IllegalArgumentException("Invalid IPv6 address");
        return new HostAndPort(host, parsePort(port));
    }

    private static int parsePort(String portString) {
        try {
            int port = Integer.parseInt(portString);
            if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port: " + portString);
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port: " + portString, e);
        }
    }

    private static boolean isIpv6Literal(String host) {
        try {
            return host.contains(":") && InetAddress.getByName(host) instanceof Inet6Address;
        } catch (Exception e) {
            return false;
        }
    }

    private static class HostAndPort {
        private final String host;
        private final int port;

        private HostAndPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
