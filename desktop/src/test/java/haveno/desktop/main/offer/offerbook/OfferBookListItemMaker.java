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

package haveno.desktop.main.offer.offerbook;

import com.natpryce.makeiteasy.Instantiator;
import com.natpryce.makeiteasy.MakeItEasy;
import static com.natpryce.makeiteasy.MakeItEasy.a;
import static com.natpryce.makeiteasy.MakeItEasy.make;
import static com.natpryce.makeiteasy.MakeItEasy.with;
import com.natpryce.makeiteasy.Maker;
import com.natpryce.makeiteasy.Property;
import haveno.core.offer.OfferDirection;
import haveno.desktop.maker.OfferMaker;
import static haveno.desktop.maker.OfferMaker.xmrUsdOffer;

public class OfferBookListItemMaker {

    public static final Property<OfferBookListItem, String> id = new Property<>();
    public static final Property<OfferBookListItem, Long> price = new Property<>();
    public static final Property<OfferBookListItem, Long> amount = new Property<>();
    public static final Property<OfferBookListItem, Long> minAmount = new Property<>();
    public static final Property<OfferBookListItem, OfferDirection> direction = new Property<>();
    public static final Property<OfferBookListItem, Boolean> useMarketBasedPrice = new Property<>();
    public static final Property<OfferBookListItem, Double> marketPriceMargin = new Property<>();
    public static final Property<OfferBookListItem, String> baseCurrencyCode = new Property<>();
    public static final Property<OfferBookListItem, String> counterCurrencyCode = new Property<>();

    public static final Instantiator<OfferBookListItem> OfferBookListItem = lookup ->
            new OfferBookListItem(make(xmrUsdOffer.but(
                    with(OfferMaker.price, lookup.valueOf(price, 1000000000L)),
                    with(OfferMaker.amount, lookup.valueOf(amount, 1000000000L)),
                    with(OfferMaker.minAmount, lookup.valueOf(amount, 1000000000L)),
                    with(OfferMaker.direction, lookup.valueOf(direction, OfferDirection.BUY)),
                    with(OfferMaker.useMarketBasedPrice, lookup.valueOf(useMarketBasedPrice, false)),
                    with(OfferMaker.marketPriceMargin, lookup.valueOf(marketPriceMargin, 0.0)),
                    with(OfferMaker.baseCurrencyCode, lookup.valueOf(baseCurrencyCode, "XMR")),
                    with(OfferMaker.counterCurrencyCode, lookup.valueOf(counterCurrencyCode, "USD")),
                    with(OfferMaker.id, lookup.valueOf(id, "1234"))
            )));

    public static final Instantiator<OfferBookListItem> OfferBookListItemWithRange = lookup ->
            new OfferBookListItem(make(xmrUsdOffer.but(
                    MakeItEasy.with(OfferMaker.price, lookup.valueOf(price, 100000L)),
                    with(OfferMaker.minAmount, lookup.valueOf(minAmount, 1000000000L)),
                    with(OfferMaker.amount, lookup.valueOf(amount, 2000000000L)))));

    public static final Maker<OfferBookListItem> xmrBuyItem = a(OfferBookListItem);
    public static final Maker<OfferBookListItem> xmrSellItem = a(OfferBookListItem, with(direction, OfferDirection.SELL));

    public static final Maker<OfferBookListItem> xmrItemWithRange = a(OfferBookListItemWithRange);
}
