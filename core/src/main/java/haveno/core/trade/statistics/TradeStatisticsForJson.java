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

package haveno.core.trade.statistics;

import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.monetary.Price;
import haveno.core.monetary.Volume;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.concurrent.Immutable;
import java.math.BigInteger;

@Immutable
@EqualsAndHashCode
@ToString
@Slf4j
public final class TradeStatisticsForJson {
    public final String currency;
    public final long tradePrice;
    public final long tradeAmount;
    public final long tradeDate;
    public final String paymentMethod;

    // primaryMarket fields are based on industry standard where primaryMarket is always in the focus (in the app BTC is always in the focus - will be changed in a larger refactoring once)
    public String currencyPair;

    public long primaryMarketTradePrice;
    public long primaryMarketTradeAmount;
    public long primaryMarketTradeVolume;

    public TradeStatisticsForJson(TradeStatistics3 tradeStatistics) {
        this.currency = tradeStatistics.getCurrency();
        this.paymentMethod = tradeStatistics.getPaymentMethodId();
        this.tradePrice = tradeStatistics.getPrice();
        this.tradeAmount = tradeStatistics.getAmount();
        this.tradeDate = tradeStatistics.getDateAsLong();

        try {
            Price tradePrice = getPrice();
            if (CurrencyUtil.isCryptoCurrency(currency)) {
                currencyPair = currency + "/" + Res.getBaseCurrencyCode();
                primaryMarketTradePrice = tradePrice.getValue();
                primaryMarketTradeAmount = getTradeVolume() != null ?
                        getTradeVolume().getValue() :
                        0;
                primaryMarketTradeVolume = getTradeAmount().longValueExact();
            } else {
                currencyPair = Res.getBaseCurrencyCode() + "/" + currency;
                primaryMarketTradePrice = tradePrice.getValue();
                primaryMarketTradeAmount = getTradeAmount().longValueExact();
                primaryMarketTradeVolume = getTradeVolume() != null ?
                        getTradeVolume().getValue() :
                        0;
            }
        } catch (Throwable t) {
            log.error(t.getMessage());
            t.printStackTrace();
        }
    }

    public Price getPrice() {
        return Price.valueOf(currency, tradePrice);
    }

    public BigInteger getTradeAmount() {
        return BigInteger.valueOf(tradeAmount);
    }

    public Volume getTradeVolume() {
        try {
            return getPrice().getVolumeByAmount(getTradeAmount());
        } catch (Throwable t) {
            return Volume.parse("0", currency);
        }
    }
}
