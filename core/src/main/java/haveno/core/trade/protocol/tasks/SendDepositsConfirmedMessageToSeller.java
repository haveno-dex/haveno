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

package haveno.core.trade.protocol.tasks;

import haveno.common.crypto.PubKeyRing;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.trade.Trade;
import haveno.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

/**
 * Send message on first confirmation to decrypt peer payment account and update multisig hex.
 */
@Slf4j
public class SendDepositsConfirmedMessageToSeller extends SendDepositsConfirmedMessage {

    public SendDepositsConfirmedMessageToSeller(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    public NodeAddress getReceiverNodeAddress() {
        return trade.getSeller().getNodeAddress();
    }

    @Override
    public PubKeyRing getReceiverPubKeyRing() {
        return trade.getSeller().getPubKeyRing();
    }
}
