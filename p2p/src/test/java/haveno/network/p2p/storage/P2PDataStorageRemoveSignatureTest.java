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
import haveno.network.p2p.TestUtils;
import haveno.network.p2p.storage.mocks.ExpirableProtectedStoragePayloadStub;
import haveno.network.p2p.storage.payload.ProtectedStorageEntry;
import haveno.network.p2p.storage.payload.ProtectedStoragePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class P2PDataStorageRemoveSignatureTest {
    private TestState testState;

    @BeforeEach
    public void setUp() {
        this.testState = new TestState();
        Version.setBaseCryptoNetworkId(1);
    }

    // The storage-entry signature must be bound to its operation, so a captured add/refresh signature cannot
    // be replayed as a remove to force-cancel a maker's live order network-wide.
    @Test
    public void addSignatureIsNotValidForRemove() throws NoSuchAlgorithmException, CryptoException {
        KeyPair ownerKeys = TestUtils.generateKeyPair();
        ProtectedStoragePayload payload = new ExpirableProtectedStoragePayloadStub(ownerKeys.getPublic());

        // an add entry (signed over the plain hash) is valid for add but must NOT be usable as a remove
        ProtectedStorageEntry addEntry = testState.mockedStorage.getProtectedStorageEntry(payload, ownerKeys);
        assertTrue(addEntry.isValidForAddOperation(), "add entry should be valid for add");
        assertFalse(addEntry.isValidForRemoveOperation(), "an add/refresh signature must not verify as a remove");

        // a remove entry (signed over the remove hash) is valid for remove but not usable as an add
        ProtectedStorageEntry removeEntry = testState.mockedStorage.getProtectedStorageEntryForRemove(payload, ownerKeys);
        assertTrue(removeEntry.isValidForRemoveOperation(), "a properly signed remove should be valid for remove");
        assertFalse(removeEntry.isValidForAddOperation(), "a remove signature must not verify as an add");
    }
}
