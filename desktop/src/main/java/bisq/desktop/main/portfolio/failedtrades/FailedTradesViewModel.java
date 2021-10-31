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

package haveno.desktop.main.portfolio.failedtrades;

import haveno.desktop.common.model.ActivatableWithDataModel;
import haveno.desktop.common.model.ViewModel;
import haveno.desktop.util.DisplayUtils;

import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;

import com.google.inject.Inject;

import javax.inject.Named;

import javafx.collections.ObservableList;

class FailedTradesViewModel extends ActivatableWithDataModel<FailedTradesDataModel> implements ViewModel {
    private final CoinFormatter formatter;


    @Inject
    public FailedTradesViewModel(FailedTradesDataModel dataModel, @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter) {
        super(dataModel);

        this.formatter = formatter;
    }

    public ObservableList<FailedTradesListItem> getList() {
        return dataModel.getList();
    }

    String getTradeId(FailedTradesListItem item) {
        return item.getTrade().getShortId();
    }

    String getAmount(FailedTradesListItem item) {
        if (item != null && item.getTrade() != null)
            return formatter.formatCoin(item.getTrade().getTradeAmount());
        else
            return "";
    }

    String getPrice(FailedTradesListItem item) {
        return (item != null) ? FormattingUtils.formatPrice(item.getTrade().getTradePrice()) : "";
    }

    String getVolume(FailedTradesListItem item) {
        if (item != null && item.getTrade() != null)
            return DisplayUtils.formatVolumeWithCode(item.getTrade().getTradeVolume());
        else
            return "";
    }

    String getDirectionLabel(FailedTradesListItem item) {
        return (item != null) ? DisplayUtils.getDirectionWithCode(dataModel.getDirection(item.getTrade().getOffer()), item.getTrade().getOffer().getCurrencyCode()) : "";
    }

    String getMarketLabel(FailedTradesListItem item) {
        if ((item == null))
            return "";

        return CurrencyUtil.getCurrencyPair(item.getTrade().getOffer().getCurrencyCode());
    }

    String getDate(FailedTradesListItem item) {
        return DisplayUtils.formatDateTime(item.getTrade().getDate());
    }

    String getState(FailedTradesListItem item) {
        return item != null ? Res.get("portfolio.failed.Failed") : "";
    }
}
