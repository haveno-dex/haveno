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

import com.google.inject.Inject;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.trade.failed.FailedTradesManager;
import haveno.desktop.common.model.ActivatableDataModel;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

import java.util.stream.Collectors;

class FailedTradesDataModel extends ActivatableDataModel {

    private final FailedTradesManager failedTradesManager;
    private final TradeManager tradeManager;

    private final ObservableList<FailedTradesListItem> list = FXCollections.observableArrayList();
    private final ListChangeListener<Trade> tradesListChangeListener;

    @Inject
    public FailedTradesDataModel(FailedTradesManager failedTradesManager, TradeManager tradeManager) {
        this.failedTradesManager = failedTradesManager;
        this.tradeManager = tradeManager;

        tradesListChangeListener = change -> applyList();
    }

    @Override
    protected void activate() {
        applyList();
        failedTradesManager.getObservableList().addListener(tradesListChangeListener);
    }

    @Override
    protected void deactivate() {
        failedTradesManager.getObservableList().removeListener(tradesListChangeListener);
    }

    public ObservableList<FailedTradesListItem> getList() {
        return list;
    }

    public OfferDirection getDirection(Offer offer) {
        return failedTradesManager.wasMyOffer(offer) ? offer.getDirection() : offer.getMirroredDirection();
    }

    private void applyList() {
        list.clear();

        list.addAll(failedTradesManager.getObservableList().stream().map(FailedTradesListItem::new).collect(Collectors.toList()));

        // we sort by date, earliest first
        list.sort((o1, o2) -> o2.getTrade().getDate().compareTo(o1.getTrade().getDate()));
    }

    public void onMoveTradeToPendingTrades(Trade trade) {
        failedTradesManager.removeTrade(trade);
        tradeManager.addFailedTradeToPendingTrades(trade);
    }

    public void unfailTrade(Trade trade) {
        failedTradesManager.unFailTrade(trade);
    }

    public String checkUnfail(Trade trade) {
        return failedTradesManager.checkUnFail(trade);
    }
}
