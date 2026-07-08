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

package haveno.network.p2p.storage.persistence;

import haveno.network.p2p.storage.P2PDataStorage;
import haveno.network.p2p.storage.mocks.HistoricalDataStoreServiceFake;
import haveno.network.p2p.storage.mocks.PersistableNetworkPayloadStub;
import haveno.network.p2p.storage.payload.PersistableNetworkPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HistoricalDataStoreServiceTest {

    // TESTCASE: putIfAbsent returns the existing payload so callers can detect duplicates
    @Test
    public void putIfAbsentReturnsExistingPayload() {
        HistoricalDataStoreServiceFake service = new HistoricalDataStoreServiceFake();
        PersistableNetworkPayload payload = new PersistableNetworkPayloadStub(new byte[]{1});
        P2PDataStorage.ByteArray hash = new P2PDataStorage.ByteArray(payload.getHash());

        assertNull(service.putIfAbsent(hash, payload));
        assertSame(payload, service.putIfAbsent(hash, new PersistableNetworkPayloadStub(new byte[]{1})));
    }

    // TESTCASE: AppendOnlyDataStoreService.put reports true only for newly added payloads, so re-delivered
    // payloads from redundant get-data responses do not signal listeners or count as sync progress
    @Test
    public void appendOnlyPutReportsAlreadyPresentPayloads() {
        AppendOnlyDataStoreService appendOnlyDataStoreService = new AppendOnlyDataStoreService();
        appendOnlyDataStoreService.addService(new HistoricalDataStoreServiceFake());
        PersistableNetworkPayload payload = new PersistableNetworkPayloadStub(new byte[]{1});
        P2PDataStorage.ByteArray hash = new P2PDataStorage.ByteArray(payload.getHash());

        assertTrue(appendOnlyDataStoreService.put(hash, payload));
        assertFalse(appendOnlyDataStoreService.put(hash, payload));
    }
}
