/*
 * This file is part of Haveno.
 *
 * haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.network.p2p.seed;

import haveno.network.p2p.NodeAddress;

import java.util.Collection;

public interface SeedNodeRepository {

    boolean isSeedNode(NodeAddress nodeAddress);

    Collection<NodeAddress> getSeedNodeAddresses();
}
