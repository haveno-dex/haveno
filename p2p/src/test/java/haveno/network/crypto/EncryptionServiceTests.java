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
import haveno.common.persistence.PersistenceManager;
import haveno.network.p2p.AckMessage;
import haveno.network.p2p.AckMessageSourceType;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.PrefixedSealedAndSignedMessage;
import haveno.network.p2p.mailbox.IgnoredMailboxMap;
import haveno.network.p2p.mailbox.IgnoredMailboxService;
import haveno.network.p2p.mailbox.MailboxItem;
import haveno.network.p2p.mailbox.MailboxMessageList;
import haveno.network.p2p.mailbox.MailboxMessageService;
import haveno.network.p2p.network.NetworkNode;
import haveno.network.p2p.storage.P2PDataStorage;
import haveno.network.p2p.storage.payload.MailboxStoragePayload;
import haveno.network.p2p.storage.payload.ProtectedMailboxStorageEntry;
import haveno.network.p2p.storage.payload.ProtectedStorageEntry;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import haveno.common.crypto.AuthenticatedEncryption;
import haveno.common.crypto.Encryption;
import haveno.common.crypto.Hash;
import haveno.common.crypto.SealedAndSigned;
import haveno.common.crypto.Sig;
import haveno.common.crypto.CryptoException;
import haveno.common.proto.network.NetworkProtoResolver;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.file.FileUtil;
import haveno.common.proto.network.NetworkEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.File;
import java.io.IOException;

public class EncryptionServiceTests {

    private KeyRing keyRing;
    private File dir;

    @BeforeEach
    public void setup() throws IOException {
        Version.setBaseCryptoNetworkId(1);
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
    public void legacyAndAuthenticatedMessagesRoundTripAndOutgoingUsesConfiguredVersion() throws Exception {
        MockMessage message = new MockMessage(42);
        NetworkProtoResolver resolver = mock(NetworkProtoResolver.class);
        when(resolver.fromProto(any(protobuf.NetworkEnvelope.class))).thenReturn(message);
        EncryptionService service = new EncryptionService(keyRing, resolver);
        SealedAndSigned defaultSeal = service.encryptAndSign(keyRing.getPubKeyRing(), message);
        assertEquals(Version.NETWORK_ENCRYPTION_VERSION == 2,
                AuthenticatedEncryption.isEnvelope(defaultSeal.getEncryptedPayloadWithHmac()));
        assertEquals(message, service.decryptAndVerify(defaultSeal).getNetworkEnvelope());
        for (int version : new int[]{1, 2}) {
            SealedAndSigned seal = EncryptionService.encryptHybridWithSignature(message, keyRing.getSignatureKeyPair(),
                    keyRing.getPubKeyRing().getEncryptionPubKey(), version);
            assertEquals(version == 2, AuthenticatedEncryption.isEnvelope(seal.getEncryptedPayloadWithHmac()));
            assertEquals(message, service.decryptHybridWithSignature(seal, keyRing.getEncryptionKeyPair().getPrivate()).getNetworkEnvelope());
            if (version == 1) {
                // A baseline client can open version one using only its legacy primitives.
                assertArrayEquals(message.toProtoNetworkEnvelope().toByteArray(), Encryption.decryptPayloadWithHmac(
                        seal.getEncryptedPayloadWithHmac(), Encryption.decryptSecretKey(seal.getEncryptedSecretKey(),
                                keyRing.getEncryptionKeyPair().getPrivate())));
            }
        }
    }

    @Test
    public void evenValidlySignedDamagedEnvelopeNeverFallsBackOrReachesResolver() throws Exception {
        NetworkProtoResolver resolver = mock(NetworkProtoResolver.class);
        EncryptionService service = new EncryptionService(keyRing, resolver);
        SealedAndSigned seal = EncryptionService.encryptHybridWithSignature(new MockMessage(7), keyRing.getSignatureKeyPair(),
                keyRing.getPubKeyRing().getEncryptionPubKey(), 2);
        byte[] bytes = seal.getEncryptedPayloadWithHmac().clone();
        bytes[bytes.length - 1] ^= 1;
        byte[] signature = Sig.sign(keyRing.getSignatureKeyPair().getPrivate(), Hash.getSha256Hash(bytes));
        SealedAndSigned damaged = new SealedAndSigned(seal.getEncryptedSecretKey(), bytes, signature, keyRing.getSignatureKeyPair().getPublic());
        assertThrows(CryptoException.class, () -> service.decryptHybridWithSignature(damaged, keyRing.getEncryptionKeyPair().getPrivate()));
        verifyNoInteractions(resolver);
    }

    @Test
    public void readerUpgradeRecoversPersistedIgnoredMailboxEntriesWithBothFormats() throws Exception {
        for (int version : new int[]{1, 2}) {
            Clock clock = Clock.systemUTC();
            AckMessage message = new AckMessage(new NodeAddress("sender", 9999), AckMessageSourceType.TRADE_MESSAGE,
                    "PaymentReceivedMessage", "source-uid", "trade-id", true, null);
            KeyPair sender = Sig.generateKeyPair();
            SealedAndSigned seal = EncryptionService.encryptHybridWithSignature(message, sender,
                    keyRing.getPubKeyRing().getEncryptionPubKey(), version);
            ProtectedMailboxStorageEntry entry = mailboxEntry(seal, sender, keyRing.getPubKeyRing().getSignaturePubKey(), clock);
            String outerUid = entry.getMailboxStoragePayload().getPrefixedSealedAndSignedMessage().getUid();
            NetworkProtoResolver resolver = mock(NetworkProtoResolver.class);
            when(resolver.getClock()).thenReturn(clock);
            when(resolver.fromProto(any(protobuf.NetworkEnvelope.class))).thenReturn(message);
            when(resolver.fromProto(any(protobuf.StoragePayload.class))).thenAnswer(invocation ->
                    MailboxStoragePayload.fromProto(((protobuf.StoragePayload) invocation.getArgument(0)).getMailboxStoragePayload()));

            // An older reader persisted both the failed UID and the still-encrypted mailbox entry.
            IgnoredMailboxService ignored = restoredIgnoredMailbox(outerUid, clock);
            MailboxMessageList previous = new MailboxMessageList(List.of(new MailboxItem(entry, null)));
            protobuf.PersistableEnvelope stored = protobuf.PersistableEnvelope.parseFrom(previous.toProtoMessage().toByteArray());
            PersistenceManager<MailboxMessageList> persistence = mock(PersistenceManager.class);
            doAnswer(invocation -> {
                Consumer<MailboxMessageList> handler = invocation.getArgument(0);
                handler.accept(MailboxMessageList.fromProto(stored.getMailboxMessageList(), resolver));
                return null;
            }).when(persistence).readPersisted(any(), any());
            P2PDataStorage storage = mock(P2PDataStorage.class);
            Map<P2PDataStorage.ByteArray, ProtectedStorageEntry> entries = new ConcurrentHashMap<>();
            when(storage.getMap()).thenReturn(entries);
            doAnswer(invocation -> {
                ProtectedMailboxStorageEntry loaded = invocation.getArgument(0);
                entries.put(P2PDataStorage.get32ByteHashAsByteArray(loaded.getProtectedStoragePayload()), loaded);
                return null;
            }).when(storage).addProtectedMailboxStorageEntryToMap(any());
            NetworkNode node = mock(NetworkNode.class);
            when(node.getNodeAddress()).thenReturn(new NodeAddress("receiver", 9998));
            MailboxMessageService service = new MailboxMessageService(node, null, storage,
                    new EncryptionService(keyRing, resolver), ignored, persistence, keyRing, clock, false);
            CountDownLatch delivered = new CountDownLatch(1);
            service.addDecryptedMailboxListener((decrypted, address) -> delivered.countDown());
            service.readPersisted(() -> { });
            service.onAllServicesInitialized();
            service.initAfterBootstrapped();
            assertTrue(delivered.await(10, TimeUnit.SECONDS));
            assertEquals(message, service.getMyDecryptedMailboxMessages().iterator().next().getNetworkEnvelope());

            ArgumentCaptor<MailboxMessageList> mailbox = ArgumentCaptor.forClass(MailboxMessageList.class);
            verify(persistence).initialize(mailbox.capture(), eq(PersistenceManager.Source.PRIVATE_LOW_PRIO));
            assertEquals(1, mailbox.getValue().size(), "the old outer-UID entry must be replaced, not retained twice");
            assertTrue(mailbox.getValue().getList().get(0).isMine());
            assertEquals(message.getUid(), mailbox.getValue().getList().get(0).getUid());
            assertTrue(ignored.isIgnored(outerUid), "recovery must work even if the old ignore cache loads first");
        }
    }

    @Test
    public void addressedMailboxFailuresRemainRetriableWithoutWeakeningAuthentication() throws Exception {
        Clock clock = Clock.systemUTC();
        KeyPair sender = Sig.generateKeyPair();
        SealedAndSigned seal = EncryptionService.encryptHybridWithSignature(new MockMessage(9), sender,
                keyRing.getPubKeyRing().getEncryptionPubKey(), 2);
        byte[] damaged = seal.getEncryptedPayloadWithHmac().clone();
        damaged[damaged.length - 1] ^= 1;
        SealedAndSigned signedDamage = new SealedAndSigned(seal.getEncryptedSecretKey(), damaged,
                Sig.sign(sender.getPrivate(), Hash.getSha256Hash(damaged)), sender.getPublic());
        ProtectedMailboxStorageEntry addressed = mailboxEntry(signedDamage, sender, keyRing.getPubKeyRing().getSignaturePubKey(), clock);
        NetworkProtoResolver resolver = mock(NetworkProtoResolver.class);
        IgnoredMailboxService ignored = new IgnoredMailboxService(mock(PersistenceManager.class));
        MailboxMessageService service = new MailboxMessageService(null, null, null,
                new EncryptionService(keyRing, resolver), ignored, mock(PersistenceManager.class), keyRing, clock, false);
        var decrypt = MailboxMessageService.class.getDeclaredMethod("tryDecryptProtectedMailboxStorageEntry", ProtectedMailboxStorageEntry.class);
        decrypt.setAccessible(true);
        assertFalse(((MailboxItem) decrypt.invoke(service, addressed)).isMine());
        assertFalse(ignored.isIgnored(addressed.getMailboxStoragePayload().getPrefixedSealedAndSignedMessage().getUid()));
        verifyNoInteractions(resolver);

        ProtectedMailboxStorageEntry foreign = mailboxEntry(signedDamage, sender, Sig.generateKeyPair().getPublic(), clock);
        assertFalse(((MailboxItem) decrypt.invoke(service, foreign)).isMine());
        String foreignUid = foreign.getMailboxStoragePayload().getPrefixedSealedAndSignedMessage().getUid();
        assertTrue(ignored.isIgnored(foreignUid), "other recipients' failures should remain cached");
        assertFalse(((MailboxItem) decrypt.invoke(service, foreign)).isMine());
        verifyNoInteractions(resolver);
    }

    private IgnoredMailboxService restoredIgnoredMailbox(String uid, Clock clock) throws Exception {
        IgnoredMailboxMap old = new IgnoredMailboxMap();
        old.put(uid, clock.millis());
        protobuf.PersistableEnvelope stored = protobuf.PersistableEnvelope.parseFrom(old.toProtoMessage().toByteArray());
        PersistenceManager<IgnoredMailboxMap> persistence = mock(PersistenceManager.class);
        doAnswer(invocation -> {
            Consumer<IgnoredMailboxMap> handler = invocation.getArgument(0);
            handler.accept(IgnoredMailboxMap.fromProto(stored.getIgnoredMailboxMap()));
            return null;
        }).when(persistence).readPersisted(any(), any());
        IgnoredMailboxService ignored = new IgnoredMailboxService(persistence);
        ignored.readPersisted(() -> { });
        return ignored;
    }

    private ProtectedMailboxStorageEntry mailboxEntry(SealedAndSigned seal, KeyPair sender, PublicKey receiver, Clock clock) throws Exception {
        PrefixedSealedAndSignedMessage prefixed = new PrefixedSealedAndSignedMessage(new NodeAddress("sender", 9999), seal);
        MailboxStoragePayload payload = new MailboxStoragePayload(prefixed, sender.getPublic(), receiver, MailboxStoragePayload.TTL);
        byte[] signature = Sig.sign(sender.getPrivate(), P2PDataStorage.get32ByteHash(new P2PDataStorage.DataAndSeqNrPair(payload, 1)));
        ProtectedMailboxStorageEntry entry = new ProtectedMailboxStorageEntry(payload, sender.getPublic(), 1, signature, receiver, clock);
        assertTrue(entry.isValidForAddOperation());
        return entry;
    }

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
