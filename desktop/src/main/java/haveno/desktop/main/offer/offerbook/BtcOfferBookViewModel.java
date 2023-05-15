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

package haveno.desktop.main.offer.offerbook;

import com.google.inject.Inject;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.api.CoreApi;
import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.GlobalSettings;
import haveno.core.locale.TradeCurrency;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferFilterService;
import haveno.core.offer.OpenOfferManager;
import haveno.core.payment.PaymentAccountUtil;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.ClosedTradableManager;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.util.FormattingUtils;
import haveno.core.util.PriceUtil;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.xmr.setup.WalletsSetup;
import haveno.desktop.Navigation;
import haveno.desktop.util.GUIUtil;
import haveno.network.p2p.P2PService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import javax.inject.Named;
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
                        return paymentMethod.isTraditional();
                    }
                    return paymentMethod.isTraditional() &&
                            PaymentAccountUtil.supportsCurrency(paymentMethod, selectedTradeCurrency);
                })
                .collect(Collectors.toList()));
    }

    @Override
    void fillCurrencies(ObservableList<TradeCurrency> tradeCurrencies,
                        ObservableList<TradeCurrency> allCurrencies) {
        // Used for ignoring filter (show all)
        tradeCurrencies.add(new CryptoCurrency(GUIUtil.SHOW_ALL_FLAG, ""));
        tradeCurrencies.addAll(preferences.getTraditionalCurrenciesAsObservable());
        tradeCurrencies.add(new CryptoCurrency(GUIUtil.EDIT_FLAG, ""));

        allCurrencies.add(new CryptoCurrency(GUIUtil.SHOW_ALL_FLAG, ""));
        allCurrencies.addAll(CurrencyUtil.getAllSortedTraditionalCurrencies());
        allCurrencies.add(new CryptoCurrency(GUIUtil.EDIT_FLAG, ""));
    }

    @Override
    Predicate<OfferBookListItem> getCurrencyAndMethodPredicate(OfferDirection direction,
                                                               TradeCurrency selectedTradeCurrency) {
        return offerBookListItem -> {
            Offer offer = offerBookListItem.getOffer();
            boolean directionResult = offer.getDirection() != direction;
            boolean currencyResult = (showAllTradeCurrenciesProperty.get() && offer.isTraditionalOffer()) ||
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

        if (CurrencyUtil.isTraditionalCurrency(defaultTradeCurrency.getCode()) && hasPaymentAccountForCurrency(defaultTradeCurrency)) {
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
            return CurrencyUtil.getMainTraditionalCurrencies().stream().sorted((o1, o2) ->
                    Boolean.compare(!hasPaymentAccountForCurrency(o1),
                            !hasPaymentAccountForCurrency(o2))).collect(Collectors.toList()).get(0);
        }
    }

    @Override
    String getCurrencyCodeFromPreferences(OfferDirection direction) {
        // validate if previous stored currencies are Fiat ones
        String currencyCode = direction == OfferDirection.BUY ? preferences.getBuyScreenCurrencyCode() : preferences.getSellScreenCurrencyCode();

        return CurrencyUtil.isTraditionalCurrency(currencyCode) ? currencyCode : null;
    }
}
