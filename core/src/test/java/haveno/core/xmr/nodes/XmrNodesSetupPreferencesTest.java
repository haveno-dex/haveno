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

import haveno.core.user.Preferences;
import haveno.core.xmr.nodes.XmrNodes.XmrNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static haveno.core.xmr.nodes.XmrNodes.MoneroNodesOption.CUSTOM;
import static haveno.core.xmr.nodes.XmrNodes.MoneroNodesOption.PUBLIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class XmrNodesSetupPreferencesTest {
    @Test
    public void testSelectPreferredNodesWhenPublicOption() {
        Preferences delegate = mock(Preferences.class);
        when(delegate.getMoneroNodesOptionOrdinal()).thenReturn(PUBLIC.ordinal());

        XmrNodesSetupPreferences preferences = new XmrNodesSetupPreferences(delegate);
        List<XmrNode> nodes = preferences.selectPreferredNodes(mock(XmrNodes.class));

        assertTrue(nodes.isEmpty());
    }

    @Test
    public void testSelectPreferredNodesWhenCustomOption() {
        Preferences delegate = mock(Preferences.class);
        when(delegate.getMoneroNodesOptionOrdinal()).thenReturn(CUSTOM.ordinal());
        when(delegate.getMoneroNodes()).thenReturn("aaa.onion,bbb.onion");

        XmrNodesSetupPreferences preferences = new XmrNodesSetupPreferences(delegate);
        List<XmrNode> nodes = preferences.selectPreferredNodes(mock(XmrNodes.class));

        assertEquals(2, nodes.size());
    }
}
