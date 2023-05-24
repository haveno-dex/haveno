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
import haveno.network.p2p.storage.mocks.PersistableExpirableProtectedStoragePayloadStub;
import haveno.network.p2p.storage.mocks.PersistableNetworkPayloadStub;
import haveno.network.p2p.storage.mocks.ProtectedStoragePayloadStub;
import haveno.network.p2p.storage.payload.PersistableNetworkPayload;
import haveno.network.p2p.storage.payload.ProtectedStorageEntry;
import haveno.network.p2p.storage.payload.ProtectedStoragePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static haveno.network.p2p.storage.TestState.MAX_SEQUENCE_NUMBER_MAP_SIZE_BEFORE_PURGE;
import static haveno.network.p2p.storage.TestState.SavedTestState;
import static haveno.network.p2p.storage.TestState.getTestNodeAddress;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the P2PDataStore behavior that expires old Entries periodically.
 */
public class P2PDataStorageRemoveExpiredTest {
    private TestState testState;

    @BeforeEach
    public void setUp() {
        testState = new TestState();

        // Deep in the bowels of protobuf we grab the messageID from the version module. This is required to hash the
        // full MailboxStoragePayload so make sure it is initialized.
        Version.setBaseCryptoNetworkId(1);
    }

    // TESTCASE: Correctly skips entries that are not expirable
    @Test
    public void removeExpiredEntries_SkipsNonExpirableEntries() throws NoSuchAlgorithmException, CryptoException {
        KeyPair ownerKeys = TestUtils.generateKeyPair();
        ProtectedStoragePayload protectedStoragePayload = new ProtectedStoragePayloadStub(ownerKeys.getPublic());
        ProtectedStorageEntry protectedStorageEntry = testState.mockedStorage.getProtectedStorageEntry(protectedStoragePayload, ownerKeys);
        assertTrue(testState.mockedStorage.addProtectedStorageEntry(protectedStorageEntry, getTestNodeAddress(), null));

        SavedTestState beforeState = testState.saveTestState(protectedStorageEntry);
        testState.mockedStorage.removeExpiredEntries();

        testState.verifyProtectedStorageRemove(beforeState, protectedStorageEntry, false, false, false, false);
    }

    // TESTCASE: Correctly skips all PersistableNetworkPayloads since they are not expirable
    @Test
    public void removeExpiredEntries_skipsPersistableNetworkPayload() {
        PersistableNetworkPayload persistableNetworkPayload = new PersistableNetworkPayloadStub(true);

        assertTrue(testState.mockedStorage.addPersistableNetworkPayload(persistableNetworkPayload, getTestNodeAddress(), false));

        testState.mockedStorage.removeExpiredEntries();

        assertTrue(testState.mockedStorage.appendOnlyDataStoreService.getMap(persistableNetworkPayload).containsKey(new P2PDataStorage.ByteArray(persistableNetworkPayload.getHash())));
    }

    // TESTCASE: Correctly skips non-persistable entries that are not expired
    @Test
    public void removeExpiredEntries_SkipNonExpiredExpirableEntries() throws CryptoException, NoSuchAlgorithmException {
        KeyPair ownerKeys = TestUtils.generateKeyPair();
        ProtectedStoragePayload protectedStoragePayload = new ExpirableProtectedStoragePayloadStub(ownerKeys.getPublic());
        ProtectedStorageEntry protectedStorageEntry = testState.mockedStorage.getProtectedStorageEntry(protectedStoragePayload, ownerKeys);
        assertTrue(testState.mockedStorage.addProtectedStorageEntry(protectedStorageEntry, getTestNodeAddress(), null));

        SavedTestState beforeState = testState.saveTestState(protectedStorageEntry);
        testState.mockedStorage.removeExpiredEntries();

        testState.verifyProtectedStorageRemove(beforeState, protectedStorageEntry, false, false, false, false);
    }

    // TESTCASE: Correctly expires non-persistable entries that are expired
    @Test
    public void removeExpiredEntries_ExpiresExpiredExpirableEntries() throws CryptoException, NoSuchAlgorithmException {
        KeyPair ownerKeys = TestUtils.generateKeyPair();
        ProtectedStoragePayload protectedStoragePayload = new ExpirableProtectedStoragePayloadStub(ownerKeys.getPublic(), 0);
        ProtectedStorageEntry protectedStorageEntry = testState.mockedStorage.getProtectedStorageEntry(protectedStoragePayload, ownerKeys);
        assertTrue(testState.mockedStorage.addProtectedStorageEntry(protectedStorageEntry, getTestNodeAddress(), null));

        // Increment the clock by an hour which will cause the Payloads to be outside the TTL range
        testState.incrementClock();

        SavedTestState beforeState = testState.saveTestState(protectedStorageEntry);
        testState.mockedStorage.removeExpiredEntries();

        testState.verifyProtectedStorageRemove(beforeState, protectedStorageEntry, true, true, false, false);
    }

    // TESTCASE: Correctly skips persistable entries that are not expired
    @Test
    public void removeExpiredEntries_SkipNonExpiredPersistableExpirableEntries() throws CryptoException, NoSuchAlgorithmException {
        KeyPair ownerKeys = TestUtils.generateKeyPair();
        ProtectedStoragePayload protectedStoragePayload = new PersistableExpirableProtectedStoragePayloadStub(ownerKeys.getPublic());
        ProtectedStorageEntry protectedStorageEntry = testState.mockedStorage.getProtectedStorageEntry(protectedStoragePayload, ownerKeys);
        assertTrue(testState.mockedStorage.addProtectedStorageEntry(protectedStorageEntry, getTestNodeAddress(), null));

        SavedTestState beforeState = testState.saveTestState(protectedStorageEntry);
        testState.mockedStorage.removeExpiredEntries();

        testState.verifyProtectedStorageRemove(beforeState, protectedStorageEntry, false, false, false, false);
    }

    // TESTCASE: Correctly expires persistable entries that are expired
    @Test
    public void removeExpiredEntries_ExpiresExpiredPersistableExpirableEntries() throws CryptoException, NoSuchAlgorithmException {
        KeyPair ownerKeys = TestUtils.generateKeyPair();
        ProtectedStoragePayload protectedStoragePayload = new PersistableExpirableProtectedStoragePayloadStub(ownerKeys.getPublic(), 0);
        ProtectedStorageEntry protectedStorageEntry = testState.mockedStorage.getProtectedStorageEntry(protectedStoragePayload, ownerKeys);
        assertTrue(testState.mockedStorage.addProtectedStorageEntry(protectedStorageEntry, getTestNodeAddress(), null));

        // Increment the clock by an hour which will cause the Payloads to be outside the TTL range
        testState.incrementClock();

        SavedTestState beforeState = testState.saveTestState(protectedStorageEntry);
        testState.mockedStorage.removeExpiredEntries();

        testState.verifyProtectedStorageRemove(beforeState, protectedStorageEntry, true, true, false, false);
    }

    // TESTCASE: Ensure we try to purge old entries sequence number map when size exceeds the maximum size
    // and that entries less than PURGE_AGE_DAYS remain
    @Test
    public void removeExpiredEntries_PurgeSeqNrMap() throws CryptoException, NoSuchAlgorithmException {
        final int initialClockIncrement = 5;

        ArrayList<ProtectedStorageEntry> expectedRemoves = new ArrayList<>();

        // Add 4 entries to our sequence number map that will be purged
        KeyPair purgedOwnerKeys = TestUtils.generateKeyPair();
        ProtectedStoragePayload purgedProtectedStoragePayload = new PersistableExpirableProtectedStoragePayloadStub(purgedOwnerKeys.getPublic(), 0);
        ProtectedStorageEntry purgedProtectedStorageEntry = testState.mockedStorage.getProtectedStorageEntry(purgedProtectedStoragePayload, purgedOwnerKeys);
        expectedRemoves.add(purgedProtectedStorageEntry);

        assertTrue(testState.mockedStorage.addProtectedStorageEntry(purgedProtectedStorageEntry, getTestNodeAddress(), null));

        for (int i = 0; i < MAX_SEQUENCE_NUMBER_MAP_SIZE_BEFORE_PURGE - 1; ++i) {
            KeyPair ownerKeys = TestUtils.generateKeyPair();
            ProtectedStoragePayload protectedStoragePayload = new PersistableExpirableProtectedStoragePayloadStub(ownerKeys.getPublic(), 0);
            ProtectedStorageEntry tmpEntry = testState.mockedStorage.getProtectedStorageEntry(protectedStoragePayload, ownerKeys);
            expectedRemoves.add(tmpEntry);
            assertTrue(testState.mockedStorage.addProtectedStorageEntry(tmpEntry, getTestNodeAddress(), null));
        }

        // Increment the time by 5 days which is less than the purge requirement. This will allow the map to have
        // some values that will be purged and others that will stay.
        testState.clockFake.increment(TimeUnit.DAYS.toMillis(initialClockIncrement));

        // Add a final entry that will not be purged
        KeyPair keepOwnerKeys = TestUtils.generateKeyPair();
        ProtectedStoragePayload keepProtectedStoragePayload = new PersistableExpirableProtectedStoragePayloadStub(keepOwnerKeys.getPublic(), 0);
        ProtectedStorageEntry keepProtectedStorageEntry = testState.mockedStorage.getProtectedStorageEntry(keepProtectedStoragePayload, keepOwnerKeys);
        expectedRemoves.add(keepProtectedStorageEntry);

        assertTrue(testState.mockedStorage.addProtectedStorageEntry(keepProtectedStorageEntry, getTestNodeAddress(), null));

        // P2PDataStorage::PURGE_AGE_DAYS == 10 days
        // Advance time past it so they will be valid purge targets
        testState.clockFake.increment(TimeUnit.DAYS.toMillis(P2PDataStorage.PURGE_AGE_DAYS + 1 - initialClockIncrement));

        // The first 4 entries (11 days old) should be purged from the SequenceNumberMap
        SavedTestState beforeState = testState.saveTestState(purgedProtectedStorageEntry);
        testState.mockedStorage.removeExpiredEntries();
        testState.verifyProtectedStorageRemove(beforeState, expectedRemoves, true, true, false, false);
    }
}
