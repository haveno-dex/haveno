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

package haveno.desktop.main.portfolio.failedtrades;

import org.apache.commons.lang3.StringUtils;

import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.trade.ArbitratorTrade;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.failed.FailedTradesManager;
import haveno.core.util.FormattingUtils;
import haveno.core.util.VolumeUtil;
import haveno.desktop.util.DisplayUtils;
import haveno.desktop.util.filtering.FilterableListItem;
import haveno.desktop.util.filtering.FilteringUtils;
import lombok.Getter;

class FailedTradesListItem implements FilterableListItem {
    @Getter
    private final Trade trade;
    private final FailedTradesManager failedTradesManager;

    FailedTradesListItem(Trade trade,
                         FailedTradesManager failedTradesManager) {
        this.trade = trade;
        this.failedTradesManager = failedTradesManager;
    }

    FailedTradesListItem() {
        this.trade = null;
        this.failedTradesManager = null;
    }

    public String getDateAsString() {
        return DisplayUtils.formatDateTime(trade.getDate());
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

    public String getDirectionLabel() {
        Offer offer = trade.getOffer();
        OfferDirection direction = failedTradesManager.wasMyOffer(offer) || trade instanceof ArbitratorTrade
                ? offer.getDirection()
                : offer.getMirroredDirection();
        String currencyCode = trade.getOffer().getCounterCurrencyCode();
        return DisplayUtils.getDirectionWithCode(direction, currencyCode, offer.isPrivateOffer());
    }

    public String getVolumeAsString() {
        return VolumeUtil.formatVolumeWithCode(trade.getVolume());
    }

    public String getState() {
        return Res.get("portfolio.failed.Failed");
    }

    @Override
    public boolean match(String filterString) {
        if (filterString.isEmpty()) {
            return true;
        }
        if (StringUtils.containsIgnoreCase(getDateAsString(), filterString)) {
            return true;
        }
        if (StringUtils.containsIgnoreCase(getMarketDescription(), filterString)) {
            return true;
        }
        if (StringUtils.containsIgnoreCase(getPriceAsString(), filterString)) {
            return true;
        }
        if (StringUtils.containsIgnoreCase(getPaymentMethod(), filterString)) {
            return true;
        }
        if (StringUtils.containsIgnoreCase(getAmountAsString(), filterString)) {
            return true;
        }
        if (StringUtils.containsIgnoreCase(getDirectionLabel(), filterString)) {
            return true;
        }
        if (StringUtils.containsIgnoreCase(getVolumeAsString(), filterString)) {
            return true;
        }
        if (StringUtils.containsIgnoreCase(getState(), filterString)) {
            return true;
        }
        if (StringUtils.containsIgnoreCase(getTrade().getOffer().getCombinedExtraInfo(), filterString)) {
            return true;
        }
        if (trade.getBuyer().getPaymentAccountPayload() != null && StringUtils.containsIgnoreCase(getTrade().getBuyer().getPaymentAccountPayload().getPaymentDetails(), filterString)) {
            return true;
        }
        if (trade.getSeller().getPaymentAccountPayload() != null && StringUtils.containsIgnoreCase(getTrade().getSeller().getPaymentAccountPayload().getPaymentDetails(), filterString)) {
            return true;
        }
        if (FilteringUtils.match(trade.getOffer(), filterString)) {
            return true;
        }
        return FilteringUtils.match(trade, filterString);
    }
}
