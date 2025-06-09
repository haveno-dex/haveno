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

package haveno.desktop.main.offer.signedoffer;

import com.google.inject.Inject;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

import java.util.stream.Collectors;



import haveno.core.offer.OpenOfferManager;
import haveno.core.offer.SignedOffer;
import haveno.desktop.common.model.ActivatableDataModel;
import java.sql.Date;

class SignedOffersDataModel extends ActivatableDataModel {
    private final OpenOfferManager openOfferManager;
    private final ObservableList<SignedOfferListItem> list = FXCollections.observableArrayList();
    private final ListChangeListener<SignedOffer> tradesListChangeListener;

    @Inject
    public SignedOffersDataModel(OpenOfferManager openOfferManager) {
        this.openOfferManager = openOfferManager;

        tradesListChangeListener = change -> applyList();
    }

    @Override
    protected void activate() {
        openOfferManager.getObservableSignedOffersList().addListener(tradesListChangeListener);
        applyList();
    }

    @Override
    protected void deactivate() {
        openOfferManager.getObservableSignedOffersList().removeListener(tradesListChangeListener);
    }

    public ObservableList<SignedOfferListItem> getList() {
        return list;
    }

    private void applyList() {
        list.clear();

        synchronized (openOfferManager.getObservableSignedOffersList()) {
            list.addAll(openOfferManager.getObservableSignedOffersList().stream().map(SignedOfferListItem::new).collect(Collectors.toList()));
        }

        // we sort by date, the earliest first
        list.sort((o1, o2) -> new Date(o2.getSignedOffer().getTimeStamp()).compareTo(new Date(o1.getSignedOffer().getTimeStamp())));
    }
}
