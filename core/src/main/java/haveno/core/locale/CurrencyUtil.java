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

package haveno.core.locale;

import com.google.common.base.Suppliers;
import haveno.asset.Asset;
import haveno.asset.AssetRegistry;
import haveno.asset.Coin;
import haveno.asset.Token;
import haveno.common.config.BaseCurrencyNetwork;
import haveno.common.config.Config;
import haveno.core.filter.FilterManager;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.String.format;

@Slf4j
public class CurrencyUtil {
    public static void setup() {
        setBaseCurrencyCode("XMR");
    }

    private static final AssetRegistry assetRegistry = new AssetRegistry();

    private static String baseCurrencyCode = "XMR";

    // Calls to isTraditionalCurrency and isCryptoCurrency are very frequent so we use a cache of the results.
    // The main improvement was already achieved with using memoize for the source maps, but
    // the caching still reduces performance costs by about 20% for isCryptoCurrency (1752 ms vs 2121 ms) and about 50%
    // for isTraditionalCurrency calls (1777 ms vs 3467 ms).
    // See: https://github.com/bisq-network/bisq/pull/4955#issuecomment-745302802
    private static final Map<String, Boolean> isTraditionalCurrencyMap = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> isCryptoCurrencyMap = new ConcurrentHashMap<>();

    private static final Supplier<Map<String, TraditionalCurrency>> traditionalCurrencyMapSupplier = Suppliers.memoize(
            CurrencyUtil::createTraditionalCurrencyMap);
    private static final Supplier<Map<String, CryptoCurrency>> cryptoCurrencyMapSupplier = Suppliers.memoize(
            CurrencyUtil::createCryptoCurrencyMap);

    public static void setBaseCurrencyCode(String baseCurrencyCode) {
        CurrencyUtil.baseCurrencyCode = baseCurrencyCode;
    }

    public static Collection<TraditionalCurrency> getAllSortedFiatCurrencies(Comparator comparator) {
        return getAllSortedTraditionalCurrencies(comparator).stream()
                .filter(currency -> CurrencyUtil.isFiatCurrency(currency.getCode()))
                .collect(Collectors.toList());  // sorted by currency name
    }

    public static List<TradeCurrency> getAllFiatCurrencies() {
        return getAllTraditionalCurrencies().stream()
                .filter(currency -> CurrencyUtil.isFiatCurrency(currency.getCode()))
                .collect(Collectors.toList());
    }

    public static List<TradeCurrency> getAllSortedFiatCurrencies() {
        return getAllSortedTraditionalCurrencies().stream()
                .filter(currency -> CurrencyUtil.isFiatCurrency(currency.getCode()))
                .collect(Collectors.toList());  // sorted by currency name
    }

    public static Collection<TraditionalCurrency> getAllSortedTraditionalCurrencies() {
        return traditionalCurrencyMapSupplier.get().values();  // sorted by currency name
    }

    public static List<TradeCurrency> getAllTraditionalCurrencies() {
        return new ArrayList<>(traditionalCurrencyMapSupplier.get().values());
    }

    public static Collection<TraditionalCurrency> getAllSortedTraditionalCurrencies(Comparator comparator) {
        return (List<TraditionalCurrency>) getAllSortedTraditionalCurrencies().stream()
                .sorted(comparator)                     // sorted by comparator param
                .collect(Collectors.toList());
    }

    private static Map<String, TraditionalCurrency> createTraditionalCurrencyMap() {
        List<TraditionalCurrency> currencies = CountryUtil.getAllCountries().stream()
                .map(country -> getCurrencyByCountryCode(country.code))
                .collect(Collectors.toList());
        for (String isoCode : nonFiatIsoCodes) currencies.add(new TraditionalCurrency(Currency.getInstance(isoCode)));
        return currencies.stream().sorted(TradeCurrency::compareTo)
                .distinct()
                .collect(Collectors.toMap(TradeCurrency::getCode, Function.identity(), (x, y) -> x, LinkedHashMap::new));
    }

    public static List<TraditionalCurrency> getMainFiatCurrencies() {
        List<TraditionalCurrency> list = new ArrayList<>();
        list.add(new TraditionalCurrency("USD"));
        list.add(new TraditionalCurrency("EUR"));
        list.add(new TraditionalCurrency("GBP"));
        list.add(new TraditionalCurrency("CAD"));
        list.add(new TraditionalCurrency("AUD"));
        list.add(new TraditionalCurrency("RUB"));
        list.add(new TraditionalCurrency("INR"));
        list.add(new TraditionalCurrency("NGN"));
        postProcessTraditionalCurrenciesList(list);
        return list;
    }

    public static List<TraditionalCurrency> getMainTraditionalCurrencies() {
        List<TraditionalCurrency> list = getMainFiatCurrencies();
        for (String isoCode : nonFiatIsoCodes) list.add(new TraditionalCurrency(isoCode));
        postProcessTraditionalCurrenciesList(list);
        return list;
    }

    private static List<String> nonFiatIsoCodes = Arrays.asList("XAG", "XAU");

    private static void postProcessTraditionalCurrenciesList(List<TraditionalCurrency> list) {
        list.sort(TradeCurrency::compareTo);

        TradeCurrency defaultTradeCurrency = getDefaultTradeCurrency();
        TraditionalCurrency defaultTraditionalCurrency =
                defaultTradeCurrency instanceof TraditionalCurrency ? (TraditionalCurrency) defaultTradeCurrency : null;
        if (defaultTraditionalCurrency != null && list.contains(defaultTraditionalCurrency)) {
            list.remove(defaultTradeCurrency);
            list.add(0, defaultTraditionalCurrency);
        }
    }

    public static Collection<CryptoCurrency> getAllSortedCryptoCurrencies() {
        return cryptoCurrencyMapSupplier.get().values();
    }

    private static Map<String, CryptoCurrency> createCryptoCurrencyMap() {
        return getSortedAssetStream()
                .map(CurrencyUtil::assetToCryptoCurrency)
                .collect(Collectors.toMap(TradeCurrency::getCode, Function.identity(), (x, y) -> x, LinkedHashMap::new));
    }

    public static Stream<Asset> getSortedAssetStream() {
        return assetRegistry.stream()
                .filter(CurrencyUtil::assetIsNotBaseCurrency)
                .filter(asset -> assetMatchesNetworkIfMainnet(asset, Config.baseCurrencyNetwork()))
                .sorted(Comparator.comparing(Asset::getName));
    }

    public static List<CryptoCurrency> getMainCryptoCurrencies() {
        final List<CryptoCurrency> result = new ArrayList<>();
        result.add(new CryptoCurrency("BTC", "Bitcoin"));
        result.add(new CryptoCurrency("BCH", "Bitcoin Cash"));
        result.add(new CryptoCurrency("ETH", "Ether"));
        result.add(new CryptoCurrency("LTC", "Litecoin"));
        result.sort(TradeCurrency::compareTo);
        return result;
    }

    public static List<CryptoCurrency> getRemovedCryptoCurrencies() {
        final List<CryptoCurrency> currencies = new ArrayList<>();
        currencies.add(new CryptoCurrency("BCHC", "Bitcoin Clashic"));
        currencies.add(new CryptoCurrency("ACH", "AchieveCoin"));
        currencies.add(new CryptoCurrency("SC", "Siacoin"));
        currencies.add(new CryptoCurrency("PPI", "PiedPiper Coin"));
        currencies.add(new CryptoCurrency("PEPECASH", "Pepe Cash"));
        currencies.add(new CryptoCurrency("GRC", "Gridcoin"));
        currencies.add(new CryptoCurrency("LTZ", "LitecoinZ"));
        currencies.add(new CryptoCurrency("ZOC", "01coin"));
        currencies.add(new CryptoCurrency("BURST", "Burstcoin"));
        currencies.add(new CryptoCurrency("STEEM", "Steem"));
        currencies.add(new CryptoCurrency("DAC", "DACash"));
        currencies.add(new CryptoCurrency("RDD", "ReddCoin"));
        return currencies;
    }

    public static List<TradeCurrency> getMatureMarketCurrencies() {
        ArrayList<TradeCurrency> currencies = new ArrayList<>(Arrays.asList(
                new TraditionalCurrency("EUR"),
                new TraditionalCurrency("USD"),
                new TraditionalCurrency("GBP"),
                new TraditionalCurrency("CAD"),
                new TraditionalCurrency("AUD"),
                new TraditionalCurrency("BRL")
        ));
        currencies.sort(Comparator.comparing(TradeCurrency::getCode));
        return currencies;
    }

    public static boolean isFiatCurrency(String currencyCode) {
        if (!isTraditionalCurrency(currencyCode)) return false;
        if ("xag".equalsIgnoreCase(currencyCode) || "xau".equalsIgnoreCase(currencyCode)) return false;
        return true;
    }

    public static boolean isTraditionalCurrency(String currencyCode) {
        if (currencyCode != null && isTraditionalCurrencyMap.containsKey(currencyCode)) {
            return isTraditionalCurrencyMap.get(currencyCode);
        }

        try {
            boolean isTraditionalCurrency = currencyCode != null
                    && !currencyCode.isEmpty()
                    && !isCryptoCurrency(currencyCode)
                    && Currency.getInstance(currencyCode) != null;

            if (currencyCode != null) {
                isTraditionalCurrencyMap.put(currencyCode, isTraditionalCurrency);
            }

            return isTraditionalCurrency;
        } catch (Throwable t) {
            isTraditionalCurrencyMap.put(currencyCode, false);
            return false;
        }
    }

    public static Optional<TraditionalCurrency> getTraditionalCurrency(String currencyCode) {
        return Optional.ofNullable(traditionalCurrencyMapSupplier.get().get(currencyCode));
    }

    /**
     * We return true if it is BTC or any of our currencies available in the assetRegistry.
     * For removed assets it would fail as they are not found but we don't want to conclude that they are traditional then.
     * As the caller might not deal with the case that a currency can be neither a cryptoCurrency nor Traditional if not found
     * we return true as well in case we have no traditional currency for the code.
     *
     * As we use a boolean result for isCryptoCurrency and isTraditionalCurrency we do not treat missing currencies correctly.
     * To throw an exception might be an option but that will require quite a lot of code change, so we don't do that
     * for the moment, but could be considered for the future. Another maybe better option is to introduce an enum which
     * contains 3 entries (CryptoCurrency, Traditional, Undefined).
     */
    public static boolean isCryptoCurrency(String currencyCode) {
        if (currencyCode != null) currencyCode = currencyCode.toUpperCase();
        if (currencyCode != null && isCryptoCurrencyMap.containsKey(currencyCode.toUpperCase())) {
            return isCryptoCurrencyMap.get(currencyCode.toUpperCase());
        }

        boolean isCryptoCurrency;
        if (currencyCode == null) {
            // Some tests call that method with null values. Should be fixed in the tests but to not break them return false.
            isCryptoCurrency = false;
        } else if (getCryptoCurrency(currencyCode).isPresent()) {
            // If we find the code in our assetRegistry we return true.
            // It might be that an asset was removed from the assetsRegistry, we deal with such cases below by checking if
            // it is a traditional currency
            isCryptoCurrency = true;
        } else if (getTraditionalCurrency(currencyCode).isEmpty()) {
            // In case the code is from a removed asset we cross check if there exist a traditional currency with that code,
            // if we don't find a traditional currency we treat it as a crypto currency.
            isCryptoCurrency = true;
        } else {
            // If we would have found a traditional currency we return false
            isCryptoCurrency = false;
        }

        if (currencyCode != null) {
            isCryptoCurrencyMap.put(currencyCode, isCryptoCurrency);
        }

        return isCryptoCurrency;
    }

    public static Optional<CryptoCurrency> getCryptoCurrency(String currencyCode) {
        return Optional.ofNullable(cryptoCurrencyMapSupplier.get().get(currencyCode));
    }

    public static Optional<TradeCurrency> getTradeCurrency(String currencyCode) {
        Optional<TraditionalCurrency> traditionalCurrencyOptional = getTraditionalCurrency(currencyCode);
        if (traditionalCurrencyOptional.isPresent() && isTraditionalCurrency(currencyCode))
            return Optional.of(traditionalCurrencyOptional.get());

        Optional<CryptoCurrency> cryptoCurrencyOptional = getCryptoCurrency(currencyCode);
        if (cryptoCurrencyOptional.isPresent() && isCryptoCurrency(currencyCode))
            return Optional.of(cryptoCurrencyOptional.get());

        return Optional.empty();
    }

    public static Optional<List<TradeCurrency>> getTradeCurrencies(List<String> currencyCodes) {
        List<TradeCurrency> tradeCurrencies = new ArrayList<>();
        currencyCodes.stream().forEachOrdered(c ->
                tradeCurrencies.add(getTradeCurrency(c).orElseThrow(() ->
                        new IllegalArgumentException(format("%s is not a valid trade currency code", c)))));
        return tradeCurrencies.isEmpty()
                ? Optional.empty()
                : Optional.of(tradeCurrencies);
    }

    public static Optional<List<TradeCurrency>> getTradeCurrenciesInList(List<String> currencyCodes,
                                                                         List<TradeCurrency> validCurrencies) {
        Optional<List<TradeCurrency>> tradeCurrencies = getTradeCurrencies(currencyCodes);
        Consumer<List<TradeCurrency>> validateCandidateCurrencies = (list) -> {
            for (TradeCurrency tradeCurrency : list) {
                if (!validCurrencies.contains(tradeCurrency)) {
                    throw new IllegalArgumentException(
                            format("%s is not a member of valid currencies list",
                                    tradeCurrency.getCode()));
                }
            }
        };
        tradeCurrencies.ifPresent(validateCandidateCurrencies);
        return tradeCurrencies;
    }

    public static TraditionalCurrency getCurrencyByCountryCode(String countryCode) {
        if (countryCode.equals("XK"))
            return new TraditionalCurrency("EUR");

        Currency currency = Currency.getInstance(new Locale(LanguageUtil.getDefaultLanguage(), countryCode));
        return new TraditionalCurrency(currency.getCurrencyCode());
    }


    public static String getNameByCode(String currencyCode) {
        if (isCryptoCurrency(currencyCode)) {
            // We might not find the name in case we have a call for a removed asset.
            // If BTC is the code (used in tests) we also want return Bitcoin as name.
            final Optional<CryptoCurrency> removedCryptoCurrency = getRemovedCryptoCurrencies().stream()
                    .filter(cryptoCurrency -> cryptoCurrency.getCode().equals(currencyCode))
                    .findAny();

            String xmrOrRemovedAsset = "XMR".equals(currencyCode) ? "Monero" :
                removedCryptoCurrency.isPresent() ? removedCryptoCurrency.get().getName() : Res.get("shared.na");
            return getCryptoCurrency(currencyCode).map(TradeCurrency::getName).orElse(xmrOrRemovedAsset);
        }
        try {
            return Currency.getInstance(currencyCode).getDisplayName();
        } catch (Throwable t) {
            log.debug("No currency name available {}", t.getMessage());
            return currencyCode;
        }
    }

    public static Optional<CryptoCurrency> findCryptoCurrencyByName(String currencyName) {
        return getAllSortedCryptoCurrencies().stream()
                .filter(e -> e.getName().equals(currencyName))
                .findAny();
    }

    public static String getNameAndCode(String currencyCode) {
        return getNameByCode(currencyCode) + " (" + currencyCode + ")";
    }

    public static TradeCurrency getDefaultTradeCurrency() {
        return GlobalSettings.getDefaultTradeCurrency();
    }

    private static boolean assetIsNotBaseCurrency(Asset asset) {
        return !assetMatchesCurrencyCode(asset, baseCurrencyCode);
    }

    // TODO We handle assets of other types (Token, ERC20) as matching the network which is not correct.
    // We should add support for network property in those tokens as well.
    public static boolean assetMatchesNetwork(Asset asset, BaseCurrencyNetwork baseCurrencyNetwork) {
        return !(asset instanceof Coin) ||
                ((Coin) asset).getNetwork().name().equals(baseCurrencyNetwork.getNetwork());
    }

    // We only check for coins not other types of assets (TODO network check should be supported for all assets)
    public static boolean assetMatchesNetworkIfMainnet(Asset asset, BaseCurrencyNetwork baseCurrencyNetwork) {
        return !(asset instanceof Coin) ||
                coinMatchesNetworkIfMainnet((Coin) asset, baseCurrencyNetwork);
    }

    // We want all coins available also in testnet or regtest for testing purpose
    public static boolean coinMatchesNetworkIfMainnet(Coin coin, BaseCurrencyNetwork baseCurrencyNetwork) {
        boolean matchesNetwork = assetMatchesNetwork(coin, baseCurrencyNetwork);
        return !baseCurrencyNetwork.isMainnet() || matchesNetwork;
    }

    private static CryptoCurrency assetToCryptoCurrency(Asset asset) {
        return new CryptoCurrency(asset.getTickerSymbol(), asset.getName(), asset instanceof Token);
    }

    public static boolean assetMatchesCurrencyCode(Asset asset, String currencyCode) {
        return currencyCode.equals(asset.getTickerSymbol());
    }

    public static Optional<Asset> findAsset(AssetRegistry assetRegistry, String currencyCode,
                                            BaseCurrencyNetwork baseCurrencyNetwork) {
        List<Asset> assets = assetRegistry.stream()
                .filter(asset -> assetMatchesCurrencyCode(asset, currencyCode)).collect(Collectors.toList());

        // If we don't have the ticker symbol we throw an exception
        if (assets.stream().findFirst().isEmpty())
            return Optional.empty();

        // We check for exact match with network, e.g. BTC$TESTNET
        Optional<Asset> optionalAssetMatchesNetwork = assets.stream()
                .filter(asset -> assetMatchesNetwork(asset, baseCurrencyNetwork))
                .findFirst();
        if (optionalAssetMatchesNetwork.isPresent())
            return optionalAssetMatchesNetwork;

        // In testnet or regtest we want to show all coins as well. Most coins have only Mainnet defined so we deliver
        // that if no exact match was found in previous step
        if (!baseCurrencyNetwork.isMainnet()) {
            Optional<Asset> optionalAsset = assets.stream().findFirst();
            return optionalAsset;
        }

        // If we are in mainnet we need have a mainnet asset defined.
        throw new IllegalArgumentException("We are on mainnet and we could not find an asset with network type mainnet");
    }

    public static Optional<Asset> findAsset(String tickerSymbol) {
        return assetRegistry.stream()
                .filter(asset -> asset.getTickerSymbol().equals(tickerSymbol))
                .findAny();
    }

    public static Optional<Asset> findAsset(String tickerSymbol, BaseCurrencyNetwork baseCurrencyNetwork) {
        return assetRegistry.stream()
                .filter(asset -> asset.getTickerSymbol().equals(tickerSymbol))
                .filter(asset -> assetMatchesNetwork(asset, baseCurrencyNetwork))
                .findAny();
    }

    // Excludes all assets which got removed by voting
    public static List<CryptoCurrency> getActiveSortedCryptoCurrencies(FilterManager filterManager) {
        return getAllSortedCryptoCurrencies().stream()
                .filter(e -> !filterManager.isCurrencyBanned(e.getCode()))
                .collect(Collectors.toList());
    }

    public static String getCurrencyPair(String currencyCode) {
        if (isTraditionalCurrency(currencyCode))
            return Res.getBaseCurrencyCode() + "/" + currencyCode;
        else
            return currencyCode + "/" + Res.getBaseCurrencyCode();
    }

    public static String getCounterCurrency(String currencyCode) {
        if (isTraditionalCurrency(currencyCode))
            return currencyCode;
        else
            return Res.getBaseCurrencyCode();
    }

    public static String getPriceWithCurrencyCode(String currencyCode) {
        return getPriceWithCurrencyCode(currencyCode, "shared.priceInCurForCur");
    }

    public static String getPriceWithCurrencyCode(String currencyCode, String translationKey) {
        if (isCryptoCurrency(currencyCode))
            return Res.get(translationKey, Res.getBaseCurrencyCode(), currencyCode);
        else
            return Res.get(translationKey, currencyCode, Res.getBaseCurrencyCode());
    }

    public static String getOfferVolumeCode(String currencyCode) {
        return Res.get("shared.offerVolumeCode", currencyCode);
    }

    public static boolean apiSupportsCryptoCurrency(String currencyCode) {
        // Although this method is only used by the core.api package, its
        // presence here avoids creating a new util class just for this method.
        if (isCryptoCurrency(currencyCode))
            return currencyCode.equals("BTC")
                    || currencyCode.equals("XMR");
        else
            throw new IllegalArgumentException(
                    format("Method requires a crypto currency code, but was given '%s'.",
                            currencyCode));
    }

    public static List<TradeCurrency> getAllTransferwiseUSDCurrencies() {
        return List.of(new TraditionalCurrency("USD"));
    }
}
