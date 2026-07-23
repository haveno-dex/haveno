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

package haveno.network.p2p.storage.payload;

import haveno.common.crypto.Sig;
import haveno.network.p2p.storage.mocks.ProtectedStoragePayloadStub;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

public class ProtectedStorageEntryValidityTest {

    private static KeyPair keyPair() {
        return Sig.generateKeyPair();
    }

    // A sequence number that can no longer be superseded must be rejected, so an entry cannot permanently
    // lock its payload out of future updates via integer overflow.
    @Test
    public void rejectsOverflowingSequenceNumber() {
        KeyPair owner = keyPair();
        ProtectedStoragePayload payload = new ProtectedStoragePayloadStub(owner.getPublic());

        assertFalse(new ProtectedStorageEntry(payload, owner.getPublic(), Integer.MAX_VALUE, new byte[]{1}, Clock.systemDefaultZone())
                .isValidForAddOperation(), "seqNr == Integer.MAX_VALUE must be rejected");
        assertFalse(new ProtectedStorageEntry(payload, owner.getPublic(), -1, new byte[]{1}, Clock.systemDefaultZone())
                .isValidForAddOperation(), "negative seqNr must be rejected");
    }

    // A MailboxStoragePayload smuggled into a plain ProtectedStorageEntry must not be removable, otherwise a
    // captured mailbox add could be replayed as a plain remove to suppress a victim's message.
    @Test
    public void rejectsMailboxPayloadInPlainEntry() {
        KeyPair owner = keyPair();
        MailboxStoragePayload mailboxPayload = mock(MailboxStoragePayload.class);

        ProtectedStorageEntry plainEntry = new ProtectedStorageEntry(
                mailboxPayload, owner.getPublic(), 1, new byte[]{1}, Clock.systemDefaultZone());

        assertFalse(plainEntry.isValidForRemoveOperation(), "mailbox payload in a plain entry must not be removable");
    }
}
