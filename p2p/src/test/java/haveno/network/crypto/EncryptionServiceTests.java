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

package haveno.network.crypto;

import haveno.common.crypto.CryptoException;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.file.FileUtil;
import haveno.common.proto.network.NetworkEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;

public class EncryptionServiceTests {
    private static final Logger log = LoggerFactory.getLogger(EncryptionServiceTests.class);

    private KeyRing keyRing;
    private File dir;

    @BeforeEach
    public void setup() throws CertificateException, NoSuchAlgorithmException, KeyStoreException, IOException, CryptoException {

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

    //TODO Use NetworkProtoResolver, PersistenceProtoResolver or ProtoResolver which are all in io.haveno.common.
/*
    @Test
    public void testDecryptAndVerifyMessage() throws CryptoException {
        EncryptionService encryptionService = new EncryptionService(keyRing, TestUtils.getProtobufferResolver());
        final PrivateNotificationPayload privateNotification = new PrivateNotificationPayload("test");
        privateNotification.setSigAndPubKey("", pubKeyRing.getSignaturePubKey());
        final NodeAddress nodeAddress = new NodeAddress("localhost", 2222);
        PrivateNotificationMessage data = new PrivateNotificationMessage(privateNotification,
                nodeAddress,
                UUID.randomUUID().toString());
        PrefixedSealedAndSignedMessage encrypted = new PrefixedSealedAndSignedMessage(nodeAddress,
                encryptionService.encryptAndSign(pubKeyRing, data),
                Hash.getHash("localhost"),
                UUID.randomUUID().toString());
        DecryptedMsgWithPubKey decrypted = encryptionService.decryptAndVerify(encrypted.sealedAndSigned);
        assertEquals(data.privateNotificationPayload.message,
                ((PrivateNotificationMessage) decrypted.message).privateNotificationPayload.message);
    }


    @Test
    public void testDecryptHybridWithSignature() {
        long ts = System.currentTimeMillis();
        log.trace("start ");
        for (int i = 0; i < 100; i++) {
            Ping payload = new Ping(new Random().nextInt(), 10);
            SealedAndSigned sealedAndSigned = null;
            try {
                sealedAndSigned = Encryption.encryptHybridWithSignature(payload,
                        keyRing.getSignatureKeyPair(), keyRing.getPubKeyRing().getEncryptionPubKey());
            } catch (CryptoException e) {
                log.error("encryptHybridWithSignature failed");
                e.printStackTrace();
                assertTrue(false);
            }
            try {
                EncryptionService encryptionService = new EncryptionService(null, TestUtils.getProtobufferResolver());
                DecryptedDataTuple tuple = encryptionService.decryptHybridWithSignature(sealedAndSigned, keyRing.getEncryptionKeyPair().getPrivate());
                assertEquals(((Ping) tuple.payload).nonce, payload.nonce);
            } catch (CryptoException e) {
                log.error("decryptHybridWithSignature failed");
                e.printStackTrace();
                assertTrue(false);
            }
        }
        log.trace("took " + (System.currentTimeMillis() - ts) + " ms.");
    }*/

    private static class MockMessage extends NetworkEnvelope {
        public final int nonce;

        public MockMessage(int nonce) {
            super("0");
            this.nonce = nonce;
        }

        @Override
        public String getMessageVersion() {
            return "0";
        }

        @Override
        public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
            return protobuf.NetworkEnvelope.newBuilder().setPing(protobuf.Ping.newBuilder().setNonce(nonce)).build();
        }
    }
}
/*@Value
final class TestMessage implements MailboxMessage {
    public String data = "test";
    private final String messageVersion = Version.getP2PMessageVersion();
    private final String uid;
    private final String senderNodeAddress;

    public TestMessage(String data) {
        this.data = data;
        uid = UUID.randomUUID().toString();
        senderNodeAddress = null;
    }


    @Override
    public PB.NetworkEnvelope toProtoNetworkEnvelope() {
        throw new NotImplementedException();
    }
}*/
