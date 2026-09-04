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

import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.crypto.PubKeyRing;
import haveno.common.file.FileUtil;
import haveno.core.monetary.TraditionalMoney;
import haveno.core.monetary.Volume;
import haveno.core.offer.Offer;
import haveno.core.trade.protocol.TradePeer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IndistinguishablePaymentTest {
    private static final String SEPA = "SEPA";
    private static final Volume EUR_100 = volume("EUR", "100");

    private File dir;
    private PubKeyRing buyer;
    private PubKeyRing seller;
    private PubKeyRing other;

    @BeforeEach
    public void setup() throws Exception {
        dir = File.createTempFile("temp_tests", "");
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdir();
        buyer = newPubKeyRing("buyer");
        seller = newPubKeyRing("seller");
        other = newPubKeyRing("other");
    }

    @AfterEach
    public void tearDown() throws Exception {
        FileUtil.deleteDirectory(dir);
    }

    @Test
    public void sameTermsAreIndistinguishable() {
        Trade trade = newTrade(buyer, seller, SEPA, EUR_100);
        Trade duplicate = newTrade(buyer, seller, SEPA, EUR_100);
        assertTrue(trade.hasIndistinguishablePayment(duplicate));
        assertTrue(duplicate.hasIndistinguishablePayment(trade));
    }

    @Test
    public void sameInstanceIsNotAMatch() {
        Trade trade = newTrade(buyer, seller, SEPA, EUR_100);
        assertFalse(trade.hasIndistinguishablePayment(trade));
    }

    @Test
    public void differentCounterpartyIsDistinguishable() {
        Trade trade = newTrade(buyer, seller, SEPA, EUR_100);
        assertFalse(trade.hasIndistinguishablePayment(newTrade(other, seller, SEPA, EUR_100)));
        assertFalse(trade.hasIndistinguishablePayment(newTrade(buyer, other, SEPA, EUR_100)));
    }

    @Test
    public void oppositeDirectionIsDistinguishable() {
        Trade trade = newTrade(buyer, seller, SEPA, EUR_100);
        assertFalse(trade.hasIndistinguishablePayment(newTrade(seller, buyer, SEPA, EUR_100)));
    }

    @Test
    public void differentPaymentMethodIsDistinguishable() {
        Trade trade = newTrade(buyer, seller, SEPA, EUR_100);
        assertFalse(trade.hasIndistinguishablePayment(newTrade(buyer, seller, "REVOLUT", EUR_100)));
    }

    @Test
    public void instantVariantIsIndistinguishable() {
        Trade trade = newTrade(buyer, seller, SEPA, EUR_100);
        assertTrue(trade.hasIndistinguishablePayment(newTrade(buyer, seller, "SEPA_INSTANT", EUR_100)));
        Trade cryptoTrade = newTrade(buyer, seller, "BLOCK_CHAINS", EUR_100);
        assertTrue(cryptoTrade.hasIndistinguishablePayment(newTrade(buyer, seller, "BLOCK_CHAINS_INSTANT", EUR_100)));
    }

    @Test
    public void differentVolumeOrCurrencyIsDistinguishable() {
        Trade trade = newTrade(buyer, seller, SEPA, EUR_100);
        assertFalse(trade.hasIndistinguishablePayment(newTrade(buyer, seller, SEPA, volume("EUR", "100.01"))));
        assertFalse(trade.hasIndistinguishablePayment(newTrade(buyer, seller, SEPA, volume("USD", "100"))));
        assertFalse(trade.hasIndistinguishablePayment(newTrade(buyer, seller, SEPA, null)));
    }

    @Test
    public void rotatedEncryptionKeyStillMatchesBySignatureKey() {
        PubKeyRing rotated = new PubKeyRing(buyer.getSignaturePubKeyBytes(), other.getEncryptionPubKeyBytes());
        Trade trade = newTrade(buyer, seller, SEPA, EUR_100);
        assertTrue(trade.hasIndistinguishablePayment(newTrade(rotated, seller, SEPA, EUR_100)));
    }

    @Test
    public void unknownPeerIsDistinguishable() {
        Trade trade = newTrade(buyer, seller, SEPA, EUR_100);
        assertFalse(trade.hasIndistinguishablePayment(newTrade(null, seller, SEPA, EUR_100)));
    }

    private Trade newTrade(PubKeyRing buyerPubKeyRing, PubKeyRing sellerPubKeyRing, String paymentMethodId, Volume volume) {
        Offer offer = mock(Offer.class);
        when(offer.getPaymentMethodId()).thenReturn(paymentMethodId);
        Trade trade = mock(Trade.class);
        when(trade.hasIndistinguishablePayment(any())).thenCallRealMethod();
        when(trade.getBuyer()).thenReturn(peer(buyerPubKeyRing));
        when(trade.getSeller()).thenReturn(peer(sellerPubKeyRing));
        when(trade.getOffer()).thenReturn(offer);
        when(trade.getVolume()).thenReturn(volume);
        return trade;
    }

    private static Volume volume(String currencyCode, String amount) {
        return new Volume(TraditionalMoney.parseTraditionalMoney(currencyCode, amount));
    }

    private static TradePeer peer(PubKeyRing pubKeyRing) {
        TradePeer peer = new TradePeer();
        peer.setPubKeyRing(pubKeyRing);
        return peer;
    }

    private PubKeyRing newPubKeyRing(String name) {
        File keyDir = new File(dir, name);
        //noinspection ResultOfMethodCallIgnored
        keyDir.mkdir();
        return new KeyRing(new KeyStorage(keyDir), null, true).getPubKeyRing();
    }
}
