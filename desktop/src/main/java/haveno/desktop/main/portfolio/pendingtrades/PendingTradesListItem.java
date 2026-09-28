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

package haveno.desktop.main.portfolio.pendingtrades;

import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.monetary.Price;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.desktop.util.filtering.FilterableListItem;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.collections.ObservableMap;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * We could remove that wrapper if it is not needed for additional UI only fields.
 */
public class PendingTradesListItem implements FilterableListItem {
    @Getter
    enum TradeStatus {
        UPDATING("portfolio.pending.tradeView.awaitingInformation", false),
        CONFIRMING_DEPOSITS("portfolio.pending.tradeView.confirming", false),
        DEPOSITS_CONFIRMED("portfolio.pending.tradeView.depositsConfirmed", false),
        RECOMMENDED_WAIT("portfolio.pending.tradeView.recommendedWait", false),
        SEND_PAYMENT("portfolio.pending.tradeView.sendPayment", true),
        SEND_PAYMENT_OVERDUE("portfolio.pending.tradeView.sendPayment", true),
        WAITING_BUYER("portfolio.pending.tradeView.waitingBuyer", false),
        WAITING_SELLER("portfolio.pending.tradeView.waitingSeller", false),
        CONFIRM_RECEIPT("portfolio.pending.tradeView.confirmReceipt", true),
        CONFIRM_RECEIPT_OVERDUE("portfolio.pending.tradeView.confirmReceipt", true),
        CONFIRMING_PAYMENT("portfolio.pending.tradeView.confirmingPayment", false),
        RETRY_CONFIRMATION("portfolio.pending.status.retryConfirmation", true),
        IN_DISPUTE("portfolio.pending.tradeView.inDispute", true),
        ARBITRATED("portfolio.closed.ticketClosed", false),
        COMPLETED("portfolio.pending.step5.completed", false);

        private final String resourceKey;
        private final boolean attention;

        TradeStatus(String resourceKey, boolean attention) {
            this.resourceKey = resourceKey;
            this.attention = attention;
        }
    }

    public static final Logger log = LoggerFactory.getLogger(PendingTradesListItem.class);
    private final CoinFormatter btcFormatter;
    private final Trade trade;
    @Getter
    private final ObservableValue<TradeStatus> tradeStatus;

    public PendingTradesListItem(Trade trade, CoinFormatter btcFormatter, ObservableMap<String, Boolean> showPaymentDetailsEarly) {
        this.trade = trade;
        this.btcFormatter = btcFormatter;
        tradeStatus = Bindings.createObjectBinding(
                () -> calculateTradeStatus(showPaymentDetailsEarly.getOrDefault(trade.getId(), false)),
                trade.stateProperty(), trade.payoutStateProperty(), trade.disputeStateProperty(), trade.tradePeriodStateProperty(),
                showPaymentDetailsEarly);
    }

    public Trade getTrade() {
        return trade;
    }

    public Price getPrice() {
        return trade.getPrice();
    }

    public String getPriceAsString() {
        return FormattingUtils.formatPrice(trade.getPrice());
    }

    public String getAmountAsString() {
        return HavenoUtils.formatXmr(trade.getAmount());
    }

    public String getPaymentMethod() {
        return trade.getOffer().getPaymentMethodNameWithCountryCode();
    }

    public String getMarketDescription() {
        return CurrencyUtil.getCurrencyPair(trade.getOffer().getCounterCurrencyCode());
    }

    public String getRole() {
        return Res.get(trade.isArbitrator() ? "shared.arbitrator" : trade.isMaker() ? "shared.maker" : "shared.taker");
    }

    public String getDirection() {
        return trade.isArbitrator() ? "" : Res.get(trade.isBuyer() ? "shared.buy" : "shared.sell");
    }

    private TradeStatus calculateTradeStatus(boolean showPaymentDetailsEarly) {
        TradeStatus status = calculateBaseTradeStatus(showPaymentDetailsEarly);
        if (trade.tradePeriodStateProperty().get() != Trade.TradePeriodState.TRADE_PERIOD_OVER) return status;
        return status == TradeStatus.SEND_PAYMENT ? TradeStatus.SEND_PAYMENT_OVERDUE :
                status == TradeStatus.CONFIRM_RECEIPT ? TradeStatus.CONFIRM_RECEIPT_OVERDUE : status;
    }

    private TradeStatus calculateBaseTradeStatus(boolean showPaymentDetailsEarly) {
        if (trade.isPayoutPublished() || trade.isCompleted()) return TradeStatus.COMPLETED;
        if (trade.getDisputeState().isDisputed()) {
            return trade.getDisputeState().isCloseRequested() ? TradeStatus.ARBITRATED : TradeStatus.IN_DISPUTE;
        }
        if (trade.isArbitrator()) {
            if (trade.isDepositsUnlocked()) return TradeStatus.DEPOSITS_CONFIRMED;
            return trade.isDepositsPublished() ? TradeStatus.CONFIRMING_DEPOSITS : TradeStatus.UPDATING;
        }

        switch (trade.getState()) {
            case BUYER_CONFIRMED_PAYMENT_SENT:
            case BUYER_SENT_PAYMENT_SENT_MSG:
            case BUYER_SAW_ARRIVED_PAYMENT_SENT_MSG:
                return trade.isBuyer() ? TradeStatus.CONFIRMING_PAYMENT : TradeStatus.CONFIRM_RECEIPT;
            case BUYER_SEND_FAILED_PAYMENT_SENT_MSG:
                return trade.isBuyer() ? TradeStatus.RETRY_CONFIRMATION : TradeStatus.WAITING_BUYER;
            case SELLER_SEND_FAILED_PAYMENT_RECEIVED_MSG:
                return trade.isSeller() ? TradeStatus.RETRY_CONFIRMATION : TradeStatus.WAITING_SELLER;
            case SELLER_CONFIRMED_PAYMENT_RECEIPT:
                return trade.isBuyer() ? TradeStatus.WAITING_SELLER : TradeStatus.CONFIRMING_PAYMENT;
            default:
                break;
        }
        if (trade.isPaymentReceived()) return trade.isBuyer() ? TradeStatus.COMPLETED : TradeStatus.CONFIRMING_PAYMENT;
        if (trade.isPaymentMarkedSent()) {
            return trade.isBuyer() ? TradeStatus.WAITING_SELLER : TradeStatus.CONFIRM_RECEIPT;
        }
        if (trade.isDepositsUnlocked()) {
            if (trade.isSeller()) return TradeStatus.WAITING_BUYER;
            boolean recommendedWait = HavenoUtils.RECOMMEND_CONFIRMATIONS_BEFORE_SENDING_PAYMENT &&
                    !trade.isDepositsFinalized() && !showPaymentDetailsEarly;
            return recommendedWait ? TradeStatus.RECOMMENDED_WAIT : TradeStatus.SEND_PAYMENT;
        }
        return trade.isDepositsPublished() ? TradeStatus.CONFIRMING_DEPOSITS : TradeStatus.UPDATING;
    }

    @Override
    public boolean match(String filterString) {
        if (filterString.isEmpty()) {
            return true;
        }
        if (StringUtils.containsIgnoreCase(getTrade().getId(), filterString)) {
            return true;
        }
        if (StringUtils.containsIgnoreCase(getRole(), filterString)) {
            return true;
        }
        if (StringUtils.containsIgnoreCase(getDirection(), filterString)) {
            return true;
        }
        if (StringUtils.containsIgnoreCase(Res.get(tradeStatus.getValue().getResourceKey()), filterString)) {
            return true;
        }
        if (StringUtils.containsIgnoreCase(getAmountAsString(), filterString)) {
            return true;
        }
        if (StringUtils.containsIgnoreCase(getPaymentMethod(), filterString)) {
            return true;
        }
        if (StringUtils.containsIgnoreCase(getMarketDescription(), filterString)) {
            return true;
        }
        if (StringUtils.containsIgnoreCase(getTrade().getOffer().getCombinedExtraInfo(), filterString)) {
            return true;
        }
        if (getTrade().getBuyer().getPaymentAccountPayload() != null && StringUtils.containsIgnoreCase(getTrade().getBuyer().getPaymentAccountPayload().getPaymentDetails(), filterString)) {
            return true;
        }
        if (getTrade().getSeller().getPaymentAccountPayload() != null && StringUtils.containsIgnoreCase(getTrade().getSeller().getPaymentAccountPayload().getPaymentDetails(), filterString)) {
            return true;
        }
        return StringUtils.containsIgnoreCase(getPriceAsString(), filterString);
    }

    // items are recreated on list updates, so identify by trade to keep table selection stable
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof PendingTradesListItem)) return false;
        return trade.getId().equals(((PendingTradesListItem) obj).trade.getId());
    }

    @Override
    public int hashCode() {
        return trade.getId().hashCode();
    }
}
