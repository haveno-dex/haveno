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

import haveno.common.UserThread;
import haveno.common.crypto.Encryption;
import haveno.core.network.MessageState;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PaymentAccountFactory;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.payload.ZelleAccountPayload;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeResult;
import haveno.core.support.dispute.DisputeResult.Winner;
import haveno.core.trade.protocol.ProcessModel;
import haveno.core.trade.protocol.TradePeer;
import haveno.core.trade.protocol.tasks.BuyerSendPaymentSentMessageToSeller;
import haveno.core.xmr.wallet.XmrWalletService;
import javafx.beans.property.SimpleObjectProperty;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class TradeTest {

    private static final long AMOUNT = 1000;
    private static final long BUYER_DEPOSIT = 150;

    @Test
    public void testArbitratedBuyerGain() {
        assertEquals(BigInteger.valueOf(AMOUNT), mockTrade(false, mockDispute(Winner.BUYER, BUYER_DEPOSIT + AMOUNT, 1)).getArbitratedBuyerGain());
        assertEquals(BigInteger.ZERO, mockTrade(false, mockDispute(Winner.SELLER, BUYER_DEPOSIT, 1)).getArbitratedBuyerGain());
        assertNull(mockTrade(true, mockDispute(Winner.SELLER, BUYER_DEPOSIT, 1)).getArbitratedBuyerGain()); // seller confirmed payment
        assertNull(mockTrade(false, mockDispute(null, 0, 1)).getArbitratedBuyerGain()); // ticket closed without a ruling
        assertNull(mockTrade(false).getArbitratedBuyerGain());
    }

    @Test
    public void testLatestRulingAmongTickets() {
        Dispute noRuling = mock(Dispute.class);
        when(noRuling.getDisputeResultProperty()).thenReturn(new SimpleObjectProperty<>());
        Trade trade = mockTrade(false, mockDispute(Winner.BUYER, BUYER_DEPOSIT + AMOUNT, 2), mockDispute(Winner.SELLER, BUYER_DEPOSIT, 1), noRuling);
        assertEquals(BigInteger.valueOf(AMOUNT), trade.getArbitratedBuyerGain());
        trade = mockTrade(false, mockDispute(Winner.SELLER, BUYER_DEPOSIT, 1), mockDispute(null, 0, 2)); // later ticket closed without a ruling
        assertEquals(BigInteger.ZERO, trade.getArbitratedBuyerGain());
    }

    @Test
    public void testShouldPublishTradeStatistics() {
        assertTrue(mockTrade(true).shouldPublishTradeStatistics()); // seller confirmed payment
        assertTrue(mockTrade(false, mockDispute(Winner.BUYER, BUYER_DEPOSIT + AMOUNT, 1)).shouldPublishTradeStatistics());
        assertFalse(mockTrade(false, mockDispute(Winner.BUYER, BUYER_DEPOSIT + AMOUNT - 1, 1)).shouldPublishTradeStatistics());
        assertFalse(mockTrade(false, mockDispute(Winner.BUYER, BUYER_DEPOSIT, 1)).shouldPublishTradeStatistics()); // buyer gets all without a seller deposit
        assertFalse(mockTrade(false, mockDispute(Winner.SELLER, BUYER_DEPOSIT, 1)).shouldPublishTradeStatistics());
        assertFalse(mockTrade(false).shouldPublishTradeStatistics());
    }

    @Test
    public void testPeerPaymentAccountPayloadMustBeOfContractPaymentMethod() throws Exception {
        assertPeerPaymentAccountRejected(new ZelleAccountPayload(PaymentMethod.ZELLE_ID, "id"), PaymentMethod.REVOLUT_ID);
    }

    @Test
    public void testPeerPaymentAccountPayloadTypeMustMatchContractMethod() throws Exception {
        assertPeerPaymentAccountRejected(new ZelleAccountPayload(PaymentMethod.REVOLUT_ID, "id"), PaymentMethod.REVOLUT_ID);
    }

    @Test
    public void testMatchingPeerPaymentAccountPayloads() throws Exception {
        for (PaymentMethod method : List.of(PaymentMethod.ZELLE, PaymentMethod.REVOLUT, PaymentMethod.SEPA,
                PaymentMethod.SEPA_INSTANT, PaymentMethod.BLOCK_CHAINS, PaymentMethod.BLOCK_CHAINS_INSTANT,
                PaymentMethod.getDummyPaymentMethod(PaymentMethod.OK_PAY_ID),
                PaymentMethod.getDummyPaymentMethod(PaymentMethod.POPMONEY_ID),
                PaymentMethod.getDummyPaymentMethod(PaymentMethod.CHASE_QUICK_PAY_ID))) {
            PaymentAccount account = PaymentAccountFactory.getPaymentAccount(method);
            account.init();
            PaymentAccountPayload payload = account.getPaymentAccountPayload();
            for (boolean maker : List.of(true, false)) {
                Trade trade = tradeWithPeerPaymentAccount(maker, payload, method.getId());
                if (maker && method == PaymentMethod.SEPA_INSTANT) when(trade.getContract().getMakerPaymentMethodId()).thenReturn(PaymentMethod.SEPA_ID);
                if (!maker && method == PaymentMethod.SEPA) when(trade.getContract().getTakerPaymentMethodId()).thenReturn(PaymentMethod.SEPA_INSTANT_ID);
                trade.decryptPeerPaymentAccountPayload(trade.getTradePeer().getPaymentAccountKey());
                assertEquals(payload.toProtoMessage(), trade.getTradePeer().getPaymentAccountPayload().toProtoMessage());
                assertTrue(trade.getProcessModel().getPaymentAccountDecryptedProperty().get());
            }
        }
    }

    @Test
    public void testPaymentSentDeliveryCallbacksPreserveAcknowledgment() {
        try (MockedStatic<UserThread> userThread = mockStatic(UserThread.class)) {
            Trade trade = newTrade(false);
            trade.setState(Trade.State.SELLER_RECEIVED_PAYMENT_SENT_MSG);
            trade.getSeller().setPaymentSentMessageState(MessageState.ACKNOWLEDGED);
            List<Runnable> callbacks = new BuyerSendPaymentSentMessageToSeller(null, trade) {
                List<Runnable> deliveryCallbacks() {
                    return List.of(this::setStateSent, this::setStateArrived, this::setStateStoredInMailbox, this::setStateFault);
                }
            }.deliveryCallbacks();
            for (Runnable callback : callbacks) {
                callback.run();
                assertEquals(Trade.State.SELLER_RECEIVED_PAYMENT_SENT_MSG, trade.getState());
                assertTrue(trade.getSeller().isPaymentSentMessageAcked());
            }

            // an ack which landed while a delivery callback set the state is applied again
            for (Trade.State state : List.of(Trade.State.BUYER_SAW_ARRIVED_PAYMENT_SENT_MSG, Trade.State.BUYER_STORED_IN_MAILBOX_PAYMENT_SENT_MSG)) {
                for (Runnable callback : callbacks.subList(1, 3)) {
                    trade.setState(state);
                    callback.run();
                    assertEquals(Trade.State.SELLER_RECEIVED_PAYMENT_SENT_MSG, trade.getState());
                }
            }

            // an explicit negative acknowledgment still permits a retry
            trade.getSeller().setPaymentSentMessageState(MessageState.NACKED);
            assertEquals(MessageState.NACKED, trade.getSeller().getPaymentSentMessageStateProperty().get());
            trade.setState(Trade.State.BUYER_SEND_FAILED_PAYMENT_SENT_MSG);
            callbacks.get(1).run();
            assertEquals(Trade.State.BUYER_SAW_ARRIVED_PAYMENT_SENT_MSG, trade.getState());
            assertEquals(MessageState.ARRIVED, trade.getSeller().getPaymentSentMessageStateProperty().get());

            // a resend stored in the mailbox replaces an arrival which was not acked
            callbacks.get(2).run();
            assertEquals(Trade.State.BUYER_STORED_IN_MAILBOX_PAYMENT_SENT_MSG, trade.getState());
            assertEquals(MessageState.STORED_IN_MAILBOX, trade.getSeller().getPaymentSentMessageStateProperty().get());
        }
    }

    private static void assertPeerPaymentAccountRejected(PaymentAccountPayload payload, String method) throws Exception {
        for (boolean maker : List.of(true, false)) {
            Trade trade = tradeWithPeerPaymentAccount(maker, payload, method);
            RuntimeException e = assertThrows(RuntimeException.class, () -> trade.decryptPeerPaymentAccountPayload(trade.getTradePeer().getPaymentAccountKey()));
            assertTrue(e.getCause() instanceof IllegalArgumentException, String.valueOf(e.getCause()));
            assertNull(trade.getTradePeer().getPaymentAccountPayload());
            assertFalse(trade.getProcessModel().getPaymentAccountDecryptedProperty().get());
        }
    }

    private static Trade tradeWithPeerPaymentAccount(boolean maker, PaymentAccountPayload payload, String method) throws Exception {
        Trade trade = newTrade(maker);
        SecretKey key = Encryption.generateSecretKey(256);
        trade.getTradePeer().setPaymentAccountKey(key.getEncoded());
        trade.getTradePeer().setEncryptedPaymentAccountPayload(Encryption.encrypt(payload.toProtoMessage().toByteArray(), key));
        Contract contract = mock(Contract.class);
        when(contract.getMakerPaymentAccountPayloadHash()).thenReturn(payload.getHash());
        when(contract.getTakerPaymentAccountPayloadHash()).thenReturn(payload.getHash());
        when(contract.getMakerPaymentMethodId()).thenReturn(method);
        when(contract.getTakerPaymentMethodId()).thenReturn(method);
        trade.setContract(contract);
        return trade;
    }

    private static Trade newTrade(boolean maker) {
        Offer offer = mock(Offer.class);
        when(offer.getId()).thenReturn("offer");
        when(offer.getDirection()).thenReturn(OfferDirection.SELL);
        ProcessModel processModel = new ProcessModel("offer", "account", null);
        processModel.applyTransient(null, mock(TradeManager.class), offer);
        XmrWalletService walletService = mock(XmrWalletService.class);
        return maker ? new SellerAsMakerTrade(offer, BigInteger.valueOf(AMOUNT), 1, walletService, processModel, "uid", null, null, null, null)
                : new BuyerAsTakerTrade(offer, BigInteger.valueOf(AMOUNT), 1, walletService, processModel, "uid", null, null, null, null);
    }

    private static Trade mockTrade(boolean isPaymentReceived, Dispute... disputes) {
        TradePeer buyer = new TradePeer();
        buyer.setSecurityDeposit(BigInteger.valueOf(BUYER_DEPOSIT));
        Offer offer = mock(Offer.class, RETURNS_DEEP_STUBS);
        Trade trade = mock(Trade.class);
        when(trade.isPayoutPublished()).thenReturn(true);
        when(trade.isPaymentReceived()).thenReturn(isPaymentReceived);
        when(trade.isSeller()).thenReturn(true);
        when(trade.getOffer()).thenReturn(offer);
        when(trade.getBuyer()).thenReturn(buyer);
        when(trade.getAmount()).thenReturn(BigInteger.valueOf(AMOUNT));
        when(trade.getDisputes()).thenReturn(List.of(disputes));
        when(trade.getArbitratedBuyerGain()).thenCallRealMethod();
        when(trade.shouldPublishTradeStatistics()).thenCallRealMethod();
        return trade;
    }

    private static Dispute mockDispute(Winner winner, long buyerPayoutAmount, long closeDate) {
        DisputeResult disputeResult = new DisputeResult("tradeId", 0);
        disputeResult.setWinner(winner);
        disputeResult.setBuyerPayoutAmountBeforeCost(BigInteger.valueOf(buyerPayoutAmount));
        disputeResult.setCloseDate(new Date(closeDate));
        Dispute dispute = mock(Dispute.class);
        when(dispute.getDisputeResultProperty()).thenReturn(new SimpleObjectProperty<>(disputeResult));
        return dispute;
    }
}
