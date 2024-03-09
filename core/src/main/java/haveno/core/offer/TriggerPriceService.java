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

package haveno.core.offer;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.common.util.MathUtils;
import static haveno.common.util.MathUtils.roundDoubleToLong;
import static haveno.common.util.MathUtils.scaleUpByPowerOf10;
import haveno.core.locale.CurrencyUtil;
import haveno.core.monetary.CryptoMoney;
import haveno.core.monetary.Price;
import haveno.core.monetary.TraditionalMoney;
import haveno.core.provider.price.MarketPrice;
import haveno.core.provider.price.PriceFeedService;
import haveno.network.p2p.BootstrapListener;
import haveno.network.p2p.P2PService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javafx.collections.ListChangeListener;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class TriggerPriceService {
    private final P2PService p2PService;
    private final OpenOfferManager openOfferManager;
    private final PriceFeedService priceFeedService;
    private final Map<String, Set<OpenOffer>> openOffersByCurrency = new HashMap<>();

    @Inject
    public TriggerPriceService(P2PService p2PService,
                               OpenOfferManager openOfferManager,
                               PriceFeedService priceFeedService) {
        this.p2PService = p2PService;
        this.openOfferManager = openOfferManager;
        this.priceFeedService = priceFeedService;
    }

    public void onAllServicesInitialized() {
        if (p2PService.isBootstrapped()) {
            onBootstrapComplete();
        } else {
            p2PService.addP2PServiceListener(new BootstrapListener() {
                @Override
                public void onUpdatedDataReceived() {
                    onBootstrapComplete();
                }
            });
        }
    }

    private void onBootstrapComplete() {
        openOfferManager.getObservableList().addListener((ListChangeListener<OpenOffer>) c -> {
            c.next();
            if (c.wasAdded()) {
                onAddedOpenOffers(c.getAddedSubList());
            }
            if (c.wasRemoved()) {
                onRemovedOpenOffers(c.getRemoved());
            }
        });
        onAddedOpenOffers(openOfferManager.getObservableList());

        priceFeedService.updateCounterProperty().addListener((observable, oldValue, newValue) -> onPriceFeedChanged());
        onPriceFeedChanged();
    }

    private void onPriceFeedChanged() {
        openOffersByCurrency.keySet().stream()
                .map(priceFeedService::getMarketPrice)
                .filter(Objects::nonNull)
                .filter(marketPrice -> openOffersByCurrency.containsKey(marketPrice.getCurrencyCode()))
                .forEach(marketPrice -> {
                    openOffersByCurrency.get(marketPrice.getCurrencyCode()).stream()
                            .filter(openOffer -> !openOffer.isDeactivated())
                            .forEach(openOffer -> checkPriceThreshold(marketPrice, openOffer));
                });
    }

    public static boolean wasTriggered(MarketPrice marketPrice, OpenOffer openOffer) {
        Price price = openOffer.getOffer().getPrice();
        if (price == null || marketPrice == null) {
            return false;
        }

        String currencyCode = openOffer.getOffer().getCurrencyCode();
        boolean traditionalCurrency = CurrencyUtil.isTraditionalCurrency(currencyCode);
        int smallestUnitExponent = traditionalCurrency ?
                TraditionalMoney.SMALLEST_UNIT_EXPONENT :
                CryptoMoney.SMALLEST_UNIT_EXPONENT;
        long marketPriceAsLong = roundDoubleToLong(
                scaleUpByPowerOf10(marketPrice.getPrice(), smallestUnitExponent));
        long triggerPrice = openOffer.getTriggerPrice();
        if (triggerPrice <= 0) {
            return false;
        }

        OfferDirection direction = openOffer.getOffer().getDirection();
        boolean isSellOffer = direction == OfferDirection.SELL;
        boolean cryptoCurrency = CurrencyUtil.isCryptoCurrency(currencyCode);
        boolean condition = isSellOffer && !cryptoCurrency || !isSellOffer && cryptoCurrency;
        return condition ?
                marketPriceAsLong < triggerPrice :
                marketPriceAsLong > triggerPrice;
    }

    private void checkPriceThreshold(MarketPrice marketPrice, OpenOffer openOffer) {
        if (wasTriggered(marketPrice, openOffer)) {
            String currencyCode = openOffer.getOffer().getCurrencyCode();
            int smallestUnitExponent = CurrencyUtil.isTraditionalCurrency(currencyCode) ?
                    TraditionalMoney.SMALLEST_UNIT_EXPONENT :
                    CryptoMoney.SMALLEST_UNIT_EXPONENT;
            long triggerPrice = openOffer.getTriggerPrice();

            log.info("Market price exceeded the trigger price of the open offer.\n" +
                            "We deactivate the open offer with ID {}.\nCurrency: {};\nOffer direction: {};\n" +
                            "Market price: {};\nTrigger price: {}",
                    openOffer.getOffer().getShortId(),
                    currencyCode,
                    openOffer.getOffer().getDirection(),
                    marketPrice.getPrice(),
                    MathUtils.scaleDownByPowerOf10(triggerPrice, smallestUnitExponent)
            );

            openOfferManager.deactivateOpenOffer(openOffer, () -> {
            }, errorMessage -> {
            });
        } else if (openOffer.getState() == OpenOffer.State.AVAILABLE) {
            // TODO: check if open offer's reserve tx is failed or double spend seen
        }
    }

    private void onAddedOpenOffers(List<? extends OpenOffer> openOffers) {
        openOffers.forEach(openOffer -> {
            String currencyCode = openOffer.getOffer().getCurrencyCode();
            openOffersByCurrency.putIfAbsent(currencyCode, new HashSet<>());
            openOffersByCurrency.get(currencyCode).add(openOffer);

            MarketPrice marketPrice = priceFeedService.getMarketPrice(openOffer.getOffer().getCurrencyCode());
            if (marketPrice != null) {
                checkPriceThreshold(marketPrice, openOffer);
            }
        });
    }

    private void onRemovedOpenOffers(List<? extends OpenOffer> openOffers) {
        openOffers.forEach(openOffer -> {
            String currencyCode = openOffer.getOffer().getCurrencyCode();
            if (openOffersByCurrency.containsKey(currencyCode)) {
                Set<OpenOffer> set = openOffersByCurrency.get(currencyCode);
                set.remove(openOffer);
                if (set.isEmpty()) {
                    openOffersByCurrency.remove(currencyCode);
                }
            }
        });
    }
}
