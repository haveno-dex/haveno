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

package haveno.core.trade.protocol.tasks;

import haveno.common.crypto.PubKeyRing;
import haveno.core.monetary.Volume;
import haveno.core.offer.Offer;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.CashAtAtmAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.ZelleAccountPayload;
import haveno.core.trade.ArbitratorTrade;
import haveno.core.trade.BuyerAsMakerTrade;
import haveno.core.trade.SellerAsMakerTrade;
import haveno.core.trade.SellerAsTakerTrade;
import haveno.core.trade.TakerTrade;
import haveno.core.trade.Trade;
import haveno.core.trade.protocol.TradePeer;
import haveno.core.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static haveno.core.trade.protocol.tasks.ProcessInitTradeRequest.findIndistinguishableTrade;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Detecting another open trade with the same counterparty, direction, amount, and payment account of ours,
// whose payment could be indistinguishable in a dispute.
public class ProcessInitTradeRequestTest {

    private static final Volume VOLUME = Volume.parse("100", "EUR");

    private PubKeyRing peerPubKeyRing;
    private User user;

    @BeforeEach
    public void setup() {
        peerPubKeyRing = pubKeyRing(new byte[]{1});
        user = mock(User.class);
    }

    @Test
    public void testMakerRejectsDuplicateWithSameAccountCounterpartyAndVolume() {
        Trade other = mockTrade(BuyerAsMakerTrade.class, "other", tradePeer("acct", null), tradePeer(null, peerPubKeyRing), true, VOLUME);
        assertNotNull(findIndistinguishableTrade(currentTrade(VOLUME), true, "acct", peerPubKeyRing, List.of(other), user));
    }

    @Test
    public void testDifferentDirectionDoesNotMatch() {
        Trade other = mockTrade(BuyerAsMakerTrade.class, "other", tradePeer("acct", null), tradePeer(null, peerPubKeyRing), true, VOLUME);
        assertNull(findIndistinguishableTrade(currentTrade(VOLUME), false, "acct", peerPubKeyRing, List.of(other), user));
    }

    @Test
    public void testDifferentCounterpartyDoesNotMatch() {
        Trade other = mockTrade(BuyerAsMakerTrade.class, "other", tradePeer("acct", null), tradePeer(null, pubKeyRing(new byte[]{2})), true, VOLUME);
        assertNull(findIndistinguishableTrade(currentTrade(VOLUME), true, "acct", peerPubKeyRing, List.of(other), user));
    }

    @Test
    public void testRotatedEncryptionKeyStillMatchesBySignatureKey() {
        // a modified peer could keep its signature key but present a fresh encryption key per trade
        Trade other = mockTrade(BuyerAsMakerTrade.class, "other", tradePeer("acct", null), tradePeer(null, pubKeyRing(new byte[]{1})), true, VOLUME);
        assertNotNull(findIndistinguishableTrade(currentTrade(VOLUME), true, "acct", peerPubKeyRing, List.of(other), user));
    }

    @Test
    public void testDifferentVolumeOrCurrencyDoesNotMatch() {
        Trade other = mockTrade(BuyerAsMakerTrade.class, "other", tradePeer("acct", null), tradePeer(null, peerPubKeyRing), true, VOLUME);
        assertNull(findIndistinguishableTrade(currentTrade(Volume.parse("101", "EUR")), true, "acct", peerPubKeyRing, List.of(other), user));
        assertNull(findIndistinguishableTrade(currentTrade(Volume.parse("100", "USD")), true, "acct", peerPubKeyRing, List.of(other), user));
    }

    @Test
    public void testTakerRejectsDuplicateAcrossRoles() {
        // the local seller previously sold as maker and now sells as taker to the same counterparty
        Trade other = mockTrade(SellerAsMakerTrade.class, "other", tradePeer("acct", null), tradePeer(null, peerPubKeyRing), false, VOLUME);
        assertNotNull(findIndistinguishableTrade(currentTrade(VOLUME), false, "acct", peerPubKeyRing, List.of(other), user));
    }

    @Test
    public void testRecreatedAccountMatchesByStoredPayloadEndpoint() {
        TradePeer otherSelf = tradePeer("deleted-acct", null);
        otherSelf.setPaymentAccountPayload(zellePayload("+1 (416) 555-1234"));
        Trade other = mockTrade(BuyerAsMakerTrade.class, "other", otherSelf, tradePeer(null, peerPubKeyRing), true, VOLUME);
        PaymentAccount recreatedAccount = paymentAccount(zellePayload("14165551234"));
        when(user.getPaymentAccount("recreated-acct")).thenReturn(recreatedAccount);
        assertNotNull(findIndistinguishableTrade(currentTrade(VOLUME), true, "recreated-acct", peerPubKeyRing, List.of(other), user));
    }

    @Test
    public void testDifferentEndpointsDoNotMatch() {
        TradePeer otherSelf = tradePeer("deleted-acct", null);
        otherSelf.setPaymentAccountPayload(zellePayload("+1 (416) 555-1234"));
        Trade other = mockTrade(BuyerAsMakerTrade.class, "other", otherSelf, tradePeer(null, peerPubKeyRing), true, VOLUME);
        PaymentAccount otherEndpointAccount = paymentAccount(zellePayload("+1 (416) 555-9999"));
        when(user.getPaymentAccount("other-endpoint-acct")).thenReturn(otherEndpointAccount);
        assertNull(findIndistinguishableTrade(currentTrade(VOLUME), true, "other-endpoint-acct", peerPubKeyRing, List.of(other), user));
    }

    @Test
    public void testMissingEndpointEvidenceFailsClosed() {
        // neither account resolves and no payload is stored, so the endpoints cannot be proven to differ
        Trade other = mockTrade(BuyerAsMakerTrade.class, "other", tradePeer("deleted-acct", null), tradePeer(null, peerPubKeyRing), true, VOLUME);
        assertNotNull(findIndistinguishableTrade(currentTrade(VOLUME), true, "also-deleted-acct", peerPubKeyRing, List.of(other), user));
    }

    @Test
    public void testMethodsWithoutStableEndpointMatchConservatively() {
        TradePeer otherSelf = tradePeer("acct1", null);
        otherSelf.setPaymentAccountPayload(new CashAtAtmAccountPayload("CASH_AT_ATM", "id1"));
        Trade other = mockTrade(BuyerAsMakerTrade.class, "other", otherSelf, tradePeer(null, peerPubKeyRing), true, VOLUME);
        PaymentAccount cashAtAtmAccount = paymentAccount(new CashAtAtmAccountPayload("CASH_AT_ATM", "id2"));
        when(user.getPaymentAccount("acct2")).thenReturn(cashAtAtmAccount);
        assertNotNull(findIndistinguishableTrade(currentTrade(VOLUME), true, "acct2", peerPubKeyRing, List.of(other), user));
    }

    @Test
    public void testUnsetMakerPubKeyRingFallsBackToOffer() {
        // the local seller takes twice from the same maker whose key ring is not yet set on the first trade
        Trade other = mockTrade(SellerAsTakerTrade.class, "other", tradePeer(null, null), tradePeer("acct", null), false, VOLUME);
        Offer offer = mock(Offer.class);
        when(offer.getPubKeyRing()).thenReturn(peerPubKeyRing);
        when(other.getOffer()).thenReturn(offer);
        assertNotNull(findIndistinguishableTrade(currentTrade(VOLUME), false, "acct", peerPubKeyRing, List.of(other), user));
    }

    @Test
    public void testArbitratorAndCurrentTradeInstanceIgnored() {
        Trade current = currentTrade(VOLUME);
        Trade arbitratorTrade = mockTrade(ArbitratorTrade.class, "other", tradePeer("acct", null), tradePeer(null, peerPubKeyRing), true, VOLUME);
        assertNull(findIndistinguishableTrade(current, true, "acct", peerPubKeyRing, List.of(arbitratorTrade, current), user));
    }

    @Test
    public void testDistinctFailedAttemptWithSameOfferIdMatches() {
        // failed attempts for one offer share its trade id, so only the exact current instance is skipped
        Trade other = mockTrade(BuyerAsMakerTrade.class, "trade", tradePeer("acct", null), tradePeer(null, peerPubKeyRing), true, VOLUME);
        assertNotNull(findIndistinguishableTrade(currentTrade(VOLUME), true, "acct", peerPubKeyRing, List.of(other), user));
    }

    private static Trade currentTrade(Volume volume) {
        return mockTrade(BuyerAsMakerTrade.class, "trade", new TradePeer(), new TradePeer(), true, volume);
    }

    // in a maker trade the self peer is the maker, in a taker trade the taker
    private static Trade mockTrade(Class<? extends Trade> type, String id, TradePeer self, TradePeer peer, boolean selfIsBuyer, Volume volume) {
        Trade trade = mock(type);
        boolean selfIsMaker = !(trade instanceof TakerTrade);
        TradePeer maker = selfIsMaker ? self : peer;
        TradePeer taker = selfIsMaker ? peer : self;
        when(trade.getId()).thenReturn(id);
        when(trade.getMaker()).thenReturn(maker);
        when(trade.getTaker()).thenReturn(taker);
        when(trade.getBuyer()).thenReturn(selfIsBuyer ? self : peer);
        when(trade.getVolume()).thenReturn(volume);
        return trade;
    }

    private static PubKeyRing pubKeyRing(byte[] signaturePubKeyBytes) {
        PubKeyRing pubKeyRing = mock(PubKeyRing.class);
        when(pubKeyRing.getSignaturePubKeyBytes()).thenReturn(signaturePubKeyBytes);
        return pubKeyRing;
    }

    private static TradePeer tradePeer(String paymentAccountId, PubKeyRing pubKeyRing) {
        TradePeer peer = new TradePeer();
        peer.setPaymentAccountId(paymentAccountId);
        peer.setPubKeyRing(pubKeyRing);
        return peer;
    }

    private static PaymentAccount paymentAccount(PaymentAccountPayload payload) {
        PaymentAccount account = mock(PaymentAccount.class);
        when(account.getPaymentAccountPayload()).thenReturn(payload);
        return account;
    }

    private static ZelleAccountPayload zellePayload(String emailOrMobileNr) {
        ZelleAccountPayload payload = new ZelleAccountPayload("ZELLE", "id");
        payload.setEmailOrMobileNr(emailOrMobileNr);
        return payload;
    }
}
