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

package haveno.network;

import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;
import org.junit.jupiter.api.Test;

import java.net.Inet6Address;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Socks5ProxyProviderTest {

    @Test
    public void testParsesIpv4ProxyAddress() {
        Socks5Proxy proxy = new Socks5ProxyProvider("127.0.0.1:9050", "").getSocks5ProxyXmr();

        // noinspection ConstantConditions
        assertEquals("127.0.0.1", proxy.getInetAddress().getHostAddress());
        assertEquals(9050, proxy.getPort());
    }

    @Test
    public void testParsesBracketedIpv6ProxyAddress() {
        Socks5Proxy proxy = new Socks5ProxyProvider("[::1]:9050", "").getSocks5ProxyXmr();

        // noinspection ConstantConditions
        assertTrue(proxy.getInetAddress() instanceof Inet6Address);
        assertEquals(9050, proxy.getPort());
    }

    @Test
    public void testRejectsProxyAddressWithoutPort() {
        assertNull(new Socks5ProxyProvider("127.0.0.1", "").getSocks5ProxyXmr());
    }

    @Test
    public void testRejectsBracketedNonIpv6ProxyAddress() {
        assertNull(new Socks5ProxyProvider("[localhost]:9050", "").getSocks5ProxyXmr());
    }
}
