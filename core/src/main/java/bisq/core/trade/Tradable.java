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

package bisq.core.trade;

import bisq.core.monetary.Price;
import bisq.core.monetary.Volume;
import bisq.core.offer.Offer;
import bisq.network.p2p.NodeAddress;

import bisq.common.proto.persistable.PersistablePayload;

import org.bitcoinj.core.Coin;

import java.util.Date;
import java.util.Optional;

public interface Tradable extends PersistablePayload {
    Offer getOffer();

    Date getDate();

    String getId();

    String getShortId();

    default Optional<Trade> asTradeModel() {
        if (this instanceof Trade) {
            return Optional.of(((Trade) this));
        } else {
            return Optional.empty();
        }
    }

    default Optional<Volume> getOptionalVolume() {
        return asTradeModel().map(Trade::getVolume).or(() -> Optional.ofNullable(getOffer().getVolume()));
    }

    default Optional<Price> getOptionalPrice() {
        return asTradeModel().map(Trade::getPrice).or(() -> Optional.ofNullable(getOffer().getPrice()));
    }

    default Optional<Coin> getOptionalAmount() {
        return asTradeModel().map(Trade::getAmount);
    }

    default Optional<Long> getOptionalAmountAsLong() {
        return asTradeModel().map(Trade::getAmountAsLong);
    }

    default Optional<Coin> getOptionalTxFee() {
        return asTradeModel().map(Trade::getTxFee);
    }

    default Optional<Coin> getOptionalTakerFee() {
        return asTradeModel().map(Trade::getTakerFee);
    }

    default Optional<Coin> getOptionalMakerFee() {
        return asTradeModel().map(Trade::getOffer).map(Offer::getMakerFee).or(() -> Optional.ofNullable(getOffer().getMakerFee()));
    }

    default Optional<NodeAddress> getOptionalTradePeerNodeAddress() {
        return asTradeModel().map(Trade::getTradePeerNodeAddress);
    }
}
