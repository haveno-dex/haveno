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

package haveno.desktop.util;

import com.google.common.collect.Lists;
import haveno.core.locale.TradeCurrency;
import haveno.core.user.Preferences;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

public class CurrencyList {
    private final CurrencyPredicates predicates;
    private final Preferences preferences;
    private final List<CurrencyListItem> delegate;

    public CurrencyList(Preferences preferences) {
        this(new ArrayList<>(), preferences, new CurrencyPredicates());
    }

    public CurrencyList(List<CurrencyListItem> delegate, Preferences preferences, CurrencyPredicates predicates) {
        this.delegate = delegate;
        this.predicates = predicates;
        this.preferences = preferences;
    }

    public ObservableList<CurrencyListItem> getObservableList() {
        return FXCollections.observableList(delegate);
    }

    public void updateWithCurrencies(List<TradeCurrency> currencies, @Nullable CurrencyListItem first) {
        List<CurrencyListItem> result = Lists.newLinkedList();
        Optional.ofNullable(first).ifPresent(result::add);
        result.addAll(getPartitionedSortedItems(currencies));
        delegate.clear();
        delegate.addAll(result);
    }

    private List<CurrencyListItem> getPartitionedSortedItems(List<TradeCurrency> currencies) {
        Map<TradeCurrency, Integer> tradesPerCurrency = countTrades(currencies);
        List<CurrencyListItem> traditionalCurrencies = new ArrayList<>();
        List<CurrencyListItem> cryptoCurrencies = new ArrayList<>();

        for (Map.Entry<TradeCurrency, Integer> entry : tradesPerCurrency.entrySet()) {
            TradeCurrency currency = entry.getKey();
            Integer count = entry.getValue();
            CurrencyListItem item = new CurrencyListItem(currency, count);

            if (predicates.isTraditionalCurrency(currency)) {
                traditionalCurrencies.add(item);
            }

            if (predicates.isCryptoCurrency(currency)) {
                cryptoCurrencies.add(item);
            }
        }

        Comparator<CurrencyListItem> comparator = getComparator();
        traditionalCurrencies.sort(comparator);
        cryptoCurrencies.sort(comparator);

        List<CurrencyListItem> result = new ArrayList<>();
        result.addAll(traditionalCurrencies);
        result.addAll(cryptoCurrencies);

        return result;
    }

    private Comparator<CurrencyListItem> getComparator() {
        Comparator<CurrencyListItem> result;
        if (preferences.isSortMarketCurrenciesNumerically()) {
            Comparator<CurrencyListItem> byCount = Comparator.comparingInt(left -> left.numTrades);
            result = byCount.reversed();
        } else {
            result = Comparator.comparing(item -> item.tradeCurrency);
        }
        return result;
    }

    private Map<TradeCurrency, Integer> countTrades(List<TradeCurrency> currencies) {
        Map<TradeCurrency, Integer> result = new HashMap<>();

        BiFunction<TradeCurrency, Integer, Integer> incrementCurrentOrOne =
                (key, value) -> value == null ? 1 : value + 1;
        currencies.forEach(currency -> result.compute(currency, incrementCurrentOrOne));

        Set<TradeCurrency> preferred = new HashSet<>();
        preferred.addAll(preferences.getTraditionalCurrencies());
        preferred.addAll(preferences.getCryptoCurrencies());
        preferred.forEach(currency -> result.putIfAbsent(currency, 0));

        return result;
    }
}
