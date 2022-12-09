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

package bisq.core.trade;

import bisq.common.config.Config;
import bisq.common.crypto.PubKeyRing;
import bisq.common.crypto.Sig;
import bisq.core.offer.Offer;
import bisq.core.offer.OfferPayload;
import bisq.core.support.dispute.arbitration.arbitrator.Arbitrator;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.messages.PaymentReceivedMessage;
import bisq.core.trade.messages.PaymentSentMessage;
import bisq.core.util.JsonUtil;
import bisq.core.util.ParsingUtils;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.MonetaryFormat;
import com.google.common.base.CaseFormat;
import com.google.common.base.Charsets;

/**
 * Collection of utilities.
 */
@Slf4j
public class HavenoUtils {

    public static final String LOOPBACK_HOST = "127.0.0.1"; // local loopback address to host Monero node
    public static final String LOCALHOST = "localhost";

    // multipliers to convert units
    private static BigInteger CENTINEROS_AU_MULTIPLIER = new BigInteger("10000");
    private static BigInteger XMR_AU_MULTIPLIER = new BigInteger("1000000000000");

    public static BigInteger coinToAtomicUnits(Coin coin) {
        return centinerosToAtomicUnits(coin.value);
    }

    public static double coinToXmr(Coin coin) {
        return atomicUnitsToXmr(coinToAtomicUnits(coin));
    }

    public static BigInteger centinerosToAtomicUnits(long centineros) {
        return BigInteger.valueOf(centineros).multiply(CENTINEROS_AU_MULTIPLIER);
    }

    public static double centinerosToXmr(long centineros) {
        return atomicUnitsToXmr(centinerosToAtomicUnits(centineros));
    }

    public static long atomicUnitsToCentineros(long atomicUnits) { // TODO: atomic units should be BigInteger; remove this?
        return atomicUnits / CENTINEROS_AU_MULTIPLIER.longValue();
    }

    public static long atomicUnitsToCentineros(BigInteger atomicUnits) {
        return atomicUnits.divide(CENTINEROS_AU_MULTIPLIER).longValueExact();
    }

    public static Coin atomicUnitsToCoin(BigInteger atomicUnits) {
        return Coin.valueOf(atomicUnitsToCentineros(atomicUnits));
    }

    public static double atomicUnitsToXmr(BigInteger atomicUnits) {
        return new BigDecimal(atomicUnits).divide(new BigDecimal(XMR_AU_MULTIPLIER)).doubleValue();
    }

    public static BigInteger xmrToAtomicUnits(double xmr) {
        return BigDecimal.valueOf(xmr).multiply(new BigDecimal(XMR_AU_MULTIPLIER)).toBigInteger();
    }

    public static long xmrToCentineros(double xmr) {
        return atomicUnitsToCentineros(xmrToAtomicUnits(xmr));
    }
    
    private static final MonetaryFormat xmrCoinFormat = Config.baseCurrencyNetworkParameters().getMonetaryFormat();


    public static Coin getMakerFeePerBtc() {
         return ParsingUtils.parseToCoin("0.001", xmrCoinFormat);
    }

    public static Coin getMinMakerFee() {
         return ParsingUtils.parseToCoin("0.00005", xmrCoinFormat);
    }

    public static Coin getTakerFeePerBtc() {
         return ParsingUtils.parseToCoin("0.003", xmrCoinFormat);
    }

    public static Coin getMinTakerFee() {
         return ParsingUtils.parseToCoin("0.00005", xmrCoinFormat);
    }

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
            return host.equals(LOOPBACK_HOST) || host.equals(LOCALHOST);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Check if the arbitrator signature is valid for an offer.
     * 
     * @param offer is a signed offer with payload
     * @param arbitrator is the original signing arbitrator
     * @return true if the arbitrator's signature is valid for the offer
     */
    public static boolean isArbitratorSignatureValid(Offer offer, Arbitrator arbitrator) {
        
        // copy offer payload
        OfferPayload offerPayloadCopy = OfferPayload.fromProto(offer.toProtoMessage().getOfferPayload());
        
        // remove arbitrator signature from signed payload
        String signature = offerPayloadCopy.getArbitratorSignature();
        offerPayloadCopy.setArbitratorSignature(null);
        
        // get unsigned offer payload as json string
        String unsignedOfferAsJson = JsonUtil.objectToJson(offerPayloadCopy);
        
        // verify arbitrator signature
        try {
            return Sig.verify(arbitrator.getPubKeyRing().getSignaturePubKey(), unsignedOfferAsJson, signature);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Check if the maker signature for a trade request is valid.
     * 
     * @param request is the trade request to check
     * @return true if the maker's signature is valid for the trade request
     */
    public static boolean isMakerSignatureValid(InitTradeRequest request, String signature, PubKeyRing makerPubKeyRing) {
        
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
        try {
            return Sig.verify(makerPubKeyRing.getSignaturePubKey(),
                    tradeRequestAsJson,
                    signature);
        } catch (Exception e) {
            return false;
        }
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
        String errMessage = "The buyer signature is invalid for the " + message.getClass().getSimpleName() + " for trade " + trade.getId();
        try {
            if (!Sig.verify(trade.getBuyer().getPubKeyRing().getSignaturePubKey(), unsignedMessageAsJson.getBytes(Charsets.UTF_8), signature)) throw new RuntimeException(errMessage);
        } catch (Exception e) {
            throw new RuntimeException(errMessage);
        }

        // verify trade id
        if (!trade.getId().equals(message.getTradeId())) throw new RuntimeException("The " + message.getClass().getSimpleName() + " has the wrong trade id, expected " + trade.getId() + " but was " + message.getTradeId());
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
        String errMessage = "The seller signature is invalid for the " + message.getClass().getSimpleName() + " for trade " + trade.getId();
        try {
            if (!Sig.verify(trade.getSeller().getPubKeyRing().getSignaturePubKey(), unsignedMessageAsJson.getBytes(Charsets.UTF_8), signature)) throw new RuntimeException(errMessage);
        } catch (Exception e) {
            throw new RuntimeException(errMessage);
        }

        // verify trade id
        if (!trade.getId().equals(message.getTradeId())) throw new RuntimeException("The " + message.getClass().getSimpleName() + " has the wrong trade id, expected " + trade.getId() + " but was " + message.getTradeId());

        // verify buyer signature of payment sent message
        verifyPaymentSentMessage(trade, message.getPaymentSentMessage());
    }

    public static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // TODO: replace with GenUtils.executeTasks()
    public static void executeTasks(Collection<Runnable> tasks) {
        executeTasks(tasks, tasks.size());
    }

    public static void executeTasks(Collection<Runnable> tasks, int poolSize) {
        if (tasks.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        for (Runnable task : tasks) pool.submit(task);
        pool.shutdown();
        try {
            if (!pool.awaitTermination(60, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            throw new RuntimeException(e);
        }
    }

    public static String toCamelCase(String underscore) {
        return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, underscore);
    }
}
