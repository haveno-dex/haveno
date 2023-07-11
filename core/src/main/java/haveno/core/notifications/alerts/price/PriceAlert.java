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

package haveno.core.notifications.alerts.price;

import haveno.common.util.MathUtils;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.monetary.CryptoMoney;
import haveno.core.monetary.TraditionalMoney;
import haveno.core.notifications.MobileMessage;
import haveno.core.notifications.MobileMessageType;
import haveno.core.notifications.MobileNotificationService;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.user.User;
import haveno.core.util.FormattingUtils;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;

@Slf4j
@Singleton
public class PriceAlert {
    private final PriceFeedService priceFeedService;
    private final MobileNotificationService mobileNotificationService;
    private final User user;

    @Inject
    public PriceAlert(PriceFeedService priceFeedService, MobileNotificationService mobileNotificationService, User user) {
        this.priceFeedService = priceFeedService;
        this.user = user;
        this.mobileNotificationService = mobileNotificationService;
    }

    public void onAllServicesInitialized() {
        priceFeedService.updateCounterProperty().addListener((observable, oldValue, newValue) -> update());
    }

    private void update() {
        if (user.getPriceAlertFilter() != null) {
            PriceAlertFilter filter = user.getPriceAlertFilter();
            String currencyCode = filter.getCurrencyCode();
            MarketPrice marketPrice = priceFeedService.getMarketPrice(currencyCode);
            if (marketPrice != null) {
                int exp = CurrencyUtil.isTraditionalCurrency(currencyCode) ? TraditionalMoney.SMALLEST_UNIT_EXPONENT : CryptoMoney.SMALLEST_UNIT_EXPONENT;
                double priceAsDouble = marketPrice.getPrice();
                long priceAsLong = MathUtils.roundDoubleToLong(MathUtils.scaleUpByPowerOf10(priceAsDouble, exp));
                String currencyName = CurrencyUtil.getNameByCode(currencyCode);
                if (priceAsLong > filter.getHigh() || priceAsLong < filter.getLow()) {
                    String msg = Res.get("account.notifications.priceAlert.message.msg",
                            currencyName,
                            FormattingUtils.formatMarketPrice(priceAsDouble, currencyCode),
                            CurrencyUtil.getCurrencyPair(currencyCode));
                    MobileMessage message = new MobileMessage(Res.get("account.notifications.priceAlert.message.title", currencyName),
                            msg,
                            MobileMessageType.PRICE);
                    log.error(msg);
                    try {
                        mobileNotificationService.sendMessage(message);

                        // If an alert got triggered we remove the filter.
                        user.removePriceAlertFilter();
                    } catch (Exception e) {
                        log.error(e.toString());
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    public static MobileMessage getTestMsg() {
        String currencyCode = "USD";
        String currencyName = CurrencyUtil.getNameByCode(currencyCode);
        String msg = Res.get("account.notifications.priceAlert.message.msg",
                currencyName,
                "6023.34",
                "BTC/USD");
        return new MobileMessage(Res.get("account.notifications.priceAlert.message.title", currencyName),
                msg,
                MobileMessageType.PRICE);
    }
}
