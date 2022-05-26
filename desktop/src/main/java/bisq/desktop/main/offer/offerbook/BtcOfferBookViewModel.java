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

package bisq.desktop.main.offer.offerbook;

import bisq.desktop.Navigation;
import bisq.desktop.util.GUIUtil;

import bisq.core.account.witness.AccountAgeWitnessService;
import bisq.core.api.CoreApi;
import bisq.core.btc.setup.WalletsSetup;
import bisq.core.locale.CryptoCurrency;
import bisq.core.locale.CurrencyUtil;
import bisq.core.locale.GlobalSettings;
import bisq.core.locale.TradeCurrency;
import bisq.core.offer.Offer;
import bisq.core.offer.OfferDirection;
import bisq.core.offer.OfferFilterService;
import bisq.core.offer.OpenOfferManager;
import bisq.core.payment.PaymentAccountUtil;
import bisq.core.payment.payload.PaymentMethod;
import bisq.core.provider.price.PriceFeedService;
import bisq.core.trade.ClosedTradableManager;
import bisq.core.user.Preferences;
import bisq.core.user.User;
import bisq.core.util.FormattingUtils;
import bisq.core.util.PriceUtil;
import bisq.core.util.coin.CoinFormatter;

import bisq.network.p2p.P2PService;

import com.google.inject.Inject;

import javax.inject.Named;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class BtcOfferBookViewModel extends OfferBookViewModel {

    @Inject
    public BtcOfferBookViewModel(User user,
                                 OpenOfferManager openOfferManager,
                                 OfferBook offerBook,
                                 Preferences preferences,
                                 WalletsSetup walletsSetup,
                                 P2PService p2PService,
                                 PriceFeedService priceFeedService,
                                 ClosedTradableManager closedTradableManager,
                                 AccountAgeWitnessService accountAgeWitnessService,
                                 Navigation navigation,
                                 PriceUtil priceUtil,
                                 OfferFilterService offerFilterService,
                                 @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter btcFormatter,
                                 CoreApi coreApi) {
        super(user, openOfferManager, offerBook, preferences, walletsSetup, p2PService, priceFeedService, closedTradableManager, accountAgeWitnessService, navigation, priceUtil, offerFilterService, btcFormatter, coreApi);
    }

    @Override
    void saveSelectedCurrencyCodeInPreferences(OfferDirection direction, String code) {
        if (direction == OfferDirection.BUY) {
            preferences.setBuyScreenCurrencyCode(code);
        } else {
            preferences.setSellScreenCurrencyCode(code);
        }
    }

    @Override
    protected ObservableList<PaymentMethod> filterPaymentMethods(ObservableList<PaymentMethod> list,
                                                                 TradeCurrency selectedTradeCurrency) {
        return FXCollections.observableArrayList(list.stream()
                .filter(paymentMethod -> {
                    if (showAllTradeCurrenciesProperty.get()) {
                        return paymentMethod.isFiat();
                    }
                    return paymentMethod.isFiat() &&
                            PaymentAccountUtil.supportsCurrency(paymentMethod, selectedTradeCurrency);
                })
                .collect(Collectors.toList()));
    }

    @Override
    void fillCurrencies(ObservableList<TradeCurrency> tradeCurrencies,
                        ObservableList<TradeCurrency> allCurrencies) {
        // Used for ignoring filter (show all)
        tradeCurrencies.add(new CryptoCurrency(GUIUtil.SHOW_ALL_FLAG, ""));
        tradeCurrencies.addAll(preferences.getFiatCurrenciesAsObservable());
        tradeCurrencies.add(new CryptoCurrency(GUIUtil.EDIT_FLAG, ""));

        allCurrencies.add(new CryptoCurrency(GUIUtil.SHOW_ALL_FLAG, ""));
        allCurrencies.addAll(CurrencyUtil.getAllSortedFiatCurrencies());
        allCurrencies.add(new CryptoCurrency(GUIUtil.EDIT_FLAG, ""));
    }

    @Override
    Predicate<OfferBookListItem> getCurrencyAndMethodPredicate(OfferDirection direction,
                                                               TradeCurrency selectedTradeCurrency) {
        return offerBookListItem -> {
            Offer offer = offerBookListItem.getOffer();
            boolean directionResult = offer.getDirection() != direction;
            boolean currencyResult = (showAllTradeCurrenciesProperty.get() && offer.isFiatOffer()) ||
                    offer.getCurrencyCode().equals(selectedTradeCurrency.getCode());
            boolean paymentMethodResult = showAllPaymentMethods ||
                    offer.getPaymentMethod().equals(selectedPaymentMethod);
            boolean notMyOfferOrShowMyOffersActivated = !isMyOffer(offerBookListItem.getOffer()) || preferences.isShowOwnOffersInOfferBook();
            return directionResult && currencyResult && paymentMethodResult && notMyOfferOrShowMyOffersActivated;
        };
    }

    @Override
    TradeCurrency getDefaultTradeCurrency() {
        TradeCurrency defaultTradeCurrency = GlobalSettings.getDefaultTradeCurrency();

        if (CurrencyUtil.isFiatCurrency(defaultTradeCurrency.getCode()) && hasPaymentAccountForCurrency(defaultTradeCurrency)) {
            return defaultTradeCurrency;
        }

        ObservableList<TradeCurrency> tradeCurrencies = FXCollections.observableArrayList(getTradeCurrencies());
        if (!tradeCurrencies.isEmpty()) {
            // drop show all entry and select first currency with payment account available
            tradeCurrencies.remove(0);
            List<TradeCurrency> sortedList = tradeCurrencies.stream().sorted((o1, o2) ->
                    Boolean.compare(!hasPaymentAccountForCurrency(o1),
                            !hasPaymentAccountForCurrency(o2))).collect(Collectors.toList());
            return sortedList.get(0);
        } else {
            return CurrencyUtil.getMainFiatCurrencies().stream().sorted((o1, o2) ->
                    Boolean.compare(!hasPaymentAccountForCurrency(o1),
                            !hasPaymentAccountForCurrency(o2))).collect(Collectors.toList()).get(0);
        }
    }

    @Override
    String getCurrencyCodeFromPreferences(OfferDirection direction) {
        // validate if previous stored currencies are Fiat ones
        String currencyCode = direction == OfferDirection.BUY ? preferences.getBuyScreenCurrencyCode() : preferences.getSellScreenCurrencyCode();

        return CurrencyUtil.isFiatCurrency(currencyCode) ? currencyCode : null;
    }
}
