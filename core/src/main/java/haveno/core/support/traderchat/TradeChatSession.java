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

package haveno.core.support.traderchat;

import haveno.core.support.SupportSession;
import haveno.core.support.messages.ChatMessage;
import haveno.core.trade.Trade;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

@Slf4j
public class TradeChatSession extends SupportSession {

    @Nullable
    private Trade trade;

    public TradeChatSession(@Nullable Trade trade,
                            boolean isClient) {
        super(isClient);
        this.trade = trade;
    }

    @Override
    public String getTradeId() {
        return trade != null ? trade.getId() : "";
    }

    @Override
    public int getClientId() {
        // TODO remove that client-server concept for trade chat
        // Get pubKeyRing of taker. Maker is considered server for chat sessions
        try {
            return trade.getContract().getTakerPubKeyRing().hashCode();
        } catch (NullPointerException e) {
            log.warn("Unable to get takerPubKeyRing from Trade Contract - {}", e.toString());
        }
        return 0;
    }

    @Override
    public ObservableList<ChatMessage> getObservableChatMessageList() {
        return trade != null ? trade.getChatMessages() : FXCollections.observableArrayList();
    }

    @Override
    public boolean chatIsOpen() {
        return trade != null && trade.getState() != Trade.State.TRADE_COMPLETED;
    }

    @Override
    public boolean isDisputeAgent() {
        return false;
    }
}
