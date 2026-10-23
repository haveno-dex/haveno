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

package haveno.network.crypto;

import haveno.common.app.Version;
import haveno.common.crypto.CryptoException;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.file.FileUtil;
import haveno.common.proto.ProtobufferException;
import haveno.common.proto.ProtobufferRuntimeException;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.common.proto.network.NetworkProtoResolver;
import haveno.common.crypto.SealedAndSigned;
import haveno.common.persistence.PersistenceManager;
import haveno.network.p2p.AckMessage;
import haveno.network.p2p.AckMessageSourceType;
import haveno.network.p2p.DecryptedMessageWithPubKey;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.PrefixedSealedAndSignedMessage;
import haveno.network.p2p.mailbox.IgnoredMailboxService;
import haveno.network.p2p.mailbox.MailboxItem;
import haveno.network.p2p.mailbox.MailboxMessageService;
import haveno.network.p2p.peers.keepalive.messages.Ping;
import haveno.network.p2p.storage.payload.MailboxStoragePayload;
import haveno.network.p2p.storage.payload.ProtectedMailboxStorageEntry;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
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

    @Test
    public void lockedKeysDoNotPermanentlyIgnoreValidMailboxMessages() throws Exception {
        Version.setBaseCryptoNetworkId(1);
        NodeAddress sender = new NodeAddress("peer:9999");
        AckMessage ack = new AckMessage(sender, AckMessageSourceType.TRADE_MESSAGE, "message", "uid", "trade", true, null);
        NetworkProtoResolver resolver = mock(NetworkProtoResolver.class);
        when(resolver.fromProto(any(protobuf.NetworkEnvelope.class))).thenAnswer(invocation -> {
            protobuf.NetworkEnvelope proto = invocation.getArgument(0);
            return AckMessage.fromProto(proto.getAckMessage(), proto.getMessageVersion());
        });
        EncryptionService encryption = spy(new EncryptionService(keyRing, resolver));
        IgnoredMailboxService ignored = new IgnoredMailboxService(mock(PersistenceManager.class));
        MailboxMessageService service = new MailboxMessageService(null, null, null, encryption, ignored,
                mock(PersistenceManager.class), keyRing, Clock.systemUTC(), false);
        PrefixedSealedAndSignedMessage envelope = new PrefixedSealedAndSignedMessage(sender,
                encryption.encryptAndSign(keyRing.getPubKeyRing(), ack));
        ProtectedMailboxStorageEntry entry = mailboxEntry(envelope);
        Method decrypt = MailboxMessageService.class.getDeclaredMethod("tryDecryptProtectedMailboxStorageEntry", ProtectedMailboxStorageEntry.class);
        decrypt.setAccessible(true);

        keyRing.lockKeys();
        assertFalse(((MailboxItem) decrypt.invoke(service, entry)).isMine());
        keyRing.unlockKeys(null, false);
        assertFalse(ignored.isIgnored(envelope.getUid()));
        doAnswer(invocation -> {
            keyRing.lockKeys();
            return invocation.callRealMethod();
        }).when(encryption).decryptAndVerify(any(), any());
        assertTrue(((MailboxItem) decrypt.invoke(service, entry)).isMine());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void unknownMailboxMessageCanBeRetriedAfterUpgrade() throws Exception {
        Version.setBaseCryptoNetworkId(1);
        EncryptionService encryption = mock(EncryptionService.class);
        IgnoredMailboxService ignored = new IgnoredMailboxService(mock(PersistenceManager.class));
        MailboxMessageService service = new MailboxMessageService(null, null, null, encryption, ignored,
                mock(PersistenceManager.class), keyRing, Clock.systemUTC(), false);
        NodeAddress sender = new NodeAddress("peer:9999");
        AckMessage ack = new AckMessage(sender, AckMessageSourceType.TRADE_MESSAGE, "message", "uid", "trade", true, null);
        Method decryptBatch = MailboxMessageService.class.getDeclaredMethod("getMailboxItems", Collection.class);
        decryptBatch.setAccessible(true);

        for (Exception failure : List.of(new ProtobufferException("Unknown proto message case"),
                new ProtobufferRuntimeException("Unknown proto payment account payload"))) {
            SealedAndSigned sealed = mock(SealedAndSigned.class);
            PrefixedSealedAndSignedMessage envelope = new PrefixedSealedAndSignedMessage(sender, sealed);
            ProtectedMailboxStorageEntry entry = mailboxEntry(envelope);
            when(encryption.decryptAndVerify(sealed, keyRing.getEncryptionKeyPair().getPrivate()))
                    .thenThrow(failure)
                    .thenReturn(new DecryptedMessageWithPubKey(ack, keyRing.getSignatureKeyPair().getPublic()));
            Set<MailboxItem> beforeUpgrade = (Set<MailboxItem>) decryptBatch.invoke(service, List.of(entry));
            assertFalse(beforeUpgrade.iterator().next().isMine());
            assertFalse(ignored.isIgnored(envelope.getUid()));
            Set<MailboxItem> afterUpgrade = (Set<MailboxItem>) decryptBatch.invoke(service, List.of(entry));
            assertTrue(afterUpgrade.iterator().next().isMine());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void malformedMailboxEnvelopeDoesNotAbortBatch() throws Exception {
        Version.setBaseCryptoNetworkId(1);
        EncryptionService encryption = mock(EncryptionService.class);
        IgnoredMailboxService ignored = mock(IgnoredMailboxService.class);
        MailboxMessageService service = new MailboxMessageService(null, null, null, encryption, ignored,
                mock(PersistenceManager.class), keyRing, Clock.systemUTC(), false);
        NodeAddress sender = new NodeAddress("peer:9999");
        SealedAndSigned poison = mock(SealedAndSigned.class);
        SealedAndSigned valid = mock(SealedAndSigned.class);
        PrefixedSealedAndSignedMessage poisonEnvelope = new PrefixedSealedAndSignedMessage(sender, poison);
        ProtectedMailboxStorageEntry poisonEntry = mailboxEntry(poisonEnvelope);
        ProtectedMailboxStorageEntry validEntry = mailboxEntry(new PrefixedSealedAndSignedMessage(sender, valid));
        when(encryption.decryptAndVerify(poison, keyRing.getEncryptionKeyPair().getPrivate())).thenReturn(new DecryptedMessageWithPubKey(new Ping(1, 1),
                keyRing.getSignatureKeyPair().getPublic()));
        AckMessage ack = new AckMessage(sender, AckMessageSourceType.TRADE_MESSAGE, "message", "uid", "trade", true, null);
        when(encryption.decryptAndVerify(valid, keyRing.getEncryptionKeyPair().getPrivate())).thenReturn(new DecryptedMessageWithPubKey(ack,
                keyRing.getSignatureKeyPair().getPublic()));
        Method decryptBatch = MailboxMessageService.class.getDeclaredMethod("getMailboxItems", Collection.class);
        decryptBatch.setAccessible(true);
        Set<MailboxItem> items = (Set<MailboxItem>) decryptBatch.invoke(service, List.of(poisonEntry, validEntry));
        assertEquals(2, items.size());
        assertEquals(1, items.stream().filter(MailboxItem::isMine).count());
        verify(ignored).ignore(poisonEnvelope.getUid(), poisonEntry.getCreationTimeStamp());

        when(encryption.decryptAndVerify(poison, keyRing.getEncryptionKeyPair().getPrivate())).thenThrow(new IllegalArgumentException("malformed protobuf"));
        items = (Set<MailboxItem>) decryptBatch.invoke(service, List.of(poisonEntry, validEntry));
        assertEquals(1, items.stream().filter(MailboxItem::isMine).count());
    }

    @Test
    public void failingMailboxListenerDoesNotAbortBatch() throws Exception {
        Version.setBaseCryptoNetworkId(1);
        MailboxMessageService service = new MailboxMessageService(null, null, null, mock(EncryptionService.class),
                mock(IgnoredMailboxService.class), mock(PersistenceManager.class), keyRing, Clock.systemUTC(), false);
        List<String> delivered = new ArrayList<>();
        service.addDecryptedMailboxListener((message, sender) -> {
            String tradeId = ((AckMessage) message.getNetworkEnvelope()).getSourceId();
            if (tradeId.equals("poison")) throw new IllegalStateException("listener failed");
            delivered.add(tradeId);
        });
        NodeAddress sender = new NodeAddress("peer:9999");
        Set<MailboxItem> items = new HashSet<>();
        for (String tradeId : List.of("poison", "trade")) {
            ProtectedMailboxStorageEntry entry = mailboxEntry(new PrefixedSealedAndSignedMessage(sender, mock(SealedAndSigned.class)));
            MailboxStoragePayload payload = entry.getMailboxStoragePayload();
            when(entry.getProtectedStoragePayload()).thenReturn(payload);
            when(payload.toProtoMessage()).thenReturn(protobuf.StoragePayload.getDefaultInstance());
            AckMessage ack = new AckMessage(sender, AckMessageSourceType.TRADE_MESSAGE, "message", "uid", tradeId, true, null);
            items.add(new MailboxItem(entry, new DecryptedMessageWithPubKey(ack, keyRing.getSignatureKeyPair().getPublic())));
        }
        MailboxMessageService.setMailboxMessageComparator((a, b) -> a.getUid().compareTo(b.getUid()));
        Method handleBatch = MailboxMessageService.class.getDeclaredMethod("handleMailboxItems", Set.class);
        handleBatch.setAccessible(true);
        handleBatch.invoke(service, items);
        assertEquals(List.of("trade"), delivered);
    }

    private ProtectedMailboxStorageEntry mailboxEntry(PrefixedSealedAndSignedMessage envelope) {
        ProtectedMailboxStorageEntry entry = mock(ProtectedMailboxStorageEntry.class);
        MailboxStoragePayload payload = mock(MailboxStoragePayload.class);
        when(entry.getMailboxStoragePayload()).thenReturn(payload);
        when(payload.getPrefixedSealedAndSignedMessage()).thenReturn(envelope);
        return entry;
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
