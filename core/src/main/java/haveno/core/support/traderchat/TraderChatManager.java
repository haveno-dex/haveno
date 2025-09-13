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

package haveno.core.support.traderchat;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.common.crypto.PubKeyRing;
import haveno.common.crypto.PubKeyRingProvider;
import haveno.core.api.CoreNotificationService;
import haveno.core.api.XmrConnectionService;
import haveno.core.locale.Res;
import haveno.core.support.SupportManager;
import haveno.core.support.SupportType;
import haveno.core.support.messages.ChatMessage;
import haveno.core.support.messages.SupportMessage;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.AckMessageSourceType;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import java.util.List;
import java.util.Optional;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class TraderChatManager extends SupportManager {
    private final PubKeyRingProvider pubKeyRingProvider;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public TraderChatManager(P2PService p2PService,
                             XmrConnectionService xmrConnectionService,
                             XmrWalletService xmrWalletService,
                             CoreNotificationService notificationService,
                             TradeManager tradeManager,
                             PubKeyRingProvider pubKeyRingProvider) {
        super(p2PService, xmrConnectionService, xmrWalletService, notificationService, tradeManager);
        this.pubKeyRingProvider = pubKeyRingProvider;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Implement template methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public SupportType getSupportType() {
        return SupportType.TRADE;
    }

    @Override
    public void requestPersistence() {
        tradeManager.requestPersistence();
    }

    @Override
    public NodeAddress getPeerNodeAddress(ChatMessage message) {
        return tradeManager.getOpenTrade(message.getTradeId()).map(trade -> {
            if (trade.getContract() != null) {
                return trade.getContract().getPeersNodeAddress(pubKeyRingProvider.get());
            } else {
                return null;
            }
        }).orElse(null);
    }

    @Override
    public PubKeyRing getPeerPubKeyRing(ChatMessage message) {
        return tradeManager.getOpenTrade(message.getTradeId()).map(trade -> {
            if (trade.getContract() != null) {
                return trade.getContract().getPeersPubKeyRing(pubKeyRingProvider.get());
            } else {
                return null;
            }
        }).orElse(null);
    }

    @Override
    public List<ChatMessage> getAllChatMessages(String tradeId) {
        return Optional.of(tradeManager.getTrade(tradeId)).map(Trade::getChatMessages)
                .orElse(FXCollections.emptyObservableList());
    }

    @Override
    public boolean channelOpen(ChatMessage message) {
        return tradeManager.getOpenTrade(message.getTradeId()).isPresent();
    }

    @Override
    public void addAndPersistChatMessage(ChatMessage message) {
        tradeManager.getOpenTrade(message.getTradeId()).ifPresent(trade -> {
            ObservableList<ChatMessage> chatMessages = trade.getChatMessages();
            if (chatMessages.stream().noneMatch(m -> m.getUid().equals(message.getUid()))) {
                if (chatMessages.isEmpty()) {
                    addSystemMsg(trade);
                }
                trade.addAndPersistChatMessage(message);
                tradeManager.requestPersistence();
            } else {
                log.warn("Trade got a chatMessage that we have already stored. UId = {} TradeId = {}",
                        message.getUid(), message.getTradeId());
            }
        });
    }

    @Override
    protected AckMessageSourceType getAckMessageSourceType() {
        return AckMessageSourceType.TRADE_CHAT_MESSAGE;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onAllServicesInitialized() {
        super.onAllServicesInitialized();
        tryApplyMessages();
    }

    @Override
    public void onSupportMessage(SupportMessage message) {
        if (canProcessMessage(message)) {
            log.info("Received {} with tradeId {} and uid {}",
                    message.getClass().getSimpleName(), message.getTradeId(), message.getUid());
            if (message instanceof ChatMessage) {
                handle((ChatMessage) message);
            } else {
                log.warn("Unsupported message at dispatchMessage. message={}", message);
            }
        }
    }

    public void addSystemMsg(Trade trade) {
        // We need to use the trade date as otherwise our system msg would not be displayed first as the list is sorted
        // by date.
        ChatMessage chatMessage = new ChatMessage(
                getSupportType(),
                trade.getId(),
                0,
                false,
                Res.get("tradeChat.rules"),
                new NodeAddress("null:0000"),
                trade.getDate().getTime());
        chatMessage.setSystemMessage(true);
        trade.getChatMessages().add(chatMessage);

        requestPersistence();
    }
}
