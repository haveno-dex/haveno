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
import haveno.core.xmr.nodes.XmrNodeConverter.Facade;
import haveno.core.xmr.nodes.XmrNodes.XmrNode;
import haveno.network.DnsLookupException;
import org.bitcoinj.core.PeerAddress;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class XmrNodeConverterTest {
    @Test
    public void testConvertOnionHost() {
        XmrNode node = mock(XmrNode.class);
        when(node.getOnionAddress()).thenReturn("aaa.onion");

        //InetAddress inetAddress = mock(InetAddress.class);

        Facade facade = mock(Facade.class);
        //when(facade.onionHostToInetAddress(any())).thenReturn(inetAddress);

        PeerAddress peerAddress = new XmrNodeConverter(facade).convertOnionHost(node);
        // noinspection ConstantConditions
        assertEquals(node.getOnionAddress(), peerAddress.getHostname());
    }

    @Test
    public void testConvertClearNode() {
        final String ip = "192.168.0.1";

        XmrNode node = mock(XmrNode.class);
        when(node.getHostNameOrAddress()).thenReturn(ip);

        PeerAddress peerAddress = new XmrNodeConverter().convertClearNode(node);
        // noinspection ConstantConditions
        InetAddress inetAddress = peerAddress.getAddr();
        assertEquals(ip, inetAddress.getHostAddress());
    }

    @Test
    public void testConvertWithTor() throws DnsLookupException {
        InetAddress expected = mock(InetAddress.class);

        Facade facade = mock(Facade.class);
        when(facade.torLookup(any(), anyString())).thenReturn(expected);

        XmrNode node = mock(XmrNode.class);
        when(node.getHostNameOrAddress()).thenReturn("aaa.onion");

        PeerAddress peerAddress = new XmrNodeConverter(facade).convertWithTor(node, mock(Socks5Proxy.class));

        // noinspection ConstantConditions
        assertEquals(expected, peerAddress.getAddr());
    }
}
