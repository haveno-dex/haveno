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

package haveno.core.notifications.alerts;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.common.crypto.PubKeyRingProvider;
import haveno.core.locale.Res;
import haveno.core.notifications.MobileMessage;
import haveno.core.notifications.MobileMessageType;
import haveno.core.notifications.MobileNotificationService;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javafx.collections.ListChangeListener;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class TradeEvents {
    private final PubKeyRingProvider pubKeyRingProvider;
    private final TradeManager tradeManager;
    private final MobileNotificationService mobileNotificationService;
    private boolean isInitialized = false;

    @Inject
    public TradeEvents(TradeManager tradeManager, PubKeyRingProvider pubKeyRingProvider, MobileNotificationService mobileNotificationService) {
        this.tradeManager = tradeManager;
        this.mobileNotificationService = mobileNotificationService;
        this.pubKeyRingProvider = pubKeyRingProvider;
    }

    public void onAllServicesInitialized() {
        tradeManager.getObservableList().addListener((ListChangeListener<Trade>) c -> {
            c.next();
            if (c.wasAdded()) {
                c.getAddedSubList().forEach(this::setTradePhaseListener);
            }
        });
        tradeManager.getObservableList().forEach(this::setTradePhaseListener);
        isInitialized = true;
    }

    private void setTradePhaseListener(Trade trade) {
        if (isInitialized) log.info("We got a new trade. id={}", trade.getId());
        if (!trade.isPayoutPublished()) {
            trade.statePhaseProperty().addListener((observable, oldValue, newValue) -> {
                String msg = null;
                String shortId = trade.getShortId();
                switch (newValue) {
                    case INIT:
                    case DEPOSIT_REQUESTED:
                    case DEPOSITS_PUBLISHED:
                        break;
                    case DEPOSITS_UNLOCKED:
                        if (trade.getContract() != null && pubKeyRingProvider.get().equals(trade.getContract().getBuyerPubKeyRing()))
                            msg = Res.get("account.notifications.trade.message.msg.conf", shortId);
                        break;
                    case PAYMENT_SENT:
                        // We only notify the seller
                        if (trade.getContract() != null && pubKeyRingProvider.get().equals(trade.getContract().getSellerPubKeyRing()))
                            msg = Res.get("account.notifications.trade.message.msg.started", shortId);
                        break;
                    case PAYMENT_RECEIVED:
                        // We only notify the buyer
                        if (trade.getContract() != null && pubKeyRingProvider.get().equals(trade.getContract().getBuyerPubKeyRing()))
                            msg = Res.get("account.notifications.trade.message.msg.completed", shortId);
                        break;
                }
                if (msg != null) {
                    MobileMessage message = new MobileMessage(Res.get("account.notifications.trade.message.title"),
                            msg,
                            shortId,
                            MobileMessageType.TRADE);
                    try {
                        mobileNotificationService.sendMessage(message);
                    } catch (Exception e) {
                        log.error(e.toString());
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    public static List<MobileMessage> getTestMessages() {
        String shortId = UUID.randomUUID().toString().substring(0, 8);
        List<MobileMessage> list = new ArrayList<>();
        list.add(new MobileMessage(Res.get("account.notifications.trade.message.title"),
                Res.get("account.notifications.trade.message.msg.conf", shortId),
                shortId,
                MobileMessageType.TRADE));
        list.add(new MobileMessage(Res.get("account.notifications.trade.message.title"),
                Res.get("account.notifications.trade.message.msg.started", shortId),
                shortId,
                MobileMessageType.TRADE));
        list.add(new MobileMessage(Res.get("account.notifications.trade.message.title"),
                Res.get("account.notifications.trade.message.msg.completed", shortId),
                shortId,
                MobileMessageType.TRADE));
        return list;
    }
}
