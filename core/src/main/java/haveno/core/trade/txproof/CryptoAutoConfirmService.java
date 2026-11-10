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
package haveno.core.trade.txproof;

import haveno.core.payment.payload.AssetAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.protocol.SellerProtocol;
import haveno.core.user.AutoConfirmSettings;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in automatic confirmation of crypto payments (BTC, LTC, ETH, ERC-20) for the seller.
 * <p>
 * Manual confirmation remains the default. A trade is auto-confirmed only if the user enabled
 * auto-confirm for the currency, the trade amount is within the configured limit, the buyer's
 * transaction id is not used by any other trade, and the payment proof is fully verified.
 */
@Slf4j
public final class CryptoAutoConfirmService {

    private static final long POLL_INTERVAL_SEC = 120;
    private static final long MAX_POLL_SEC = TimeUnit.HOURS.toSeconds(24);
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "CryptoAutoConfirm");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<String> POLLING_TRADE_IDS = ConcurrentHashMap.newKeySet();
    private static final CryptoTxProofService PROOF_SERVICE = new CryptoTxProofService();

    private CryptoAutoConfirmService() {
    }

    /**
     * Called on the seller's side after the PaymentSentMessage was processed.
     */
    public static void onPaymentSentMessageProcessed(Trade trade) {
        try {
            if (!trade.isSeller()) return;
            String currencyCode = trade.getOffer().getCounterCurrencyCode();
            if (!CryptoTxProofService.isSupported(currencyCode)) return;
            if (HavenoUtils.preferences == null) return;
            Optional<AutoConfirmSettings> settings = HavenoUtils.preferences.findAutoConfirmSettings(currencyCode);
            if (settings.isEmpty() || !settings.get().isEnabled() || settings.get().getServiceAddresses().isEmpty()) return;
            if (trade.getAmount().longValueExact() > settings.get().getTradeLimit()) {
                log.info("Not auto-confirming {} {} because the trade amount exceeds the auto-confirm limit", trade.getClass().getSimpleName(), trade.getShortId());
                return;
            }
            if (trade.getCounterCurrencyTxId() == null) {
                log.info("Not auto-confirming {} {} because the buyer did not provide a transaction id", trade.getClass().getSimpleName(), trade.getShortId());
                return;
            }
            if (isTxIdUsedByOtherTrade(trade)) {
                log.warn("Not auto-confirming {} {} because transaction id {} is used by another trade", trade.getClass().getSimpleName(), trade.getShortId(), trade.getCounterCurrencyTxId());
                return;
            }
            if (!POLLING_TRADE_IDS.add(trade.getId())) return;
            long startMs = System.currentTimeMillis();
            schedulePoll(trade, currencyCode, settings.get(), startMs, 0);
        } catch (Exception e) {
            log.warn("Error starting crypto auto-confirm for {} {}: {}", trade.getClass().getSimpleName(), trade.getShortId(), e.getMessage());
        }
    }

    private static void schedulePoll(Trade trade, String currencyCode, AutoConfirmSettings settings, long startMs, long delaySec) {
        EXECUTOR.schedule(() -> poll(trade, currencyCode, settings, startMs), delaySec, TimeUnit.SECONDS);
    }

    private static void poll(Trade trade, String currencyCode, AutoConfirmSettings settings, long startMs) {
        try {
            if (trade.isPaymentReceived() || !settings.isEnabled()) {
                POLLING_TRADE_IDS.remove(trade.getId());
                return;
            }
            PaymentAccountPayload payload = trade.getSeller().getPaymentAccountPayload();
            if (!(payload instanceof AssetAccountPayload)) {
                POLLING_TRADE_IDS.remove(trade.getId());
                return;
            }
            String recipient = ((AssetAccountPayload) payload).getAddress();
            BigDecimal expectedAmount = new BigDecimal(trade.getVolume().toPlainString());
            long tradeStartSec = trade.getTakeOfferDate().getTime() / 1000;
            CryptoTxProofResult result = PROOF_SERVICE.verify(currencyCode, settings.getServiceAddresses().get(0), trade.getCounterCurrencyTxId(),
                    recipient, expectedAmount, settings.getRequiredConfirmations(), tradeStartSec);
            log.info("Crypto auto-confirm result for {} {}: {}", trade.getClass().getSimpleName(), trade.getShortId(), result);
            if (result.isVerified() && !isTxIdUsedByOtherTrade(trade)) {
                POLLING_TRADE_IDS.remove(trade.getId());
                ((SellerProtocol) trade.getProtocol()).onPaymentReceived(() -> {
                    log.info("Auto-confirmed payment received for {} {}", trade.getClass().getSimpleName(), trade.getShortId());
                }, errorMessage -> {
                    log.warn("Error auto-confirming payment received for {} {}: {}", trade.getClass().getSimpleName(), trade.getShortId(), errorMessage);
                });
                return;
            }
            if (result.getStatus() == CryptoTxProofResult.Status.FAILED || System.currentTimeMillis() - startMs > TimeUnit.SECONDS.toMillis(MAX_POLL_SEC)) {
                POLLING_TRADE_IDS.remove(trade.getId()); // fall back to manual confirmation
                return;
            }
            schedulePoll(trade, currencyCode, settings, startMs, POLL_INTERVAL_SEC);
        } catch (Exception e) {
            log.warn("Error polling crypto auto-confirm for {} {}: {}", trade.getClass().getSimpleName(), trade.getShortId(), e.getMessage());
            schedulePoll(trade, currencyCode, settings, startMs, POLL_INTERVAL_SEC);
        }
    }

    // a payment can only prove one trade
    private static boolean isTxIdUsedByOtherTrade(Trade trade) {
        if (HavenoUtils.tradeManager == null) return true; // fail closed
        String txId = trade.getCounterCurrencyTxId();
        List<Trade> trades = new ArrayList<>(HavenoUtils.tradeManager.getObservableList());
        trades.addAll(HavenoUtils.tradeManager.getClosedTrades());
        return trades.stream()
                .filter(other -> !other.getId().equals(trade.getId()))
                .anyMatch(other -> txId.equalsIgnoreCase(other.getCounterCurrencyTxId()));
    }
}
