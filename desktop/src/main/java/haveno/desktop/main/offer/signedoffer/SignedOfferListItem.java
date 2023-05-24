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

package haveno.desktop.main.offer.signedoffer;

import org.apache.commons.lang3.StringUtils;

import lombok.Getter;

import haveno.core.offer.SignedOffer;
import haveno.desktop.util.filtering.FilterableListItem;

class SignedOfferListItem implements FilterableListItem {
    @Getter
    private final SignedOffer signedOffer;

    SignedOfferListItem(SignedOffer signedOffer) {
        this.signedOffer = signedOffer;
    }

    @Override
    public boolean match(String filterString) {
        if (filterString.isEmpty()) {
            return true;
        }
        if (StringUtils.containsIgnoreCase(String.valueOf(signedOffer.getTraderId()), filterString)) {
            return true;
        }
        if (StringUtils.containsIgnoreCase(String.valueOf(signedOffer.getOfferId()), filterString)) {
            return true;
        }
        return StringUtils.containsIgnoreCase(String.valueOf(signedOffer.getReserveTxKeyImages()), filterString);
    }
}
