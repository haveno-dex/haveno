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

package haveno.network.p2p.storage;

import haveno.common.app.Version;
import haveno.common.config.Config;
import haveno.common.proto.network.NetworkProtoResolver;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.TestUtils;
import haveno.network.p2p.network.CloseConnectionReason;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.network.ConnectionListener;
import haveno.network.p2p.network.InboundConnection;
import haveno.network.p2p.network.LocalhostNetworkNode;
import haveno.network.p2p.network.OutboundConnection;
import haveno.network.p2p.network.SetupListener;
import haveno.network.p2p.peers.getdata.messages.GetUpdatedDataRequest;
import haveno.network.p2p.peers.keepalive.messages.Ping;
import haveno.network.p2p.storage.mocks.ExpirableProtectedStoragePayloadStub;
import haveno.network.p2p.storage.payload.ProtectedStorageEntry;
import haveno.network.p2p.storage.payload.ProtectedStoragePayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPair;
import java.util.HashSet;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static haveno.network.p2p.storage.TestState.getTestNodeAddress;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests of the P2PDataStore ConnectionListener interface with the connections of a real network node.
 */
public class P2PDataStoreDisconnectNetworkTest {
    private final AtomicReference<CloseConnectionReason> closeConnectionReason = new AtomicReference<>();
    private final AtomicReference<Connection> closedConnection = new AtomicReference<>();
    private final CountDownLatch disconnectLatch = new CountDownLatch(1);
    private LocalhostNetworkNode networkNode;

    @BeforeEach
    public void setUp() {
        Version.setBaseCryptoNetworkId(1);
    }

    @AfterEach
    public void tearDown() {
        if (networkNode != null)
            networkNode.shutDown(null);
    }

    // TESTCASE: A peer that claims the owner's address on an inbound connection and then drops it doesn't reduce TTL
    @Test
    public void inboundPeerClaimsOwnerAddressAndDisconnects() throws Exception {
        TestState testState = new TestState();
        ProtectedStorageEntry ownersEntry = addEntry(testState, getTestNodeAddress());
        long ownersCreationTimeStamp = ownersEntry.getCreationTimeStamp();
        int port = getFreePort();
        startNetworkNode(testState, port);

        // one message with the owner's address as sender, then the connection is dropped
        try (Socket socket = new Socket("localhost", port)) {
            sendGetUpdatedDataRequest(socket, getTestNodeAddress());
        }

        assertTrue(disconnectLatch.await(30, TimeUnit.SECONDS));
        assertInstanceOf(InboundConnection.class, closedConnection.get());
        assertEquals(Optional.of(getTestNodeAddress()), closedConnection.get().getPeersNodeAddressOptional());
        assertFalse(closeConnectionReason.get().isIntended);
        assertEquals(ownersCreationTimeStamp, ownersEntry.getCreationTimeStamp());
    }

    // TESTCASE: A dialed peer that claims the owner's address keeps the dialed address, so only its own entries are backdated
    @Test
    public void outboundPeerClaimsOwnerAddressAndDisconnects() throws Exception {
        TestState testState = new TestState();
        try (ServerSocket peersServerSocket = new ServerSocket(0)) {
            peersServerSocket.setSoTimeout(30_000);
            NodeAddress peersNodeAddress = new NodeAddress("localhost", peersServerSocket.getLocalPort());
            ProtectedStorageEntry ownersEntry = addEntry(testState, getTestNodeAddress());
            ProtectedStorageEntry peersEntry = addEntry(testState, peersNodeAddress);
            long ownersCreationTimeStamp = ownersEntry.getCreationTimeStamp();
            long peersCreationTimeStamp = peersEntry.getCreationTimeStamp();
            startNetworkNode(testState, getFreePort());

            networkNode.sendMessage(peersNodeAddress, new Ping(1, 0));

            // the dialed peer claims the owner's address as sender and keeps the connection until the node drops it
            try (Socket socket = peersServerSocket.accept()) {
                sendGetUpdatedDataRequest(socket, getTestNodeAddress());
                assertTrue(disconnectLatch.await(30, TimeUnit.SECONDS));
            }

            assertInstanceOf(OutboundConnection.class, closedConnection.get());
            assertEquals(Optional.of(peersNodeAddress), closedConnection.get().getPeersNodeAddressOptional());
            assertFalse(closeConnectionReason.get().isIntended);
            assertEquals(ownersCreationTimeStamp, ownersEntry.getCreationTimeStamp());
            assertTrue(peersEntry.getCreationTimeStamp() < peersCreationTimeStamp);
        }
    }

    private void startNetworkNode(TestState testState, int port) throws Exception {
        setConnectionConfig();
        networkNode = new LocalhostNetworkNode(port, getUpdatedDataRequestResolver(), null, 12);
        // listeners are not ordered, so the storage is called here before the latch opens
        networkNode.addConnectionListener(new ConnectionListener() {
            @Override
            public void onConnection(Connection connection) {
            }

            @Override
            public void onDisconnect(CloseConnectionReason reason, Connection connection) {
                testState.mockedStorage.onDisconnect(reason, connection);
                closeConnectionReason.set(reason);
                closedConnection.set(connection);
                disconnectLatch.countDown();
            }
        });

        CountDownLatch startupLatch = new CountDownLatch(1);
        networkNode.start(new SetupListener() {
            @Override
            public void onTorNodeReady() {
            }

            @Override
            public void onHiddenServicePublished() {
                startupLatch.countDown();
            }
        });
        assertTrue(startupLatch.await(30, TimeUnit.SECONDS));
    }

    // the connection throttlers read a config which the app injects statically
    private static void setConnectionConfig() throws ReflectiveOperationException {
        Field config = Connection.class.getDeclaredField("config");
        config.setAccessible(true);
        if (config.get(null) == null)
            config.set(null, new Config());
    }

    private static ProtectedStorageEntry addEntry(TestState testState, NodeAddress ownerNodeAddress) throws Exception {
        KeyPair ownerKeys = TestUtils.generateKeyPair();
        ProtectedStoragePayload protectedStoragePayload = new ExpirableProtectedStoragePayloadStub(ownerKeys.getPublic(),
                TimeUnit.DAYS.toMillis(90), ownerNodeAddress);
        ProtectedStorageEntry protectedStorageEntry = testState.mockedStorage.getProtectedStorageEntry(protectedStoragePayload, ownerKeys);
        assertTrue(testState.mockedStorage.addProtectedStorageEntry(protectedStorageEntry, ownerNodeAddress, null));
        return protectedStorageEntry;
    }

    private static void sendGetUpdatedDataRequest(Socket socket, NodeAddress senderNodeAddress) throws IOException {
        new GetUpdatedDataRequest(senderNodeAddress, 1, new HashSet<>())
                .toProtoNetworkEnvelope()
                .writeDelimitedTo(socket.getOutputStream());
        socket.getOutputStream().flush();
    }

    private static int getFreePort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }

    private static NetworkProtoResolver getUpdatedDataRequestResolver() throws IOException {
        NetworkProtoResolver networkProtoResolver = mock(NetworkProtoResolver.class);
        when(networkProtoResolver.fromProto(any(protobuf.NetworkEnvelope.class))).thenAnswer(invocation -> {
            protobuf.NetworkEnvelope proto = invocation.getArgument(0);
            return GetUpdatedDataRequest.fromProto(proto.getGetUpdatedDataRequest(), proto.getMessageVersion());
        });
        return networkProtoResolver;
    }
}
