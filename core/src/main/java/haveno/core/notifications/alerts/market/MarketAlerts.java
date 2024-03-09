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

package haveno.core.notifications.alerts.market;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.common.crypto.KeyRing;
import haveno.common.util.MathUtils;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.monetary.CryptoMoney;
import haveno.core.monetary.Price;
import haveno.core.monetary.TraditionalMoney;
import haveno.core.notifications.MobileMessage;
import haveno.core.notifications.MobileMessageType;
import haveno.core.notifications.MobileNotificationService;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferBookService;
import haveno.core.offer.OfferDirection;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.user.User;
import haveno.core.util.FormattingUtils;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class MarketAlerts {
    private final OfferBookService offerBookService;
    private final MobileNotificationService mobileNotificationService;
    private final User user;
    private final PriceFeedService priceFeedService;
    private final KeyRing keyRing;

    @Inject
    private MarketAlerts(OfferBookService offerBookService, MobileNotificationService mobileNotificationService,
                         User user, PriceFeedService priceFeedService, KeyRing keyRing) {
        this.offerBookService = offerBookService;
        this.mobileNotificationService = mobileNotificationService;
        this.user = user;
        this.priceFeedService = priceFeedService;
        this.keyRing = keyRing;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onAllServicesInitialized() {
        offerBookService.addOfferBookChangedListener(new OfferBookService.OfferBookChangedListener() {
            @Override
            public void onAdded(Offer offer) {
                onOfferAdded(offer);
            }

            @Override
            public void onRemoved(Offer offer) {
            }
        });
        applyFilterOnAllOffers();
    }

    public void addMarketAlertFilter(MarketAlertFilter filter) {
        user.addMarketAlertFilter(filter);
        applyFilterOnAllOffers();
    }

    public void removeMarketAlertFilter(MarketAlertFilter filter) {
        user.removeMarketAlertFilter(filter);
    }

    public List<MarketAlertFilter> getMarketAlertFilters() {
        return user.getMarketAlertFilters();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void applyFilterOnAllOffers() {
        offerBookService.getOffers().forEach(this::onOfferAdded);
    }

    // We combine the offer ID and the price (either as % price or as fixed price) to get also updates for edited offers
    // % price get multiplied by 10000 to have 0.12% be converted to 12. For fixed price we have precision of 8 for
    // crypto and traditional.
    private String getAlertId(Offer offer) {
        double price = offer.isUseMarketBasedPrice() ? offer.getMarketPriceMarginPct() * 10000 : offer.getOfferPayload().getPrice();
        String priceString = String.valueOf((long) price);
        return offer.getId() + "|" + priceString;
    }

    private void onOfferAdded(Offer offer) {
        String currencyCode = offer.getCurrencyCode();
        MarketPrice marketPrice = priceFeedService.getMarketPrice(currencyCode);
        Price offerPrice = offer.getPrice();
        if (marketPrice != null && offerPrice != null) {
            boolean isSellOffer = offer.getDirection() == OfferDirection.SELL;
            String shortOfferId = offer.getShortId();
            boolean isTraditionalCurrency = CurrencyUtil.isTraditionalCurrency(currencyCode);
            String alertId = getAlertId(offer);
            user.getMarketAlertFilters().stream()
                    .filter(marketAlertFilter -> !offer.isMyOffer(keyRing))
                    .filter(marketAlertFilter -> offer.getPaymentMethod().equals(marketAlertFilter.getPaymentAccount().getPaymentMethod()))
                    .filter(marketAlertFilter -> marketAlertFilter.notContainsAlertId(alertId))
                    .forEach(marketAlertFilter -> {
                        int triggerValue = marketAlertFilter.getTriggerValue();
                        boolean isTriggerForBuyOffer = marketAlertFilter.isBuyOffer();
                        double marketPriceAsDouble1 = marketPrice.getPrice();
                        int precision = CurrencyUtil.isTraditionalCurrency(currencyCode) ?
                                TraditionalMoney.SMALLEST_UNIT_EXPONENT :
                                CryptoMoney.SMALLEST_UNIT_EXPONENT;
                        double marketPriceAsDouble = MathUtils.scaleUpByPowerOf10(marketPriceAsDouble1, precision);
                        double offerPriceValue = offerPrice.getValue();
                        double ratio = offerPriceValue / marketPriceAsDouble;
                        ratio = 1 - ratio;
                        if (isTraditionalCurrency && isSellOffer)
                            ratio *= -1;
                        else if (!isTraditionalCurrency && !isSellOffer)
                            ratio *= -1;

                        ratio = ratio * 10000;
                        boolean triggered = ratio <= triggerValue;
                        if (!triggered)
                            return;

                        boolean isTriggerForBuyOfferAndTriggered = !isSellOffer && isTriggerForBuyOffer;
                        boolean isTriggerForSellOfferAndTriggered = isSellOffer && !isTriggerForBuyOffer;
                        if (isTriggerForBuyOfferAndTriggered || isTriggerForSellOfferAndTriggered) {
                            String direction = isSellOffer ? Res.get("shared.sell") : Res.get("shared.buy");
                            String marketDir;
                            if (isTraditionalCurrency) {
                                if (isSellOffer) {
                                    marketDir = ratio > 0 ?
                                            Res.get("account.notifications.marketAlert.message.msg.above") :
                                            Res.get("account.notifications.marketAlert.message.msg.below");
                                } else {
                                    marketDir = ratio < 0 ?
                                            Res.get("account.notifications.marketAlert.message.msg.above") :
                                            Res.get("account.notifications.marketAlert.message.msg.below");
                                }
                            } else {
                                if (isSellOffer) {
                                    marketDir = ratio < 0 ?
                                            Res.get("account.notifications.marketAlert.message.msg.above") :
                                            Res.get("account.notifications.marketAlert.message.msg.below");
                                } else {
                                    marketDir = ratio > 0 ?
                                            Res.get("account.notifications.marketAlert.message.msg.above") :
                                            Res.get("account.notifications.marketAlert.message.msg.below");
                                }
                            }

                            ratio = Math.abs(ratio);
                            String msg = Res.get("account.notifications.marketAlert.message.msg",
                                    direction,
                                    CurrencyUtil.getCurrencyPair(currencyCode),
                                    FormattingUtils.formatPrice(offerPrice),
                                    FormattingUtils.formatToPercentWithSymbol(ratio / 10000d),
                                    marketDir,
                                    Res.get(offer.getPaymentMethod().getId()),
                                    shortOfferId);
                            MobileMessage message = new MobileMessage(Res.get("account.notifications.marketAlert.message.title"),
                                    msg,
                                    shortOfferId,
                                    MobileMessageType.MARKET);
                            try {
                                boolean wasSent = mobileNotificationService.sendMessage(message);
                                if (wasSent) {
                                    // In case we have disabled alerts wasSent is false and we do not
                                    // persist the offer
                                    marketAlertFilter.addAlertId(alertId);
                                    user.requestPersistence();
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    });
        }
    }

    public static MobileMessage getTestMsg() {
        String shortId = UUID.randomUUID().toString().substring(0, 8);
        return new MobileMessage(Res.get("account.notifications.marketAlert.message.title"),
                "A new 'sell BTC/USD' offer with price 6019.2744 (5.36% below market price) and payment method " +
                        "'Perfect Money' was published to the Haveno offerbook.\n" +
                        "Offer ID: wygiaw.",
                shortId,
                MobileMessageType.MARKET);
    }
}
