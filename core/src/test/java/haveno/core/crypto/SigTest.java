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

package haveno.core.crypto;

import com.google.protobuf.ByteString;
import haveno.common.crypto.CryptoException;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.crypto.Sig;
import haveno.common.file.FileUtil;
import haveno.core.alert.Alert;
import haveno.core.alert.AlertManager;
import haveno.core.user.User;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.storage.HashMapChangedListener;
import haveno.network.p2p.storage.payload.ProtectedStorageEntry;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class SigTest {
    private static final Logger log = LoggerFactory.getLogger(SigTest.class);
    private KeyRing keyRing;
    private File dir;

    @BeforeEach
    public void setup() throws IOException {

        dir = File.createTempFile("temp_tests", "");
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdir();
        KeyStorage keyStorage = new KeyStorage(dir);
        keyRing = new KeyRing(keyStorage, null, true);
    }

    @AfterEach
    public void tearDown() throws IOException {
        FileUtil.deleteDirectory(dir);
    }


    @Test
    public void testSignature() {
        long ts = System.currentTimeMillis();
        log.trace("start ");
        for (int i = 0; i < 100; i++) {
            String msg = String.valueOf(new Random().nextInt());
            String sig = null;
            try {
                sig = Sig.sign(keyRing.getSignatureKeyPair().getPrivate(), msg);
            } catch (CryptoException e) {
                log.error("sign failed");
                e.printStackTrace();
                assertTrue(false);
            }
            try {
                assertTrue(Sig.verify(keyRing.getSignatureKeyPair().getPublic(), msg, sig));
            } catch (CryptoException e) {
                log.error("verify failed");
                e.printStackTrace();
                assertTrue(false);
            }
        }
        log.trace("took {} ms.", System.currentTimeMillis() - ts);
    }

    @Test
    public void metadataSignedAlertRoundTripsAndStillVerifiesForLegacyClients() throws Exception {
        ECKey signingKey = new ECKey();
        Alert signed = signedAlert(signingKey);
        Alert received = Alert.fromProto(signed.toProtoMessage().getAlert());
        signingKey.verifyMessage(Utils.HEX.encode(received.getMessage().getBytes(StandardCharsets.UTF_8)), received.getSignatureAsBase64());
        assertAlertAccepted(signingKey, received, true);
    }

    @Test
    public void alertMetadataAndOwnerCannotBeChangedOrStripped() {
        ECKey signingKey = new ECKey();
        Alert signed = signedAlert(signingKey);
        List<Consumer<protobuf.Alert.Builder>> mutations = List.of(
                builder -> builder.setMessage("different message"),
                builder -> builder.setIsUpdateInfo(false),
                builder -> builder.setIsPreReleaseInfo(true),
                builder -> builder.setVersion("999.0.0"),
                builder -> builder.setOwnerPubKeyBytes(ByteString.copyFrom(Sig.generateKeyPair().getPublic().getEncoded())),
                protobuf.Alert.Builder::clearExtraData,
                builder -> builder.putExtraData("metadataSignature", "malformed"),
                builder -> builder.putExtraData("injected", "value"));
        for (Consumer<protobuf.Alert.Builder> mutation : mutations) {
            protobuf.Alert.Builder builder = signed.toProtoMessage().getAlert().toBuilder();
            mutation.accept(builder);
            assertAlertAccepted(signingKey, Alert.fromProto(builder.build()), false);
        }
        assertAlertAccepted(new ECKey(), signed, false);
    }

    @Test
    public void alertMetadataSignatureUsesStableMapOrdering() {
        ECKey signingKey = new ECKey();
        Alert alert = Alert.fromProto(signedAlert(signingKey).toProtoMessage().getAlert().toBuilder()
                .putExtraData("z", "last").putExtraData("a", "first").build());
        alert.setMetadataSignature(signingKey.signMessage(alert.getMetadataSignaturePayload()));
        protobuf.Alert reordered = alert.toProtoMessage().getAlert().toBuilder().clearExtraData()
                .putExtraData("a", "first").putExtraData("metadataSignature", alert.getMetadataSignature())
                .putExtraData("z", "last").build();
        assertAlertAccepted(signingKey, Alert.fromProto(reordered), true);
    }

    private Alert signedAlert(ECKey signingKey) {
        AlertManager manager = alertManager(signingKey, mock(P2PService.class));
        Alert alert = new Alert("release message", true, false, "99.0.0");
        assertTrue(manager.addAlertMessageIfKeyIsValid(alert, Utils.HEX.encode(signingKey.getPrivKeyBytes())));
        return alert;
    }

    private AlertManager alertManager(ECKey signingKey, P2PService p2pService) {
        return new AlertManager(p2pService, keyRing, mock(User.class), false, false) {
            @Override
            protected List<String> getPubKeyList() {
                return List.of(Utils.HEX.encode(signingKey.getPubKey()));
            }
        };
    }

    private void assertAlertAccepted(ECKey signingKey, Alert alert, boolean accepted) {
        P2PService p2pService = mock(P2PService.class);
        AlertManager manager = alertManager(signingKey, p2pService);
        ArgumentCaptor<HashMapChangedListener> listener = ArgumentCaptor.forClass(HashMapChangedListener.class);
        verify(p2pService).addHashSetChangedListener(listener.capture());
        ProtectedStorageEntry entry = new ProtectedStorageEntry(alert, alert.getOwnerPubKey(), 1, new byte[0], Clock.systemUTC());
        listener.getValue().onAdded(List.of(entry));
        if (accepted) assertEquals(alert, manager.alertMessageProperty().get());
        else assertNull(manager.alertMessageProperty().get());
        listener.getValue().onRemoved(List.of(entry));
        assertNull(manager.alertMessageProperty().get());
    }
}


