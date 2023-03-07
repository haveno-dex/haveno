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

package haveno.network.p2p.storage.payload;

import haveno.common.app.Capabilities;
import haveno.common.proto.network.NetworkPayload;

/**
 * Used for payloads which requires certain capability.
 * <p/>
 * This is used for TradeStatistics to be able to support old versions which don't know about that class.
 * We only send the data to nodes which are capable to handle that data (e.g. TradeStatistics supported from v. 0.4.9.1 on).
 */
public interface CapabilityRequiringPayload extends NetworkPayload {
    /**
     * @return Capabilities the other node need to support to receive that message
     */
    Capabilities getRequiredCapabilities();
}
