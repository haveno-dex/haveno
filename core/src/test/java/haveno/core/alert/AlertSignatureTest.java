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

package haveno.core.alert;

import com.google.protobuf.ByteString;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.Sig;
import haveno.core.user.User;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.storage.HashMapChangedListener;
import haveno.network.p2p.storage.payload.ProtectedStorageEntry;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Utils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AlertSignatureTest {
    private final ECKey devKey = new ECKey();
    private final KeyPair ownerKeys = Sig.generateKeyPair();
    private P2PService p2PService;
    private AlertManager manager;
    private HashMapChangedListener listener;

    @BeforeEach
    void setUp() {
        p2PService = mock(P2PService.class);
        KeyRing keyRing = mock(KeyRing.class);
        when(keyRing.getSignatureKeyPair()).thenReturn(ownerKeys);
        manager = new AlertManager(p2PService, keyRing, mock(User.class), false, false) {
            @Override
            protected List<String> getPubKeyList() {
                return List.of(Utils.HEX.encode(devKey.getPubKey()));
            }
        };
        ArgumentCaptor<HashMapChangedListener> captor = ArgumentCaptor.forClass(HashMapChangedListener.class);
        verify(p2PService).addHashSetChangedListener(captor.capture());
        listener = captor.getValue();
    }

    @Test
    void publishedAlertVerifiesAfterRoundTrip() {
        Alert alert = new Alert("please update", true, false, "1.2.3");
        assertTrue(manager.addAlertMessageIfKeyIsValid(alert, Utils.HEX.encode(devKey.getPrivKeyBytes())));
        verify(p2PService).addProtectedStorageEntry(alert);
        Alert received = Alert.fromProto(alert.toProtoMessage().getAlert());
        receive(received);
        assertSame(received, manager.alertMessageProperty().get());
    }

    @Test
    void rejectsChangesToEverySignedField() {
        Alert alert = signedAlert();
        List<Consumer<protobuf.Alert.Builder>> changes = List.of(
                b -> b.setMessage("forged"),
                b -> b.setVersion("9.9.9"),
                b -> b.setIsUpdateInfo(false),
                b -> b.setIsPreReleaseInfo(true),
                b -> b.setOwnerPubKeyBytes(ByteString.copyFrom(Sig.generateKeyPair().getPublic().getEncoded())),
                b -> b.putExtraData("Aa", "changed"),
                b -> b.putExtraData("extra", "value"),
                protobuf.Alert.Builder::clearExtraData);
        for (Consumer<protobuf.Alert.Builder> change : changes) {
            protobuf.Alert.Builder builder = alert.toProtoMessage().getAlert().toBuilder();
            change.accept(builder);
            receive(Alert.fromProto(builder.build()));
            assertNull(manager.alertMessageProperty().get());
        }
    }

    @Test
    void mapInsertionOrderDoesNotChangeSignature() {
        Alert alert = signedAlert();
        protobuf.Alert reordered = alert.toProtoMessage().getAlert().toBuilder()
                .clearExtraData().putExtraData("BB", "second").putExtraData("Aa", "first").build();
        Alert received = Alert.fromProto(reordered);
        assertEquals(alert.getSignaturePayloadAsHex(alert.getOwnerPubKeyBytes()),
                received.getSignaturePayloadAsHex(received.getOwnerPubKeyBytes()));
        receive(received);
        assertSame(received, manager.alertMessageProperty().get());
    }

    @Test
    void rejectsLegacyAndMalformedSignatures() {
        Alert alert = new Alert("please update", true, false, "1.2.3");
        String legacySignature = devKey.signMessage(Utils.HEX.encode(alert.getMessage().getBytes(StandardCharsets.UTF_8)));
        alert.setSigAndPubKey(legacySignature, ownerKeys.getPublic());
        receive(alert);
        assertNull(manager.alertMessageProperty().get());
        alert.setSigAndPubKey("invalid signature", ownerKeys.getPublic());
        receive(alert);
        assertNull(manager.alertMessageProperty().get());
    }

    private Alert signedAlert() {
        protobuf.Alert proto = protobuf.Alert.newBuilder()
                .setMessage("please update").setIsUpdateInfo(true).setVersion("1.2.3")
                .setOwnerPubKeyBytes(ByteString.copyFrom(ownerKeys.getPublic().getEncoded()))
                .setSignatureAsBase64("unsigned").putExtraData("Aa", "first").putExtraData("BB", "second").build();
        Alert alert = Alert.fromProto(proto);
        alert.setSigAndPubKey(devKey.signMessage(alert.getSignaturePayloadAsHex(ownerKeys.getPublic().getEncoded())), ownerKeys.getPublic());
        return alert;
    }

    private void receive(Alert alert) {
        listener.onAdded(List.of(new ProtectedStorageEntry(alert, alert.getOwnerPubKey(), 1, new byte[]{1}, Clock.systemUTC())));
    }
}
