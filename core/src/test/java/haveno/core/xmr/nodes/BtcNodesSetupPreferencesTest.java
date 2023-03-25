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
import haveno.core.xmr.nodes.BtcNodes.BtcNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static haveno.core.xmr.nodes.BtcNodes.BitcoinNodesOption.CUSTOM;
import static haveno.core.xmr.nodes.BtcNodes.BitcoinNodesOption.PUBLIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BtcNodesSetupPreferencesTest {
    @Test
    public void testSelectPreferredNodesWhenPublicOption() {
        Preferences delegate = mock(Preferences.class);
        when(delegate.getBitcoinNodesOptionOrdinal()).thenReturn(PUBLIC.ordinal());

        BtcNodesSetupPreferences preferences = new BtcNodesSetupPreferences(delegate);
        List<BtcNode> nodes = preferences.selectPreferredNodes(mock(BtcNodes.class));

        assertTrue(nodes.isEmpty());
    }

    @Test
    public void testSelectPreferredNodesWhenCustomOption() {
        Preferences delegate = mock(Preferences.class);
        when(delegate.getBitcoinNodesOptionOrdinal()).thenReturn(CUSTOM.ordinal());
        when(delegate.getBitcoinNodes()).thenReturn("aaa.onion,bbb.onion");

        BtcNodesSetupPreferences preferences = new BtcNodesSetupPreferences(delegate);
        List<BtcNode> nodes = preferences.selectPreferredNodes(mock(BtcNodes.class));

        assertEquals(2, nodes.size());
    }
}
