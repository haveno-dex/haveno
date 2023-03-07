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

package haveno.cli;

import haveno.proto.grpc.OfferInfo;

import java.util.List;
import java.util.function.Function;

import static haveno.cli.ColumnHeaderConstants.COL_HEADER_DIRECTION;
import static java.lang.String.format;
import static protobuf.OfferDirection.BUY;
import static protobuf.OfferDirection.SELL;

class DirectionFormat {

    static int getLongestDirectionColWidth(List<OfferInfo> offers) {
        if (offers.isEmpty() || offers.get(0).getBaseCurrencyCode().equals("XMR"))
            return COL_HEADER_DIRECTION.length();
        else
            return 18;  // .e.g., "Sell BSQ (Buy XMR)".length()
    }

    static final Function<OfferInfo, String> directionFormat = (offer) -> {
        String baseCurrencyCode = offer.getBaseCurrencyCode();
        boolean isCryptoCurrencyOffer = !baseCurrencyCode.equals("XMR");
        if (!isCryptoCurrencyOffer) {
            return baseCurrencyCode;
        } else {
            // Return "Sell BSQ (Buy XMR)", or "Buy BSQ (Sell XMR)".
            String direction = offer.getDirection();
            String mirroredDirection = getMirroredDirection(direction);
            Function<String, String> mixedCase = (word) -> word.charAt(0) + word.substring(1).toLowerCase();
            return format("%s %s (%s %s)",
                    mixedCase.apply(mirroredDirection),
                    baseCurrencyCode,
                    mixedCase.apply(direction),
                    offer.getCounterCurrencyCode());
        }
    };

    static String getMirroredDirection(String directionAsString) {
        return directionAsString.equalsIgnoreCase(BUY.name()) ? SELL.name() : BUY.name();
    }
}
