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

package haveno.core.trade;

import com.google.protobuf.ByteString;
import com.google.protobuf.TextFormat;
import haveno.common.app.Version;
import haveno.common.crypto.AuthenticatedEncryption;
import haveno.common.crypto.Encryption;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.Sig;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.offer.Offer;
import haveno.core.payment.payload.FasterPaymentsAccountPayload;
import haveno.core.trade.protocol.ProcessModel;
import haveno.core.trade.protocol.TradePeer;
import haveno.core.trade.protocol.tasks.ProcessSignContractRequest;
import haveno.core.trade.messages.SignContractRequest;
import haveno.core.trade.messages.SignContractResponse;
import haveno.core.util.JsonUtil;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.SendDirectMessageListener;
import java.math.BigInteger;
import javax.crypto.SecretKey;
import monero.daemon.model.MoneroNetworkType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentAccountEncryptionTest {
    @Test
    void contractResponseUsesConfiguredPaymentAccountEncryption() throws Exception {
        String payout = "4BJHitCigGy6giuYsJFP26KGkTKiQDJ6HJP1pan2ir2CCV8Twc2WWmo4fu1NVXt8XLGYAkjo5cJ3yH68Lfz9ZXEUJ9MeqPW";
        Trade trade = mock(Trade.class);
        ProcessModel process = mock(ProcessModel.class);
        TradePeer maker = new TradePeer();
        TradePeer taker = new TradePeer();
        NodeAddress peerAddress = new NodeAddress("maker", 1);
        maker.setNodeAddress(peerAddress);
        FasterPaymentsAccountPayload payload = new FasterPaymentsAccountPayload("FASTER_PAYMENTS", "account");
        payload.setHolderName("Alice");
        taker.setPaymentAccountPayload(payload);
        taker.setPaymentAccountPayloadHash(payload.getHash());
        KeyRing keyRing = mock(KeyRing.class);
        when(keyRing.getSignatureKeyPair()).thenReturn(Sig.generateKeyPair());
        P2PService p2p = mock(P2PService.class);
        Offer offer = mock(Offer.class);
        when(offer.getId()).thenReturn("trade");
        Contract contract = mock(Contract.class);
        when(trade.getOffer()).thenReturn(offer);
        when(trade.getId()).thenReturn("trade");
        when(trade.getProcessModel()).thenReturn(process);
        when(trade.getMaker()).thenReturn(maker);
        when(trade.getSelf()).thenReturn(taker);
        when(trade.getTradePeer()).thenReturn(maker);
        when(trade.getTradePeer(peerAddress)).thenReturn(maker);
        when(trade.createContract()).thenReturn(contract);
        when(process.getMaker()).thenReturn(maker);
        when(process.getTaker()).thenReturn(taker);
        when(process.getTempTradePeerNodeAddress()).thenReturn(peerAddress);
        when(process.getMultisigAddress()).thenReturn("multisig");
        when(process.getKeyRing()).thenReturn(keyRing);
        when(process.getP2PService()).thenReturn(p2p);
        when(process.getTradeManager()).thenReturn(mock(TradeManager.class));
        var request = protobuf.SignContractRequest.newBuilder().setTradeId("trade").setUid("uid")
                .setAccountId("maker-account").setPaymentAccountPayloadHash(ByteString.copyFrom(new byte[32]))
                .setPayoutAddress(payout).setDepositTxHash("deposit");
        // Schemas predating multisig-bound contracts ignore the address.
        TextFormat.Parser.newBuilder().setAllowUnknownFields(true).build().merge("multisig_address: 'multisig'", request);
        when(process.getTradeMessage()).thenReturn(SignContractRequest.fromProto(request.build(), null, Version.getP2PMessageVersion()));
        ErrorMessageHandler failure = mock(ErrorMessageHandler.class);
        TaskRunner<Trade> runner = new TaskRunner<>(trade, Trade.class, () -> { }, failure);
        runner.addTasks(ProcessSignContractRequest.class);
        try (var wallet = mockStatic(XmrWalletService.class); var json = mockStatic(JsonUtil.class)) {
            wallet.when(XmrWalletService::getMoneroNetworkType).thenReturn(MoneroNetworkType.MAINNET);
            json.when(() -> JsonUtil.objectToJson(contract)).thenReturn("{}");
            runner.run();
        }

        verifyNoInteractions(failure);
        ArgumentCaptor<SignContractResponse> response = ArgumentCaptor.forClass(SignContractResponse.class);
        verify(p2p).sendEncryptedDirectMessage(eq(peerAddress), isNull(), response.capture(), any(SendDirectMessageListener.class));
        byte[] ciphertext = response.getValue().getEncryptedPaymentAccountPayload();
        assertEquals(Version.NETWORK_ENCRYPTION_VERSION == 2, AuthenticatedEncryption.isEnvelope(ciphertext));
        assertArrayEquals(payload.toProtoMessage().toByteArray(),
                Version.NETWORK_ENCRYPTION_VERSION == 2
                        ? AuthenticatedEncryption.decrypt(ciphertext, Encryption.getSecretKeyFromBytes(taker.getPaymentAccountKey()), "payment-account")
                        : Encryption.decrypt(ciphertext, Encryption.getSecretKeyFromBytes(taker.getPaymentAccountKey())));
    }

    @Test
    void bothFormatsOpenAndVerifyAgainstTheExistingContract() throws Exception {
        for (int version : new int[]{1, 2}) {
            ProcessModel process = new ProcessModel("offer", "account", null);
            Trade trade = new BuyerAsTakerTrade(mock(Offer.class), BigInteger.ONE, 1,
                    mock(XmrWalletService.class), process, "trade", null, null, null, null);
            FasterPaymentsAccountPayload payload = new FasterPaymentsAccountPayload("FASTER_PAYMENTS", "account");
            payload.setHolderName("Alice");
            payload.setSortCode("123456");
            payload.setAccountNr("12345678");
            Contract contract = mock(Contract.class);
            when(contract.getMakerPaymentAccountPayloadHash()).thenReturn(payload.getHash());
            trade.setContract(contract);
            SecretKey key = Encryption.generateSecretKey(256);
            byte[] bytes = payload.toProtoMessage().toByteArray();
            byte[] encrypted = version == 1 ? Encryption.encrypt(bytes, key)
                    : AuthenticatedEncryption.encrypt(bytes, key, "payment-account");
            trade.getTradePeer().setEncryptedPaymentAccountPayload(encrypted);
            trade.decryptPeerPaymentAccountPayload(key.getEncoded());
            assertEquals(payload, trade.getTradePeer().getPaymentAccountPayload());
            assertTrue(process.getPaymentAccountDecryptedProperty().get());
            byte[] acceptedKey = trade.getTradePeer().getPaymentAccountKey().clone();
            assertThrows(RuntimeException.class, () -> trade.decryptPeerPaymentAccountPayload(Encryption.generateSecretKey(256).getEncoded()));
            assertArrayEquals(acceptedKey, trade.getTradePeer().getPaymentAccountKey());
            assertEquals(payload, trade.getTradePeer().getPaymentAccountPayload());
        }
    }
}
