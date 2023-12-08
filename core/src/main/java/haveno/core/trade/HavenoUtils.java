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

package haveno.core.trade;

import com.google.common.base.CaseFormat;
import com.google.common.base.Charsets;
import haveno.common.config.Config;
import haveno.common.crypto.CryptoException;
import haveno.common.crypto.Hash;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.PubKeyRing;
import haveno.common.crypto.Sig;
import haveno.common.util.Utilities;
import haveno.core.app.HavenoSetup;
import haveno.core.offer.OfferPayload;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.support.dispute.arbitration.arbitrator.Arbitrator;
import haveno.core.trade.messages.InitTradeRequest;
import haveno.core.trade.messages.PaymentReceivedMessage;
import haveno.core.trade.messages.PaymentSentMessage;
import haveno.core.util.JsonUtil;
import haveno.core.util.ParsingUtils;
import haveno.network.p2p.NodeAddress;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URI;
import java.security.PrivateKey;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroRpcConnection;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroTxWallet;

import org.bitcoinj.core.Coin;

/**
 * Collection of utilities.
 */
@Slf4j
public class HavenoUtils {

    // Use the US locale as a base for all DecimalFormats (commas should be omitted from number strings).
    public static final DecimalFormatSymbols DECIMAL_FORMAT_SYMBOLS = DecimalFormatSymbols.getInstance(Locale.US);
    public static int XMR_SMALLEST_UNIT_EXPONENT = 12;
    public static final String LOOPBACK_HOST = "127.0.0.1"; // local loopback address to host Monero node
    public static final String LOCALHOST = "localhost";
    private static final long CENTINEROS_AU_MULTIPLIER = 10000;
    private static final BigInteger XMR_AU_MULTIPLIER = new BigInteger("1000000000000");
    public static final DecimalFormat XMR_FORMATTER = new DecimalFormat("##############0.000000000000", DECIMAL_FORMAT_SYMBOLS);
    public static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
    private static final int POOL_SIZE = 10;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(POOL_SIZE);
    private static final Map<String, ExecutorService> POOLS = new HashMap<>();

    // TODO: better way to share references?
    public static ArbitrationManager arbitrationManager;
    public static HavenoSetup havenoSetup;

    // ----------------------- CONVERSION UTILS -------------------------------

    public static BigInteger coinToAtomicUnits(Coin coin) {
        return centinerosToAtomicUnits(coin.value);
    }

    public static BigInteger centinerosToAtomicUnits(long centineros) {
        return BigInteger.valueOf(centineros).multiply(BigInteger.valueOf(CENTINEROS_AU_MULTIPLIER));
    }

    public static double centinerosToXmr(long centineros) {
        return atomicUnitsToXmr(centinerosToAtomicUnits(centineros));
    }

    public static Coin centinerosToCoin(long centineros) {
        return atomicUnitsToCoin(centinerosToAtomicUnits(centineros));
    }

    public static long atomicUnitsToCentineros(long atomicUnits) {
        return atomicUnits / CENTINEROS_AU_MULTIPLIER;
    }

    public static long atomicUnitsToCentineros(BigInteger atomicUnits) {
        return atomicUnits.divide(BigInteger.valueOf(CENTINEROS_AU_MULTIPLIER)).longValueExact();
    }

    public static Coin atomicUnitsToCoin(long atomicUnits) {
        return Coin.valueOf(atomicUnitsToCentineros(atomicUnits));
    }

    public static Coin atomicUnitsToCoin(BigInteger atomicUnits) {
        return atomicUnitsToCoin(atomicUnits.longValueExact());
    }

    public static double atomicUnitsToXmr(long atomicUnits) {
        return atomicUnitsToXmr(BigInteger.valueOf(atomicUnits));
    }

    public static double atomicUnitsToXmr(BigInteger atomicUnits) {
        return new BigDecimal(atomicUnits).divide(new BigDecimal(XMR_AU_MULTIPLIER)).doubleValue();
    }

    public static BigInteger xmrToAtomicUnits(double xmr) {
        return BigDecimal.valueOf(xmr).setScale(8, RoundingMode.DOWN).multiply(new BigDecimal(XMR_AU_MULTIPLIER)).toBigInteger();
    }

    public static long xmrToCentineros(double xmr) {
        return atomicUnitsToCentineros(xmrToAtomicUnits(xmr));
    }

    public static double coinToXmr(Coin coin) {
        return atomicUnitsToXmr(coinToAtomicUnits(coin));
    }

    public static double divide(BigInteger auDividend, BigInteger auDivisor) {
        return (double) atomicUnitsToCentineros(auDividend) / (double) atomicUnitsToCentineros(auDivisor);
    }

    // ------------------------- FORMAT UTILS ---------------------------------

    public static String formatXmr(BigInteger atomicUnits) {
        return formatXmr(atomicUnits, false);
    }

    public static String formatXmr(BigInteger atomicUnits, int decimalPlaces) {
        return formatXmr(atomicUnits, false, decimalPlaces);
    }

    public static String formatXmr(BigInteger atomicUnits, boolean appendCode) {
        return formatXmr(atomicUnits, appendCode, 0);
    }

    public static String formatXmr(BigInteger atomicUnits, boolean appendCode, int decimalPlaces) {
        if (atomicUnits == null) return "";
        return formatXmr(atomicUnits.longValueExact(), appendCode, decimalPlaces);
    }

    public static String formatXmr(long atomicUnits) {
        return formatXmr(atomicUnits, false, 0);
    }

    public static String formatXmr(long atomicUnits, boolean appendCode) {
        return formatXmr(atomicUnits, appendCode, 0);
    }

    public static String formatXmr(long atomicUnits, boolean appendCode, int decimalPlaces) {
        String formatted = XMR_FORMATTER.format(atomicUnitsToXmr(atomicUnits));

        // strip trailing 0s
        if (formatted.contains(".")) {
            while (formatted.length() > 3 && formatted.charAt(formatted.length() - 1) == '0') {
                formatted = formatted.substring(0, formatted.length() - 1);
            }
        }
        return applyDecimals(formatted, Math.max(2, decimalPlaces)) + (appendCode ? " XMR" : "");
    }

    public static String formatPercent(double percent) {
        return (percent * 100) + "%";
    }

    private static String applyDecimals(String decimalStr, int decimalPlaces) {
        if (decimalStr.contains(".")) return decimalStr + getNumZeros(decimalPlaces - (decimalStr.length() - decimalStr.indexOf(".") - 1));
        else return decimalStr + "." + getNumZeros(decimalPlaces);
    }

    private static String getNumZeros(int numZeros) {
        String zeros = "";
        for (int i = 0; i < numZeros; i++) zeros += "0";
        return zeros;
    }

    public static BigInteger parseXmr(String input) {
        if (input == null || input.length() == 0) return BigInteger.ZERO;
        try {
            return xmrToAtomicUnits(new BigDecimal(ParsingUtils.parseNumberStringToDouble(input)).doubleValue());
        } catch (Exception e) {
            return BigInteger.ZERO;
        }
    }

    // ------------------------------ FEE UTILS -------------------------------

    @Nullable
    public static BigInteger getMakerFee(@Nullable BigInteger amount) {
        if (amount != null) {
            BigInteger feePerXmr = getFeePerXmr(HavenoUtils.getMakerFeePerXmr(), amount);
            return feePerXmr.max(HavenoUtils.getMinMakerFee());
        } else {
            return null;
        }
    }

    @Nullable
    public static BigInteger getTakerFee(@Nullable BigInteger amount) {
        if (amount != null) {
            BigInteger feePerXmr = HavenoUtils.getFeePerXmr(HavenoUtils.getTakerFeePerXmr(), amount);
            return feePerXmr.max(HavenoUtils.getMinTakerFee());
        } else {
            return null;
        }
    }

    private static BigInteger getMakerFeePerXmr() {
        return HavenoUtils.xmrToAtomicUnits(0.001);
    }

    public static BigInteger getMinMakerFee() {
        return HavenoUtils.xmrToAtomicUnits(0.00005);
    }

    private static BigInteger getTakerFeePerXmr() {
        return HavenoUtils.xmrToAtomicUnits(0.003);
    }

    public static BigInteger getMinTakerFee() {
        return HavenoUtils.xmrToAtomicUnits(0.00005);
    }

    public static BigInteger getFeePerXmr(BigInteger feePerXmr, BigInteger amount) {
        BigDecimal feePerXmrAsDecimal = feePerXmr == null ? BigDecimal.valueOf(0) : new BigDecimal(feePerXmr);
        BigDecimal amountMultiplier = BigDecimal.valueOf(divide(amount == null ? BigInteger.ZERO : amount, HavenoUtils.xmrToAtomicUnits(1.0)));
        return feePerXmrAsDecimal.multiply(amountMultiplier).toBigInteger();
    }

    // ------------------------ SIGNING AND VERIFYING -------------------------

    public static byte[] sign(KeyRing keyRing, String message) {
        return sign(keyRing.getSignatureKeyPair().getPrivate(), message);
    }

    public static byte[] sign(KeyRing keyRing, byte[] message) {
        return sign(keyRing.getSignatureKeyPair().getPrivate(), message);
    }

    public static byte[] sign(PrivateKey privateKey, String message) {
        return sign(privateKey, message.getBytes(Charsets.UTF_8));
    }

    public static byte[] sign(PrivateKey privateKey, byte[] message) {
        try {
            return Sig.sign(privateKey, message);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static void verifySignature(PubKeyRing pubKeyRing, String message, byte[] signature) {
        verifySignature(pubKeyRing, message.getBytes(Charsets.UTF_8), signature);
    }

    public static void verifySignature(PubKeyRing pubKeyRing, byte[] message, byte[] signature) {
        try {
            boolean isValid = Sig.verify(pubKeyRing.getSignaturePubKey(), message, signature);
            if (!isValid) throw new IllegalArgumentException("Signature verification failed.");
        } catch (CryptoException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static boolean isSignatureValid(PubKeyRing pubKeyRing, String message, byte[] signature) {
        return isSignatureValid(pubKeyRing, message.getBytes(Charsets.UTF_8), signature);
    }

    public static boolean isSignatureValid(PubKeyRing pubKeyRing, byte[] message, byte[] signature) {
        try {
            verifySignature(pubKeyRing, message, signature);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Sign an offer.
     * 
     * @param offer is an unsigned offer to sign
     * @param keyRing is the arbitrator's key ring to sign with
     * @return the arbitrator's signature
     */
    public static byte[] signOffer(OfferPayload offer, KeyRing keyRing) {
        return HavenoUtils.sign(keyRing, offer.getSignatureHash());
    }

    /**
     * Check if the arbitrator signature is valid for an offer.
     *
     * @param offer is a signed offer with payload
     * @param arbitrator is the original signing arbitrator
     * @return true if the arbitrator's signature is valid for the offer
     */
    public static boolean isArbitratorSignatureValid(OfferPayload offer, Arbitrator arbitrator) {
        return isSignatureValid(arbitrator.getPubKeyRing(), offer.getSignatureHash(), offer.getArbitratorSignature());
    }

    /**
     * Check if the maker signature for a trade request is valid.
     *
     * @param request is the trade request to check
     * @return true if the maker's signature is valid for the trade request
     */
    public static boolean isMakerSignatureValid(InitTradeRequest request, byte[] signature, PubKeyRing makerPubKeyRing) {

        // re-create trade request with signed fields
        InitTradeRequest signedRequest = new InitTradeRequest(
                request.getTradeId(),
                request.getSenderNodeAddress(),
                request.getPubKeyRing(),
                request.getTradeAmount(),
                request.getTradePrice(),
                request.getTradeFee(),
                request.getAccountId(),
                request.getPaymentAccountId(),
                request.getPaymentMethodId(),
                request.getUid(),
                request.getMessageVersion(),
                request.getAccountAgeWitnessSignatureOfOfferId(),
                request.getCurrentDate(),
                request.getMakerNodeAddress(),
                request.getTakerNodeAddress(),
                null,
                null,
                null,
                null,
                request.getPayoutAddress(),
                null
                );

        // get trade request as string
        String tradeRequestAsJson = JsonUtil.objectToJson(signedRequest);

        // verify maker signature
        return isSignatureValid(makerPubKeyRing, tradeRequestAsJson, signature);
    }

    /**
     * Verify the buyer signature for a PaymentSentMessage.
     *
     * @param trade - the trade to verify
     * @param message - signed payment sent message to verify
     * @return true if the buyer's signature is valid for the message
     */
    public static void verifyPaymentSentMessage(Trade trade, PaymentSentMessage message) {

        // remove signature from message
        byte[] signature = message.getBuyerSignature();
        message.setBuyerSignature(null);

        // get unsigned message as json string
        String unsignedMessageAsJson = JsonUtil.objectToJson(message);

        // replace signature
        message.setBuyerSignature(signature);

        // verify signature
        if (!isSignatureValid(trade.getBuyer().getPubKeyRing(), unsignedMessageAsJson, signature)) {
            throw new IllegalArgumentException("The buyer signature is invalid for the " + message.getClass().getSimpleName() + " for " + trade.getClass().getSimpleName() + " " + trade.getId());
        }

        // verify trade id
        if (!trade.getId().equals(message.getTradeId())) throw new IllegalArgumentException("The " + message.getClass().getSimpleName() + " has the wrong trade id, expected " + trade.getId() + " but was " + message.getTradeId());
    }

    /**
     * Verify the seller signature for a PaymentReceivedMessage.
     *
     * @param trade - the trade to verify
     * @param message - signed payment received message to verify
     * @return true if the seller's signature is valid for the message
     */
    public static void verifyPaymentReceivedMessage(Trade trade, PaymentReceivedMessage message) {

        // remove signature from message
        byte[] signature = message.getSellerSignature();
        message.setSellerSignature(null);

        // get unsigned message as json string
        String unsignedMessageAsJson = JsonUtil.objectToJson(message);

        // replace signature
        message.setSellerSignature(signature);

        // verify signature
        if (!isSignatureValid(trade.getSeller().getPubKeyRing(), unsignedMessageAsJson, signature)) {
            throw new IllegalArgumentException("The seller signature is invalid for the " + message.getClass().getSimpleName() + " for " + trade.getClass().getSimpleName() + " " + trade.getId());
        }

        // verify trade id
        if (!trade.getId().equals(message.getTradeId())) throw new IllegalArgumentException("The " + message.getClass().getSimpleName() + " has the wrong trade id, expected " + trade.getId() + " but was " + message.getTradeId());

        // verify buyer signature of payment sent message
        if (message.getPaymentSentMessage() != null) verifyPaymentSentMessage(trade, message.getPaymentSentMessage());
    }

    // ----------------------------- OTHER UTILS ------------------------------

    /**
     * Get address to collect trade fees.
     *
     * @return the address which collects trade fees
     */
    public static String getTradeFeeAddress() {
        switch (Config.baseCurrencyNetwork()) {
        case XMR_LOCAL:
            return "Bd37nTGHjL3RvPxc9dypzpWiXQrPzxxG4RsWAasD9CV2iZ1xfFZ7mzTKNDxWBfsqQSUimctAsGtTZ8c8bZJy35BYL9jYj88";
        case XMR_STAGENET:
            return "5B11hTJdG2XDNwjdKGLRxwSLwDhkbGg7C7UEAZBxjE6FbCeRMjudrpNACmDNtWPiSnNfjDQf39QRjdtdgoL69txv81qc2Mc";
        case XMR_MAINNET:
            throw new RuntimeException("Mainnet fee address not implemented");
        default:
            throw new RuntimeException("Unhandled base currency network: " + Config.baseCurrencyNetwork());
        }
    }

    /**
     * Check if the given URI is on local host.
     */
    public static boolean isLocalHost(String uri) {
        try {
            String host = new URI(uri).getHost();
            return LOOPBACK_HOST.equals(host) || LOCALHOST.equals(host);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns a unique deterministic id for sending a trade mailbox message.
     *
     * @param trade the trade
     * @param tradeMessageClass the trade message class
     * @param receiver the receiver address
     * @return a unique deterministic id for sending a trade mailbox message
     */
    public static String getDeterministicId(Trade trade, Class<?> tradeMessageClass, NodeAddress receiver) {
        String uniqueId = trade.getId() + "_" + tradeMessageClass.getSimpleName() + "_" + trade.getRole() + "_to_" + trade.getPeerRole(trade.getTradePeer(receiver));
        return Utilities.bytesAsHexString(Hash.getSha256Ripemd160hash(uniqueId.getBytes(Charsets.UTF_8)));
    }

    public static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static Future<?> runTask(String threadId, Runnable task) {
        synchronized (POOLS) {
            if (!POOLS.containsKey(threadId)) POOLS.put(threadId, Executors.newFixedThreadPool(1));
            return POOLS.get(threadId).submit(task);
        }
    }

    public static void removeThreadId(String threadId) {
        synchronized (POOLS) {
            if (POOLS.containsKey(threadId)) {
                POOLS.get(threadId).shutdown();
                POOLS.remove(threadId);
            }
        }
    }

    /**
     * Submit tasks to a global thread pool.
     */
    public static Future<?> submitTask(Runnable task) {
        return POOL.submit(task);
    }

    public static List<Future<?>> submitTasks(List<Runnable> tasks) {
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable task : tasks) futures.add(submitTask(task));
        return futures;
    }

    // TODO: replace with GenUtils.executeTasks() once monero-java updated

    public static void executeTasks(Collection<Runnable> tasks) {
        executeTasks(tasks, tasks.size());
    }

    public static void executeTasks(Collection<Runnable> tasks, int maxConcurrency) {
        executeTasks(tasks, maxConcurrency, null);
    }

    public static void executeTasks(Collection<Runnable> tasks, int maxConcurrency, Long timeoutSeconds) {
        if (tasks.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(maxConcurrency);
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable task : tasks) futures.add(pool.submit(task));
        pool.shutdown();

        // interrupt after timeout
        if (timeoutSeconds != null) {
            try {
                if (!pool.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) pool.shutdownNow();
            } catch (InterruptedException e) {
                pool.shutdownNow();
                throw new RuntimeException(e);
            }
        }

        // throw exception from any tasks
        try {
            for (Future<?> future : futures) future.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String toCamelCase(String underscore) {
        return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, underscore);
    }

    public static boolean connectionConfigsEqual(MoneroRpcConnection c1, MoneroRpcConnection c2) {
        if (c1 == c2) return true;
        if (c1 == null) return false;
        return c1.equals(c2); // equality considers uri, username, and password
    }

    // TODO: move to monero-java MoneroTxWallet
    public static MoneroDestination getDestination(String address, MoneroTxWallet tx) {
        for (MoneroDestination destination : tx.getOutgoingTransfer().getDestinations()) {
            if (address.equals(destination.getAddress())) return destination;
        }
        return null;
    }
}
