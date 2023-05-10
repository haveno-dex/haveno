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

import haveno.common.app.Version;
import haveno.common.crypto.CryptoException;
import haveno.common.crypto.Sig;
import haveno.network.p2p.PrefixedSealedAndSignedMessage;
import haveno.network.p2p.TestUtils;
import haveno.network.p2p.storage.P2PDataStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ProtectedMailboxStorageEntryTest {

    private static MailboxStoragePayload buildMailboxStoragePayload(PublicKey payloadSenderPubKeyForAddOperation,
                                                                    PublicKey payloadOwnerPubKey) {

        // Mock out the PrefixedSealedAndSignedMessage with a version that just serializes to the DEFAULT_INSTANCE
        // in protobuf. This object is never validated in the test, but needs to be hashed as part of the testing path.
        PrefixedSealedAndSignedMessage prefixedSealedAndSignedMessageMock = mock(PrefixedSealedAndSignedMessage.class);
        protobuf.NetworkEnvelope networkEnvelopeMock = mock(protobuf.NetworkEnvelope.class);
        when(networkEnvelopeMock.getPrefixedSealedAndSignedMessage()).thenReturn(
                protobuf.PrefixedSealedAndSignedMessage.getDefaultInstance());
        when(prefixedSealedAndSignedMessageMock.toProtoNetworkEnvelope()).thenReturn(networkEnvelopeMock);

        return new MailboxStoragePayload(
                prefixedSealedAndSignedMessageMock,
                payloadSenderPubKeyForAddOperation,
                payloadOwnerPubKey,
                MailboxStoragePayload.TTL);
    }

    private static ProtectedMailboxStorageEntry buildProtectedMailboxStorageEntry(
            MailboxStoragePayload mailboxStoragePayload, KeyPair ownerKey, PublicKey receiverKey, int sequenceNumber) throws CryptoException {
        byte[] hashOfDataAndSeqNr = P2PDataStorage.get32ByteHash(new P2PDataStorage.DataAndSeqNrPair(mailboxStoragePayload, sequenceNumber));
        byte[] signature = Sig.sign(ownerKey.getPrivate(), hashOfDataAndSeqNr);

        return new ProtectedMailboxStorageEntry(mailboxStoragePayload, ownerKey.getPublic(), sequenceNumber, signature, receiverKey, Clock.systemDefaultZone());
    }

    @BeforeEach
    public void SetUp() {
        // Deep in the bowels of protobuf we grab the messageID from the version module. This is required to hash the
        // full MailboxStoragePayload so make sure it is initialized.
        Version.setBaseCryptoNetworkId(1);
    }

    // TESTCASE: validForAddOperation() should return true if the Entry owner and sender key specified in payload match
    @Test
    public void isValidForAddOperation() throws NoSuchAlgorithmException, CryptoException {
        KeyPair senderKeys = TestUtils.generateKeyPair();
        KeyPair receiverKeys = TestUtils.generateKeyPair();

        MailboxStoragePayload mailboxStoragePayload = buildMailboxStoragePayload(senderKeys.getPublic(), receiverKeys.getPublic());
        ProtectedStorageEntry protectedStorageEntry = buildProtectedMailboxStorageEntry(mailboxStoragePayload, senderKeys, receiverKeys.getPublic(), 1);

        assertTrue(protectedStorageEntry.isValidForAddOperation());
    }

    // TESTCASE: validForAddOperation() should return false if the Entry owner and sender key specified in payload don't match
    @Test
    public void isValidForAddOperation_EntryOwnerPayloadReceiverMismatch() throws NoSuchAlgorithmException, CryptoException {
        KeyPair senderKeys = TestUtils.generateKeyPair();
        KeyPair receiverKeys = TestUtils.generateKeyPair();

        MailboxStoragePayload mailboxStoragePayload = buildMailboxStoragePayload(senderKeys.getPublic(), receiverKeys.getPublic());
        ProtectedStorageEntry protectedStorageEntry = buildProtectedMailboxStorageEntry(mailboxStoragePayload, receiverKeys, receiverKeys.getPublic(), 1);

        assertFalse(protectedStorageEntry.isValidForAddOperation());
    }

    // TESTCASE: validForAddOperation() should fail if Entry.receiversPubKey and Payload.ownerPubKey don't match
    @Test
    public void isValidForAddOperation_EntryReceiverPayloadReceiverMismatch() throws NoSuchAlgorithmException, CryptoException {
        KeyPair senderKeys = TestUtils.generateKeyPair();
        KeyPair receiverKeys = TestUtils.generateKeyPair();

        MailboxStoragePayload mailboxStoragePayload = buildMailboxStoragePayload(senderKeys.getPublic(), receiverKeys.getPublic());
        ProtectedStorageEntry protectedStorageEntry = buildProtectedMailboxStorageEntry(mailboxStoragePayload, senderKeys, senderKeys.getPublic(), 1);

        assertFalse(protectedStorageEntry.isValidForAddOperation());
    }

    // TESTCASE: validForAddOperation() should fail if the signature isn't valid
    @Test
    public void isValidForAddOperation_BadSignature() throws NoSuchAlgorithmException {
        KeyPair senderKeys = TestUtils.generateKeyPair();
        KeyPair receiverKeys = TestUtils.generateKeyPair();

        MailboxStoragePayload mailboxStoragePayload = buildMailboxStoragePayload(senderKeys.getPublic(), receiverKeys.getPublic());
        ProtectedStorageEntry protectedStorageEntry = new ProtectedMailboxStorageEntry(
                mailboxStoragePayload, senderKeys.getPublic(), 1, new byte[] { 0 }, receiverKeys.getPublic(), Clock.systemDefaultZone());

        assertFalse(protectedStorageEntry.isValidForAddOperation());
    }

    // TESTCASE: validForRemoveOperation() should return true if the Entry owner and payload owner match
    @Test
    public void validForRemove() throws NoSuchAlgorithmException, CryptoException {
        KeyPair senderKeys = TestUtils.generateKeyPair();
        KeyPair receiverKeys = TestUtils.generateKeyPair();

        MailboxStoragePayload mailboxStoragePayload = buildMailboxStoragePayload(senderKeys.getPublic(), receiverKeys.getPublic());
        ProtectedStorageEntry protectedStorageEntry = buildProtectedMailboxStorageEntry(mailboxStoragePayload, receiverKeys, receiverKeys.getPublic(), 1);

        assertTrue(protectedStorageEntry.isValidForRemoveOperation());
    }

    // TESTCASE: validForRemoveOperation() should return false if the Entry owner and payload owner don't match
    @Test
    public void validForRemoveEntryOwnerPayloadOwnerMismatch() throws NoSuchAlgorithmException, CryptoException {
        KeyPair senderKeys = TestUtils.generateKeyPair();
        KeyPair receiverKeys = TestUtils.generateKeyPair();

        MailboxStoragePayload mailboxStoragePayload = buildMailboxStoragePayload(senderKeys.getPublic(), receiverKeys.getPublic());
        ProtectedStorageEntry protectedStorageEntry = buildProtectedMailboxStorageEntry(mailboxStoragePayload, senderKeys, receiverKeys.getPublic(), 1);

        assertFalse(protectedStorageEntry.isValidForRemoveOperation());
    }

    // TESTCASE: isValidForRemoveOperation() should fail if the signature is bad
    @Test
    public void isValidForRemoveOperation_BadSignature() throws NoSuchAlgorithmException {
        KeyPair senderKeys = TestUtils.generateKeyPair();
        KeyPair receiverKeys = TestUtils.generateKeyPair();

        MailboxStoragePayload mailboxStoragePayload = buildMailboxStoragePayload(senderKeys.getPublic(), receiverKeys.getPublic());
        ProtectedStorageEntry protectedStorageEntry =
                new ProtectedMailboxStorageEntry(mailboxStoragePayload, receiverKeys.getPublic(),
                            1, new byte[] { 0 }, receiverKeys.getPublic(), Clock.systemDefaultZone());

        assertFalse(protectedStorageEntry.isValidForRemoveOperation());
    }

    // TESTCASE: isValidForRemoveOperation() should fail if the receiversPubKey does not match the Entry owner
    @Test
    public void isValidForRemoveOperation_ReceiversPubKeyMismatch() throws NoSuchAlgorithmException, CryptoException {
        KeyPair senderKeys = TestUtils.generateKeyPair();
        KeyPair receiverKeys = TestUtils.generateKeyPair();

        MailboxStoragePayload mailboxStoragePayload = buildMailboxStoragePayload(senderKeys.getPublic(), receiverKeys.getPublic());
        ProtectedStorageEntry protectedStorageEntry = buildProtectedMailboxStorageEntry(mailboxStoragePayload, receiverKeys, senderKeys.getPublic(), 1);

        assertFalse(protectedStorageEntry.isValidForRemoveOperation());
    }

    // TESTCASE: isMetadataEquals() should succeed if the sequence number changes
    @Test
    public void isMetadataEquals() throws NoSuchAlgorithmException, CryptoException {
        KeyPair senderKeys = TestUtils.generateKeyPair();
        KeyPair receiverKeys = TestUtils.generateKeyPair();

        MailboxStoragePayload mailboxStoragePayload = buildMailboxStoragePayload(senderKeys.getPublic(), receiverKeys.getPublic());
        ProtectedStorageEntry seqNrOne = buildProtectedMailboxStorageEntry(mailboxStoragePayload, senderKeys, receiverKeys.getPublic(), 1);

        ProtectedStorageEntry seqNrTwo = buildProtectedMailboxStorageEntry(mailboxStoragePayload, senderKeys, receiverKeys.getPublic(), 2);

        assertTrue(seqNrOne.matchesRelevantPubKey(seqNrTwo));
    }

    // TESTCASE: isMetadataEquals() should fail if the receiversPubKey changes
    @Test
    public void isMetadataEquals_receiverPubKeyChanged() throws NoSuchAlgorithmException, CryptoException {
        KeyPair senderKeys = TestUtils.generateKeyPair();
        KeyPair receiverKeys = TestUtils.generateKeyPair();

        MailboxStoragePayload mailboxStoragePayload = buildMailboxStoragePayload(senderKeys.getPublic(), receiverKeys.getPublic());
        ProtectedStorageEntry seqNrOne = buildProtectedMailboxStorageEntry(mailboxStoragePayload, senderKeys, receiverKeys.getPublic(), 1);

        ProtectedStorageEntry seqNrTwo = buildProtectedMailboxStorageEntry(mailboxStoragePayload, senderKeys, senderKeys.getPublic(), 1);

        assertFalse(seqNrOne.matchesRelevantPubKey(seqNrTwo));
    }
}
