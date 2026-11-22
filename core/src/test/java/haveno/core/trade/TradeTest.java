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

import haveno.common.ThreadUtils;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.app.Version;
import haveno.common.crypto.Encryption;
import haveno.common.crypto.PubKeyRing;
import haveno.common.crypto.Sig;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.core.api.CoreNotificationService;
import haveno.core.api.XmrConnectionService;
import haveno.core.offer.Offer;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeResult;
import haveno.core.support.dispute.DisputeResult.Winner;
import haveno.core.trade.messages.CancelTradeMessage;
import haveno.core.trade.protocol.ProcessModel;
import haveno.core.trade.protocol.TradePeer;
import haveno.core.trade.protocol.TradePeer.CancelState;
import haveno.core.trade.protocol.TradeProtocol;
import haveno.core.trade.protocol.tasks.SendMailboxMessageTask;
import haveno.core.trade.protocol.tasks.TradeTask;
import haveno.core.xmr.wallet.XmrWalletBase;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.AckMessage;
import haveno.network.p2p.AckMessageSourceType;
import haveno.network.p2p.DecryptedMessageWithPubKey;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.SendMailboxMessageListener;
import haveno.network.p2p.mailbox.MailboxMessageService;
import haveno.network.p2p.network.CloseConnectionReason;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.network.NetworkNode;
import javafx.beans.property.SimpleObjectProperty;
import monero.common.MoneroError;
import monero.common.MoneroRpcConnection;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroIncomingTransfer;
import monero.wallet.model.MoneroMultisigSignResult;
import monero.wallet.model.MoneroOutgoingTransfer;
import monero.wallet.model.MoneroTxSet;
import monero.wallet.model.MoneroTxWallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class TradeTest {

    private static final long AMOUNT = 1000;
    private static final long BUYER_DEPOSIT = 150;

    @BeforeEach
    public void setup() {
        Version.setBaseCryptoNetworkId(1);
    }

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
    public void testCancelRequests() {
        TradePeer buyer = new TradePeer();
        TradePeer seller = new TradePeer();
        assertTrue(seller.canRequestCancel(buyer));
        seller.setCancelState(CancelState.REQUESTED);
        assertFalse(seller.canRequestCancel(buyer)); // repeated request
        seller.setCancelState(CancelState.DECLINED);
        assertFalse(seller.canRequestCancel(buyer)); // request after a decline
        assertTrue(buyer.canRequestCancel(seller));
        buyer.setCancelState(CancelState.REQUESTED);
        assertTrue(seller.canRequestCancel(buyer)); // agrees to the buyer's request
        seller.setCancelState(CancelState.REQUESTED);
        assertFalse(seller.canRequestCancel(buyer));
        assertFalse(buyer.canRequestCancel(seller));
    }

    @Test
    public void testCanceled() {
        Trade trade = mockTrade(false);
        TradePeer seller = new TradePeer();
        when(trade.getSeller()).thenReturn(seller);
        when(trade.getDisputeState()).thenReturn(Trade.DisputeState.NO_DISPUTE);
        when(trade.isCancelPermitted()).thenCallRealMethod();
        when(trade.isCancelAgreed()).thenCallRealMethod();
        when(trade.isCanceled()).thenCallRealMethod();
        assertFalse(trade.isCanceled()); // payout without a request to cancel
        seller.setCancelState(CancelState.DECLINED);
        assertTrue(trade.isCanceled()); // a decline cannot revoke an already delivered payout tx
        trade.getBuyer().setCancelState(CancelState.REQUESTED);
        assertTrue(trade.isCanceled()); // the seller agreed, known or not
        trade.getBuyer().setCancelState(CancelState.UNDEFINED);
        seller.setCancelState(CancelState.REQUESTED);
        assertTrue(trade.isCanceled());
        assertFalse(trade.isCancelPermitted()); // payout is published
        assertFalse(trade.shouldPublishTradeStatistics());
        when(trade.isPaymentMarkedSent()).thenReturn(true);
        assertFalse(trade.isCanceled()); // payout after the payment was marked sent
        when(trade.isPaymentMarkedSent()).thenReturn(false);
        when(trade.getDisputeState()).thenReturn(Trade.DisputeState.DISPUTE_OPENED);
        assertTrue(trade.isCanceled()); // opening a dispute cannot revoke a cancel payout

        // traders can agree to cancel until the payment is marked sent, a dispute opens or the payout is published
        when(trade.isPayoutPublished()).thenReturn(false);
        assertFalse(trade.isCancelPermitted());
        when(trade.getDisputeState()).thenReturn(Trade.DisputeState.NO_DISPUTE);
        assertTrue(trade.isCancelPermitted());
        assertFalse(trade.isCancelAgreed()); // buyer has not asked
        trade.getBuyer().setCancelState(CancelState.REQUESTED);
        assertTrue(trade.isCancelAgreed());
        when(trade.isPaymentMarkedSent()).thenReturn(true);
        assertFalse(trade.isCancelPermitted());
        assertFalse(trade.isCancelAgreed());
        when(trade.isPaymentMarkedSent()).thenReturn(false);
        when(trade.getPaymentSentDate()).thenReturn(1L);
        assertFalse(trade.isCancelPermitted()); // payment was marked sent before the state was reset
        when(trade.isPayoutPublished()).thenReturn(true);
        assertFalse(trade.isCanceled()); // so its payout is not a cancel
    }

    @Test
    public void testCancelPayoutWhileDisputeOpens() {
        Dispute dispute = mockDispute(null, 0, 1); // a ticket can close without a ruling
        Trade trade = mockTrade(false, dispute);
        TradePeer seller = new TradePeer();
        seller.setSecurityDeposit(BigInteger.valueOf(BUYER_DEPOSIT));
        seller.setCancelState(CancelState.REQUESTED);
        when(trade.getSeller()).thenReturn(seller);
        when(trade.getDisputeState()).thenReturn(Trade.DisputeState.DISPUTE_OPENED);
        when(trade.getDisputeResult()).thenCallRealMethod();
        when(trade.isCanceled()).thenCallRealMethod();
        when(trade.getOptionalAmount()).thenReturn(Optional.of(BigInteger.valueOf(AMOUNT)));
        MoneroTxWallet payout = new MoneroTxWallet().setFee(BigInteger.TEN).setIsConfirmed(false);
        doCallRealMethod().when(trade).setPayoutTx(payout);

        trade.setPayoutTx(payout);
        assertTrue(trade.isCanceled());
        assertEquals(BigInteger.valueOf(BUYER_DEPOSIT - 5), trade.getBuyer().getPayoutAmount());
        assertEquals(BigInteger.valueOf(BUYER_DEPOSIT + AMOUNT - 5), seller.getPayoutAmount());
        assertEquals(BigInteger.ZERO, ClosedTradableUtil.getTotalAmount(List.of(trade)));

        // an arbitrator's payout can be observed before its ruling arrives
        DisputeResult ruling = mockDispute(Winner.BUYER, BUYER_DEPOSIT + AMOUNT, 2).getDisputeResultProperty().get();
        ruling.setSellerPayoutAmountBeforeCost(BigInteger.valueOf(BUYER_DEPOSIT));
        ruling.setSubtractFeeFrom(DisputeResult.SubtractFeeFrom.BUYER_AND_SELLER);
        dispute.getDisputeResultProperty().set(ruling);
        trade.setPayoutTx(payout);
        assertFalse(trade.isCanceled());
        assertEquals(BigInteger.valueOf(BUYER_DEPOSIT + AMOUNT - 5), trade.getBuyer().getPayoutAmount());
        assertEquals(BigInteger.valueOf(BUYER_DEPOSIT - 5), seller.getPayoutAmount());
        assertEquals(BigInteger.valueOf(AMOUNT), ClosedTradableUtil.getTotalAmount(List.of(trade)));

        // a mirrored ticket without a result must not hide the ruling
        Dispute noRuling = mockDispute(null, 0, 3);
        when(trade.getDisputes()).thenReturn(List.of(dispute, noRuling));
        assertFalse(trade.isCanceled());
    }

    @Test
    public void testCancelActionable() {
        Trade trade = mockTrade(false);
        TradePeer seller = new TradePeer();
        TradePeer buyer = trade.getBuyer();
        when(trade.getSelf()).thenReturn(seller);
        when(trade.getTradePeer()).thenReturn(buyer);
        when(trade.getDisputeState()).thenReturn(Trade.DisputeState.NO_DISPUTE);
        when(trade.isPayoutPublished()).thenReturn(false);
        when(trade.isDepositsUnlocked()).thenReturn(true);
        when(trade.isCancelPermitted()).thenCallRealMethod();
        when(trade.isCancelActionable()).thenCallRealMethod();
        assertFalse(trade.isCancelActionable()); // the peer has not asked
        buyer.setCancelState(CancelState.REQUESTED);
        assertTrue(trade.isCancelActionable()); // the peer's request can be answered
        seller.setCancelState(CancelState.REQUESTED);
        assertFalse(trade.isCancelActionable()); // the seller agreed and waits for the buyer
        when(trade.isBuyer()).thenReturn(true);
        assertTrue(trade.isCancelActionable()); // the buyer completes an agreed cancel
        when(trade.isDepositsUnlocked()).thenReturn(false);
        assertFalse(trade.isCancelActionable());
    }

    @Test
    public void testSignedCancelKeepsBuyerPolling() {
        Trade trade = mockTrade(false);
        when(trade.isIdling()).thenCallRealMethod();
        when(trade.walletExistsNoSync()).thenReturn(true);
        when(trade.isPayoutPublished()).thenReturn(false);
        when(trade.isDepositsUnlocked()).thenReturn(true);
        when(trade.isDepositsFinalized()).thenReturn(true);
        when(trade.isBuyer()).thenReturn(true);
        when(trade.isSeller()).thenReturn(false);
        when(trade.isCancelSigned()).thenCallRealMethod();
        assertTrue(trade.isIdling());
        trade.getBuyer().setCancelPayoutTxHex("signed-cancel");
        assertFalse(trade.isIdling()); // the daemon may have accepted a broadcast which returned an error
        when(trade.isPayoutUnlocked()).thenReturn(true);
        assertTrue(trade.isIdling());
    }

    @Test
    public void testCancelSubmissionFailureKeepsOnlyPotentiallyPublishedHex() throws Exception {
        for (boolean previouslySigned : List.of(false, true)) {
            for (String error : List.of("Failed to parse multisig tx data.", "Not enough signers signed this transaction.", "Transaction was rejected by daemon")) {
                try (CancelFixture fixture = new CancelFixture(); MockedStatic<HavenoUtils> utils = mockStatic(HavenoUtils.class, CALLS_REAL_METHODS)) {
                    utils.when(() -> HavenoUtils.waitFor(anyLong())).thenAnswer(invocation -> null); // skip the delay between attempts
                    MoneroWallet wallet = fixture.prepareCancelWallet();
                    if (previouslySigned) fixture.self.setCancelPayoutTxHex("signed-cancel");
                    when(wallet.submitMultisigTxHex("signed-cancel")).thenThrow(new MoneroError(error));

                    boolean isRejected = error.contains("was rejected");
                    RuntimeException e = assertThrows(RuntimeException.class, () -> fixture.trade.processCancelPayoutTx("cancel-payout"));
                    assertEquals(!isRejected, e instanceof IllegalArgumentException);
                    boolean mayBePublished = previouslySigned || isRejected;
                    assertEquals(mayBePublished ? "signed-cancel" : null, fixture.self.getCancelPayoutTxHex());
                    assertEquals(mayBePublished, fixture.trade.isCancelSigned()); // only an unsubmitted cancel can release the payment guard
                    InOrder order = inOrder(fixture.manager, wallet);
                    order.verify(fixture.manager).persistNowAndWait();
                    order.verify(wallet).submitMultisigTxHex("signed-cancel");
                    verify(wallet, times(isRejected ? TradeProtocol.MAX_ATTEMPTS : 1)).submitMultisigTxHex("signed-cancel"); // a rejected cancel is submitted again
                    verify(wallet, times(previouslySigned ? 0 : 1)).signMultisigTxHex("cancel-payout");
                }
            }
        }
    }

    @Test
    public void testRejectedCancelIsSubmittedAgainThroughOtherConnections() throws Exception {
        try (CancelFixture fixture = new CancelFixture(); MockedStatic<HavenoUtils> utils = mockStatic(HavenoUtils.class, CALLS_REAL_METHODS)) {
            utils.when(() -> HavenoUtils.waitFor(anyLong())).thenAnswer(invocation -> null); // skip the delay between attempts
            MoneroWallet wallet = fixture.prepareCancelWallet();
            MoneroRpcConnection connection = new MoneroRpcConnection("http://localhost:18081");
            when(fixture.connectionService.getConnection()).thenReturn(connection);
            when(fixture.connectionService.isConnected()).thenReturn(true);
            when(wallet.getDaemonConnection()).thenReturn(connection);
            MoneroError rejection = new MoneroError("Transaction was rejected by daemon");
            when(wallet.submitMultisigTxHex("signed-cancel")).thenThrow(rejection, rejection).thenReturn(List.of("cancel-tx"));

            fixture.trade.processCancelPayoutTx("cancel-payout");
            verify(wallet, times(3)).submitMultisigTxHex("signed-cancel");
            verify(wallet).signMultisigTxHex("cancel-payout"); // the same signed payout tx is submitted each time
            verify(fixture.connectionService).requestConnectionSwitch(connection, fixture.trade, true);
            verify(fixture.trade).setPayoutState(Trade.PayoutState.PAYOUT_PUBLISHED);
            assertEquals("signed-cancel", fixture.self.getCancelPayoutTxHex());
        }
    }

    @Test
    public void testCancelPayoutIsNotSubmittedIfPersistenceFails() throws Exception {
        try (CancelFixture fixture = new CancelFixture()) {
            MoneroWallet wallet = fixture.prepareCancelWallet();
            CompletionException failure = new CompletionException(new IllegalStateException("disk unavailable"));
            doThrow(failure).when(fixture.manager).persistNowAndWait();

            IllegalStateException error = assertThrows(IllegalStateException.class, () -> fixture.trade.processCancelPayoutTx("cancel-payout"));
            assertSame(failure, error.getCause());
            assertEquals("signed-cancel", fixture.self.getCancelPayoutTxHex());
            assertTrue(fixture.trade.isCancelSigned());
            verify(fixture.manager).persistNowAndWait();
            verify(wallet).signMultisigTxHex("cancel-payout");
            verify(wallet, never()).submitMultisigTxHex(anyString());
        }
    }

    @Test
    public void testCancelRequestPersistsBeforeSending() {
        try (CancelFixture fixture = new CancelFixture()) {
            when(fixture.trade.createCancelPayoutTx()).thenAnswer(invocation -> {
                assertEquals(CancelState.UNDEFINED, fixture.self.getCancelState()); // not requested until the message can be sent
                return fixture.payout;
            });
            doAnswer(invocation -> {
                assertEquals(CancelState.REQUESTED, fixture.self.getCancelState());
                return null;
            }).when(fixture.manager).persistNowAndWait();
            fixture.protocol.onCancelRequested(mock(ResultHandler.class), mock(ErrorMessageHandler.class));
            InOrder order = inOrder(fixture.manager, fixture.mailbox);
            order.verify(fixture.manager).persistNowAndWait();
            order.verify(fixture.mailbox).sendEncryptedMailboxMessage(any(), any(), any(CancelTradeMessage.class), any());
        }
    }

    @Test
    public void testCancelRequestDoesNotSendIfPersistenceFails() {
        try (CancelFixture fixture = new CancelFixture()) {
            doThrow(new IllegalStateException("disk unavailable")).when(fixture.manager).persistNowAndWait();
            ErrorMessageHandler errorHandler = mock(ErrorMessageHandler.class);
            fixture.protocol.onCancelRequested(mock(ResultHandler.class), errorHandler);
            verify(fixture.mailbox, never()).sendEncryptedMailboxMessage(any(), any(), any(), any());
            verify(errorHandler).handleErrorMessage(anyString());
            assertEquals(CancelState.UNDEFINED, fixture.self.getCancelState());
        }
    }

    @Test
    public void testCancelStateIsPersisted() {
        for (CancelState state : CancelState.values()) {
            TradePeer peer = new TradePeer();
            peer.setCancelState(state);
            assertEquals(state, TradePeer.fromProto((protobuf.TradePeer) peer.toProtoMessage(), null).getCancelState());
        }
    }

    @Test
    public void testCancelDeclineAnswersOnceApplied() {
        try (CancelFixture fixture = new CancelFixture()) {
            when(fixture.trade.isBuyer()).thenReturn(true);
            when(fixture.trade.isSeller()).thenReturn(false);
            ResultHandler resultHandler = mock(ResultHandler.class);
            ErrorMessageHandler errorHandler = mock(ErrorMessageHandler.class);
            fixture.protocol.onCancelDeclined(resultHandler, errorHandler); // the peer has not asked
            verify(errorHandler).handleErrorMessage(anyString());

            // a decline which cannot be kept leaves the request and its payout tx to answer
            fixture.receive(new CancelTradeMessage("trade-id", fixture.peer.getNodeAddress(), "request", false, "cancel-payout", "multisig"));
            doThrow(new IllegalStateException("disk unavailable")).when(fixture.manager).persistNowAndWait();
            fixture.protocol.onCancelDeclined(resultHandler, errorHandler);
            verify(errorHandler).handleErrorMessage("disk unavailable");
            verify(resultHandler, never()).handleResult();
            assertEquals(CancelState.REQUESTED, fixture.peer.getCancelState());
            assertEquals("cancel-payout", fixture.peer.getCancelPayoutTxHex());
            assertNull(fixture.self.getCancelTradeMessage());

            doNothing().when(fixture.manager).persistNowAndWait();
            fixture.protocol.onCancelDeclined(resultHandler, errorHandler);
            verify(resultHandler).handleResult();
            assertEquals(CancelState.DECLINED, fixture.peer.getCancelState());
            assertNull(fixture.peer.getCancelPayoutTxHex());
            assertTrue(fixture.self.getCancelTradeMessage().isDeclined());
        }
    }

    @Test
    public void testFailedCancelRequestKeepsPendingDecline() {
        try (CancelFixture fixture = new CancelFixture()) {
            fixture.receive();
            fixture.protocol.onCancelDeclined(mock(ResultHandler.class), mock(ErrorMessageHandler.class));
            CancelTradeMessage decline = fixture.self.getCancelTradeMessage();
            assertTrue(decline.isDeclined());

            doThrow(new IllegalStateException("disk unavailable")).when(fixture.manager).persistNowAndWait();
            fixture.protocol.onCancelRequested(mock(ResultHandler.class), mock(ErrorMessageHandler.class));
            assertEquals(CancelState.UNDEFINED, fixture.self.getCancelState());
            assertEquals(decline, fixture.self.getCancelTradeMessage());
            fixture.resend();
            verify(fixture.mailbox, times(2)).sendEncryptedMailboxMessage(any(), any(), eq(decline), any());
        }
    }

    @Test
    public void testCancelSucceedsIfPersistingCompletionNoticeFails() throws Exception {
        try (CancelFixture fixture = new CancelFixture()) {
            MoneroWallet wallet = fixture.prepareCancelWallet();
            fixture.peer.setCancelState(CancelState.REQUESTED);
            fixture.peer.setCancelPayoutTxHex("cancel-payout");
            when(wallet.submitMultisigTxHex("signed-cancel")).thenAnswer(invocation -> {
                when(fixture.trade.isPayoutPublished()).thenReturn(true);
                return List.of("cancel-tx");
            });
            doAnswer(invocation -> null).doThrow(new IllegalStateException("disk unavailable"))
                    .when(fixture.manager).persistNowAndWait();
            ResultHandler resultHandler = mock(ResultHandler.class);
            ErrorMessageHandler errorHandler = mock(ErrorMessageHandler.class);

            fixture.protocol.onCancelConfirmed(resultHandler, errorHandler);
            InOrder order = inOrder(fixture.manager, wallet, resultHandler);
            order.verify(fixture.manager).persistNowAndWait();
            order.verify(wallet).submitMultisigTxHex("signed-cancel");
            order.verify(fixture.manager).persistNowAndWait();
            order.verify(resultHandler).handleResult();
            verify(errorHandler, never()).handleErrorMessage(any());
            assertTrue(fixture.trade.isPayoutPublished());
            assertTrue(fixture.trade.isCancelSigned());
        }
    }

    @Test
    public void testCancelIsOnlyConfirmedForCancelPayout() throws Exception {
        try (CancelFixture fixture = new CancelFixture()) {
            fixture.prepareCancelWallet();
            fixture.peer.setCancelState(CancelState.REQUESTED);
            when(fixture.trade.isPayoutPublished()).thenReturn(true); // e.g. after the buyer paid and the seller confirmed
            when(fixture.trade.isCancelPermitted()).thenReturn(false);
            ResultHandler resultHandler = mock(ResultHandler.class);
            ErrorMessageHandler errorHandler = mock(ErrorMessageHandler.class);

            fixture.protocol.onCancelConfirmed(resultHandler, errorHandler);
            verify(resultHandler, never()).handleResult();
            verify(errorHandler).handleErrorMessage(any());
            assertEquals(CancelState.UNDEFINED, fixture.self.getCancelState());
            verify(fixture.mailbox, never()).sendEncryptedMailboxMessage(any(), any(), any(), any());

            when(fixture.trade.isCanceled()).thenReturn(true);
            fixture.protocol.onCancelConfirmed(resultHandler, errorHandler);
            verify(resultHandler).handleResult();
        }
    }

    @Test
    public void testCancelRequestSurvivesUncertainDelivery() {
        try (CancelFixture fixture = new CancelFixture()) {
            doAnswer(invocation -> {
                invocation.getArgument(3, SendMailboxMessageListener.class).onFault("not sufficiently broadcast");
                return null;
            }).when(fixture.mailbox).sendEncryptedMailboxMessage(any(), any(), any(), any());
            ErrorMessageHandler errorHandler = mock(ErrorMessageHandler.class);
            fixture.protocol.onCancelRequested(mock(ResultHandler.class), errorHandler);
            assertEquals(CancelState.REQUESTED, fixture.self.getCancelState());
            verify(errorHandler).handleErrorMessage(anyString());

            // the buyer can receive the request and publish without its completion notice arriving
            when(fixture.trade.getBuyer()).thenReturn(fixture.peer);
            when(fixture.trade.getSeller()).thenReturn(fixture.self);
            when(fixture.trade.getDisputeState()).thenReturn(Trade.DisputeState.NO_DISPUTE);
            when(fixture.trade.isPayoutPublished()).thenReturn(true);
            when(fixture.trade.isCanceled()).thenCallRealMethod();
            assertEquals(CancelState.UNDEFINED, fixture.peer.getCancelState());
            assertTrue(fixture.trade.isCanceled());
        }
    }

    @Test
    public void testCancelRequestRollsBackIfPayoutCreationFails() {
        try (CancelFixture fixture = new CancelFixture()) {
            when(fixture.trade.createCancelPayoutTx()).thenThrow(new IllegalStateException("wallet unavailable"));
            ErrorMessageHandler errorHandler = mock(ErrorMessageHandler.class);
            fixture.protocol.onCancelRequested(mock(ResultHandler.class), errorHandler);
            assertEquals(CancelState.UNDEFINED, fixture.self.getCancelState());
            verify(fixture.mailbox, never()).sendEncryptedMailboxMessage(any(), any(), any(), any());
            verify(errorHandler).handleErrorMessage(anyString());
        }
    }

    @Test
    public void testCancelMessageIsResentUntilAcked() {
        try (CancelFixture fixture = new CancelFixture()) {
            fixture.peer.setCancelState(CancelState.REQUESTED);
            doAnswer(invocation -> {
                invocation.getArgument(3, SendMailboxMessageListener.class).onFault("no connections");
                return null;
            }).doAnswer(invocation -> {
                invocation.getArgument(3, SendMailboxMessageListener.class).onArrived();
                return null;
            }).when(fixture.mailbox).sendEncryptedMailboxMessage(any(), any(), any(), any());
            ErrorMessageHandler errorHandler = mock(ErrorMessageHandler.class);
            fixture.protocol.onCancelRequested(mock(ResultHandler.class), errorHandler);
            verify(errorHandler).handleErrorMessage(anyString());
            assertEquals(CancelState.REQUESTED, fixture.self.getCancelState());
            CancelTradeMessage sent = fixture.self.getCancelTradeMessage();

            // the agreement is sent again after a fault, and after it arrived without an ack
            fixture.resend();
            fixture.resend();
            verify(fixture.mailbox, times(3)).sendEncryptedMailboxMessage(any(), any(), eq(sent), any());
            assertEquals(List.of(2L, 4L, 8L), fixture.resendDelayMins);

            fixture.receive(new AckMessage(fixture.peer.getNodeAddress(), AckMessageSourceType.TRADE_MESSAGE, CancelTradeMessage.class.getSimpleName(), sent.getUid(), "trade-id", true, null));
            assertNull(fixture.self.getCancelTradeMessage());
            fixture.resend();
            verify(fixture.mailbox, times(3)).sendEncryptedMailboxMessage(any(), any(), any(), any());
        }
    }

    @Test
    public void testCancelMessageStoredInMailboxIsResentUntilAcked() {
        try (CancelFixture fixture = new CancelFixture()) {
            doAnswer(invocation -> {
                invocation.getArgument(3, SendMailboxMessageListener.class).onStoredInMailbox();
                return null;
            }).when(fixture.mailbox).sendEncryptedMailboxMessage(any(), any(), any(), any());
            ResultHandler resultHandler = mock(ResultHandler.class);
            fixture.protocol.onCancelRequested(resultHandler, mock(ErrorMessageHandler.class));
            verify(resultHandler).handleResult();
            CancelTradeMessage sent = fixture.self.getCancelTradeMessage();
            assertNotNull(sent);
            assertEquals(sent, TradePeer.fromProto((protobuf.TradePeer) fixture.self.toProtoMessage(), null).getCancelTradeMessage());
            assertEquals(SendMailboxMessageTask.INITIAL_RESEND_DELAY_MINS_MAILBOX, fixture.resendDelayMins.get(fixture.resendDelayMins.size() - 1));
            fixture.resend();
            verify(fixture.mailbox, times(2)).sendEncryptedMailboxMessage(any(), any(), eq(sent), any());
            fixture.protocol.onRestart();
            verify(fixture.mailbox, times(3)).sendEncryptedMailboxMessage(any(), any(), eq(sent), any());
            fixture.receive(new AckMessage(fixture.peer.getNodeAddress(), AckMessageSourceType.TRADE_MESSAGE, CancelTradeMessage.class.getSimpleName(), sent.getUid(), "trade-id", true, null));
            assertNull(fixture.self.getCancelTradeMessage());
            assertTrue(fixture.resends.isEmpty());
        }
    }

    @Test
    public void testCancelRetriesAreBounded() {
        try (CancelFixture fixture = new CancelFixture()) {
            fixture.protocol.onCancelRequested(mock(ResultHandler.class), mock(ErrorMessageHandler.class));
            for (int i = 0; i < TradeProtocol.MAX_CANCEL_RESEND_ATTEMPTS + 5; i++) fixture.resend();
            assertTrue(fixture.resendDelayMins.stream().allMatch(delay -> delay > 0 && delay <= SendMailboxMessageTask.INITIAL_RESEND_DELAY_MINS_MAILBOX));
            verify(fixture.mailbox, times(TradeProtocol.MAX_CANCEL_RESEND_ATTEMPTS + 1)).sendEncryptedMailboxMessage(any(), any(), any(), any());
        }
    }

    @Test
    public void testCancelRetryUsesFreshConnectionAfterArrivalWithoutAck() {
        try (CancelFixture fixture = new CancelFixture()) {
            NetworkNode network = fixture.trade.getProcessModel().getP2PService().getNetworkNode();
            Connection other = mock(Connection.class);
            when(other.getPeersNodeAddressOptional()).thenReturn(Optional.of(new NodeAddress("other.onion", 9999)));
            when(network.getAllConnections()).thenReturn(Set.of(other));
            doAnswer(invocation -> {
                invocation.getArgument(3, SendMailboxMessageListener.class).onArrived();
                return null;
            }).when(fixture.mailbox).sendEncryptedMailboxMessage(any(), any(), any(), any());
            fixture.protocol.onCancelRequested(mock(ResultHandler.class), mock(ErrorMessageHandler.class));
            verify(network, never()).closeConnections(any(), any());
            fixture.resend();
            verify(network).closeConnections(fixture.peer.getNodeAddress(), CloseConnectionReason.NO_ACK_RECEIVED);

            // sending needs another connection, so keep the trade peer's connection if it is the only one
            when(other.getPeersNodeAddressOptional()).thenReturn(Optional.of(fixture.peer.getNodeAddress()));
            fixture.resend();
            verify(network).closeConnections(any(), any());
        }
    }

    @Test
    public void testCancelPayoutRecoversAfterTemporaryWalletFailures() {
        try (CancelFixture fixture = new CancelFixture()) {
            fixture.self.setCancelState(CancelState.REQUESTED);
            int[] attempts = {0};
            when(fixture.trade.createCancelPayoutTx()).thenAnswer(invocation -> {
                if (++attempts[0] <= TradeProtocol.MAX_ATTEMPTS) throw new IllegalStateException("wallet unavailable");
                return fixture.payout;
            });
            for (int i = 0; i <= TradeProtocol.MAX_ATTEMPTS; i++) fixture.receive();
            verify(fixture.trade, times(TradeProtocol.MAX_ATTEMPTS + 1)).createCancelPayoutTx();
            verify(fixture.mailbox).sendEncryptedMailboxMessage(any(), any(), any(CancelTradeMessage.class), any());
            verify(fixture.manager).sendAckMessage(any(), any(), eq(fixture.message), eq(true), any(), any());
        }
    }

    @Test
    public void testOldCancelDeliveryDoesNotStopLatestMessage() {
        try (CancelFixture fixture = new CancelFixture()) {
            fixture.protocol.onCancelRequested(mock(ResultHandler.class), mock(ErrorMessageHandler.class));
            CancelTradeMessage first = fixture.self.getCancelTradeMessage();
            ArgumentCaptor<SendMailboxMessageListener> listener = ArgumentCaptor.forClass(SendMailboxMessageListener.class);
            verify(fixture.mailbox).sendEncryptedMailboxMessage(any(), any(), eq(first), listener.capture());
            fixture.receive();
            CancelTradeMessage latest = fixture.self.getCancelTradeMessage();
            assertFalse(first.getUid().equals(latest.getUid()));
            listener.getValue().onStoredInMailbox();
            fixture.receive(new AckMessage(fixture.peer.getNodeAddress(), AckMessageSourceType.TRADE_MESSAGE, CancelTradeMessage.class.getSimpleName(), first.getUid(), "trade-id", true, null));
            assertEquals(latest, fixture.self.getCancelTradeMessage());
            fixture.resend();
            verify(fixture.mailbox, times(2)).sendEncryptedMailboxMessage(any(), any(), eq(latest), any());
        }
    }

    @Test
    public void testStaleCancelDeclineCannotUndoAgreement() {
        for (boolean restart : List.of(false, true)) {
            try (CancelFixture fixture = new CancelFixture()) {
                fixture.protocol.onCancelRequested(mock(ResultHandler.class), mock(ErrorMessageHandler.class));
                CancelTradeMessage decline = new CancelTradeMessage("trade-id", fixture.peer.getNodeAddress(), "decline", true, null, null);
                fixture.receive(decline);
                assertEquals(CancelState.DECLINED, fixture.self.getCancelState());

                // the peer changes their mind and asks, then this trader agrees
                fixture.receive();
                fixture.protocol.onCancelRequested(mock(ResultHandler.class), mock(ErrorMessageHandler.class));
                CancelTradeMessage agreement = fixture.self.getCancelTradeMessage();
                assertEquals(CancelState.REQUESTED, fixture.self.getCancelState());
                assertEquals(CancelState.REQUESTED, fixture.peer.getCancelState());

                // an earlier decline can arrive again, including after the processed uid is lost on restart
                TestProtocol protocol = restart ? new TestProtocol(fixture.trade) : fixture.protocol;
                DecryptedMessageWithPubKey decrypted = mock(DecryptedMessageWithPubKey.class);
                when(decrypted.getNetworkEnvelope()).thenReturn(decline);
                protocol.onMailboxMessageAdded(decrypted, fixture.peer.getNodeAddress());
                assertEquals(CancelState.REQUESTED, fixture.self.getCancelState());
                assertEquals(agreement, fixture.self.getCancelTradeMessage());
                verify(fixture.manager, times(2)).sendAckMessage(any(), any(), eq(decline), eq(true), any(), any());
                protocol.onRestart();
                verify(fixture.mailbox, times(2)).sendEncryptedMailboxMessage(any(), any(), eq(agreement), any());
            }
        }
    }

    @Test
    public void testDeclinedCancelRequestIsNotAppliedAgain() {
        for (boolean isBuyer : List.of(false, true)) {
            for (boolean agrees : List.of(false, true)) {
                try (CancelFixture fixture = new CancelFixture()) {
                    when(fixture.trade.isBuyer()).thenReturn(isBuyer);
                    when(fixture.trade.isSeller()).thenReturn(!isBuyer);
                    CancelTradeMessage request = new CancelTradeMessage("trade-id", fixture.peer.getNodeAddress(), "request", false, isBuyer ? "cancel-payout" : null, "multisig");

                    // this trader declines the peer's request, then asks
                    fixture.receive(request);
                    fixture.protocol.onCancelDeclined(mock(ResultHandler.class), mock(ErrorMessageHandler.class));
                    fixture.protocol.onCancelRequested(mock(ResultHandler.class), mock(ErrorMessageHandler.class));
                    assertEquals(CancelState.REQUESTED, fixture.self.getCancelState());

                    // a late copy of the declined request is no agreement, also after a restart
                    TestProtocol protocol = new TestProtocol(fixture.trade);
                    fixture.receive(protocol, request);
                    assertEquals(CancelState.DECLINED, fixture.peer.getCancelState());
                    assertNull(fixture.peer.getCancelPayoutTxHex());
                    verify(fixture.manager, times(2)).sendAckMessage(any(), any(), eq(request), eq(true), any(), any());
                    verify(fixture.trade, times(isBuyer ? 0 : 1)).createCancelPayoutTx();

                    // the peer's answer still applies
                    fixture.receive(protocol, new CancelTradeMessage("trade-id", fixture.peer.getNodeAddress(), "answer", !agrees, agrees && isBuyer ? "cancel-payout-2" : null, null));
                    CancelState answer = agrees ? CancelState.REQUESTED : CancelState.DECLINED;
                    assertEquals(answer, fixture.self.getCancelState());
                    assertEquals(answer, fixture.peer.getCancelState());
                    assertEquals(agrees && isBuyer ? "cancel-payout-2" : null, fixture.peer.getCancelPayoutTxHex());
                    verify(fixture.trade, times(isBuyer ? 0 : agrees ? 2 : 1)).createCancelPayoutTx();
                }
            }
        }
    }

    @Test
    public void testCancelRequestIsNotAppliedAgainAfterNewerRequest() {
        try (CancelFixture fixture = new CancelFixture()) {
            fixture.self.setCancelState(CancelState.REQUESTED);
            CancelTradeMessage older = new CancelTradeMessage("trade-id", fixture.peer.getNodeAddress(), "older", false, null, "multisig-1");
            CancelTradeMessage newer = new CancelTradeMessage("trade-id", fixture.peer.getNodeAddress(), "newer", false, null, "multisig-2");

            // late copies keep the newer multisig data and use none of the seller's attempts
            for (CancelTradeMessage message : List.of(older, newer, older, newer, older)) fixture.receive(message);
            assertEquals("multisig-2", fixture.peer.getUpdatedMultisigHex());
            verify(fixture.trade, times(2)).createCancelPayoutTx();
            verify(fixture.manager, times(5)).sendAckMessage(any(), any(), any(), eq(true), any(), any());
        }
    }

    @Test
    public void testProcessedCancelMessagesArePersistedAndBounded() {
        TradePeer maker = mock(TradePeer.class);
        PubKeyRing pubKeyRing = new PubKeyRing(Sig.generateKeyPair().getPublic(), Encryption.generateKeyPair().getPublic());
        ProcessModel processModel = new ProcessModel("trade-id", "account", pubKeyRing, new TradePeer(), maker, new TradePeer());
        when(maker.toProtoMessage()).thenReturn(new TradePeer().toProtoMessage());
        for (int i = 0; i < 100; i++) processModel.setCancelMessageProcessed("uid-" + i);
        ProcessModel restored = ProcessModel.fromProto(processModel.toProtoMessage(), null);
        assertEquals(processModel.getProcessedCancelMessageUids(), restored.getProcessedCancelMessageUids());
        assertTrue(restored.isCancelMessageProcessed("uid-99"));
        assertFalse(restored.isCancelMessageProcessed("uid-0"));

        // a uid recorded while the traders are serialized is not kept without what its message changed
        when(maker.toProtoMessage()).thenAnswer(invocation -> {
            processModel.setCancelMessageProcessed("late");
            return new TradePeer().toProtoMessage();
        });
        assertFalse(processModel.toProtoMessage().getProcessedCancelMessageUidsList().contains("late"));
        assertTrue(processModel.toProtoMessage().getProcessedCancelMessageUidsList().contains("late"));
    }

    @Test
    public void testCancelMessageIsResentAfterRestartWhileItStands() {
        try (CancelFixture fixture = new CancelFixture()) {
            fixture.protocol.onCancelRequested(mock(ResultHandler.class), mock(ErrorMessageHandler.class));
            CancelTradeMessage sent = fixture.self.getCancelTradeMessage();
            assertEquals(sent, TradePeer.fromProto((protobuf.TradePeer) fixture.self.toProtoMessage(), null).getCancelTradeMessage());
            fixture.protocol.onRestart();
            verify(fixture.mailbox, times(2)).sendEncryptedMailboxMessage(any(), any(), eq(sent), any());

            // not once the peer declined the request or the trade can no longer be canceled
            fixture.receive(new CancelTradeMessage("trade-id", fixture.peer.getNodeAddress(), "decline", true, null, null));
            assertEquals(CancelState.DECLINED, fixture.self.getCancelState());
            fixture.protocol.onRestart();
            fixture.self.setCancelState(CancelState.REQUESTED);
            when(fixture.trade.isCancelPermitted()).thenReturn(false);
            fixture.protocol.onRestart();
            verify(fixture.mailbox, times(2)).sendEncryptedMailboxMessage(any(), any(), any(), any());
        }
    }

    @Test
    public void testCancelMessageIsAckedOnceProcessed() {
        try (CancelFixture fixture = new CancelFixture()) {
            fixture.self.setCancelState(CancelState.REQUESTED);
            when(fixture.trade.createCancelPayoutTx()).thenThrow(new IllegalStateException("wallet unavailable")).thenReturn(fixture.payout);
            fixture.receive();
            verify(fixture.manager, never()).sendAckMessage(any(), any(), any(), anyBoolean(), any(), any());
            verify(fixture.mailbox, never()).removeMailboxMsg(any());

            // the peer's next send is processed and acked, and a repeat is only acked
            fixture.receive();
            fixture.receive();
            verify(fixture.manager, times(2)).sendAckMessage(any(), any(), eq(fixture.message), eq(true), any(), any());
            verify(fixture.mailbox, times(2)).removeMailboxMsg(fixture.message);
            verify(fixture.trade, times(2)).createCancelPayoutTx();
            verify(fixture.mailbox).sendEncryptedMailboxMessage(any(), any(), any(), any());
        }
    }

    @Test
    public void testCancelMessageIsAckedIfPayoutIsPublishedWhileCreatingAnother() {
        try (CancelFixture fixture = new CancelFixture()) {
            fixture.self.setCancelState(CancelState.REQUESTED);
            boolean[] published = {false};
            when(fixture.trade.isPayoutPublished()).thenAnswer(invocation -> published[0]);
            when(fixture.trade.createCancelPayoutTx()).thenAnswer(invocation -> {
                published[0] = true; // seen by the poll after the wallet fails to spend the outputs again
                throw new RuntimeException("payout tx is already published");
            });
            fixture.receive();
            verify(fixture.mailbox, never()).sendEncryptedMailboxMessage(any(), any(), any(), any());
            verify(fixture.manager).sendAckMessage(any(), any(), eq(fixture.message), eq(true), any(), any());
            verify(fixture.mailbox).removeMailboxMsg(fixture.message);
        }
    }

    @Test
    public void testCancelRequestPersistsBeforeRemovingMailboxMessage() {
        try (CancelFixture fixture = new CancelFixture()) {
            doAnswer(invocation -> {
                assertEquals(CancelState.REQUESTED, fixture.peer.getCancelState());
                assertTrue(fixture.trade.getProcessModel().isCancelMessageProcessed(fixture.message.getUid()));
                return null;
            }).when(fixture.manager).persistNowAndWait();
            fixture.receive();
            InOrder order = inOrder(fixture.manager, fixture.mailbox);
            order.verify(fixture.manager).persistNowAndWait();
            order.verify(fixture.manager).sendAckMessage(any(), any(), eq(fixture.message), eq(true), any(), any());
            order.verify(fixture.mailbox).removeMailboxMsg(fixture.message);
        }
    }

    @Test
    public void testCancelRequestRemainsInMailboxIfPersistenceFails() {
        try (CancelFixture fixture = new CancelFixture()) {
            doThrow(new IllegalStateException("disk unavailable")).when(fixture.manager).persistNowAndWait();
            assertThrows(IllegalStateException.class, fixture::receive);
            verify(fixture.manager, never()).sendAckMessage(any(), any(), any(), anyBoolean(), any(), any());
            verify(fixture.mailbox, never()).removeMailboxMsg(any());
        }
    }

    @Test
    public void testIneligibleCancelRequestGetsDecline() {
        try (CancelFixture fixture = new CancelFixture()) {
            when(fixture.trade.isCancelPermitted()).thenReturn(false);
            fixture.receive();
            ArgumentCaptor<CancelTradeMessage> reply = ArgumentCaptor.forClass(CancelTradeMessage.class);
            verify(fixture.mailbox).sendEncryptedMailboxMessage(any(), any(), reply.capture(), any());
            assertTrue(reply.getValue().isDeclined());
            assertEquals(CancelState.UNDEFINED, fixture.peer.getCancelState());

            // unlike a request, the decline is sent again while the trade cannot be canceled
            fixture.resend();
            verify(fixture.mailbox, times(2)).sendEncryptedMailboxMessage(any(), any(), eq(reply.getValue()), any());
        }
    }

    @Test
    public void testIneligibleCancelDeclineDoesNotGetAnotherDecline() {
        try (CancelFixture fixture = new CancelFixture()) {
            when(fixture.trade.isCancelPermitted()).thenReturn(false);
            fixture.receive(new CancelTradeMessage("trade-id", fixture.peer.getNodeAddress(), "decline", true, null, null));
            verify(fixture.mailbox, never()).sendEncryptedMailboxMessage(any(), any(), any(), any());
        }
    }

    @Test
    public void testPublishedCancelDoesNotGetDecline() {
        try (CancelFixture fixture = new CancelFixture()) {
            when(fixture.trade.isCancelPermitted()).thenReturn(false);
            when(fixture.trade.isPayoutPublished()).thenReturn(true);
            fixture.receive();
            verify(fixture.mailbox, never()).sendEncryptedMailboxMessage(any(), any(), any(), any());
            verify(fixture.mailbox).removeMailboxMsg(fixture.message);
        }
    }

    @Test
    public void testCancelResendsRemainBoundedWithNewMultisigData() {
        try (CancelFixture fixture = new CancelFixture()) {
            fixture.self.setCancelState(CancelState.REQUESTED);
            for (int i = 0; i <= TradeProtocol.MAX_ATTEMPTS; i++) {
                fixture.receive(new CancelTradeMessage("trade-id", fixture.peer.getNodeAddress(), "uid-" + i, false, null, "multisig-" + i));
            }
            verify(fixture.trade, times(TradeProtocol.MAX_ATTEMPTS)).createCancelPayoutTx();
            verify(fixture.manager, times(TradeProtocol.MAX_ATTEMPTS)).sendAckMessage(any(), any(), any(), eq(true), any(), any()); // the unanswered request stays for a restart
        }
    }

    @Test
    public void testCancelMessagesNotifyOnce() {
        try (CancelFixture fixture = new CancelFixture(); MockedStatic<HavenoUtils> sounds = mockStatic(HavenoUtils.class)) {
            when(fixture.trade.isDepositsUnlocked()).thenReturn(false); // the peer's wallet can see the unlock first
            fixture.receive();
            fixture.receive(); // a repeated request is no news
            sounds.verify(HavenoUtils::playChimeSound);
            verify(fixture.notifications).sendTradeNotification(fixture.trade, "Cancel Requested", "The buyer has asked to cancel the trade");

            // a seller who asked waits for the buyer, who can complete the cancel once the seller agrees
            fixture.self.setCancelState(CancelState.REQUESTED);
            fixture.peer.setCancelState(CancelState.UNDEFINED);
            fixture.receive(new CancelTradeMessage("trade-id", fixture.peer.getNodeAddress(), "ask", false, null, "multisig"));
            sounds.verify(HavenoUtils::playChimeSound);
            fixture.peer.setCancelState(CancelState.UNDEFINED);
            when(fixture.trade.isSeller()).thenReturn(false);
            when(fixture.trade.isBuyer()).thenReturn(true);
            fixture.receive(new CancelTradeMessage("trade-id", fixture.peer.getNodeAddress(), "agree", false, "cancel-payout", "multisig"));
            sounds.verify(HavenoUtils::playChimeSound, times(2));
            verify(fixture.notifications).sendTradeNotification(fixture.trade, "Cancel Agreed", "The seller has agreed to cancel the trade");

            fixture.peer.setCancelState(CancelState.UNDEFINED);
            for (int i = 0; i < 2; i++) fixture.receive(new CancelTradeMessage("trade-id", fixture.peer.getNodeAddress(), "decline-" + i, true, null, null));
            assertEquals(CancelState.DECLINED, fixture.self.getCancelState());
            sounds.verify(HavenoUtils::playChimeSound, times(3));
            verify(fixture.notifications).sendTradeNotification(fixture.trade, "Cancel Declined", "The seller has declined to cancel the trade");
            verify(fixture.notifications, times(3)).sendTradeNotification(any(), anyString(), anyString());
        }
    }

    @Test
    public void testFailedCancelNotificationKeepsMessageProcessed() {
        try (CancelFixture fixture = new CancelFixture()) {
            doThrow(new NullPointerException("no volume")).when(fixture.notifications).sendTradeNotification(any(), anyString(), anyString());
            fixture.receive();
            assertEquals(CancelState.REQUESTED, fixture.peer.getCancelState());
            verify(fixture.manager).sendAckMessage(any(), any(), eq(fixture.message), eq(true), any(), any());
            verify(fixture.mailbox).removeMailboxMsg(fixture.message);
        }
    }

    private static class CancelFixture implements AutoCloseable {
        private final Trade trade = mock(Trade.class);
        private final TradePeer self = new TradePeer();
        private final TradePeer peer = new TradePeer();
        private final TradeManager manager = mock(TradeManager.class);
        private final MailboxMessageService mailbox = mock(MailboxMessageService.class);
        private final CancelTradeMessage message = new CancelTradeMessage("trade-id", new NodeAddress("peer.onion", 9999), "uid", false, null, "multisig");
        private final MoneroTxWallet payout = mock(MoneroTxWallet.class, RETURNS_DEEP_STUBS);
        private final MockedStatic<ThreadUtils> threads = mockStatic(ThreadUtils.class);
        private final MockedStatic<UserThread> timers = mockStatic(UserThread.class);
        private final CoreNotificationService notifications = mock(CoreNotificationService.class);
        private final CoreNotificationService originalNotifications = HavenoUtils.notificationService;
        private final XmrConnectionService connectionService = mock(XmrConnectionService.class);
        private final List<Runnable> resends = new ArrayList<>();
        private final List<Long> resendDelayMins = new ArrayList<>();
        private final TestProtocol protocol;

        private CancelFixture() {
            threads.when(() -> ThreadUtils.execute(any(Runnable.class), anyString())).thenAnswer(invocation -> {
                invocation.getArgument(0, Runnable.class).run();
                return null;
            });
            timers.when(() -> UserThread.runAfter(any(Runnable.class), anyLong(), eq(TimeUnit.MINUTES))).thenAnswer(invocation -> {
                Runnable resend = invocation.getArgument(0, Runnable.class);
                resends.add(resend);
                resendDelayMins.add(invocation.getArgument(1, Long.class));
                Timer timer = mock(Timer.class);
                doAnswer(ignored -> resends.remove(resend)).when(timer).stop();
                return timer;
            });
            ProcessModel process = mock(ProcessModel.class, withSettings().useConstructor("trade-id", "account", mock(PubKeyRing.class)).defaultAnswer(RETURNS_DEEP_STUBS));
            when(process.isCancelMessageProcessed(anyString())).thenCallRealMethod();
            doCallRealMethod().when(process).setCancelMessageProcessed(anyString());
            when(process.getTradeManager()).thenReturn(manager);
            when(process.getP2PService().getMailboxMessageService()).thenReturn(mailbox);
            when(process.getMyNodeAddress()).thenReturn(new NodeAddress("self.onion", 9999));
            when(trade.getProcessModel()).thenReturn(process);
            when(trade.getId()).thenReturn("trade-id");
            when(trade.getLock()).thenReturn(new Object());
            when(trade.getSelf()).thenReturn(self);
            when(trade.getArbitrator()).thenReturn(new TradePeer());
            when(trade.getTradePeer()).thenReturn(peer);
            when(trade.getTradePeer(message.getSenderNodeAddress())).thenReturn(peer);
            when(trade.getVerifiedTradePeer(any())).thenReturn(peer);
            peer.setNodeAddress(message.getSenderNodeAddress());
            peer.setPubKeyRing(mock(PubKeyRing.class));
            when(trade.getBuyer()).thenReturn(peer);
            when(trade.isDepositRequested()).thenReturn(true);
            when(trade.isSeller()).thenReturn(true);
            when(trade.isInitialized()).thenReturn(true);
            when(trade.isCancelPermitted()).thenReturn(true);
            when(trade.isCancelActionable()).thenCallRealMethod();
            when(trade.isDepositsUnlocked()).thenReturn(true);
            HavenoUtils.notificationService = notifications;
            when(payout.getTxSet().getMultisigTxHex()).thenReturn("cancel-payout");
            when(trade.createCancelPayoutTx()).thenReturn(payout);
            protocol = new TestProtocol(trade);
        }

        private MoneroWallet prepareCancelWallet() throws Exception {
            MoneroWallet wallet = mock(MoneroWallet.class);
            XmrWalletService walletService = mock(XmrWalletService.class);
            setField(XmrWalletBase.class, trade, "wallet", wallet);
            setField(XmrWalletBase.class, trade, "walletLock", new Object());
            setField(XmrWalletBase.class, trade, "xmrConnectionService", connectionService);
            setField(Trade.class, trade, "pollLock", new Object());
            setField(Trade.class, trade, "xmrWalletService", walletService);
            setField(Trade.class, trade, "processModel", trade.getProcessModel());
            when(trade.getWallet()).thenReturn(wallet);
            when(trade.isBuyer()).thenReturn(true);
            when(trade.isSeller()).thenReturn(false);
            when(trade.getBuyer()).thenReturn(self);
            when(trade.getSeller()).thenReturn(peer);
            self.setDepositTx(new MoneroTxWallet().setIncomingTransfers(List.of(new MoneroIncomingTransfer().setAmount(BigInteger.valueOf(BUYER_DEPOSIT)))));
            peer.setDepositTx(new MoneroTxWallet().setIncomingTransfers(List.of(new MoneroIncomingTransfer().setAmount(BigInteger.valueOf(BUYER_DEPOSIT + AMOUNT)))));
            Contract contract = mock(Contract.class);
            when(contract.getBuyerPayoutAddressString()).thenReturn("buyer-address");
            when(contract.getSellerPayoutAddressString()).thenReturn("seller-address");
            when(trade.getContract()).thenReturn(contract);
            MoneroTxWallet tx = new MoneroTxWallet().setFee(BigInteger.TEN).setWeight(100L)
                    .setUnlockTime(BigInteger.ZERO).setChangeAmount(BigInteger.ZERO)
                    .setOutputSum(BigInteger.valueOf(BUYER_DEPOSIT * 2 + AMOUNT - 10))
                    .setOutgoingTransfer(new MoneroOutgoingTransfer().setDestinations(List.of(
                            new MoneroDestination("buyer-address", BigInteger.valueOf(BUYER_DEPOSIT - 5)),
                            new MoneroDestination("seller-address", BigInteger.valueOf(BUYER_DEPOSIT + AMOUNT - 5)))));
            MoneroTxSet txSet = new MoneroTxSet().setTxs(List.of(tx));
            when(wallet.describeTxSet(any())).thenReturn(txSet);
            when(wallet.describeMultisigTxSet("signed-cancel")).thenReturn(txSet);
            MoneroMultisigSignResult signed = new MoneroMultisigSignResult();
            signed.setSignedMultisigTxHex("signed-cancel");
            when(wallet.signMultisigTxHex("cancel-payout")).thenReturn(signed);
            when(wallet.getUnlockedBalance()).thenReturn(BigInteger.valueOf(BUYER_DEPOSIT * 2 + AMOUNT));
            when(walletService.getFeeEstimate(100L)).thenReturn(BigInteger.TEN);
            when(trade.isCancelSigned()).thenCallRealMethod();
            doCallRealMethod().when(trade).processCancelPayoutTx(anyString());
            return wallet;
        }

        // runs the next scheduled resend
        private void resend() {
            if (!resends.isEmpty()) resends.remove(0).run();
        }

        private void receive() {
            receive(message);
        }

        private void receive(NetworkEnvelope received) {
            receive(protocol, received);
        }

        private void receive(TradeProtocol protocol, NetworkEnvelope received) {
            DecryptedMessageWithPubKey decrypted = mock(DecryptedMessageWithPubKey.class);
            when(decrypted.getNetworkEnvelope()).thenReturn(received);
            protocol.onMailboxMessageAdded(decrypted, peer.getNodeAddress());
        }

        @Override
        public void close() {
            HavenoUtils.notificationService = originalNotifications;
            timers.close();
            threads.close();
        }
    }

    private static void setField(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class TestProtocol extends TradeProtocol {

        private TestProtocol(Trade trade) {
            super(trade);
        }

        @Override
        public Class<? extends TradeTask>[] getDepositsConfirmedTasks() {
            return null;
        }

        private void onRestart() {
            onInitializeAfterMailboxMessages();
        }
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
