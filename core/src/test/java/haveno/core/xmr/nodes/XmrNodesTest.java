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

import haveno.core.trade.HavenoUtils;
import haveno.core.xmr.nodes.XmrNodes.XmrNode;
import monero.common.MoneroRpcConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class XmrNodesTest {

    @Test
    public void testFromFullAddressParsesHostNameWithPort() {
        XmrNode node = XmrNode.fromFullAddress("feder8.me:18089");

        assertEquals("feder8.me", node.getHostNameOrAddress());
        assertEquals(18089, node.getPort());
        assertEquals("feder8.me:18089", node.getHostNameOrAddressWithPort());
        assertEquals("http://feder8.me:18089", node.getClearNetUri());
    }

    @Test
    public void testFromFullAddressParsesBracketedIpv6WithPort() {
        XmrNode node = XmrNode.fromFullAddress("[2607:3c40:1900:33e0::1]:18089");

        assertEquals("2607:3c40:1900:33e0::1", node.getHostNameOrAddress());
        assertEquals(18089, node.getPort());
        assertEquals("[2607:3c40:1900:33e0::1]:18089", node.getHostNameOrAddressWithPort());
        assertEquals("http://[2607:3c40:1900:33e0::1]:18089", node.getClearNetUri());
        assertEquals("http://[2607:3c40:1900:33e0::1]:18089", new MoneroRpcConnection(node.getClearNetUri()).getUri());
    }

    @Test
    public void testFromFullAddressParsesUnbracketedIpv6WithMoneroPort() {
        XmrNode node = XmrNode.fromFullAddress("2a0b:f4c2:2::63:18081");

        assertEquals("2a0b:f4c2:2::63", node.getHostNameOrAddress());
        assertEquals(18081, node.getPort());
        assertEquals("[2a0b:f4c2:2::63]:18081", node.getHostNameOrAddressWithPort());
        assertEquals("http://[2a0b:f4c2:2::63]:18081", node.getClearNetUri());
        assertEquals("http://[2a0b:f4c2:2::63]:18081", new MoneroRpcConnection(node.getClearNetUri()).getUri());
    }

    @Test
    public void testFromFullAddressParsesUnbracketedIpv6WithoutPort() {
        XmrNode node = XmrNode.fromFullAddress("2607:3c40:1900:33e0::1");

        assertEquals("2607:3c40:1900:33e0::1", node.getHostNameOrAddress());
        assertEquals(HavenoUtils.getDefaultMoneroPort(), node.getPort());
        assertEquals("[2607:3c40:1900:33e0::1]:" + HavenoUtils.getDefaultMoneroPort(), node.getHostNameOrAddressWithPort());
        assertEquals("http://[2607:3c40:1900:33e0::1]:" + HavenoUtils.getDefaultMoneroPort(), node.getClearNetUri());
    }

    @Test
    public void testFromFullAddressParsesLocalhostWithPort() {
        XmrNode node = XmrNode.fromFullAddress("localhost:18081");

        assertEquals("localhost", node.getHostNameOrAddress());
        assertEquals(18081, node.getPort());
        assertEquals("localhost:18081", node.getHostNameOrAddressWithPort());
        assertEquals("http://localhost:18081", node.getClearNetUri());
    }

    @Test
    public void testFromFullAddressParsesOnionWithPort() {
        XmrNode node = XmrNode.fromFullAddress("wizseedscybbttk4bmb2lzvbuk2jtect37lcpva4l3twktmkzemwbead.onion:18089");

        assertEquals("wizseedscybbttk4bmb2lzvbuk2jtect37lcpva4l3twktmkzemwbead.onion", node.getOnionAddress());
        assertEquals(18089, node.getPort());
        assertEquals("wizseedscybbttk4bmb2lzvbuk2jtect37lcpva4l3twktmkzemwbead.onion:18089", node.getOnionAddressWithPort());
    }

    @Test
    public void testFromFullAddressRejectsInvalidIpv6Port() {
        assertThrows(IllegalArgumentException.class, () -> XmrNode.fromFullAddress("[2607:3c40:1900:33e0::1]:65536"));
    }
}
