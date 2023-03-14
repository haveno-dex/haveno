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

package haveno.core.offer;

import com.fasterxml.jackson.annotation.JsonIgnore;
import haveno.common.util.MathUtils;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.monetary.Price;
import haveno.core.monetary.Volume;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.trade.HavenoUtils;
import org.bitcoinj.utils.MonetaryFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.Date;

public class OfferForJson {
    private static final Logger log = LoggerFactory.getLogger(OfferForJson.class);

    public final OfferDirection direction;
    public final String currencyCode;
    public final long minAmount;
    public final long amount;
    public final long price;
    public final long date;
    public final boolean useMarketBasedPrice;
    public final double marketPriceMargin;
    public final String paymentMethod;
    public final String id;

    // primaryMarket fields are based on industry standard where primaryMarket is always in the focus (in the app BTC is always in the focus - will be changed in a larger refactoring once)
    public String currencyPair;
    public OfferDirection primaryMarketDirection;

    public String priceDisplayString;
    public String primaryMarketAmountDisplayString;
    public String primaryMarketMinAmountDisplayString;
    public String primaryMarketVolumeDisplayString;
    public String primaryMarketMinVolumeDisplayString;

    public long primaryMarketPrice;
    public long primaryMarketAmount;
    public long primaryMarketMinAmount;
    public long primaryMarketVolume;
    public long primaryMarketMinVolume;

    @JsonIgnore
    transient private final MonetaryFormat fiatFormat = new MonetaryFormat().shift(0).minDecimals(4).repeatOptionalDecimals(0, 0);
    @JsonIgnore
    transient private final MonetaryFormat altcoinFormat = new MonetaryFormat().shift(0).minDecimals(8).repeatOptionalDecimals(0, 0);
    @JsonIgnore
    transient private final MonetaryFormat coinFormat = MonetaryFormat.BTC;


    public OfferForJson(OfferDirection direction,
                        String currencyCode,
                        BigInteger minAmount,
                        BigInteger amount,
                        @Nullable Price price,
                        Date date,
                        String id,
                        boolean useMarketBasedPrice,
                        double marketPriceMargin,
                        PaymentMethod paymentMethod) {

        this.direction = direction;
        this.currencyCode = currencyCode;
        this.minAmount = minAmount.longValueExact();
        this.amount = amount.longValueExact();
        this.price = price.getValue();
        this.date = date.getTime();
        this.id = id;
        this.useMarketBasedPrice = useMarketBasedPrice;
        this.marketPriceMargin = marketPriceMargin;
        this.paymentMethod = paymentMethod.getId();

        setDisplayStrings();
    }

    private void setDisplayStrings() {
        try {
            final Price price = getPrice();
            if (CurrencyUtil.isCryptoCurrency(currencyCode)) {
                primaryMarketDirection = direction == OfferDirection.BUY ? OfferDirection.SELL : OfferDirection.BUY;
                currencyPair = currencyCode + "/" + Res.getBaseCurrencyCode();

                // int precision = 8;
                //decimalFormat.setMaximumFractionDigits(precision);

                // amount and volume is inverted for json
                priceDisplayString = altcoinFormat.noCode().format(price.getMonetary()).toString();
                primaryMarketMinAmountDisplayString = altcoinFormat.noCode().format(getMinVolume().getMonetary()).toString();
                primaryMarketAmountDisplayString = altcoinFormat.noCode().format(getVolume().getMonetary()).toString();
                primaryMarketMinVolumeDisplayString = HavenoUtils.formatXmr(getMinAmount()).toString();
                primaryMarketVolumeDisplayString = HavenoUtils.formatXmr(getAmount()).toString();

                primaryMarketPrice = price.getValue();
                primaryMarketMinAmount = getMinVolume().getValue();
                primaryMarketAmount = getVolume().getValue();
                primaryMarketMinVolume = getMinAmount().longValueExact();
                primaryMarketVolume = getAmount().longValueExact();
            } else {
                primaryMarketDirection = direction;
                currencyPair = Res.getBaseCurrencyCode() + "/" + currencyCode;

                priceDisplayString = fiatFormat.noCode().format(price.getMonetary()).toString();
                primaryMarketMinAmountDisplayString = HavenoUtils.formatXmr(getMinAmount()).toString();
                primaryMarketAmountDisplayString = HavenoUtils.formatXmr(getAmount()).toString();
                primaryMarketMinVolumeDisplayString = fiatFormat.noCode().format(getMinVolume().getMonetary()).toString();
                primaryMarketVolumeDisplayString = fiatFormat.noCode().format(getVolume().getMonetary()).toString();

                // we use precision 4 for fiat based price but on the markets api we use precision 8 so we scale up by 10000
                primaryMarketPrice = (long) MathUtils.scaleUpByPowerOf10(price.getValue(), 4);
                primaryMarketMinVolume = (long) MathUtils.scaleUpByPowerOf10(getMinVolume().getValue(), 4);
                primaryMarketVolume = (long) MathUtils.scaleUpByPowerOf10(getVolume().getValue(), 4);

                primaryMarketMinAmount = getMinAmount().longValueExact();
                primaryMarketAmount = getAmount().longValueExact();
            }

        } catch (Throwable t) {
            log.error("Error at setDisplayStrings: " + t.getMessage());
        }
    }

    private Price getPrice() {
        return Price.valueOf(currencyCode, price);
    }

    private BigInteger getAmount() {
        return BigInteger.valueOf(amount);
    }

    private BigInteger getMinAmount() {
        return BigInteger.valueOf(minAmount);
    }

    private Volume getVolume() {
        return getPrice().getVolumeByAmount(getAmount());
    }

    private Volume getMinVolume() {
        return getPrice().getVolumeByAmount(getMinAmount());
    }
}
