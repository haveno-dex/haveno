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

package bisq.desktop.main.portfolio.closedtrades;

import bisq.desktop.common.model.ActivatableDataModel;

import bisq.core.account.witness.AccountAgeWitnessService;
import bisq.core.monetary.Price;
import bisq.core.monetary.Volume;
import bisq.core.provider.price.MarketPrice;
import bisq.core.provider.price.PriceFeedService;
import bisq.core.trade.ClosedTradableFormatter;
import bisq.core.trade.ClosedTradableManager;
import bisq.core.trade.ClosedTradableUtil;
import bisq.core.trade.Tradable;
import bisq.core.user.Preferences;
import bisq.core.util.PriceUtil;
import bisq.core.util.VolumeUtil;

import org.bitcoinj.core.Coin;

import com.google.inject.Inject;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

class ClosedTradesDataModel extends ActivatableDataModel {

    final ClosedTradableManager closedTradableManager;
    final ClosedTradableFormatter closedTradableFormatter;
    private final Preferences preferences;
    private final PriceFeedService priceFeedService;
    final AccountAgeWitnessService accountAgeWitnessService;
    private final ObservableList<ClosedTradesListItem> list = FXCollections.observableArrayList();
    private final ListChangeListener<Tradable> tradesListChangeListener;

    @Inject
    public ClosedTradesDataModel(ClosedTradableManager closedTradableManager,
                                 ClosedTradableFormatter closedTradableFormatter,
                                 Preferences preferences,
                                 PriceFeedService priceFeedService,
                                 AccountAgeWitnessService accountAgeWitnessService) {
        this.closedTradableManager = closedTradableManager;
        this.closedTradableFormatter = closedTradableFormatter;
        this.preferences = preferences;
        this.priceFeedService = priceFeedService;
        this.accountAgeWitnessService = accountAgeWitnessService;

        tradesListChangeListener = change -> applyList();
    }

    @Override
    protected void activate() {
        applyList();
        closedTradableManager.getObservableList().addListener(tradesListChangeListener);
    }

    @Override
    protected void deactivate() {
        closedTradableManager.getObservableList().removeListener(tradesListChangeListener);
    }

    ObservableList<ClosedTradesListItem> getList() {
        return list;
    }

    List<Tradable> getListAsTradables() {
        return list.stream().map(ClosedTradesListItem::getTradable).collect(Collectors.toList());
    }

    Coin getTotalAmount() {
        return ClosedTradableUtil.getTotalAmount(getListAsTradables());
    }

    Optional<Volume> getVolumeInUserFiatCurrency(Coin amount) {
        return getVolume(amount, preferences.getPreferredTradeCurrency().getCode());
    }

    Optional<Volume> getVolume(Coin amount, String currencyCode) {
        MarketPrice marketPrice = priceFeedService.getMarketPrice(currencyCode);
        if (marketPrice == null) {
            return Optional.empty();
        }

        Price price = PriceUtil.marketPriceToPrice(marketPrice);
        return Optional.of(VolumeUtil.getVolume(amount, price));
    }

    Volume getBsqVolumeInUsdWithAveragePrice(Coin amount) {
        return closedTradableManager.getBsqVolumeInUsdWithAveragePrice(amount);
    }

    Coin getTotalTxFee() {
        return ClosedTradableUtil.getTotalTxFee(getListAsTradables());
    }

    Coin getTotalTradeFee() {
        return closedTradableManager.getTotalTradeFee(getListAsTradables());
    }

    boolean isCurrencyForTradeFeeBtc(Tradable item) {
        return item != null;
    }

    private void applyList() {
        list.clear();
        list.addAll(
                closedTradableManager.getObservableList().stream()
                        .map(tradable -> new ClosedTradesListItem(tradable, closedTradableFormatter, closedTradableManager))
                        .collect(Collectors.toList())
        );
        // We sort by date, the earliest first
        list.sort((o1, o2) -> o2.getTradable().getDate().compareTo(o1.getTradable().getDate()));
    }
}
