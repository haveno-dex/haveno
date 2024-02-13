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

package haveno.core.trade;

import haveno.core.offer.OpenOffer;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClosedTradableUtil {
    public static BigInteger getTotalAmount(List<Tradable> tradableList) {
        return BigInteger.valueOf(tradableList.stream()
                .flatMap(tradable -> tradable.getOptionalAmount().stream())
                .mapToLong(value -> value.longValueExact())
                .sum());
    }

    public static BigInteger getTotalTxFee(List<Tradable> tradableList) {
        return BigInteger.valueOf(tradableList.stream()
                .mapToLong(tradable -> getTotalTxFee(tradable).longValueExact())
                .sum());
    }

    public static Map<String, Long> getTotalVolumeByCurrency(List<Tradable> tradableList) {
        Map<String, Long> map = new HashMap<>();
        tradableList.stream()
                .flatMap(tradable -> tradable.getOptionalVolume().stream())
                .forEach(volume -> {
                    String currencyCode = volume.getCurrencyCode();
                    map.putIfAbsent(currencyCode, 0L);
                    map.put(currencyCode, volume.getValue() + map.get(currencyCode));
                });
        return map;
    }

    public static BigInteger getTotalTxFee(Tradable tradable) {
        return tradable.getTotalTxFee();
    }

    public static boolean isOpenOffer(Tradable tradable) {
        return tradable instanceof OpenOffer;
    }

    public static boolean isHavenoV1Trade(Tradable tradable) {
        return tradable instanceof Trade;
    }

    public static Trade castToTrade(Tradable tradable) {
        return (Trade) tradable;
    }

    public static Trade castToTradeModel(Tradable tradable) {
        return (Trade) tradable;
    }
}
