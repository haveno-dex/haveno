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

package haveno.core.support.dispute.refund;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.app.Version;
import haveno.common.config.Config;
import haveno.common.crypto.KeyRing;
import haveno.core.api.XmrConnectionService;
import haveno.core.api.CoreNotificationService;
import haveno.core.locale.Res;
import haveno.core.offer.OpenOffer;
import haveno.core.offer.OpenOfferManager;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.support.SupportType;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeManager;
import haveno.core.support.dispute.DisputeResult;
import haveno.core.support.dispute.messages.DisputeClosedMessage;
import haveno.core.support.dispute.messages.DisputeOpenedMessage;
import haveno.core.support.messages.ChatMessage;
import haveno.core.support.messages.SupportMessage;
import haveno.core.trade.ClosedTradableManager;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.xmr.wallet.TradeWalletService;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.AckMessageSourceType;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
@Singleton
public final class RefundManager extends DisputeManager<RefundDisputeList> {


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public RefundManager(P2PService p2PService,
                         TradeWalletService tradeWalletService,
                         XmrWalletService walletService,
                         XmrConnectionService xmrConnectionService,
                         CoreNotificationService notificationService,
                         TradeManager tradeManager,
                         ClosedTradableManager closedTradableManager,
                         OpenOfferManager openOfferManager,
                         // TODO (woodser): remove priceFeedService?
                         KeyRing keyRing,
                         RefundDisputeListService refundDisputeListService,
                         Config config,
                         PriceFeedService priceFeedService) {
        super(p2PService, tradeWalletService, walletService, xmrConnectionService, notificationService, tradeManager, closedTradableManager,
                openOfferManager, keyRing, refundDisputeListService, config, priceFeedService);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Implement template methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public SupportType getSupportType() {
        return SupportType.REFUND;
    }

    @Override
    public void onSupportMessage(SupportMessage message) {
        if (canProcessMessage(message)) {
            log.info("Received {} with tradeId {} and uid {}",
                    message.getClass().getSimpleName(), message.getTradeId(), message.getUid());

            if (message instanceof DisputeOpenedMessage) {
                handle((DisputeOpenedMessage) message);
            } else if (message instanceof ChatMessage) {
                handle((ChatMessage) message);
            } else if (message instanceof DisputeClosedMessage) {
                handle((DisputeClosedMessage) message);
            } else {
                log.warn("Unsupported message at dispatchMessage. message={}", message);
            }
        }
    }

    @Override
    protected AckMessageSourceType getAckMessageSourceType() {
        return AckMessageSourceType.REFUND_MESSAGE;
    }

    @Override
    public void cleanupDisputes() {
        disputeListService.cleanupDisputes(tradeId -> tradeManager.closeDisputedTrade(tradeId, Trade.DisputeState.REFUND_REQUEST_CLOSED));
    }

    @Override
    protected String getDisputeInfo(Dispute dispute) {
        String role = Res.get("shared.refundAgent").toLowerCase();
        String link = "https://docs.haveno.exchange/trading-rules.html#arbitration";
        return Res.get("support.initialInfo", role, role, link);
    }

    @Override
    protected String getDisputeIntroForPeer(String disputeInfo) {
        return Res.get("support.peerOpenedDispute", disputeInfo, Version.VERSION);
    }

    @Override
    protected String getDisputeIntroForDisputeCreator(String disputeInfo) {
        return Res.get("support.youOpenedDispute", disputeInfo, Version.VERSION);
    }

    @Override
    protected void addPriceInfoMessage(Dispute dispute, int counter) {
        // At refund agent we do not add the option trade price check as the time for dispute opening is not correct.
        // In case of an option trade the mediator adds to the result summary message automatically the system message
        // with the option trade detection info so the refund agent can see that as well.
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Message handler
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    // We get that message at both peers. The dispute object is in context of the trader
    public void handle(DisputeClosedMessage disputeResultMessage) {
        DisputeResult disputeResult = disputeResultMessage.getDisputeResult();
        String tradeId = disputeResult.getTradeId();
        ChatMessage chatMessage = disputeResult.getChatMessage();
        checkNotNull(chatMessage, "chatMessage must not be null");
        Optional<Dispute> disputeOptional = findDispute(disputeResult);
        String uid = disputeResultMessage.getUid();
        if (!disputeOptional.isPresent()) {
            log.warn("We got a dispute result msg but we don't have a matching dispute. " +
                    "That might happen when we get the disputeResultMessage before the dispute was created. " +
                    "We try again after 2 sec. to apply the disputeResultMessage. TradeId = " + tradeId);
            if (!delayMsgMap.containsKey(uid)) {
                // We delay 2 sec. to be sure the comm. msg gets added first
                Timer timer = UserThread.runAfter(() -> handle(disputeResultMessage), 2);
                delayMsgMap.put(uid, timer);
            } else {
                log.warn("We got a dispute result msg after we already repeated to apply the message after a delay. " +
                        "That should never happen. TradeId = " + tradeId);
            }
            return;
        }

        Dispute dispute = disputeOptional.get();
        cleanupRetryMap(uid);
        if (!dispute.getChatMessages().contains(chatMessage)) {
            dispute.addAndPersistChatMessage(chatMessage);
        } else {
            log.warn("We got a dispute mail msg that we have already stored. TradeId = " + chatMessage.getTradeId());
        }
        dispute.setIsClosed();

        if (dispute.disputeResultProperty().get() != null) {
            log.warn("We got already a dispute result. That should only happen if a dispute needs to be closed " +
                    "again because the first close did not succeed. TradeId = " + tradeId);
        }

        dispute.setDisputeResult(disputeResult);

        Optional<Trade> tradeOptional = tradeManager.getOpenTrade(tradeId);
        if (tradeOptional.isPresent()) {
            Trade trade = tradeOptional.get();
            if (trade.getDisputeState() == Trade.DisputeState.REFUND_REQUESTED ||
                    trade.getDisputeState() == Trade.DisputeState.REFUND_REQUEST_STARTED_BY_PEER) {
                trade.setDisputeState(Trade.DisputeState.REFUND_REQUEST_CLOSED);
                tradeManager.requestPersistence();
            }
        } else {
            Optional<OpenOffer> openOfferOptional = openOfferManager.getOpenOffer(tradeId);
            openOfferOptional.ifPresent(openOffer -> openOfferManager.closeSpentOffer(openOffer.getOffer()));
        }
        sendAckMessage(chatMessage, dispute.getAgentPubKeyRing(), true, null);

        // set state after payout as we call swapAddressEntryToAvailable
        if (tradeManager.getOpenTrade(tradeId).isPresent()) {
            tradeManager.closeDisputedTrade(tradeId, Trade.DisputeState.REFUND_REQUEST_CLOSED);
        } else {
            Optional<OpenOffer> openOfferOptional = openOfferManager.getOpenOffer(tradeId);
            openOfferOptional.ifPresent(openOffer -> openOfferManager.closeSpentOffer(openOffer.getOffer()));
        }

        requestPersistence();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Nullable
    @Override
    public NodeAddress getAgentNodeAddress(Dispute dispute) {
      throw new RuntimeException("Refund manager not used in XMR adapation");
        //return dispute.getContract().getRefundAgentNodeAddress();
    }
}
