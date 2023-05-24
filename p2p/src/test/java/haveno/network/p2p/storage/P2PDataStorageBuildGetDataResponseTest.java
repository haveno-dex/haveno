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

import com.google.protobuf.Message;
import haveno.common.app.Capabilities;
import haveno.common.app.Capability;
import haveno.common.crypto.Sig;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.TestUtils;
import haveno.network.p2p.network.NetworkNode;
import haveno.network.p2p.peers.getdata.messages.GetDataRequest;
import haveno.network.p2p.peers.getdata.messages.GetDataResponse;
import haveno.network.p2p.peers.getdata.messages.GetUpdatedDataRequest;
import haveno.network.p2p.peers.getdata.messages.PreliminaryGetDataRequest;
import haveno.network.p2p.storage.mocks.PersistableNetworkPayloadStub;
import haveno.network.p2p.storage.payload.CapabilityRequiringPayload;
import haveno.network.p2p.storage.payload.PersistableNetworkPayload;
import haveno.network.p2p.storage.payload.ProtectedStorageEntry;
import haveno.network.p2p.storage.payload.ProtectedStoragePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class P2PDataStorageBuildGetDataResponseTest {
    abstract static class P2PDataStorageBuildGetDataResponseTestBase {
        // GIVEN null & non-null supportedCapabilities
        private TestState testState;

        abstract GetDataRequest buildGetDataRequest(int nonce, Set<byte[]> knownKeys);

        @Mock
        NetworkNode networkNode;

        private NodeAddress localNodeAddress;

        @BeforeEach
        public void setUp() {
            MockitoAnnotations.initMocks(this);
            this.testState = new TestState();

            this.localNodeAddress = new NodeAddress("localhost", 8080);
            when(networkNode.getNodeAddress()).thenReturn(this.localNodeAddress);

            // Set up basic capabilities to ensure message contains it
            Capabilities.app.addAll(Capability.MEDIATION);
        }

        static class RequiredCapabilitiesPNPStub extends PersistableNetworkPayloadStub
                implements CapabilityRequiringPayload {
            Capabilities capabilities;

            RequiredCapabilitiesPNPStub(Capabilities capabilities, byte[] hash) {
                super(hash);
                this.capabilities = capabilities;
            }

            @Override
            public Capabilities getRequiredCapabilities() {
                return capabilities;
            }
        }

        /**
         * Generates a unique ProtectedStorageEntry that is valid for add. This is used to initialize P2PDataStorage state
         * so the tests can validate the correct behavior. Adds of identical payloads with different sequence numbers
         * is not supported.
         */
        private ProtectedStorageEntry getProtectedStorageEntryForAdd() throws NoSuchAlgorithmException {
            return getProtectedStorageEntryForAdd(null);
        }

        private ProtectedStorageEntry getProtectedStorageEntryForAdd(Capabilities requiredCapabilities)
                throws NoSuchAlgorithmException {
            KeyPair ownerKeys = TestUtils.generateKeyPair();

            // Payload stub
            ProtectedStoragePayload protectedStoragePayload;

            if (requiredCapabilities == null)
                protectedStoragePayload = mock(ProtectedStoragePayload.class);
            else {
                protectedStoragePayload = mock(ProtectedStoragePayload.class,
                        withSettings().extraInterfaces(CapabilityRequiringPayload.class));
                when(((CapabilityRequiringPayload) protectedStoragePayload).getRequiredCapabilities())
                        .thenReturn(requiredCapabilities);
            }

            Message messageMock = mock(Message.class);
            when(messageMock.toByteArray()).thenReturn(Sig.getPublicKeyBytes(ownerKeys.getPublic()));
            when(protectedStoragePayload.toProtoMessage()).thenReturn(messageMock);

            // Entry stub
            ProtectedStorageEntry stub = mock(ProtectedStorageEntry.class);
            when(stub.getOwnerPubKey()).thenReturn(ownerKeys.getPublic());
            when(stub.isValidForAddOperation()).thenReturn(true);
            when(stub.matchesRelevantPubKey(any(ProtectedStorageEntry.class))).thenReturn(true);
            when(stub.getSequenceNumber()).thenReturn(1);
            when(stub.getProtectedStoragePayload()).thenReturn(protectedStoragePayload);

            return stub;
        }

        // TESTCASE: Given a GetDataRequest w/ unknown PNP, nothing is sent back
        @Test
        public void buildGetDataResponse_unknownPNPDoNothing() {
            PersistableNetworkPayload fromPeer = new PersistableNetworkPayloadStub(new byte[]{1});

            GetDataRequest getDataRequest =
                    this.buildGetDataRequest(1, new HashSet<>(Collections.singletonList(fromPeer.getHash())));

            AtomicBoolean outPNPTruncated = new AtomicBoolean(false);
            AtomicBoolean outPSETruncated = new AtomicBoolean(false);
            Capabilities peerCapabilities = new Capabilities();
            GetDataResponse getDataResponse = this.testState.mockedStorage.buildGetDataResponse(
                    getDataRequest, 1, outPNPTruncated, outPSETruncated, peerCapabilities);

            assertFalse(outPNPTruncated.get());
            assertFalse(outPSETruncated.get());
            assertEquals(1, getDataResponse.getRequestNonce());
            assertEquals(getDataRequest instanceof GetUpdatedDataRequest, getDataResponse.isGetUpdatedDataResponse());
            assertEquals(getDataResponse.getSupportedCapabilities(), Capabilities.app);
            assertTrue(getDataResponse.getPersistableNetworkPayloadSet().isEmpty());
            assertTrue(getDataResponse.getDataSet().isEmpty());
        }

        // TESTCASE: Given a GetDataRequest w/ known PNP, nothing is sent back
        @Test
        public void buildGetDataResponse_knownPNPDoNothing() {
            PersistableNetworkPayload fromPeerAndLocal = new PersistableNetworkPayloadStub(new byte[]{1});

            this.testState.mockedStorage.addPersistableNetworkPayload(
                    fromPeerAndLocal, this.localNodeAddress, false);

            GetDataRequest getDataRequest =
                    this.buildGetDataRequest(
                            1,
                            new HashSet<>(Collections.singletonList(fromPeerAndLocal.getHash())));

            AtomicBoolean outPNPTruncated = new AtomicBoolean(false);
            AtomicBoolean outPSETruncated = new AtomicBoolean(false);
            Capabilities peerCapabilities = new Capabilities();
            GetDataResponse getDataResponse = this.testState.mockedStorage.buildGetDataResponse(
                    getDataRequest, 1, outPNPTruncated, outPSETruncated, peerCapabilities);

            assertFalse(outPNPTruncated.get());
            assertFalse(outPSETruncated.get());
            assertEquals(1, getDataResponse.getRequestNonce());
            assertEquals(getDataRequest instanceof GetUpdatedDataRequest, getDataResponse.isGetUpdatedDataResponse());
            assertEquals(getDataResponse.getSupportedCapabilities(), Capabilities.app);
            assertTrue(getDataResponse.getPersistableNetworkPayloadSet().isEmpty());
            assertTrue(getDataResponse.getDataSet().isEmpty());
        }

        // TESTCASE: Given a GetDataRequest w/o known PNP, send it back
        @Test
        public void buildGetDataResponse_unknownPNPSendBack() {
            PersistableNetworkPayload onlyLocal = new PersistableNetworkPayloadStub(new byte[]{1});

            this.testState.mockedStorage.addPersistableNetworkPayload(
                    onlyLocal, this.localNodeAddress, false);

            GetDataRequest getDataRequest =
                    this.buildGetDataRequest(1, new HashSet<>());

            AtomicBoolean outPNPTruncated = new AtomicBoolean(false);
            AtomicBoolean outPSETruncated = new AtomicBoolean(false);
            Capabilities peerCapabilities = new Capabilities();
            GetDataResponse getDataResponse = this.testState.mockedStorage.buildGetDataResponse(
                    getDataRequest, 1, outPNPTruncated, outPSETruncated, peerCapabilities);

            assertFalse(outPNPTruncated.get());
            assertFalse(outPSETruncated.get());
            assertEquals(1, getDataResponse.getRequestNonce());
            assertEquals(getDataRequest instanceof GetUpdatedDataRequest, getDataResponse.isGetUpdatedDataResponse());
            assertEquals(getDataResponse.getSupportedCapabilities(), Capabilities.app);
            assertTrue(getDataResponse.getPersistableNetworkPayloadSet().contains(onlyLocal));
            assertTrue(getDataResponse.getDataSet().isEmpty());
        }

        // TESTCASE: Given a GetDataRequest w/o known PNP, don't send more than truncation limit
        @Test
        public void buildGetDataResponse_unknownPNPSendBackTruncation() {
            PersistableNetworkPayload onlyLocal1 = new PersistableNetworkPayloadStub(new byte[]{1});
            PersistableNetworkPayload onlyLocal2 = new PersistableNetworkPayloadStub(new byte[]{2});

            this.testState.mockedStorage.addPersistableNetworkPayload(
                    onlyLocal1, this.localNodeAddress, false);
            this.testState.mockedStorage.addPersistableNetworkPayload(
                    onlyLocal2, this.localNodeAddress, false);

            GetDataRequest getDataRequest =
                    this.buildGetDataRequest(1, new HashSet<>());

            AtomicBoolean outPNPTruncated = new AtomicBoolean(false);
            AtomicBoolean outPSETruncated = new AtomicBoolean(false);
            Capabilities peerCapabilities = new Capabilities();
            GetDataResponse getDataResponse = this.testState.mockedStorage.buildGetDataResponse(
                    getDataRequest, 1, outPNPTruncated, outPSETruncated, peerCapabilities);

            assertTrue(outPNPTruncated.get());
            assertFalse(outPSETruncated.get());
            assertEquals(1, getDataResponse.getRequestNonce());
            assertEquals(getDataRequest instanceof GetUpdatedDataRequest, getDataResponse.isGetUpdatedDataResponse());
            assertEquals(getDataResponse.getSupportedCapabilities(), Capabilities.app);
            assertEquals(1, getDataResponse.getPersistableNetworkPayloadSet().size());
            Set<PersistableNetworkPayload> persistableNetworkPayloadSet = getDataResponse.getPersistableNetworkPayloadSet();

            // We use a set at the filter so it is not deterministic which item get truncated
            assertEquals(1, persistableNetworkPayloadSet.size());
            assertTrue(getDataResponse.getDataSet().isEmpty());
        }

        // TESTCASE: Given a GetDataRequest w/o known PNP, but missing required capabilities, nothing is sent back
        @Test
        public void buildGetDataResponse_unknownPNPCapabilitiesMismatchDontSendBack() {
            PersistableNetworkPayload onlyLocal =
                    new RequiredCapabilitiesPNPStub(new Capabilities(Collections.singletonList(Capability.MEDIATION)),
                            new byte[]{1});

            this.testState.mockedStorage.addPersistableNetworkPayload(
                    onlyLocal, this.localNodeAddress, false);

            GetDataRequest getDataRequest =
                    this.buildGetDataRequest(1, new HashSet<>());

            AtomicBoolean outPNPTruncated = new AtomicBoolean(false);
            AtomicBoolean outPSETruncated = new AtomicBoolean(false);
            Capabilities peerCapabilities = new Capabilities();
            GetDataResponse getDataResponse = this.testState.mockedStorage.buildGetDataResponse(
                    getDataRequest, 2, outPNPTruncated, outPSETruncated, peerCapabilities);

            assertFalse(outPNPTruncated.get());
            assertFalse(outPSETruncated.get());
            assertEquals(1, getDataResponse.getRequestNonce());
            assertEquals(getDataRequest instanceof GetUpdatedDataRequest, getDataResponse.isGetUpdatedDataResponse());
            assertEquals(getDataResponse.getSupportedCapabilities(), Capabilities.app);
            assertTrue(getDataResponse.getPersistableNetworkPayloadSet().isEmpty());
            assertTrue(getDataResponse.getDataSet().isEmpty());
        }

        // TESTCASE: Given a GetDataRequest w/o known PNP that requires capabilities (and they match) send it back
        @Test
        public void buildGetDataResponse_unknownPNPCapabilitiesMatch() {
            PersistableNetworkPayload onlyLocal =
                    new RequiredCapabilitiesPNPStub(new Capabilities(Collections.singletonList(Capability.MEDIATION)),
                            new byte[]{1});

            this.testState.mockedStorage.addPersistableNetworkPayload(
                    onlyLocal, this.localNodeAddress, false);

            GetDataRequest getDataRequest =
                    this.buildGetDataRequest(1, new HashSet<>());

            AtomicBoolean outPNPTruncated = new AtomicBoolean(false);
            AtomicBoolean outPSETruncated = new AtomicBoolean(false);
            Capabilities peerCapabilities = new Capabilities(Collections.singletonList(Capability.MEDIATION));
            GetDataResponse getDataResponse = this.testState.mockedStorage.buildGetDataResponse(
                    getDataRequest, 2, outPNPTruncated, outPSETruncated, peerCapabilities);

            assertFalse(outPNPTruncated.get());
            assertFalse(outPSETruncated.get());
            assertEquals(1, getDataResponse.getRequestNonce());
            assertEquals(getDataRequest instanceof GetUpdatedDataRequest, getDataResponse.isGetUpdatedDataResponse());
            assertEquals(getDataResponse.getSupportedCapabilities(), Capabilities.app);
            assertTrue(getDataResponse.getPersistableNetworkPayloadSet().contains(onlyLocal));
            assertTrue(getDataResponse.getDataSet().isEmpty());
        }

        // TESTCASE: Given a GetDataRequest w/ unknown PSE, nothing is sent back
        @Test
        public void buildGetDataResponse_unknownPSEDoNothing() throws NoSuchAlgorithmException {
            ProtectedStorageEntry fromPeer = getProtectedStorageEntryForAdd();

            GetDataRequest getDataRequest =
                    this.buildGetDataRequest(1,
                            new HashSet<>(Collections.singletonList(
                                    P2PDataStorage.get32ByteHash(fromPeer.getProtectedStoragePayload()))));

            AtomicBoolean outPNPTruncated = new AtomicBoolean(false);
            AtomicBoolean outPSETruncated = new AtomicBoolean(false);
            Capabilities peerCapabilities = new Capabilities();
            GetDataResponse getDataResponse = this.testState.mockedStorage.buildGetDataResponse(
                    getDataRequest, 1, outPNPTruncated, outPSETruncated, peerCapabilities);

            assertFalse(outPNPTruncated.get());
            assertFalse(outPSETruncated.get());
            assertEquals(1, getDataResponse.getRequestNonce());
            assertEquals(getDataRequest instanceof GetUpdatedDataRequest, getDataResponse.isGetUpdatedDataResponse());
            assertEquals(getDataResponse.getSupportedCapabilities(), Capabilities.app);
            assertTrue(getDataResponse.getPersistableNetworkPayloadSet().isEmpty());
            assertTrue(getDataResponse.getDataSet().isEmpty());
        }

        // TESTCASE: Given a GetDataRequest w/ known PSE, nothing is sent back
        @Test
        public void buildGetDataResponse_knownPSEDoNothing() throws NoSuchAlgorithmException {
            ProtectedStorageEntry fromPeerAndLocal = getProtectedStorageEntryForAdd();

            GetDataRequest getDataRequest =
                    this.buildGetDataRequest(1,
                            new HashSet<>(Collections.singletonList(
                                    P2PDataStorage.get32ByteHash(fromPeerAndLocal.getProtectedStoragePayload()))));

            this.testState.mockedStorage.addProtectedStorageEntry(
                    fromPeerAndLocal, this.localNodeAddress, null);

            AtomicBoolean outPNPTruncated = new AtomicBoolean(false);
            AtomicBoolean outPSETruncated = new AtomicBoolean(false);
            Capabilities peerCapabilities = new Capabilities();
            GetDataResponse getDataResponse = this.testState.mockedStorage.buildGetDataResponse(
                    getDataRequest, 1, outPNPTruncated, outPSETruncated, peerCapabilities);

            assertFalse(outPNPTruncated.get());
            assertFalse(outPSETruncated.get());
            assertEquals(1, getDataResponse.getRequestNonce());
            assertEquals(getDataRequest instanceof GetUpdatedDataRequest, getDataResponse.isGetUpdatedDataResponse());
            assertEquals(getDataResponse.getSupportedCapabilities(), Capabilities.app);
            assertTrue(getDataResponse.getPersistableNetworkPayloadSet().isEmpty());
            assertTrue(getDataResponse.getDataSet().isEmpty());
        }

        // TESTCASE: Given a GetDataRequest w/o known PSE, send it back
        //  @Test
        public void buildGetDataResponse_unknownPSESendBack() throws NoSuchAlgorithmException {
            ProtectedStorageEntry onlyLocal = getProtectedStorageEntryForAdd();

            GetDataRequest getDataRequest = this.buildGetDataRequest(1, new HashSet<>());

            this.testState.mockedStorage.addProtectedStorageEntry(
                    onlyLocal, this.localNodeAddress, null);

            AtomicBoolean outPNPTruncated = new AtomicBoolean(false);
            AtomicBoolean outPSETruncated = new AtomicBoolean(false);
            Capabilities peerCapabilities = new Capabilities();
            GetDataResponse getDataResponse = this.testState.mockedStorage.buildGetDataResponse(
                    getDataRequest, 1, outPNPTruncated, outPSETruncated, peerCapabilities);

            assertFalse(outPNPTruncated.get());
            assertFalse(outPSETruncated.get());
            assertEquals(1, getDataResponse.getRequestNonce());
            assertEquals(getDataRequest instanceof GetUpdatedDataRequest, getDataResponse.isGetUpdatedDataResponse());
            assertEquals(getDataResponse.getSupportedCapabilities(), Capabilities.app);
            assertTrue(getDataResponse.getPersistableNetworkPayloadSet().isEmpty());
            assertTrue(getDataResponse.getDataSet().contains(onlyLocal));
        }

        // TESTCASE: Given a GetDataRequest w/o known PNP, don't send more than truncation limit
        // @Test
        public void buildGetDataResponse_unknownPSESendBackTruncation() throws NoSuchAlgorithmException {
            ProtectedStorageEntry onlyLocal1 = getProtectedStorageEntryForAdd();
            ProtectedStorageEntry onlyLocal2 = getProtectedStorageEntryForAdd();

            GetDataRequest getDataRequest = this.buildGetDataRequest(1, new HashSet<>());

            this.testState.mockedStorage.addProtectedStorageEntry(
                    onlyLocal1, this.localNodeAddress, null);
            this.testState.mockedStorage.addProtectedStorageEntry(
                    onlyLocal2, this.localNodeAddress, null);

            AtomicBoolean outPNPTruncated = new AtomicBoolean(false);
            AtomicBoolean outPSETruncated = new AtomicBoolean(false);
            Capabilities peerCapabilities = new Capabilities();
            GetDataResponse getDataResponse = this.testState.mockedStorage.buildGetDataResponse(
                    getDataRequest, 1, outPNPTruncated, outPSETruncated, peerCapabilities);

            assertFalse(outPNPTruncated.get());
            assertTrue(outPSETruncated.get());
            assertEquals(1, getDataResponse.getRequestNonce());
            assertEquals(getDataRequest instanceof GetUpdatedDataRequest, getDataResponse.isGetUpdatedDataResponse());
            assertEquals(getDataResponse.getSupportedCapabilities(), Capabilities.app);
            assertTrue(getDataResponse.getPersistableNetworkPayloadSet().isEmpty());
            assertEquals(1, getDataResponse.getDataSet().size());
            assertTrue(
                    getDataResponse.getDataSet().contains(onlyLocal1)
                            || getDataResponse.getDataSet().contains(onlyLocal2));
        }

        // TESTCASE: Given a GetDataRequest w/o known PNP, but missing required capabilities, nothing is sent back
        @Test
        public void buildGetDataResponse_unknownPSECapabilitiesMismatchDontSendBack() throws NoSuchAlgorithmException {
            ProtectedStorageEntry onlyLocal =
                    getProtectedStorageEntryForAdd(new Capabilities(Collections.singletonList(Capability.MEDIATION)));

            this.testState.mockedStorage.addProtectedStorageEntry(
                    onlyLocal, this.localNodeAddress, null);

            GetDataRequest getDataRequest = this.buildGetDataRequest(1, new HashSet<>());

            AtomicBoolean outPNPTruncated = new AtomicBoolean(false);
            AtomicBoolean outPSETruncated = new AtomicBoolean(false);
            Capabilities peerCapabilities = new Capabilities();
            GetDataResponse getDataResponse = this.testState.mockedStorage.buildGetDataResponse(
                    getDataRequest, 2, outPNPTruncated, outPSETruncated, peerCapabilities);

            assertFalse(outPNPTruncated.get());
            assertFalse(outPSETruncated.get());
            assertEquals(1, getDataResponse.getRequestNonce());
            assertEquals(getDataRequest instanceof GetUpdatedDataRequest, getDataResponse.isGetUpdatedDataResponse());
            assertEquals(getDataResponse.getSupportedCapabilities(), Capabilities.app);
            assertTrue(getDataResponse.getPersistableNetworkPayloadSet().isEmpty());
            assertTrue(getDataResponse.getDataSet().isEmpty());
        }

        // TESTCASE: Given a GetDataRequest w/o known PNP that requires capabilities (and they match) send it back
        // @Test
        public void buildGetDataResponse_unknownPSECapabilitiesMatch() throws NoSuchAlgorithmException {
            ProtectedStorageEntry onlyLocal =
                    getProtectedStorageEntryForAdd(new Capabilities(Collections.singletonList(Capability.MEDIATION)));

            this.testState.mockedStorage.addProtectedStorageEntry(
                    onlyLocal, this.localNodeAddress, null);

            GetDataRequest getDataRequest =
                    this.buildGetDataRequest(1, new HashSet<>());

            AtomicBoolean outPNPTruncated = new AtomicBoolean(false);
            AtomicBoolean outPSETruncated = new AtomicBoolean(false);
            Capabilities peerCapabilities = new Capabilities(Collections.singletonList(Capability.MEDIATION));
            GetDataResponse getDataResponse = this.testState.mockedStorage.buildGetDataResponse(
                    getDataRequest, 2, outPNPTruncated, outPSETruncated, peerCapabilities);

            assertFalse(outPNPTruncated.get());
            assertFalse(outPSETruncated.get());
            assertEquals(1, getDataResponse.getRequestNonce());
            assertEquals(getDataRequest instanceof GetUpdatedDataRequest, getDataResponse.isGetUpdatedDataResponse());
            assertEquals(getDataResponse.getSupportedCapabilities(), Capabilities.app);
            assertTrue(getDataResponse.getPersistableNetworkPayloadSet().isEmpty());
            assertTrue(getDataResponse.getDataSet().contains(onlyLocal));
        }
    }

    public static class P2PDataStorageBuildGetDataResponseTestPreliminary extends P2PDataStorageBuildGetDataResponseTestBase {

        @Override
        GetDataRequest buildGetDataRequest(int nonce, Set<byte[]> knownKeys) {
            return new PreliminaryGetDataRequest(nonce, knownKeys);
        }
    }

    public static class P2PDataStorageBuildGetDataResponseTestUpdated extends P2PDataStorageBuildGetDataResponseTestBase {

        @Override
        GetDataRequest buildGetDataRequest(int nonce, Set<byte[]> knownKeys) {
            return new GetUpdatedDataRequest(new NodeAddress("peer", 10), nonce, knownKeys);
        }
    }
}
