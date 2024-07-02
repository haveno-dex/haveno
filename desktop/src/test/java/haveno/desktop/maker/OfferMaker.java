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

package haveno.desktop.maker;

import com.natpryce.makeiteasy.Instantiator;
import com.natpryce.makeiteasy.Maker;
import com.natpryce.makeiteasy.Property;
import haveno.common.crypto.Encryption;
import haveno.common.crypto.PubKeyRing;
import haveno.common.crypto.Sig;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferPayload;
import haveno.network.p2p.NodeAddress;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import static com.natpryce.makeiteasy.MakeItEasy.a;
import static com.natpryce.makeiteasy.MakeItEasy.with;
import static com.natpryce.makeiteasy.Property.newProperty;
import static java.lang.System.currentTimeMillis;
import static java.net.InetAddress.getLocalHost;

@SuppressWarnings("InstantiationOfUtilityClass")
public class OfferMaker {

    public static final Property<Offer, String> id = newProperty();
    public static final Property<Offer, String> paymentMethodId = newProperty();
    public static final Property<Offer, String> paymentAccountId = newProperty();
    public static final Property<Offer, String> countryCode = newProperty();
    public static final Property<Offer, List<String>> countryCodes = newProperty();
    public static final Property<Offer, Long> date = newProperty();
    public static final Property<Offer, Long> price = newProperty();
    public static final Property<Offer, Long> minAmount = newProperty();
    public static final Property<Offer, Integer> roundTo = newProperty();
    public static final Property<Offer, Long> amount = newProperty();
    public static final Property<Offer, String> baseCurrencyCode = newProperty();
    public static final Property<Offer, String> counterCurrencyCode = newProperty();
    public static final Property<Offer, OfferDirection> direction = newProperty();
    public static final Property<Offer, Boolean> useMarketBasedPrice = newProperty();
    public static final Property<Offer, Double> marketPriceMargin = newProperty();
    public static final Property<Offer, NodeAddress> nodeAddress = newProperty();
    public static final Property<Offer, List<NodeAddress>> nodeAddresses = newProperty();
    public static final Property<Offer, PubKeyRing> pubKeyRing = newProperty();
    public static final Property<Offer, Long> blockHeight = newProperty();
    public static final Property<Offer, Long> txFee = newProperty();
    public static final Property<Offer, Double> makerFeePct = newProperty();
    public static final Property<Offer, Double> takerFeePct = newProperty();
    public static final Property<Offer, Double> penaltyFeePct = newProperty();
    public static final Property<Offer, Double> buyerSecurityDepositPct = newProperty();
    public static final Property<Offer, Double> sellerSecurityDepositPct = newProperty();
    public static final Property<Offer, Long> tradeLimit = newProperty();
    public static final Property<Offer, Long> maxTradePeriod = newProperty();
    public static final Property<Offer, Long> lowerClosePrice = newProperty();
    public static final Property<Offer, Long> upperClosePrice = newProperty();
    public static final Property<Offer, Integer> protocolVersion = newProperty();

    public static final Instantiator<Offer> Offer = lookup -> new Offer(
            new OfferPayload(lookup.valueOf(id, "1234"),
                    lookup.valueOf(date, currentTimeMillis()),
                    lookup.valueOf(nodeAddress, getLocalHostNodeWithPort(10000)),
                    lookup.valueOf(pubKeyRing, genPubKeyRing()),
                    lookup.valueOf(direction, OfferDirection.BUY),
                    lookup.valueOf(price, 100000L),
                    lookup.valueOf(marketPriceMargin, 0.0),
                    lookup.valueOf(useMarketBasedPrice, false),
                    lookup.valueOf(amount, 100000L),
                    lookup.valueOf(minAmount, 100000L),
                    lookup.valueOf(makerFeePct, .0015),
                    lookup.valueOf(takerFeePct, .0075),
                    lookup.valueOf(penaltyFeePct, 0.03),
                    lookup.valueOf(buyerSecurityDepositPct, .15),
                    lookup.valueOf(sellerSecurityDepositPct, .15),
                    lookup.valueOf(baseCurrencyCode, "XMR"),
                    lookup.valueOf(counterCurrencyCode, "USD"),
                    lookup.valueOf(paymentMethodId, "SEPA"),
                    lookup.valueOf(paymentAccountId, "00002c4d-1ffc-4208-8ff3-e669817b0000"),
                    lookup.valueOf(countryCode, "FR"),
                    lookup.valueOf(countryCodes, new ArrayList<>() {{
                        add("FR");
                    }}),
                    null,
                    null,
                    "2",
                    lookup.valueOf(blockHeight, 700000L),
                    lookup.valueOf(tradeLimit, 0L),
                    lookup.valueOf(maxTradePeriod, 0L),
                    false,
                    false,
                    lookup.valueOf(lowerClosePrice, 0L),
                    lookup.valueOf(upperClosePrice, 0L),
                    false,
                    null,
                    null,
                    lookup.valueOf(protocolVersion, 0),
                    getLocalHostNodeWithPort(99999),
                    null,
                    null,
                    lookup.valueOf(roundTo, 1)));

    public static final Maker<Offer> xmrUsdOffer = a(Offer);
    public static final Maker<Offer> btcBCHCOffer = a(Offer).but(with(counterCurrencyCode, "BCHC"));

    static NodeAddress getLocalHostNodeWithPort(int port) {
        try {
            return new NodeAddress(getLocalHost().getHostAddress(), port);
        } catch (UnknownHostException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static PubKeyRing genPubKeyRing() {
        return new PubKeyRing(Sig.generateKeyPair().getPublic(), Encryption.generateKeyPair().getPublic());
    }
}
