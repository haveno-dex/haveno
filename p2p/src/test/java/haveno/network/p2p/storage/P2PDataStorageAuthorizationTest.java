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

package haveno.network.p2p.storage;

import haveno.common.app.Version;
import haveno.common.crypto.CryptoException;
import haveno.common.crypto.Sig;
import haveno.network.p2p.PrefixedSealedAndSignedMessage;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.storage.messages.RemoveDataMessage;
import haveno.network.p2p.storage.messages.RefreshOfferMessage;
import haveno.network.p2p.storage.payload.MailboxStoragePayload;
import haveno.network.p2p.storage.payload.ProtectedMailboxStorageEntry;
import haveno.network.p2p.storage.payload.ProtectedStorageEntry;
import haveno.network.p2p.storage.payload.ProtectedStoragePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.KeyPair;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class P2PDataStorageAuthorizationTest {
    private TestState state;
    private final KeyPair owner = Sig.generateKeyPair();
    private final KeyPair receiver = Sig.generateKeyPair();
    private ProtectedStoragePayload payload;

    @BeforeEach
    void setUp() {
        Version.setBaseCryptoNetworkId(1);
        state = new TestState();
        payload = mock(ProtectedStoragePayload.class);
        when(payload.getOwnerPubKey()).thenReturn(owner.getPublic());
        when(payload.toProtoMessage()).thenReturn(protobuf.StoragePayload.newBuilder()
                .setAlert(protobuf.Alert.newBuilder().setMessage("storage authorization test")).build());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capturedAddAndRefreshCannotRemove(boolean viaNetwork) throws CryptoException {
        ProtectedStorageEntry add = state.mockedStorage.getProtectedStorageEntry(payload, owner);
        // An attacker may relay a removal before the victim sees the original add.
        assertRejectedRemove(add, viaNetwork);
        assertTrue(state.mockedStorage.addProtectedStorageEntry(add, null, null));

        ProtectedStorageEntry updatedAdd = state.mockedStorage.getProtectedStorageEntry(payload, owner);
        assertRejectedRemove(updatedAdd, viaNetwork);
        RefreshOfferMessage refresh = state.mockedStorage.getRefreshTTLMessage(payload, owner);
        ProtectedStorageEntry forgedRemove = new ProtectedStorageEntry(payload, owner.getPublic(),
                refresh.getSequenceNumber(), refresh.getSignature(), state.clockFake);
        assertRejectedRemove(forgedRemove, viaNetwork);
        assertTrue(state.mockedStorage.refreshTTL(refresh, null));

        ProtectedStorageEntry remove = state.mockedStorage.getProtectedStorageEntryForRemove(payload, owner);
        TestState.SavedTestState before = state.saveTestState(remove);
        assertTrue(state.mockedStorage.remove(remove, null));
        state.verifyProtectedStorageRemove(before, remove, true, true, true, true);
    }

    @Test
    void removalCannotBeReplayedAsAddOrRefresh() throws CryptoException {
        assertTrue(state.mockedStorage.addProtectedStorageEntry(state.mockedStorage.getProtectedStorageEntry(payload, owner), null, null));
        ProtectedStorageEntry remove = state.mockedStorage.getProtectedStorageEntryForRemove(payload, owner);
        TestState.SavedTestState before = state.saveTestState(remove);
        assertFalse(state.mockedStorage.addProtectedStorageEntry(remove, null, null));
        state.assertProtectedStorageAdd(before, remove, false, false, false, false);
        RefreshOfferMessage refresh = new RefreshOfferMessage(new byte[32], remove.getSignature(),
                P2PDataStorage.get32ByteHashAsByteArray(payload).bytes, remove.getSequenceNumber());
        before = state.saveTestState(refresh);
        assertFalse(state.mockedStorage.refreshTTL(refresh, null));
        state.verifyRefreshTTL(before, refresh, false);
    }

    @Test
    void removalBindsPayloadSequenceAndOwner() throws CryptoException {
        ProtectedStorageEntry remove = state.mockedStorage.getProtectedStorageEntryForRemove(payload, owner);
        assertRejectedRemove(new ProtectedStorageEntry(payload, owner.getPublic(), 2, remove.getSignature(), state.clockFake), false);
        assertRejectedRemove(new ProtectedStorageEntry(payload, receiver.getPublic(), 1, remove.getSignature(), state.clockFake), false);
        ProtectedStoragePayload changed = mock(ProtectedStoragePayload.class);
        when(changed.getOwnerPubKey()).thenReturn(owner.getPublic());
        when(changed.toProtoMessage()).thenReturn(protobuf.StoragePayload.newBuilder()
                .setAlert(protobuf.Alert.newBuilder().setMessage("different payload")).build());
        assertRejectedRemove(new ProtectedStorageEntry(changed, owner.getPublic(), 1, remove.getSignature(), state.clockFake), false);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void invalidAddAndRefreshSequencesLeaveStorageUnchanged(int sequence) throws CryptoException {
        assertTrue(state.mockedStorage.addProtectedStorageEntry(state.mockedStorage.getProtectedStorageEntry(payload, owner), null, null));
        ProtectedStorageEntry invalid = signedAdd(payload, owner, sequence);
        TestState.SavedTestState before = state.saveTestState(invalid);
        assertFalse(state.mockedStorage.addProtectedStorageEntry(invalid, null, null));
        state.assertProtectedStorageAdd(before, invalid, false, false, false, false);
        assertEquals(1, state.mockedStorage.sequenceNumberMap.get(P2PDataStorage.get32ByteHashAsByteArray(payload)).sequenceNr);
        RefreshOfferMessage refresh = new RefreshOfferMessage(new byte[32], invalid.getSignature(),
                P2PDataStorage.get32ByteHashAsByteArray(payload).bytes, sequence);
        before = state.saveTestState(refresh);
        assertFalse(state.mockedStorage.refreshTTL(refresh, null));
        state.verifyRefreshTTL(before, refresh, false);
    }

    @Test
    void highestMailboxAddCanStillBeRemovedByReceiver() throws CryptoException {
        MailboxStoragePayload mailbox = mailboxPayload();
        ProtectedMailboxStorageEntry add = mailboxEntry(mailbox, owner, Integer.MAX_VALUE - 1);
        assertTrue(state.mockedStorage.addProtectedStorageEntry(add, null, null));
        assertThrows(CryptoException.class,
                () -> state.mockedStorage.getMailboxDataWithSignedSeqNr(mailbox, owner, receiver.getPublic()));
        ProtectedMailboxStorageEntry remove = state.mockedStorage.getMailboxDataWithSignedSeqNr(mailbox, receiver, receiver.getPublic());
        assertEquals(Integer.MAX_VALUE, remove.getSequenceNumber());
        assertTrue(state.mockedStorage.remove(remove, null));
        assertFalse(state.mockedStorage.addProtectedStorageEntry(add, null, null));
        assertThrows(CryptoException.class,
                () -> state.mockedStorage.getMailboxDataWithSignedSeqNr(mailbox, receiver, receiver.getPublic()));
    }

    @Test
    void restoredMailboxEntryCanBeRemovedWithoutSequenceMapRecord() throws CryptoException {
        MailboxStoragePayload mailbox = mailboxPayload();
        state.mockedStorage.addProtectedMailboxStorageEntryToMap(mailboxEntry(mailbox, owner, Integer.MAX_VALUE - 1));
        ProtectedMailboxStorageEntry remove = state.mockedStorage.getMailboxDataWithSignedSeqNr(mailbox, receiver, receiver.getPublic());
        assertEquals(Integer.MAX_VALUE, remove.getSequenceNumber());
        assertTrue(state.mockedStorage.remove(remove, null));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void invalidMailboxAddDoesNotAdvanceCounter(int sequence) throws CryptoException {
        MailboxStoragePayload mailbox = mailboxPayload();
        ProtectedMailboxStorageEntry invalid = mailboxEntry(mailbox, owner, sequence);
        TestState.SavedTestState before = state.saveTestState(invalid);
        assertFalse(state.mockedStorage.addProtectedStorageEntry(invalid, null, null));
        state.assertProtectedStorageAdd(before, invalid, false, false, false, false);
        assertFalse(state.mockedStorage.sequenceNumberMap.containsKey(P2PDataStorage.get32ByteHashAsByteArray(mailbox)));
        if (sequence < 0)
            assertRejectedRemove(mailboxEntry(mailbox, receiver, sequence), false);
    }

    @Test
    void plainMailboxEntriesAndRefreshCannotBypassReceiverAuthorization() throws CryptoException {
        MailboxStoragePayload mailbox = mailboxPayload();
        ProtectedMailboxStorageEntry add = mailboxEntry(mailbox, owner, 1);
        assertTrue(state.mockedStorage.addProtectedStorageEntry(add, null, null));
        assertRejectedRemove(mailboxEntry(mailbox, owner, 2), false);
        for (KeyPair key : new KeyPair[]{owner, receiver}) {
            ProtectedStorageEntry plain = signedAdd(mailbox, key, 2);
            assertFalse(plain.isValidForAddOperation());
            assertRejectedRemove(plain, true);
            assertRejectedRemove(state.mockedStorage.getProtectedStorageEntryForRemove(mailbox, key), true);
        }
        RefreshOfferMessage refresh = new RefreshOfferMessage(new byte[32], signedAdd(mailbox, owner, 2).getSignature(),
                P2PDataStorage.get32ByteHashAsByteArray(mailbox).bytes, 2);
        TestState.SavedTestState before = state.saveTestState(refresh);
        assertFalse(state.mockedStorage.refreshTTL(refresh, null));
        state.verifyRefreshTTL(before, refresh, false);
        assertTrue(state.mockedStorage.remove(state.mockedStorage.getMailboxDataWithSignedSeqNr(mailbox, receiver, receiver.getPublic()), null));
    }

    @Test
    void signingFactoriesFailWithoutWrappingExhaustedCounters() throws CryptoException {
        assertTrue(state.mockedStorage.addProtectedStorageEntry(signedAdd(payload, owner, Integer.MAX_VALUE - 1), null, null));
        assertThrows(CryptoException.class, () -> state.mockedStorage.getProtectedStorageEntry(payload, owner));
        assertThrows(CryptoException.class, () -> state.mockedStorage.getRefreshTTLMessage(payload, owner));
        ProtectedStorageEntry remove = state.mockedStorage.getProtectedStorageEntryForRemove(payload, owner);
        assertEquals(Integer.MAX_VALUE, remove.getSequenceNumber());
        assertTrue(state.mockedStorage.remove(remove, null));
        assertThrows(CryptoException.class, () -> state.mockedStorage.getProtectedStorageEntryForRemove(payload, owner));
    }

    private void assertRejectedRemove(ProtectedStorageEntry entry, boolean viaNetwork) {
        P2PDataStorage.ByteArray hash = P2PDataStorage.get32ByteHashAsByteArray(entry.getProtectedStoragePayload());
        P2PDataStorage.MapValue counter = state.mockedStorage.sequenceNumberMap.get(hash);
        TestState.SavedTestState before = state.saveTestState(entry);
        if (viaNetwork) {
            Connection connection = mock(Connection.class);
            when(connection.getPeersNodeAddressOptional()).thenReturn(Optional.of(TestState.getTestNodeAddress()));
            state.mockedStorage.onMessage(new RemoveDataMessage(entry), connection);
        } else {
            assertFalse(state.mockedStorage.remove(entry, null));
        }
        state.verifyProtectedStorageRemove(before, entry, false, false, false, false);
        assertEquals(counter, state.mockedStorage.sequenceNumberMap.get(hash));
    }

    private ProtectedStorageEntry signedAdd(ProtectedStoragePayload data, KeyPair key, int sequence) throws CryptoException {
        byte[] signature = Sig.sign(key.getPrivate(), P2PDataStorage.get32ByteHash(new P2PDataStorage.DataAndSeqNrPair(data, sequence)));
        return new ProtectedStorageEntry(data, key.getPublic(), sequence, signature, state.clockFake);
    }

    private ProtectedMailboxStorageEntry mailboxEntry(MailboxStoragePayload data, KeyPair key, int sequence) throws CryptoException {
        return new ProtectedMailboxStorageEntry(data, key.getPublic(), sequence, signedAdd(data, key, sequence).getSignature(),
                receiver.getPublic(), state.clockFake);
    }

    private MailboxStoragePayload mailboxPayload() {
        PrefixedSealedAndSignedMessage message = mock(PrefixedSealedAndSignedMessage.class);
        when(message.toProtoNetworkEnvelope()).thenReturn(protobuf.NetworkEnvelope.newBuilder()
                .setPrefixedSealedAndSignedMessage(protobuf.PrefixedSealedAndSignedMessage.getDefaultInstance()).build());
        return new MailboxStoragePayload(message, owner.getPublic(), receiver.getPublic(), MailboxStoragePayload.TTL);
    }
}
