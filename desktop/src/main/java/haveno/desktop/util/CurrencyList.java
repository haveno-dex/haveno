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

    public void updateWithCurrencies(List<TradeCurrency> currencies, @Nullable List<CurrencyListItem> first) {
        List<CurrencyListItem> result = Lists.newLinkedList();
        Optional.ofNullable(first).ifPresent(result::addAll);
        result.addAll(getSortedItems(currencies));
        delegate.clear();
        delegate.addAll(result);
    }

    private List<CurrencyListItem> getSortedItems(List<TradeCurrency> currencies) {
        List<CurrencyListItem> items = new ArrayList<>();
        countTrades(currencies).forEach((currency, count) -> items.add(new CurrencyListItem(currency, count)));
        items.sort(getComparator());
        return items;
    }

    // Busiest markets first across all currency types; alphabetical keeps the fiat, crypto, other sections
    private Comparator<CurrencyListItem> getComparator() {
        Comparator<CurrencyListItem> byType = Comparator.comparingInt(this::getTypeRank);
        Comparator<CurrencyListItem> byName = Comparator.comparing(item -> predicates.isCryptoCurrency(item.tradeCurrency) ? item.tradeCurrency.getName() : item.tradeCurrency.getCode());
        if (preferences.isSortMarketCurrenciesNumerically()) {
            return Comparator.comparingInt((CurrencyListItem item) -> item.numTrades).reversed().thenComparing(byType).thenComparing(byName);
        } else {
            return byType.thenComparing(byName);
        }
    }

    private int getTypeRank(CurrencyListItem item) {
        if (predicates.isFiatCurrency(item.tradeCurrency)) return 0;
        if (predicates.isCryptoCurrency(item.tradeCurrency)) return 1;
        return 2;
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
