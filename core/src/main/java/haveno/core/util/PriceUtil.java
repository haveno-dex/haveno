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

package haveno.core.util;

import haveno.common.util.MathUtils;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.monetary.CryptoMoney;
import haveno.core.monetary.Price;
import haveno.core.monetary.TraditionalMoney;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.user.Preferences;
import haveno.core.util.validation.NonFiatPriceValidator;
import haveno.core.util.validation.FiatPriceValidator;
import haveno.core.util.validation.InputValidator;
import haveno.core.util.validation.MonetaryValidator;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

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

    public static MonetaryValidator getPriceValidator(boolean isFiatCurrency) {
        return isFiatCurrency ?
                new FiatPriceValidator() :
                new NonFiatPriceValidator();
    }

    public static InputValidator.ValidationResult isTriggerPriceValid(String triggerPriceAsString,
                                                                      MarketPrice marketPrice,
                                                                      boolean isSellOffer,
                                                                      boolean isFiatCurrency) {
        if (triggerPriceAsString == null || triggerPriceAsString.isEmpty()) {
            return new InputValidator.ValidationResult(true);
        }

        InputValidator.ValidationResult result = getPriceValidator(isFiatCurrency).validate(triggerPriceAsString);
        if (!result.isValid) {
            return result;
        }

        long triggerPriceAsLong = PriceUtil.getMarketPriceAsLong(triggerPriceAsString, marketPrice.getCurrencyCode());
        long marketPriceAsLong = PriceUtil.getMarketPriceAsLong("" +  marketPrice.getPrice(), marketPrice.getCurrencyCode());
        String marketPriceAsString = FormattingUtils.formatMarketPrice(marketPrice.getPrice(), marketPrice.getCurrencyCode());

        if ((isSellOffer && isFiatCurrency) || (!isSellOffer && !isFiatCurrency)) {
            if (triggerPriceAsLong >= marketPriceAsLong) {
                return new InputValidator.ValidationResult(false,
                        Res.get("createOffer.triggerPrice.invalid.tooHigh", marketPriceAsString));
            } else {
                return new InputValidator.ValidationResult(true);
            }
        } else {
            if (triggerPriceAsLong <= marketPriceAsLong) {
                return new InputValidator.ValidationResult(false,
                        Res.get("createOffer.triggerPrice.invalid.tooLow", marketPriceAsString));
            } else {
                return new InputValidator.ValidationResult(true);
            }
        }
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
        String currencyCode = offer.getCurrencyCode();
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

        String currencyCode = offer.getCurrencyCode();
        checkNotNull(priceFeedService, "priceFeed must not be null");
        MarketPrice marketPrice = priceFeedService.getMarketPrice(currencyCode);
        double marketPriceAsDouble = checkNotNull(marketPrice).getPrice();
        return calculatePercentage(offer, marketPriceAsDouble, direction);
    }

    public static Optional<Double> calculatePercentage(Offer offer,
                                                       double marketPrice,
                                                       OfferDirection direction) {
        // If the offer did not use % price we calculate % from current market price
        String currencyCode = offer.getCurrencyCode();
        Price price = offer.getPrice();
        int precision = CurrencyUtil.isTraditionalCurrency(currencyCode) ?
                TraditionalMoney.SMALLEST_UNIT_EXPONENT :
                CryptoMoney.SMALLEST_UNIT_EXPONENT;
        long priceAsLong = checkNotNull(price).getValue();
        double scaled = MathUtils.scaleDownByPowerOf10(priceAsLong, precision);
        double value;
        if (direction == OfferDirection.SELL) {
            if (CurrencyUtil.isTraditionalCurrency(currencyCode)) {
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
        } else {
            if (CurrencyUtil.isTraditionalCurrency(currencyCode)) {
                if (marketPrice == 1) {
                    return Optional.empty();
                }
                value = scaled / marketPrice - 1;
            } else {
                if (marketPrice == 0) {
                    return Optional.empty();
                }
                value = 1 - scaled / marketPrice;
            }
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

    public static String formatMarketPrice(long price, String currencyCode) {
        int marketPricePrecision = getMarketPricePrecision(currencyCode);
        double scaled = MathUtils.scaleDownByPowerOf10(price, marketPricePrecision);
        return FormattingUtils.formatMarketPrice(scaled, marketPricePrecision);
    }

    public static int getMarketPricePrecision(String currencyCode) {
        return CurrencyUtil.isTraditionalCurrency(currencyCode) ?
                TraditionalMoney.SMALLEST_UNIT_EXPONENT : CryptoMoney.SMALLEST_UNIT_EXPONENT;
    }
}
