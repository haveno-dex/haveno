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

package bisq.core.trade.protocol;

import bisq.core.trade.messages.MakerReadyToFundMultisigResponse;

import bisq.network.p2p.NodeAddress;

import bisq.common.handlers.ErrorMessageHandler;

public interface TakerProtocol {
    void onTakeOffer();

    enum TakerEvent implements FluentProtocol.Event {
        TAKE_OFFER
    }

    // TODO (woodser): update after rebase
    //åvoid takeAvailableOffer(ResultHandler handler);
    void handleMakerReadyToFundMultisigResponse(MakerReadyToFundMultisigResponse response, NodeAddress peer, ErrorMessageHandler errorMessageHandler);
}