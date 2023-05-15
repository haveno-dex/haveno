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

package haveno.desktop;

import haveno.core.locale.CryptoCurrency;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.TraditionalCurrency;

import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

public class MarketsPrintTool {

    public static void main(String[] args) {
        // Prints out all coins in the format used in the market_currency_selector.html.
        // Run that and copy paste the result to the market_currency_selector.html at new releases.
        StringBuilder sb = new StringBuilder();
        Locale.setDefault(new Locale("en", "US"));

        // <option value="onion_xmr">DeepOnion (ONION)</option>
        // <option value="xmr_bhd">Bahraini Dinar (BHD)</option>

        final Collection<TraditionalCurrency> allSortedTraditionalCurrencies = CurrencyUtil.getAllSortedTraditionalCurrencies();
        final Stream<MarketCurrency> traditionalStream = allSortedTraditionalCurrencies.stream()
                .filter(e -> !e.getCurrency().getCurrencyCode().equals("XMR")) // TODO (woodser): update to XMR
                .map(e -> new MarketCurrency("xmr_" + e.getCode().toLowerCase(), e.getName(), e.getCode()))
                .distinct();

        final Collection<CryptoCurrency> allSortedCryptoCurrencies = CurrencyUtil.getAllSortedCryptoCurrencies();
        final Stream<MarketCurrency> cryptoStream = allSortedCryptoCurrencies.stream()
                .filter(e -> !e.getCode().equals("XMR"))
                .map(e -> new MarketCurrency(e.getCode().toLowerCase() + "_xmr", e.getName(), e.getCode()))
                .distinct();

        Stream.concat(traditionalStream, cryptoStream)
                .sorted(Comparator.comparing(o -> o.currencyName.toLowerCase()))
                .distinct()
                .forEach(e -> sb.append("<option value=\"")
                        .append(e.marketSelector)
                        .append("\">")
                        .append(e.currencyName)
                        .append(" (")
                        .append(e.currencyCode)
                        .append(")</option>")
                        .append("\n"));
        System.out.println(sb.toString());
    }

    private static class MarketCurrency {
        final String marketSelector;
        final String currencyName;
        final String currencyCode;

        MarketCurrency(String marketSelector, String currencyName, String currencyCode) {
            this.marketSelector = marketSelector;
            this.currencyName = currencyName;
            this.currencyCode = currencyCode;
        }
    }
}
