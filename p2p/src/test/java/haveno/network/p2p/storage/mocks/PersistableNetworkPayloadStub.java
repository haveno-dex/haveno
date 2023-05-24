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

package haveno.network.p2p.storage.mocks;

import haveno.network.p2p.storage.payload.PersistableNetworkPayload;

import static org.mockito.Mockito.mock;

/**
 * Stub implementation of a PersistableNetworkPayload that can be used in tests
 * to provide canned answers to calls. Useful if the tests don't care about the implementation
 *  * details of the PersistableNetworkPayload.
 *
 * @see <a href="https://martinfowler.com/articles/mocksArentStubs.html#TheDifferenceBetweenMocksAndStubs">Reference</a>
 */
public class PersistableNetworkPayloadStub implements PersistableNetworkPayload {
    private final boolean hashSizeValid;
    private final byte[] hash;
    private final protobuf.PersistableNetworkPayload mockPayload;

    public PersistableNetworkPayloadStub(boolean hashSizeValid) {
        this(hashSizeValid, new byte[]{1});
    }

    public PersistableNetworkPayloadStub(byte[] hash) {
        this(true, hash);
    }

    private PersistableNetworkPayloadStub(boolean hashSizeValid, byte[] hash) {
        this.hashSizeValid = hashSizeValid;
        this.hash = hash;
        mockPayload = mock(protobuf.PersistableNetworkPayload.class);
    }

    @Override
    public protobuf.PersistableNetworkPayload toProtoMessage() {
        return mockPayload;
    }

    @Override
    public byte[] getHash() {
        return hash;
    }

    @Override
    public boolean verifyHashSize() {
        return this.hashSizeValid;
    }
}
