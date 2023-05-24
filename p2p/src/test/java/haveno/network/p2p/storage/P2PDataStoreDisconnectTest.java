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

import haveno.common.crypto.CryptoException;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.TestUtils;
import haveno.network.p2p.network.CloseConnectionReason;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.storage.mocks.ExpirableProtectedStoragePayloadStub;
import haveno.network.p2p.storage.payload.ProtectedStorageEntry;
import haveno.network.p2p.storage.payload.ProtectedStoragePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static haveno.network.p2p.storage.TestState.SavedTestState;
import static haveno.network.p2p.storage.TestState.getTestNodeAddress;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests of the P2PDataStore ConnectionListener interface.
 */
public class P2PDataStoreDisconnectTest {
    private TestState testState;
    private Connection mockedConnection;

    private static ProtectedStorageEntry populateTestState(TestState testState,
                                                           long ttl) throws CryptoException, NoSuchAlgorithmException {
        KeyPair ownerKeys = TestUtils.generateKeyPair();
        ProtectedStoragePayload protectedStoragePayload = new ExpirableProtectedStoragePayloadStub(ownerKeys.getPublic(), ttl);

        ProtectedStorageEntry protectedStorageEntry = testState.mockedStorage.getProtectedStorageEntry(protectedStoragePayload, ownerKeys);
        testState.mockedStorage.addProtectedStorageEntry(protectedStorageEntry, getTestNodeAddress(), null);

        return protectedStorageEntry;
    }

    private static void verifyStateAfterDisconnect(TestState currentState,
                                                   SavedTestState beforeState,
                                                   boolean wasTTLReduced) {
        ProtectedStorageEntry protectedStorageEntry = beforeState.protectedStorageEntryBeforeOp;

        currentState.verifyProtectedStorageRemove(beforeState, protectedStorageEntry,
                false, false, false, false);

        if (wasTTLReduced) {
            assertTrue(protectedStorageEntry.getCreationTimeStamp() < beforeState.creationTimestampBeforeUpdate);
        } else {
            assertEquals(protectedStorageEntry.getCreationTimeStamp(), beforeState.creationTimestampBeforeUpdate);
        }
    }

    @BeforeEach
    public void setUp() {
        this.mockedConnection = mock(Connection.class);
        this.testState = new TestState();
    }

    // TESTCASE: Bad peer info
    @Test
    public void peerConnectionUnknown() throws CryptoException, NoSuchAlgorithmException {
        when(this.mockedConnection.getPeersNodeAddressOptional()).thenReturn(Optional.empty());
        ProtectedStorageEntry protectedStorageEntry = populateTestState(testState, 2);

        SavedTestState beforeState = this.testState.saveTestState(protectedStorageEntry);

        this.testState.mockedStorage.onDisconnect(CloseConnectionReason.SOCKET_CLOSED, mockedConnection);

        verifyStateAfterDisconnect(this.testState, beforeState, false);
    }

    // TESTCASE: Intended disconnects don't trigger expiration
    @Test
    public void connectionClosedIntended() throws CryptoException, NoSuchAlgorithmException {
        when(this.mockedConnection.getPeersNodeAddressOptional()).thenReturn(Optional.of(getTestNodeAddress()));
        ProtectedStorageEntry protectedStorageEntry = populateTestState(testState, 2);

        SavedTestState beforeState = this.testState.saveTestState(protectedStorageEntry);

        this.testState.mockedStorage.onDisconnect(CloseConnectionReason.CLOSE_REQUESTED_BY_PEER, mockedConnection);

        verifyStateAfterDisconnect(this.testState, beforeState, false);
    }

    // TESTCASE: Peer NodeAddress unknown
    @Test
    public void connectionClosedSkipsItemsPeerInfoBadState() throws NoSuchAlgorithmException, CryptoException {
        when(this.mockedConnection.getPeersNodeAddressOptional()).thenReturn(Optional.empty());

        ProtectedStorageEntry protectedStorageEntry = populateTestState(testState, 2);

        SavedTestState beforeState = this.testState.saveTestState(protectedStorageEntry);

        this.testState.mockedStorage.onDisconnect(CloseConnectionReason.SOCKET_CLOSED, mockedConnection);

        verifyStateAfterDisconnect(this.testState, beforeState, false);
    }

    // TESTCASE: Unintended disconnects reduce the TTL for entrys that match disconnected peer
    @Test
    public void connectionClosedReduceTTL() throws NoSuchAlgorithmException, CryptoException {
        when(this.mockedConnection.getPeersNodeAddressOptional()).thenReturn(Optional.of(getTestNodeAddress()));

        ProtectedStorageEntry protectedStorageEntry = populateTestState(testState, TimeUnit.DAYS.toMillis(90));

        SavedTestState beforeState = this.testState.saveTestState(protectedStorageEntry);

        this.testState.mockedStorage.onDisconnect(CloseConnectionReason.SOCKET_CLOSED, mockedConnection);

        verifyStateAfterDisconnect(this.testState, beforeState, true);
    }

    // TESTCASE: Unintended disconnects don't reduce TTL for entrys that are not from disconnected peer
    @Test
    public void connectionClosedSkipsItemsNotFromPeer() throws NoSuchAlgorithmException, CryptoException {
        when(this.mockedConnection.getPeersNodeAddressOptional()).thenReturn(Optional.of(new NodeAddress("notTestNode", 2020)));

        ProtectedStorageEntry protectedStorageEntry = populateTestState(testState, 2);

        SavedTestState beforeState = this.testState.saveTestState(protectedStorageEntry);

        this.testState.mockedStorage.onDisconnect(CloseConnectionReason.SOCKET_CLOSED, mockedConnection);

        verifyStateAfterDisconnect(this.testState, beforeState, false);
    }
}
