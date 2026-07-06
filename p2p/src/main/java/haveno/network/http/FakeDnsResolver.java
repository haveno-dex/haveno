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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import org.apache.hc.client5.http.DnsResolver;

// This class is adapted from
//   http://stackoverflow.com/a/25203021/5616248
//
// HttpClient 5.5+ routes connections via DnsResolver.resolve(host, port). The default
// implementation wraps resolve(host) into resolved InetSocketAddress instances. For Tor
// SOCKS we must return unresolved addresses so the proxy performs remote hostname lookup
// (.onion services cannot be resolved locally).
class FakeDnsResolver implements DnsResolver {
    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        return null;
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return host;
    }

    @Override
    public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
        return Collections.singletonList(InetSocketAddress.createUnresolved(host, port));
    }
}
