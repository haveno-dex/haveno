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
import bisq.core.util.JsonUtil;
import java.net.URI;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Collection of utilities.
 */
public class HavenoUtils {

    public static final String LOOPBACK_HOST = "127.0.0.1"; // local loopback address to host Monero node
    public static final String LOCALHOST = "localhost";

    /**
     * Get address to collect trade fees.
     * 
     * TODO: move to config constants?
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
     * Check if the arbitrator signature for an offer is valid.
     * 
     * @param offer is a signed offer with payload
     * @param arbitrator is the possible original arbitrator
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
        boolean isValid = true;
        try {
            isValid = Sig.verify(arbitrator.getPubKeyRing().getSignaturePubKey(), unsignedOfferAsJson, signature);
        } catch (Exception e) {
            isValid = false;
        }
        
        // return result
        return isValid;
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

    public static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
    
    public static void awaitTasks(Collection<Runnable> tasks) {
        if (tasks.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        for (Runnable task : tasks) pool.submit(task);
        pool.shutdown();
        try {
            if (!pool.awaitTermination(60000, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            throw new RuntimeException(e);
        }
    }
}
