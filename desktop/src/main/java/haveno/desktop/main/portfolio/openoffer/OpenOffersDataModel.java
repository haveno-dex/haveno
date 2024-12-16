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

package haveno.desktop.main.portfolio.openoffer;

import com.google.inject.Inject;
import haveno.common.UserThread;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OpenOffer;
import haveno.core.offer.OpenOfferManager;
import haveno.core.offer.TriggerPriceService;
import haveno.core.provider.price.PriceFeedService;
import haveno.desktop.common.model.ActivatableDataModel;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

import java.util.stream.Collectors;

class OpenOffersDataModel extends ActivatableDataModel {
    private final OpenOfferManager openOfferManager;
    private final PriceFeedService priceFeedService;

    private final ObservableList<OpenOfferListItem> list = FXCollections.observableArrayList();
    private final ListChangeListener<OpenOffer> tradesListChangeListener;
    private final ChangeListener<Number> currenciesUpdateFlagPropertyListener;

    @Inject
    public OpenOffersDataModel(OpenOfferManager openOfferManager, PriceFeedService priceFeedService) {
        this.openOfferManager = openOfferManager;
        this.priceFeedService = priceFeedService;
        tradesListChangeListener = change -> UserThread.execute(() -> applyList());
        currenciesUpdateFlagPropertyListener = (observable, oldValue, newValue) -> UserThread.execute(() -> applyList());
    }

    @Override
    protected void activate() {
        openOfferManager.getObservableList().addListener(tradesListChangeListener);
        priceFeedService.updateCounterProperty().addListener(currenciesUpdateFlagPropertyListener);
        applyList();
    }

    @Override
    protected void deactivate() {
        openOfferManager.getObservableList().removeListener(tradesListChangeListener);
        priceFeedService.updateCounterProperty().removeListener(currenciesUpdateFlagPropertyListener);
    }

    void onActivateOpenOffer(OpenOffer openOffer, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        openOfferManager.activateOpenOffer(openOffer, resultHandler, errorMessageHandler);
    }

    void onDeactivateOpenOffer(OpenOffer openOffer, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        openOfferManager.deactivateOpenOffer(openOffer, resultHandler, errorMessageHandler);
    }

    void onRemoveOpenOffer(OpenOffer openOffer, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        openOfferManager.cancelOpenOffer(openOffer, resultHandler, errorMessageHandler);
    }


    public ObservableList<OpenOfferListItem> getList() {
        return list;
    }

    public OfferDirection getDirection(Offer offer) {
        return openOfferManager.isMyOffer(offer) ? offer.getDirection() : offer.getMirroredDirection();
    }

    private synchronized void applyList() {
        list.clear();

        list.addAll(openOfferManager.getOpenOffers().stream().map(OpenOfferListItem::new).collect(Collectors.toList()));

        // we sort by date, earliest first
        list.sort((o1, o2) -> o2.getOffer().getDate().compareTo(o1.getOffer().getDate()));
    }

    boolean wasTriggered(OpenOffer openOffer) {
        return TriggerPriceService.wasTriggered(priceFeedService.getMarketPrice(openOffer.getOffer().getCurrencyCode()), openOffer);
    }
}
