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

package haveno.core.trade.protocol;

import haveno.common.handlers.ErrorMessageHandler;
import haveno.core.trade.handlers.TradeResultHandler;
import haveno.core.trade.messages.InitTradeRequest;
import haveno.network.p2p.NodeAddress;

public interface TakerProtocol extends TraderProtocol {
    void onTakeOffer(TradeResultHandler tradeResultHandler, ErrorMessageHandler errorMessageHandler);
    void handleInitTradeRequest(InitTradeRequest message, NodeAddress peer);

    enum TakerEvent implements FluentProtocol.Event {
        TAKE_OFFER
    }
}