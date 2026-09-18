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

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.GsonBuilder;
import haveno.common.app.Version;
import haveno.common.crypto.Encryption;
import haveno.common.crypto.PubKeyRing;
import haveno.common.crypto.Sig;
import haveno.common.util.JsonExclude;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferPayload;
import haveno.core.trade.protocol.ProcessModel;
import haveno.core.trade.protocol.TradePeer;
import haveno.core.util.JsonUtil;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.NodeAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

public class ContractMultisigAddressTest {
    private ProcessModel processModel;
    private Trade trade;

    @BeforeEach
    void setUp() throws Exception {
        Version.setBaseCryptoNetworkId(1);
        PubKeyRing keys = new PubKeyRing(Sig.generateKeyPair().getPublic(), Encryption.generateKeyPair().getPublic());
        OfferPayload payload = OfferPayload.fromProto(protobuf.OfferPayload.newBuilder()
                .setId("multisig-contract").setPubKeyRing(keys.toProtoMessage())
                .setDirection(protobuf.OfferDirection.BUY).setBaseCurrencyCode("XMR").setCounterCurrencyCode("USD")
                .setPaymentMethodId("SEPA").setPrice(100000).setAmount(100000).build());
        processModel = new ProcessModel(payload.getId(), "account", keys);
        trade = new ArbitratorTrade(new Offer(payload), BigInteger.valueOf(100000), 100000, mock(XmrWalletService.class),
                processModel, "uid", new NodeAddress("maker", 1), new NodeAddress("taker", 2), new NodeAddress("arbitrator", 3), null);
        for (TradePeer peer : new TradePeer[]{trade.getMaker(), trade.getTaker()}) {
            peer.setAccountId("account");
            peer.setPaymentMethodId("SEPA");
            peer.setPaymentAccountPayloadHash(new byte[32]);
            peer.setPubKeyRing(keys);
        }
        trade.getMaker().setPayoutAddressString("maker-payout");
        trade.getTaker().setPayoutAddressString("taker-payout");
        trade.getMaker().setDepositTxHash("maker-deposit");
        processModel.setMultisigAddress("shared-multisig-address");
    }

    @Test
    void newContractBindsLocalAddressAndSurvivesPersistence() throws Exception {
        Contract contract = trade.createContract();
        assertEquals(processModel.getMultisigAddress(), contract.getMultisigAddress());
        String json = JsonUtil.objectToJson(contract);
        Contract restored = Contract.fromProto(protobuf.Contract.parseFrom(contract.toProtoMessage().toByteArray()), null);
        assertEquals(json, JsonUtil.objectToJson(restored));
        KeyPair signer = Sig.generateKeyPair();
        byte[] signature = Sig.sign(signer.getPrivate(), json.getBytes(StandardCharsets.UTF_8));
        assertTrue(Sig.verify(signer.getPublic(), JsonUtil.objectToJson(restored).getBytes(StandardCharsets.UTF_8), signature));
        Contract changed = Contract.fromProto(contract.toProtoMessage().toBuilder().setMultisigAddress("different-address").build(), null);
        assertFalse(Sig.verify(signer.getPublic(), JsonUtil.objectToJson(changed).getBytes(StandardCharsets.UTF_8), signature));
        Contract missing = Contract.fromProto(contract.toProtoMessage().toBuilder().clearMultisigAddress().build(), null);
        assertFalse(Sig.verify(signer.getPublic(), JsonUtil.objectToJson(missing).getBytes(StandardCharsets.UTF_8), signature));
    }

    @Test
    void oldContractKeepsOriginalJsonAndSignature() throws Exception {
        Contract current = trade.createContract();
        // Serialize the pre-upgrade field set independently of Contract's nullable-field handling.
        String oldJson = new GsonBuilder().setPrettyPrinting()
                .setExclusionStrategies(new ExclusionStrategy() {
                    @Override
                    public boolean shouldSkipField(FieldAttributes field) {
                        return field.getName().equals("multisigAddress") || field.getAnnotation(JsonExclude.class) != null;
                    }
                    @Override
                    public boolean shouldSkipClass(Class<?> type) {
                        return false;
                    }
                }).registerTypeAdapter(OfferPayload.class, new OfferPayload.JsonSerializer()).create().toJson(current);
        KeyPair signer = Sig.generateKeyPair();
        byte[] signature = Sig.sign(signer.getPrivate(), oldJson.getBytes(StandardCharsets.UTF_8));
        protobuf.Contract oldProto = current.toProtoMessage().toBuilder().clearMultisigAddress().build();
        Contract restored = Contract.fromProto(protobuf.Contract.parseFrom(oldProto.toByteArray()), null);
        assertNull(restored.getMultisigAddress());
        assertEquals(oldJson, JsonUtil.objectToJson(restored));
        assertEquals(oldProto, restored.toProtoMessage());
        assertTrue(Sig.verify(signer.getPublic(), JsonUtil.objectToJson(restored).getBytes(StandardCharsets.UTF_8), signature));
    }

    @Test
    void cannotCreateNewContractWithoutAddress() {
        for (String address : new String[]{null, "", " "}) {
            processModel.setMultisigAddress(address);
            assertThrows(IllegalArgumentException.class, trade::createContract);
        }
    }
}
