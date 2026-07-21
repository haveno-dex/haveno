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

package haveno.core.provider.price;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.inject.Inject;

import haveno.common.ThreadUtils;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.handlers.FaultHandler;
import haveno.common.util.MathUtils;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.TradeCurrency;
import haveno.core.monetary.CryptoMoney;
import haveno.core.monetary.Price;
import haveno.core.monetary.TraditionalMoney;
import haveno.core.provider.PriceHttpClient;
import haveno.core.provider.ProvidersRepository;
import haveno.core.trade.statistics.TradeStatistics3;
import haveno.core.user.Preferences;
import haveno.network.http.HttpClient;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class PriceFeedService {
    private final HttpClient httpClient;
    private final ProvidersRepository providersRepository;
    private final Preferences preferences;

    private static final long PERIOD_SEC = 60;

    private final Map<String, MarketPrice> cache = new HashMap<>();
    private final Map<String, Date> latestHavenoMarketPriceDateByCurrencyCode = new HashMap<>();
    private volatile PriceProvider priceProvider;
    @Nullable
    private Consumer<Double> priceConsumer;
    @Nullable
    private FaultHandler faultHandler;
    private String currencyCode;
    private final StringProperty currencyCodeProperty = new SimpleStringProperty();
    private final IntegerProperty updateCounter = new SimpleIntegerProperty(0);
    private long epochInMillisAtLastRequest;
    private long retryDelay = 0;
    private long requestTs;
    @Nullable
    private volatile String baseUrlOfRespondingProvider; // cleared per request to detect a missing response
    @Nullable
    private volatile String baseUrlOfLastRespondingProvider; // the provider actually serving prices
    @Nullable
    private volatile Timer requestTimer;
    @Nullable
    private Timer retryTimer;
    @Nullable
    private PriceRequest priceRequest;
    private final AtomicBoolean requestInProgress = new AtomicBoolean();
    private volatile boolean shutDownRequested;
    private String requestAllPricesError = null;
    private static final String THREAD_ID = PriceFeedService.class.getSimpleName();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public PriceFeedService(PriceHttpClient httpClient,
                            @SuppressWarnings("SameParameterValue") ProvidersRepository providersRepository,
                            @SuppressWarnings("SameParameterValue") Preferences preferences) {
        this.httpClient = httpClient;
        this.providersRepository = providersRepository;
        this.preferences = preferences;

        // Do not use Guice for PriceProvider as we might create multiple instances
        this.priceProvider = new PriceProvider(httpClient, providersRepository.getBaseUrl());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void shutDown() {
        log.info("Shutting down {}", getClass().getSimpleName());
        shutDownRequested = true;
        try {
            ThreadUtils.await(() -> {
                if (requestTimer != null) {
                    requestTimer.stop();
                    requestTimer = null;
                }
                if (retryTimer != null) {
                    retryTimer.stop();
                    retryTimer = null;
                }
                cancelRequest();
            }, THREAD_ID);
        } catch (Exception e) {
            log.warn("Error shutting down {}: {}", getClass().getSimpleName(), e.getMessage());
        }
    }

    public void setCurrencyCodeOnInit() {
        if (getCurrencyCode() == null) {
            TradeCurrency preferredTradeCurrency = preferences.getPreferredTradeCurrency();
            String code = preferredTradeCurrency != null ? preferredTradeCurrency.getCode() : "USD";
            setCurrencyCode(code);
        }
    }

    public void requestPrices() {
        request(false);
    }

    /**
     * Awaits prices to be available, but does not request them.
     */
    public void awaitExternalPrices() {
        CountDownLatch latch = new CountDownLatch(1);
        ChangeListener<? super Number> listener = (observable, oldValue, newValue) -> { 
            if (hasExternalPrices()) UserThread.execute(() -> latch.countDown());
        };
        UserThread.execute(() -> updateCounter.addListener(listener));
        if (hasExternalPrices()) UserThread.execute(() -> latch.countDown());
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            UserThread.execute(() -> updateCounter.removeListener(listener));
        }
    }

    public boolean hasExternalPrices() {
        synchronized (cache) {
            return cache.values().stream().anyMatch(MarketPrice::isExternallyProvidedPrice);
        }
    }

    public void startRequestingPrices() {
        if (requestTimer == null) request(true); // ignore if already repeat requesting
    }

    public void startRequestingPrices(Consumer<Double> resultHandler, FaultHandler faultHandler) {
        this.priceConsumer = resultHandler;
        this.faultHandler = faultHandler;
        startRequestingPrices();
    }

    // the provider serving prices, falling back to the selected one until a request succeeds
    public String getProviderNodeAddress() {
        String respondingBaseUrl = baseUrlOfLastRespondingProvider;
        return respondingBaseUrl != null ? respondingBaseUrl : httpClient.getBaseUrl();
    }

    // serialize request handling and provider rotation on THREAD_ID
    private void request(boolean repeatRequests) {
        ThreadUtils.execute(() -> doRequest(repeatRequests), THREAD_ID);
    }

    private void doRequest(boolean repeatRequests) {
        if (shutDownRequested) return;

        // a repeating request supersedes any pending retry; a one-off must not cancel the feed's only scheduler
        if (repeatRequests && retryTimer != null) {
            retryTimer.stop();
            retryTimer = null;
        }

        if (requestTs == 0)
            log.debug("request from provider {}",
                    providersRepository.getBaseUrl());
        else
            log.debug("request from provider {} {} sec. after last request",
                    providersRepository.getBaseUrl(),
                    (System.currentTimeMillis() - requestTs) / 1000d);

        requestTs = System.currentTimeMillis();

        PriceProvider provider = priceProvider;
        requestAllPrices(provider, () -> {
            // At applyPriceToConsumer we also check if price is not exceeding max. age for price data.
            boolean success = applyPriceToConsumer();
            if (success) {
                ThreadUtils.execute(() -> retryDelay = 0, THREAD_ID); // reset backoff; retryDelay is accessed on THREAD_ID
                MarketPrice marketPrice;
                synchronized (cache) {
                    marketPrice = cache.get(currencyCode);
                }
                if (marketPrice != null)
                    log.debug("Received new {} from provider {} after {} sec.",
                            marketPrice,
                            baseUrlOfRespondingProvider,
                            (System.currentTimeMillis() - requestTs) / 1000d);
                else
                    log.debug("Received new data from provider {} after {} sec. " +
                                    "Requested market price for currency {} was not provided. " +
                                    "That is expected if currency is not listed at provider.",
                            baseUrlOfRespondingProvider,
                            (System.currentTimeMillis() - requestTs) / 1000d,
                            currencyCode);
            } else {
                log.warn("applyPriceToConsumer was not successful. We retry with a new provider.");
                retryWithNewProvider("price could not be applied");
            }
        }, (errorMessage, throwable) -> {
            if (throwable instanceof PriceRequestException) {
                String baseUrlOfFaultyRequest = ((PriceRequestException) throwable).priceProviderBaseUrl;
                String baseUrlOfCurrentRequest = priceProvider.getBaseUrl();
                if (baseUrlOfCurrentRequest.equals(baseUrlOfFaultyRequest)) {
                    log.debug("We received an error requesting prices: baseUrlOfFaultyRequest={}, error={}",
                            baseUrlOfFaultyRequest, throwable.toString());
                    retryWithNewProvider(throwable.toString());
                } else {
                    log.debug("We received an error from an earlier request. We have started a new request already so we ignore that error. " +
                                    "baseUrlOfCurrentRequest={}, baseUrlOfFaultyRequest={}",
                            baseUrlOfCurrentRequest, baseUrlOfFaultyRequest);
                }
            } else {
                log.warn("We received an error with throwable={}", throwable.toString());
                retryWithNewProvider(throwable.toString());
            }

            if (faultHandler != null)
                faultHandler.handleFault(errorMessage, throwable);
        });

        if (repeatRequests) {
            if (requestTimer != null)
                requestTimer.stop();

            long delay = PERIOD_SEC + new Random().nextInt(5);
            PriceRequest pendingRequest = priceRequest;
            requestTimer = UserThread.runAfter(() -> {
                ThreadUtils.execute(() -> {
                    if (shutDownRequested) return;
                    if (retryTimer != null) return; // a pending retry owns the next request
                    // rotate only if this request is still the outstanding one and went unanswered
                    if (priceRequest == pendingRequest && baseUrlOfRespondingProvider == null) {
                        log.warn("We did not receive a response from provider {}", priceProvider.getBaseUrl());
                        doRetryWithNewProvider("no response from provider"); // rotate and back off, as for a failed request
                    } else {
                        doRequest(true);
                    }
                }, THREAD_ID);
            }, delay);
        }
    }

    private void retryWithNewProvider(String error) {
        ThreadUtils.execute(() -> doRetryWithNewProvider(error), THREAD_ID);
    }

    private void doRetryWithNewProvider(String error) {
        if (shutDownRequested) return;

        // only keep one pending retry
        if (retryTimer != null) {
            retryTimer.stop();
            retryTimer = null;
        }

        long thisRetryDelay = 0;
        String oldBaseUrl = priceProvider.getBaseUrl();
        boolean looped = setNewPriceProvider();
        if (looped) {
            retryDelay = Math.min(retryDelay + 5, PERIOD_SEC); // escalate until a request succeeds
            thisRetryDelay = retryDelay;
            log.warn("Exhausted price provider list, retrying in {} sec. Last error: {}", thisRetryDelay, error);
        }
        log.debug("We received an error at the request from provider {}. " +
                "We select the new provider {} and use that for a new request in {} sec.", oldBaseUrl, priceProvider.getBaseUrl(), thisRetryDelay);
        if (thisRetryDelay > 0) {
            retryTimer = UserThread.runAfter(() -> {
                request(true);
            }, thisRetryDelay);
        } else {
            doRequest(true);
        }
    }

    // returns true if the provider list is exhausted
    private boolean setNewPriceProvider() {
        cancelRequest();
        boolean looped = providersRepository.selectNextProviderBaseUrl();
        if (!providersRepository.getBaseUrl().isEmpty()) {
            priceProvider = new PriceProvider(httpClient, providersRepository.getBaseUrl());
        } else {
            log.warn("We cannot create a new priceProvider because new base url is empty.");
            looped = true; // no provider to rotate to, so back off
        }
        return looped;
    }

    // cancels the request in flight, if any; runs on THREAD_ID
    private void cancelRequest() {
        if (priceRequest != null) {
            priceRequest.shutDown();
            priceRequest = null;
        }
        httpClient.cancelPendingRequest();
        requestInProgress.set(false);
    }

    @Nullable
    public MarketPrice getMarketPrice(String currencyCode) {
        synchronized (cache) {
            return cache.getOrDefault(CurrencyUtil.getCurrencyCodeBase(currencyCode), null);
        }
    }

    private void setHavenoMarketPrice(String counterCurrencyCode, Price price) {
        UserThread.execute(() -> {
            String counterCurrencyCodeBase = CurrencyUtil.getCurrencyCodeBase(counterCurrencyCode);
            synchronized (cache) {
                if (!cache.containsKey(counterCurrencyCodeBase) || !cache.get(counterCurrencyCodeBase).isExternallyProvidedPrice()) {
                    cache.put(counterCurrencyCodeBase, new MarketPrice(counterCurrencyCodeBase,
                            MathUtils.scaleDownByPowerOf10(price.getValue(), CurrencyUtil.isCryptoCurrency(counterCurrencyCode) ? CryptoMoney.SMALLEST_UNIT_EXPONENT : TraditionalMoney.SMALLEST_UNIT_EXPONENT),
                            0,
                            false));
                }
                updateCounter.set(updateCounter.get() + 1);
            }
        });
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Setter
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void setCurrencyCode(String currencyCode) {
        UserThread.await(() -> {
            if (this.currencyCode == null || !this.currencyCode.equals(currencyCode)) {
                this.currencyCode = currencyCode;
                currencyCodeProperty.set(currencyCode);
                if (priceConsumer != null) applyPriceToConsumer();
            }
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getter
    ///////////////////////////////////////////////////////////////////////////////////////////

    public String getCurrencyCode() {
        return currencyCode;
    }

    public StringProperty currencyCodeProperty() {
        return currencyCodeProperty;
    }

    public ReadOnlyIntegerProperty updateCounterProperty() {
        return updateCounter;
    }

    public Date getLastRequestTimeStamp() {
        return new Date(epochInMillisAtLastRequest);
    }

    public void applyLatestHavenoMarketPrice(List<TradeStatistics3> tradeStatisticsList) {
        Map<String, TradeStatistics3> latestByCurrencyCode = new HashMap<>();
        tradeStatisticsList.forEach(e -> latestByCurrencyCode.merge(e.getCurrency(), e,
                (oldValue, newValue) -> newValue.getDate().before(oldValue.getDate()) ? oldValue : newValue));
        latestByCurrencyCode.values().forEach(this::applyHavenoMarketPrice);
    }

    // Applies a single trade statistic without scanning the full list. Ignored if we already applied a
    // more recent trade for that currency.
    public void applyHavenoMarketPrice(TradeStatistics3 tradeStatistics) {
        String currencyCode = tradeStatistics.getCurrency();
        synchronized (latestHavenoMarketPriceDateByCurrencyCode) {
            Date latestDate = latestHavenoMarketPriceDateByCurrencyCode.get(currencyCode);
            if (latestDate != null && tradeStatistics.getDate().before(latestDate)) {
                return;
            }
            latestHavenoMarketPriceDateByCurrencyCode.put(currencyCode, tradeStatistics.getDate());

            // Keep the price update inside the lock so concurrent applies enqueue their cache updates in date order.
            setHavenoMarketPrice(currencyCode, tradeStatistics.getTradePrice());
        }
    }

    /**
     * Returns prices for all available currencies. The base currency is always XMR.
     *
     * TODO: instrument requestPrices() result and fault handlers instead of using CountDownLatch and timeout
     */
    public synchronized Map<String, MarketPrice> requestAllPrices() throws ExecutionException, InterruptedException, TimeoutException, CancellationException {
        CountDownLatch latch = new CountDownLatch(1);
        ChangeListener<? super Number> listener = (observable, oldValue, newValue) -> latch.countDown();
        UserThread.execute(() -> updateCounter.addListener(listener));
        requestAllPricesError = null;
        requestPrices();
        UserThread.runAfter(() -> {
            if (latch.getCount() > 0) requestAllPricesError = "Timeout fetching market prices within 30 seconds";
            UserThread.execute(() -> latch.countDown());
        }, 30);
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            UserThread.execute(() -> updateCounter.removeListener(listener));
        }
        if (requestAllPricesError != null) throw new RuntimeException(requestAllPricesError);
        return cache;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private boolean applyPriceToConsumer() {
        boolean result = false;
        String errorMessage = null;
        if (currencyCode != null) {
            String baseUrl = priceProvider.getBaseUrl();
            if (cache.containsKey(currencyCode)) {
                try {
                    MarketPrice marketPrice = cache.get(currencyCode);
                    if (marketPrice.isExternallyProvidedPrice()) {
                        if (marketPrice.isRecentPriceAvailable()) {
                            if (priceConsumer != null)
                                priceConsumer.accept(marketPrice.getPrice());
                            result = true;
                        } else {
                            errorMessage = "Price for currency " + currencyCode + " is outdated by " +
                                    (Instant.now().toEpochMilli() - marketPrice.getTimestampMs()) / 1000 / 60 + " minutes. " +
                                    "Max. allowed age of price is " + MarketPrice.MARKET_PRICE_MAX_AGE_MS / 1000 / 60 + " minutes. " +
                                    "priceProvider=" + baseUrl + ". " +
                                    "marketPrice= " + marketPrice;
                        }
                    } else {
                        if (baseUrlOfRespondingProvider == null)
                            log.debug("Market price for currency " + currencyCode + " was not delivered by provider " +
                                    baseUrl + ". That is expected at startup.");
                        else
                            log.debug("Market price for currency " + currencyCode + " is not provided by the provider " +
                                    baseUrl + ". That is expected for currencies not listed at providers.");
                        result = true;
                    }
                } catch (Throwable t) {
                    errorMessage = "Exception at applyPriceToConsumer for currency " + currencyCode +
                            ". priceProvider=" + baseUrl + ". Exception=" + t;
                }
            } else {
                log.debug("We don't have a price for currency " + currencyCode + ". priceProvider=" + baseUrl +
                        ". That is expected for currencies not listed at providers.");
                result = true;
            }
        } else {
            errorMessage = "We don't have a currency yet set. That should never happen";
        }

        if (errorMessage != null) {
            log.warn(errorMessage);
            if (faultHandler != null)
                faultHandler.handleFault(errorMessage, new PriceRequestException(errorMessage));
        }

        UserThread.execute(() -> updateCounter.set(updateCounter.get() + 1));

        return result;
    }

    // issues a single request at a time; runs on THREAD_ID
    private void requestAllPrices(PriceProvider provider, Runnable resultHandler, FaultHandler faultHandler) {
        if (!requestInProgress.compareAndSet(false, true)) {
            log.debug("We have a request in progress. We ignore the new request to provider {}", provider.getBaseUrl());
            return;
        }

        // release the previous request's executor before starting a new request
        if (priceRequest != null) priceRequest.shutDown();

        baseUrlOfRespondingProvider = null;

        PriceRequest thisRequest = new PriceRequest();
        priceRequest = thisRequest;
        SettableFuture<Map<String, MarketPrice>> future = thisRequest.requestAllPrices(provider);
        Futures.addCallback(future, new FutureCallback<>() {
            @Override
            public void onSuccess(@Nullable Map<String, MarketPrice> result) {
                ThreadUtils.execute(() -> {
                    if (priceRequest != thisRequest) return; // request was canceled and replaced
                    requestInProgress.set(false);
                    baseUrlOfRespondingProvider = provider.getBaseUrl();
                    if (!baseUrlOfRespondingProvider.equals(baseUrlOfLastRespondingProvider)) {
                        baseUrlOfLastRespondingProvider = baseUrlOfRespondingProvider;
                        log.info("Receiving prices from provider {}", baseUrlOfRespondingProvider);
                    }
                    UserThread.execute(() -> {
                        checkNotNull(result, "Result must not be null at requestAllPrices");
                        // Each currency rate has a different timestamp, depending on when
                        // the priceNode aggregate rate was calculated
                        // However, the request timestamp is when the pricenode was queried
                        epochInMillisAtLastRequest = System.currentTimeMillis();

                        synchronized (cache) {
                            cache.putAll(result);
                        }

                        resultHandler.run();
                    });
                }, THREAD_ID);
            }

            @Override
            public void onFailure(@NotNull Throwable throwable) {
                ThreadUtils.execute(() -> {
                    if (priceRequest != thisRequest) return; // request was canceled and replaced
                    requestInProgress.set(false);
                    UserThread.execute(() -> faultHandler.handleFault("Could not load marketPrices", throwable));
                }, THREAD_ID);
            }
        }, MoreExecutors.directExecutor());
    }
}
