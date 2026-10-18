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
import com.runjva.sourceforge.jsocks.protocol.SocksException;
import com.runjva.sourceforge.jsocks.protocol.SocksSocket;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.TimeValue;

// Uses jsocks' own SOCKS5 handshake (not JDK's Proxy.Type.SOCKS) so per-connection credentials registered
// on the proxy (e.g. Tor's --torStreamIsolation) are honored.
final class Socks5ProxySockets {

    private Socks5ProxySockets() {
    }

    static Socket connect(Socks5Proxy socks5Proxy, HttpHost host, int port, TimeValue connectTimeout) throws IOException {
        try {
            SocksSocket socket = new SocksSocket(socks5Proxy, host.getHostName(), port);
            if (connectTimeout != null) {
                socket.setSoTimeout((int) connectTimeout.toMilliseconds());
            }
            return socket;
        } catch (SocksException | UnknownHostException e) {
            throw new IOException("SOCKS5 connect to " + host + " failed", e);
        }
    }

    static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
