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

import com.google.common.collect.Lists;
import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;
import haveno.core.xmr.nodes.XmrNodes.XmrNode;
import org.bitcoinj.core.PeerAddress;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class XmrNodesRepositoryTest {
    @Test
    public void testGetPeerAddressesWhenClearNodes() {
        XmrNode node = mock(XmrNode.class);
        when(node.hasClearNetAddress()).thenReturn(true);

        XmrNodeConverter converter = mock(XmrNodeConverter.class, RETURNS_DEEP_STUBS);
        XmrNodesRepository repository = new XmrNodesRepository(converter,
                Collections.singletonList(node));

        List<PeerAddress> peers = repository.getPeerAddresses(null, false);

        assertFalse(peers.isEmpty());
    }

    @Test
    public void testGetPeerAddressesWhenConverterReturnsNull() {
        XmrNodeConverter converter = mock(XmrNodeConverter.class);
        when(converter.convertClearNode(any())).thenReturn(null);

        XmrNode node = mock(XmrNode.class);
        when(node.hasClearNetAddress()).thenReturn(true);

        XmrNodesRepository repository = new XmrNodesRepository(converter,
                Collections.singletonList(node));

        List<PeerAddress> peers = repository.getPeerAddresses(null, false);

        verify(converter).convertClearNode(any());
        assertTrue(peers.isEmpty());
    }

    @Test
    public void testGetPeerAddressesWhenProxyAndClearNodes() {
        XmrNode node = mock(XmrNode.class);
        when(node.hasClearNetAddress()).thenReturn(true);

        XmrNode onionNode = mock(XmrNode.class);
        when(node.hasOnionAddress()).thenReturn(true);

        XmrNodeConverter converter = mock(XmrNodeConverter.class, RETURNS_DEEP_STUBS);
        XmrNodesRepository repository = new XmrNodesRepository(converter,
                Lists.newArrayList(node, onionNode));

        List<PeerAddress> peers = repository.getPeerAddresses(mock(Socks5Proxy.class), true);

        assertEquals(2, peers.size());
    }

    @Test
    public void testGetPeerAddressesWhenOnionNodesOnly() {
        XmrNode node = mock(XmrNode.class);
        when(node.hasClearNetAddress()).thenReturn(true);

        XmrNode onionNode = mock(XmrNode.class);
        when(node.hasOnionAddress()).thenReturn(true);

        XmrNodeConverter converter = mock(XmrNodeConverter.class, RETURNS_DEEP_STUBS);
        XmrNodesRepository repository = new XmrNodesRepository(converter,
                Lists.newArrayList(node, onionNode));

        List<PeerAddress> peers = repository.getPeerAddresses(mock(Socks5Proxy.class), false);

        assertEquals(1, peers.size());
    }
}
