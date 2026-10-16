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

package haveno.network.http;

import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.apache.hc.client5.http.socket.PlainConnectionSocketFactory;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;

// Routes plain HTTP connections through an authenticated SOCKS5 tunnel. See Socks5ProxySockets.
class SocksAuthenticatedConnectionSocketFactory extends PlainConnectionSocketFactory {
    private final Socks5Proxy socks5Proxy;

    SocksAuthenticatedConnectionSocketFactory(Socks5Proxy socks5Proxy) {
        this.socks5Proxy = socks5Proxy;
    }

    @Override
    public Socket createSocket(final HttpContext context) {
        return new Socket();
    }

    @Override
    public Socket connectSocket(
            final TimeValue connectTimeout,
            final Socket socket,
            final HttpHost host,
            final InetSocketAddress remoteAddress,
            final InetSocketAddress localAddress,
            final HttpContext context) throws IOException {
        Socks5ProxySockets.closeQuietly(socket);
        return Socks5ProxySockets.connect(socks5Proxy, host, remoteAddress.getPort(), connectTimeout);
    }
}
