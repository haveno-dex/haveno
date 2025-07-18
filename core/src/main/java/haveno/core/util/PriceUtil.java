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

package haveno.core.util;

import static com.google.common.base.Preconditions.checkNotNull;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.common.util.MathUtils;
import haveno.core.locale.CurrencyUtil;
import haveno.core.monetary.CryptoMoney;
import haveno.core.monetary.Price;
import haveno.core.monetary.TraditionalMoney;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.user.Preferences;
import haveno.core.util.validation.AmountValidator4Decimals;
import haveno.core.util.validation.AmountValidator8Decimals;
import haveno.core.util.validation.InputValidator;
import haveno.core.util.validation.MonetaryValidator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class PriceUtil {
    private final PriceFeedService priceFeedService;

    @Inject
    public PriceUtil(PriceFeedService priceFeedService,
                     TradeStatisticsManager tradeStatisticsManager,
                     Preferences preferences) {
        this.priceFeedService = priceFeedService;
    }

    public static MonetaryValidator getPriceValidator(String currencyCode) {
        return CurrencyUtil.isPricePrecise(currencyCode) ?
                new AmountValidator4Decimals() :
                new AmountValidator8Decimals();
    }

    public static InputValidator.ValidationResult isTriggerPriceValid(String triggerPriceAsString,
                                                                      MarketPrice marketPrice,
                                                                      boolean isSellOffer,
                                                                      String currencyCode) {
        if (triggerPriceAsString == null || triggerPriceAsString.isEmpty()) {
            return new InputValidator.ValidationResult(true);
        }

        InputValidator.ValidationResult result = getPriceValidator(currencyCode).validate(triggerPriceAsString);
        if (!result.isValid) {
            return result;
        }
        
        return new InputValidator.ValidationResult(true);
    }

    public static Price marketPriceToPrice(MarketPrice marketPrice) {
        String currencyCode = marketPrice.getCurrencyCode();
        double priceAsDouble = marketPrice.getPrice();
        int precision = CurrencyUtil.isTraditionalCurrency(currencyCode) ?
                TraditionalMoney.SMALLEST_UNIT_EXPONENT :
                CryptoMoney.SMALLEST_UNIT_EXPONENT;
        double scaled = MathUtils.scaleUpByPowerOf10(priceAsDouble, precision);
        long roundedToLong = MathUtils.roundDoubleToLong(scaled);
        return Price.valueOf(currencyCode, roundedToLong);
    }

    public boolean hasMarketPrice(Offer offer) {
        String currencyCode = offer.getCounterCurrencyCode();
        checkNotNull(priceFeedService, "priceFeed must not be null");
        MarketPrice marketPrice = priceFeedService.getMarketPrice(currencyCode);
        Price price = offer.getPrice();
        return price != null && marketPrice != null && marketPrice.isRecentExternalPriceAvailable();
    }

    public Optional<Double> getMarketBasedPrice(Offer offer,
                                                OfferDirection direction) {
        if (offer.isUseMarketBasedPrice()) {
            return Optional.of(offer.getMarketPriceMarginPct());
        }

        if (!hasMarketPrice(offer)) {
            log.trace("We don't have a market price. " +
                    "That case could only happen if you don't have a price feed.");
            return Optional.empty();
        }

        String currencyCode = offer.getCounterCurrencyCode();
        checkNotNull(priceFeedService, "priceFeed must not be null");
        MarketPrice marketPrice = priceFeedService.getMarketPrice(currencyCode);
        double marketPriceAsDouble = checkNotNull(marketPrice).getPrice();
        return calculatePercentage(offer, marketPriceAsDouble, direction);
    }

    public static Optional<Double> calculatePercentage(Offer offer,
                                                       double marketPrice,
                                                       OfferDirection direction) {
        // If the offer did not use % price we calculate % from current market price
        String currencyCode = offer.getCounterCurrencyCode();
        Price price = offer.getPrice();
        int precision = CurrencyUtil.isTraditionalCurrency(currencyCode) ?
                TraditionalMoney.SMALLEST_UNIT_EXPONENT :
                CryptoMoney.SMALLEST_UNIT_EXPONENT;
        long priceAsLong = checkNotNull(price).getValue();
        double scaled = MathUtils.scaleDownByPowerOf10(priceAsLong, precision);
        double value;
        if (direction == OfferDirection.SELL) {
            if (marketPrice == 0) {
                return Optional.empty();
            }
            value = 1 - scaled / marketPrice;
        } else {
            if (marketPrice == 1) {
                return Optional.empty();
            }
            value = scaled / marketPrice - 1;
        }
        return Optional.of(value);
    }

    public static long getMarketPriceAsLong(String inputValue, String currencyCode) {
        if (inputValue == null || inputValue.isEmpty() || currencyCode == null) {
            return 0;
        }

        try {
            int precision = getMarketPricePrecision(currencyCode);
            String stringValue = reformatMarketPrice(inputValue, currencyCode);
            return ParsingUtils.parsePriceStringToLong(currencyCode, stringValue, precision);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String reformatMarketPrice(String inputValue, String currencyCode) {
        if (inputValue == null || inputValue.isEmpty() || currencyCode == null) {
            return "";
        }

        double priceAsDouble = ParsingUtils.parseNumberStringToDouble(inputValue);
        int precision = getMarketPricePrecision(currencyCode);
        return FormattingUtils.formatRoundedDoubleWithPrecision(priceAsDouble, precision);
    }

    public static String formatMarketPrice(long priceAsLong, String currencyCode) {
        Price price = Price.valueOf(currencyCode, priceAsLong);
        return FormattingUtils.formatPrice(price);
    }

    public static int getMarketPricePrecision(String currencyCode) {
        return CurrencyUtil.isTraditionalCurrency(currencyCode) ?
                TraditionalMoney.SMALLEST_UNIT_EXPONENT : CryptoMoney.SMALLEST_UNIT_EXPONENT;
    }

    public static long invertLongPrice(long price, String currencyCode) {
        if (price == 0) return 0;
        int precision = CurrencyUtil.isTraditionalCurrency(currencyCode) ? TraditionalMoney.SMALLEST_UNIT_EXPONENT : CryptoMoney.SMALLEST_UNIT_EXPONENT;
        double priceDouble = MathUtils.scaleDownByPowerOf10(price, precision);
        double priceDoubleInverted = BigDecimal.ONE.divide(BigDecimal.valueOf(priceDouble), precision, RoundingMode.HALF_UP).doubleValue();
        double scaled = MathUtils.scaleUpByPowerOf10(priceDoubleInverted, precision);
        return MathUtils.roundDoubleToLong(scaled);
    }
}
