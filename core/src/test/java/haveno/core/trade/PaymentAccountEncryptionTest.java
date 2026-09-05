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

import haveno.common.crypto.AuthenticatedEncryption;
import haveno.common.crypto.Encryption;
import haveno.core.offer.Offer;
import haveno.core.payment.payload.FasterPaymentsAccountPayload;
import haveno.core.trade.protocol.ProcessModel;
import haveno.core.xmr.wallet.XmrWalletService;
import java.math.BigInteger;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentAccountEncryptionTest {
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
