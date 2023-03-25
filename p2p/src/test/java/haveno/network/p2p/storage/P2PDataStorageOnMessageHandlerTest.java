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

import haveno.common.proto.network.NetworkEnvelope;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.mocks.MockPayload;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.storage.messages.AddPersistableNetworkPayloadMessage;
import haveno.network.p2p.storage.messages.BroadcastMessage;
import haveno.network.p2p.storage.mocks.PersistableNetworkPayloadStub;
import haveno.network.p2p.storage.payload.PersistableNetworkPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests of the P2PDataStore MessageListener interface failure cases. The success cases are covered in the
 * PersistableNetworkPayloadTest and ProtectedStorageEntryTest tests,
 */
public class P2PDataStorageOnMessageHandlerTest {
    private TestState testState;

    @BeforeEach
    public void setup() {
        this.testState = new TestState();
    }

    static class UnsupportedBroadcastMessage extends BroadcastMessage {

        UnsupportedBroadcastMessage() {
            super("0");
        }
    }

    @Test
    public void invalidBroadcastMessage() {
        NetworkEnvelope envelope = new MockPayload("Mock");

        Connection mockedConnection = mock(Connection.class);
        when(mockedConnection.getPeersNodeAddressOptional()).thenReturn(Optional.of(TestState.getTestNodeAddress()));

        this.testState.mockedStorage.onMessage(envelope, mockedConnection);

        verify(this.testState.appendOnlyDataStoreListener, never()).onAdded(any(PersistableNetworkPayload.class));
        verify(this.testState.mockBroadcaster, never()).broadcast(any(BroadcastMessage.class), any(NodeAddress.class));
    }

    @Test
    public void unsupportedBroadcastMessage() {
        NetworkEnvelope envelope = new UnsupportedBroadcastMessage();

        Connection mockedConnection = mock(Connection.class);
        when(mockedConnection.getPeersNodeAddressOptional()).thenReturn(Optional.of(TestState.getTestNodeAddress()));

        this.testState.mockedStorage.onMessage(envelope, mockedConnection);

        verify(this.testState.appendOnlyDataStoreListener, never()).onAdded(any(PersistableNetworkPayload.class));
        verify(this.testState.mockBroadcaster, never()).broadcast(any(BroadcastMessage.class), any(NodeAddress.class));
    }

    @Test
    public void invalidConnectionObject() {
        PersistableNetworkPayload persistableNetworkPayload = new PersistableNetworkPayloadStub(true);
        NetworkEnvelope envelope = new AddPersistableNetworkPayloadMessage(persistableNetworkPayload);

        Connection mockedConnection = mock(Connection.class);
        when(mockedConnection.getPeersNodeAddressOptional()).thenReturn(Optional.empty());

        this.testState.mockedStorage.onMessage(envelope, mockedConnection);

        verify(this.testState.appendOnlyDataStoreListener, never()).onAdded(any(PersistableNetworkPayload.class));
        verify(this.testState.mockBroadcaster, never()).broadcast(any(BroadcastMessage.class), any(NodeAddress.class));
    }
}
