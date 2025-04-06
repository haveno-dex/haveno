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

import haveno.core.monetary.Price;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.desktop.util.filtering.FilterableListItem;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static haveno.core.locale.CurrencyUtil.getCurrencyPair;

/**
 * We could remove that wrapper if it is not needed for additional UI only fields.
 */
public class PendingTradesListItem implements FilterableListItem {
    public static final Logger log = LoggerFactory.getLogger(PendingTradesListItem.class);
    private final CoinFormatter btcFormatter;
    private final Trade trade;

    public PendingTradesListItem(Trade trade, CoinFormatter btcFormatter) {
        this.trade = trade;
        this.btcFormatter = btcFormatter;
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
        return getCurrencyPair(trade.getOffer().getCurrencyCode());
    }

    @Override
    public boolean match(String filterString) {
        if (filterString.isEmpty()) {
            return true;
        }
        if (StringUtils.containsIgnoreCase(getTrade().getId(), filterString)) {
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
}
