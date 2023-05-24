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

import haveno.common.crypto.Sig;
import haveno.common.persistence.PersistenceManager;
import haveno.common.proto.persistable.PersistablePayload;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.network.NetworkNode;
import haveno.network.p2p.peers.Broadcaster;
import haveno.network.p2p.storage.messages.AddDataMessage;
import haveno.network.p2p.storage.messages.AddPersistableNetworkPayloadMessage;
import haveno.network.p2p.storage.messages.BroadcastMessage;
import haveno.network.p2p.storage.messages.RefreshOfferMessage;
import haveno.network.p2p.storage.messages.RemoveDataMessage;
import haveno.network.p2p.storage.messages.RemoveMailboxDataMessage;
import haveno.network.p2p.storage.mocks.AppendOnlyDataStoreServiceFake;
import haveno.network.p2p.storage.mocks.ClockFake;
import haveno.network.p2p.storage.mocks.MapStoreServiceFake;
import haveno.network.p2p.storage.payload.MailboxStoragePayload;
import haveno.network.p2p.storage.payload.PersistableNetworkPayload;
import haveno.network.p2p.storage.payload.ProtectedMailboxStorageEntry;
import haveno.network.p2p.storage.payload.ProtectedStorageEntry;
import haveno.network.p2p.storage.persistence.AppendOnlyDataStoreListener;
import haveno.network.p2p.storage.persistence.ProtectedDataStoreService;
import haveno.network.p2p.storage.persistence.RemovedPayloadsService;
import haveno.network.p2p.storage.persistence.ResourceDataStoreService;
import haveno.network.p2p.storage.persistence.SequenceNumberMap;
import org.mockito.ArgumentCaptor;

import java.security.PublicKey;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.nullable;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test object that stores a P2PDataStore instance as well as the mock objects necessary for state validation.
 * <p>
 * Used in the P2PDataStorage*Test(s) in order to leverage common test set up and validation.
 */
public class TestState {
    static final int MAX_SEQUENCE_NUMBER_MAP_SIZE_BEFORE_PURGE = 5;

    P2PDataStorage mockedStorage;
    final Broadcaster mockBroadcaster;

    final AppendOnlyDataStoreListener appendOnlyDataStoreListener;
    private final HashMapChangedListener hashMapChangedListener;
    private final PersistenceManager<SequenceNumberMap> mockSeqNrPersistenceManager;
    private final ProtectedDataStoreService protectedDataStoreService;
    final ClockFake clockFake;
    private RemovedPayloadsService removedPayloadsService;

    TestState() {
        mockBroadcaster = mock(Broadcaster.class);
        mockSeqNrPersistenceManager = mock(PersistenceManager.class);
        removedPayloadsService = mock(RemovedPayloadsService.class);
        clockFake = new ClockFake();
        protectedDataStoreService = new ProtectedDataStoreService();

        mockedStorage = new P2PDataStorage(mock(NetworkNode.class),
                mockBroadcaster,
                new AppendOnlyDataStoreServiceFake(),
                protectedDataStoreService, mock(ResourceDataStoreService.class),
                mockSeqNrPersistenceManager,
                removedPayloadsService,
                clockFake,
                MAX_SEQUENCE_NUMBER_MAP_SIZE_BEFORE_PURGE);

        appendOnlyDataStoreListener = mock(AppendOnlyDataStoreListener.class);
        hashMapChangedListener = mock(HashMapChangedListener.class);
        protectedDataStoreService.addService(new MapStoreServiceFake());

        mockedStorage = createP2PDataStorageForTest(
                mockBroadcaster,
                protectedDataStoreService,
                mockSeqNrPersistenceManager,
                clockFake,
                hashMapChangedListener,
                appendOnlyDataStoreListener,
                removedPayloadsService);

        when(mockSeqNrPersistenceManager.getPersisted())
                .thenReturn(mockedStorage.sequenceNumberMap);
    }


    /**
     * Re-initializes the in-memory data structures from the storage objects to simulate a node restarting. Important
     * to note that the current TestState uses Test Doubles instead of actual disk storage so this is just "simulating"
     * not running the entire storage code paths.
     */
    void simulateRestart() {
        removedPayloadsService = mock(RemovedPayloadsService.class);
        mockedStorage = createP2PDataStorageForTest(
                mockBroadcaster,
                protectedDataStoreService,
                mockSeqNrPersistenceManager,
                clockFake,
                hashMapChangedListener,
                appendOnlyDataStoreListener,
                removedPayloadsService);

        when(mockSeqNrPersistenceManager.getPersisted())
                .thenReturn(mockedStorage.sequenceNumberMap);
    }

    private static P2PDataStorage createP2PDataStorageForTest(
            Broadcaster broadcaster,
            ProtectedDataStoreService protectedDataStoreService,
            PersistenceManager<SequenceNumberMap> sequenceNrMapPersistenceManager,
            ClockFake clock,
            HashMapChangedListener hashMapChangedListener,
            AppendOnlyDataStoreListener appendOnlyDataStoreListener,
            RemovedPayloadsService removedPayloadsService) {

        P2PDataStorage p2PDataStorage = new P2PDataStorage(mock(NetworkNode.class),
                broadcaster,
                new AppendOnlyDataStoreServiceFake(),
                protectedDataStoreService,
                mock(ResourceDataStoreService.class),
                sequenceNrMapPersistenceManager,
                removedPayloadsService,
                clock,
                MAX_SEQUENCE_NUMBER_MAP_SIZE_BEFORE_PURGE);

        // Currently TestState only supports reading ProtectedStorageEntries off disk.
        p2PDataStorage.readFromResourcesSync("unused");
        p2PDataStorage.readPersistedSync();

        p2PDataStorage.addHashMapChangedListener(hashMapChangedListener);
        p2PDataStorage.addAppendOnlyDataStoreListener(appendOnlyDataStoreListener);

        return p2PDataStorage;
    }

    private void resetState() {
        reset(mockBroadcaster);
        reset(appendOnlyDataStoreListener);
        reset(hashMapChangedListener);
    }

    void incrementClock() {
        clockFake.increment(TimeUnit.HOURS.toMillis(1));
    }

    public static NodeAddress getTestNodeAddress() {
        return new NodeAddress("address", 8080);
    }

    /**
     * Common test helpers that verify the correct events were signaled based on the test expectation and before/after states.
     */
    private void verifySequenceNumberMapWriteContains(P2PDataStorage.ByteArray payloadHash, int sequenceNumber) {
        assertEquals(sequenceNumber, mockSeqNrPersistenceManager.getPersisted().get(payloadHash).sequenceNr);
    }

    void verifyPersistableAdd(SavedTestState beforeState,
                              PersistableNetworkPayload persistableNetworkPayload,
                              boolean expectedHashMapAndDataStoreUpdated,
                              boolean expectedListenersSignaled,
                              boolean expectedBroadcast) {
        P2PDataStorage.ByteArray hash = new P2PDataStorage.ByteArray(persistableNetworkPayload.getHash());

        if (expectedHashMapAndDataStoreUpdated)
            assertEquals(persistableNetworkPayload, mockedStorage.appendOnlyDataStoreService.getMap(persistableNetworkPayload).get(hash));
        else
            assertEquals(beforeState.persistableNetworkPayloadBeforeOp, mockedStorage.appendOnlyDataStoreService.getMap(persistableNetworkPayload).get(hash));

        if (expectedListenersSignaled)
            verify(appendOnlyDataStoreListener).onAdded(persistableNetworkPayload);
        else
            verify(appendOnlyDataStoreListener, never()).onAdded(persistableNetworkPayload);

        if (expectedBroadcast)
            verify(mockBroadcaster).broadcast(any(AddPersistableNetworkPayloadMessage.class), nullable(NodeAddress.class));
        else
            verify(mockBroadcaster, never()).broadcast(any(BroadcastMessage.class), nullable(NodeAddress.class));
    }

    void assertProtectedStorageAdd(SavedTestState beforeState,
                                   ProtectedStorageEntry protectedStorageEntry,
                                   boolean expectedHashMapAndDataStoreUpdated,
                                   boolean expectedListenersSignaled,
                                   boolean expectedBroadcast,
                                   boolean expectedSequenceNrMapWrite) {
        P2PDataStorage.ByteArray hashMapHash = P2PDataStorage.get32ByteHashAsByteArray(protectedStorageEntry.getProtectedStoragePayload());

        if (expectedHashMapAndDataStoreUpdated) {
            assertEquals(protectedStorageEntry, mockedStorage.getMap().get(hashMapHash));

            if (protectedStorageEntry.getProtectedStoragePayload() instanceof PersistablePayload)
                assertEquals(protectedStorageEntry, protectedDataStoreService.getMap().get(hashMapHash));
        } else {
            assertEquals(beforeState.protectedStorageEntryBeforeOp, mockedStorage.getMap().get(hashMapHash));
            assertEquals(beforeState.protectedStorageEntryBeforeOpDataStoreMap, protectedDataStoreService.getMap().get(hashMapHash));
        }

        if (expectedListenersSignaled) {
            verify(hashMapChangedListener).onAdded(Collections.singletonList(protectedStorageEntry));
        } else {
            verify(hashMapChangedListener, never()).onAdded(Collections.singletonList(protectedStorageEntry));
        }

        if (expectedBroadcast) {
            final ArgumentCaptor<BroadcastMessage> captor = ArgumentCaptor.forClass(BroadcastMessage.class);
            // If we remove the last argument (isNull()) tests fail. No idea why as the broadcast method has an
            // overloaded method with nullable listener. Seems a testframework issue as it should not matter if the
            // method with listener is called with null argument or the other method with no listener. We removed the
            // null value from all other calls but here we can't as it breaks the test.
            verify(mockBroadcaster).broadcast(captor.capture(), nullable(NodeAddress.class), isNull());

            BroadcastMessage broadcastMessage = captor.getValue();
            assertTrue(broadcastMessage instanceof AddDataMessage);
            assertEquals(protectedStorageEntry, ((AddDataMessage) broadcastMessage).getProtectedStorageEntry());
        } else {
            verify(mockBroadcaster, never()).broadcast(any(BroadcastMessage.class), nullable(NodeAddress.class));
        }

        if (expectedSequenceNrMapWrite) {
            verifySequenceNumberMapWriteContains(P2PDataStorage.get32ByteHashAsByteArray(protectedStorageEntry.getProtectedStoragePayload()), protectedStorageEntry.getSequenceNumber());
        }
    }

    void verifyProtectedStorageRemove(SavedTestState beforeState,
                                      ProtectedStorageEntry protectedStorageEntry,
                                      boolean expectedHashMapAndDataStoreUpdated,
                                      boolean expectedListenersSignaled,
                                      boolean expectedBroadcast,
                                      boolean expectedSeqNrWrite) {

        verifyProtectedStorageRemove(beforeState, Collections.singletonList(protectedStorageEntry),
                expectedHashMapAndDataStoreUpdated, expectedListenersSignaled, expectedBroadcast,
                expectedSeqNrWrite);
    }

    void verifyProtectedStorageRemove(SavedTestState beforeState,
                                      Collection<ProtectedStorageEntry> protectedStorageEntries,
                                      boolean expectedHashMapAndDataStoreUpdated,
                                      boolean expectedListenersSignaled,
                                      boolean expectedBroadcast,
                                      boolean expectedSeqNrWrite) {

        // The default matcher expects orders to stay the same. So, create a custom matcher function since
        // we don't care about the order.
        if (expectedListenersSignaled) {
            final ArgumentCaptor<Collection<ProtectedStorageEntry>> argument = ArgumentCaptor.forClass(Collection.class);
            verify(hashMapChangedListener).onRemoved(argument.capture());

            Set<ProtectedStorageEntry> actual = new HashSet<>(argument.getValue());
            Set<ProtectedStorageEntry> expected = new HashSet<>(protectedStorageEntries);

            // Ensure we didn't remove duplicates
            assertEquals(protectedStorageEntries.size(), expected.size());
            assertEquals(argument.getValue().size(), actual.size());
            assertEquals(expected, actual);
        } else {
            verify(hashMapChangedListener, never()).onRemoved(any());
        }

        if (!expectedBroadcast)
            verify(mockBroadcaster, never()).broadcast(any(BroadcastMessage.class), nullable(NodeAddress.class));


        protectedStorageEntries.forEach(protectedStorageEntry -> {
            P2PDataStorage.ByteArray hashMapHash = P2PDataStorage.get32ByteHashAsByteArray(protectedStorageEntry.getProtectedStoragePayload());

            if (expectedSeqNrWrite)
                verifySequenceNumberMapWriteContains(P2PDataStorage.get32ByteHashAsByteArray(
                        protectedStorageEntry.getProtectedStoragePayload()), protectedStorageEntry.getSequenceNumber());

            if (expectedBroadcast) {
                if (protectedStorageEntry instanceof ProtectedMailboxStorageEntry)
                    verify(mockBroadcaster).broadcast(any(RemoveMailboxDataMessage.class), nullable(NodeAddress.class));
                else
                    verify(mockBroadcaster).broadcast(any(RemoveDataMessage.class), nullable(NodeAddress.class));
            }


            if (expectedHashMapAndDataStoreUpdated) {
                assertNull(mockedStorage.getMap().get(hashMapHash));

                if (protectedStorageEntry.getProtectedStoragePayload() instanceof PersistablePayload)
                    assertNull(protectedDataStoreService.getMap().get(hashMapHash));

            } else {
                assertEquals(beforeState.protectedStorageEntryBeforeOp, mockedStorage.getMap().get(hashMapHash));
            }
        });
    }

    void verifyRefreshTTL(SavedTestState beforeState,
                          RefreshOfferMessage refreshOfferMessage,
                          boolean expectedStateChange) {
        P2PDataStorage.ByteArray payloadHash = new P2PDataStorage.ByteArray(refreshOfferMessage.getHashOfPayload());

        ProtectedStorageEntry entryAfterRefresh = mockedStorage.getMap().get(payloadHash);

        if (expectedStateChange) {
            assertNotNull(entryAfterRefresh);
            assertEquals(refreshOfferMessage.getSequenceNumber(), entryAfterRefresh.getSequenceNumber());
            assertEquals(refreshOfferMessage.getSignature(), entryAfterRefresh.getSignature());
            assertTrue(entryAfterRefresh.getCreationTimeStamp() > beforeState.creationTimestampBeforeUpdate);

            final ArgumentCaptor<BroadcastMessage> captor = ArgumentCaptor.forClass(BroadcastMessage.class);
            verify(mockBroadcaster).broadcast(captor.capture(), nullable(NodeAddress.class));

            BroadcastMessage broadcastMessage = captor.getValue();
            assertTrue(broadcastMessage instanceof RefreshOfferMessage);
            assertEquals(refreshOfferMessage, broadcastMessage);

            verifySequenceNumberMapWriteContains(payloadHash, refreshOfferMessage.getSequenceNumber());
        } else {

            // Verify the existing entry is unchanged
            if (beforeState.protectedStorageEntryBeforeOp != null) {
                assertEquals(entryAfterRefresh, beforeState.protectedStorageEntryBeforeOp);
                assertEquals(beforeState.protectedStorageEntryBeforeOp.getSequenceNumber(), entryAfterRefresh.getSequenceNumber());
                assertEquals(beforeState.protectedStorageEntryBeforeOp.getSignature(), entryAfterRefresh.getSignature());
                assertEquals(beforeState.creationTimestampBeforeUpdate, entryAfterRefresh.getCreationTimeStamp());
            }

            verify(mockBroadcaster, never()).broadcast(any(BroadcastMessage.class), nullable(NodeAddress.class));
        }
    }

    static MailboxStoragePayload buildMailboxStoragePayload(PublicKey senderKey, PublicKey receiverKey) {
        // Need to be able to take the hash which leverages protobuf Messages
        protobuf.StoragePayload messageMock = mock(protobuf.StoragePayload.class);
        when(messageMock.toByteArray()).thenReturn(Sig.getPublicKeyBytes(receiverKey));

        MailboxStoragePayload payloadMock = mock(MailboxStoragePayload.class);
        when(payloadMock.getOwnerPubKey()).thenReturn(receiverKey);
        when(payloadMock.getSenderPubKeyForAddOperation()).thenReturn(senderKey);
        when(payloadMock.toProtoMessage()).thenReturn(messageMock);

        return payloadMock;
    }

    SavedTestState saveTestState(PersistableNetworkPayload persistableNetworkPayload) {
        return new SavedTestState(this, persistableNetworkPayload);
    }

    SavedTestState saveTestState(ProtectedStorageEntry protectedStorageEntry) {
        return new SavedTestState(this, protectedStorageEntry);
    }

    SavedTestState saveTestState(RefreshOfferMessage refreshOfferMessage) {
        return new SavedTestState(this, refreshOfferMessage);
    }

    /**
     * Wrapper object for TestState state that needs to be saved for future validation. Used in multiple tests
     * to verify that the state before and after an operation matched the expectation.
     */
    static class SavedTestState {
        final TestState state;

        // Used in PersistableNetworkPayload tests
        PersistableNetworkPayload persistableNetworkPayloadBeforeOp;

        // Used in ProtectedStorageEntry tests
        ProtectedStorageEntry protectedStorageEntryBeforeOp;
        ProtectedStorageEntry protectedStorageEntryBeforeOpDataStoreMap;

        long creationTimestampBeforeUpdate;

        private SavedTestState(TestState state) {
            this.state = state;
            creationTimestampBeforeUpdate = 0;
            state.resetState();
        }

        private SavedTestState(TestState testState, PersistableNetworkPayload persistableNetworkPayload) {
            this(testState);
            P2PDataStorage.ByteArray hash = new P2PDataStorage.ByteArray(persistableNetworkPayload.getHash());
            persistableNetworkPayloadBeforeOp = testState.mockedStorage.appendOnlyDataStoreService.getMap(persistableNetworkPayload).get(hash);
        }

        private SavedTestState(TestState testState, ProtectedStorageEntry protectedStorageEntry) {
            this(testState);

            P2PDataStorage.ByteArray hashMapHash = P2PDataStorage.get32ByteHashAsByteArray(protectedStorageEntry.getProtectedStoragePayload());
            protectedStorageEntryBeforeOp = testState.mockedStorage.getMap().get(hashMapHash);
            protectedStorageEntryBeforeOpDataStoreMap = testState.protectedDataStoreService.getMap().get(hashMapHash);


            creationTimestampBeforeUpdate = (protectedStorageEntryBeforeOp != null) ? protectedStorageEntryBeforeOp.getCreationTimeStamp() : 0;
        }

        private SavedTestState(TestState testState, RefreshOfferMessage refreshOfferMessage) {
            this(testState);

            P2PDataStorage.ByteArray hashMapHash = new P2PDataStorage.ByteArray(refreshOfferMessage.getHashOfPayload());
            protectedStorageEntryBeforeOp = testState.mockedStorage.getMap().get(hashMapHash);

            creationTimestampBeforeUpdate = (protectedStorageEntryBeforeOp != null) ? protectedStorageEntryBeforeOp.getCreationTimeStamp() : 0;
        }
    }
}
