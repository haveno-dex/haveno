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

package haveno.core.alert;

import com.google.common.util.concurrent.SettableFuture;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.Sig;
import haveno.core.user.User;
import haveno.network.p2p.DecryptedDirectMessageListener;
import haveno.network.p2p.DecryptedMessageWithPubKey;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.mailbox.MailboxMessageService;
import haveno.network.p2p.network.Connection;
import haveno.network.p2p.network.NetworkNode;
import haveno.network.p2p.peers.keepalive.messages.Ping;
import haveno.network.p2p.peers.keepalive.messages.Pong;
import haveno.network.p2p.storage.HashMapChangedListener;
import haveno.network.p2p.storage.payload.ProtectedStorageEntry;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Utils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PrivateNotificationManagerTest {
    private final NetworkNode networkNode = mock(NetworkNode.class);
    private final PrivateNotificationManager manager = new PrivateNotificationManager(mock(P2PService.class), networkNode,
            mock(MailboxMessageService.class), mock(KeyRing.class), true, false);
    private final NodeAddress address = new NodeAddress("peer.onion:9999");
    private final SettableFuture<Connection> sendFuture = SettableFuture.create();
    private final AtomicReference<String> result = new AtomicReference<>();

    @Test
    public void alertOrderingUsesSignedDateAndMalformedSignaturesAreIgnored() {
        P2PService p2p = mock(P2PService.class);
        ECKey key = new ECKey();
        AlertManager alerts = new AlertManager(p2p, null, mock(User.class), false, false) {
            @Override
            protected List<String> getPubKeyList() {
                return List.of(Utils.HEX.encode(key.getPubKey()));
            }
        };
        ArgumentCaptor<HashMapChangedListener> listener = ArgumentCaptor.forClass(HashMapChangedListener.class);
        verify(p2p).addHashSetChangedListener(listener.capture());
        Alert current = signedAlert(key, "current", 2000);
        listener.getValue().onAdded(List.of(alertEntry(current, 2000)));
        Alert old = signedAlert(key, "old", 1000);
        listener.getValue().onAdded(List.of(alertEntry(old, 9000)));
        assertEquals(current, alerts.alertMessageProperty().get());

        Alert malformed = signedAlert(key, "malformed", 3000);
        byte[] signature = new byte[65];
        signature[0] = 31;
        malformed.setMetadataSignature(Base64.getEncoder().encodeToString(signature));
        Alert newest = signedAlert(key, "newest", 4000);
        listener.getValue().onAdded(List.of(alertEntry(malformed, 9000), alertEntry(newest, 1)));
        assertEquals(newest, alerts.alertMessageProperty().get());
        listener.getValue().onRemoved(List.of(alertEntry(newest, 1)));
        assertEquals(current, alerts.alertMessageProperty().get());

        Alert tampered = signedAlert(key, "tampered", 1000);
        tampered.setCreationTimeStamp(10000);
        listener.getValue().onAdded(List.of(alertEntry(tampered, 10000)));
        assertEquals(current, alerts.alertMessageProperty().get());
        Alert legacy = signedAlert(key, "legacy", 0);
        listener.getValue().onAdded(List.of(alertEntry(legacy, 20000)));
        assertEquals(current, alerts.alertMessageProperty().get());
    }

    @Test
    public void malformedPrivateNotificationSignaturesAreIgnored() {
        P2PService p2p = mock(P2PService.class);
        ECKey key = new ECKey();
        PrivateNotificationManager notifications = new PrivateNotificationManager(p2p, networkNode,
                mock(MailboxMessageService.class), mock(KeyRing.class), false, false) {
            @Override
            protected List<String> getPubKeyList() {
                return List.of(Utils.HEX.encode(key.getPubKey()));
            }
        };
        ArgumentCaptor<DecryptedDirectMessageListener> listener = ArgumentCaptor.forClass(DecryptedDirectMessageListener.class);
        verify(p2p).addDecryptedDirectMessageListener(listener.capture());
        byte[] signature = new byte[65];
        signature[0] = 31;
        System.arraycopy(Utils.bigIntegerToBytes(ECKey.CURVE.getN(), 32), 0, signature, 1, 32);
        signature[64] = 1;
        PrivateNotificationPayload malformed = new PrivateNotificationPayload("malformed");
        malformed.setSigAndPubKey(Base64.getEncoder().encodeToString(signature), Sig.generateKeyPair().getPublic());
        listener.getValue().onDirectMessage(new DecryptedMessageWithPubKey(new PrivateNotificationMessage(malformed, address, "1"),
                Sig.generateKeyPair().getPublic()), address);
        assertNull(notifications.privateNotificationProperty().get());
        PrivateNotificationPayload valid = new PrivateNotificationPayload("valid");
        valid.setSigAndPubKey(key.signMessage(Utils.HEX.encode("valid".getBytes(StandardCharsets.UTF_8))), Sig.generateKeyPair().getPublic());
        listener.getValue().onDirectMessage(new DecryptedMessageWithPubKey(new PrivateNotificationMessage(valid, address, "2"),
                Sig.generateKeyPair().getPublic()), address);
        assertEquals(valid, notifications.privateNotificationProperty().get());
    }

    private Alert signedAlert(ECKey key, String message, long date) {
        Alert alert = new Alert(message, false, false, "1.0.0");
        alert.setSigAndPubKey("legacy-signature", Sig.generateKeyPair().getPublic());
        if (date != 0) alert.setCreationTimeStamp(date);
        alert.setMetadataSignature(key.signMessage(alert.getMetadataSignaturePayload()));
        return Alert.fromProto(alert.toProtoMessage().getAlert());
    }

    private ProtectedStorageEntry alertEntry(Alert alert, long date) {
        ProtectedStorageEntry entry = mock(ProtectedStorageEntry.class);
        when(entry.getProtectedStoragePayload()).thenReturn(alert);
        when(entry.getCreationTimeStamp()).thenReturn(date);
        return entry;
    }

    @Test
    public void testPingListensBeforeSendingAndMatchesNonce() {
        Connection connection = mock(Connection.class);
        when(connection.getPeersNodeAddressOptional()).thenReturn(Optional.of(address));
        when(networkNode.sendMessage(eq(address), any(Ping.class))).thenReturn(sendFuture);

        manager.sendPing(address, result::set);
        ArgumentCaptor<Ping> ping = ArgumentCaptor.forClass(Ping.class);
        InOrder inOrder = inOrder(networkNode);
        inOrder.verify(networkNode).addMessageListener(manager);
        inOrder.verify(networkNode).sendMessage(eq(address), ping.capture());

        manager.onMessage(new Pong(ping.getValue().getNonce() + 1), connection);
        assertNull(result.get());
        Connection otherConnection = mock(Connection.class);
        when(otherConnection.getPeersNodeAddressOptional()).thenReturn(Optional.of(new NodeAddress("other.onion:9999")));
        manager.onMessage(new Pong(ping.getValue().getNonce()), otherConnection);
        assertNull(result.get());
        when(otherConnection.getPeersNodeAddressOptional()).thenReturn(Optional.empty());
        manager.onMessage(new Pong(ping.getValue().getNonce()), otherConnection);
        assertNull(result.get());
        verify(networkNode, never()).removeMessageListener(manager);

        manager.onMessage(new Pong(ping.getValue().getNonce()), connection);
        assertEquals("SUCCESS", result.get());
        verify(networkNode).removeMessageListener(manager);

        sendFuture.set(connection);
        verify(networkNode).addMessageListener(manager);
    }

    @Test
    public void testPingFailureStopsListening() {
        when(networkNode.sendMessage(eq(address), any(Ping.class))).thenReturn(sendFuture);

        manager.sendPing(address, result::set);
        sendFuture.setException(new RuntimeException("offline"));
        assertTrue(result.get().contains("failed"));
        verify(networkNode).removeMessageListener(manager);
    }

    @Test
    public void testPingRejectedSendStopsListening() {
        when(networkNode.sendMessage(eq(address), any(Ping.class))).thenThrow(new RejectedExecutionException("saturated"));

        assertThrows(RejectedExecutionException.class, () -> manager.sendPing(address, result::set));
        verify(networkNode).removeMessageListener(manager);
    }

    @Test
    public void testOlderPingFailureDoesNotStopListeningForNewerPing() {
        SettableFuture<Connection> newerSendFuture = SettableFuture.create();
        when(networkNode.sendMessage(eq(address), any(Ping.class))).thenReturn(sendFuture);
        NodeAddress newerAddress = new NodeAddress("newer.onion:9999");
        when(networkNode.sendMessage(eq(newerAddress), any(Ping.class))).thenReturn(newerSendFuture);
        AtomicReference<String> newerResult = new AtomicReference<>();

        manager.sendPing(address, result::set);
        manager.sendPing(newerAddress, newerResult::set);
        sendFuture.setException(new RuntimeException("offline"));
        assertTrue(result.get().contains("failed"));
        assertNull(newerResult.get());
        verify(networkNode, never()).removeMessageListener(manager);

        ArgumentCaptor<Ping> ping = ArgumentCaptor.forClass(Ping.class);
        verify(networkNode).sendMessage(eq(newerAddress), ping.capture());
        Connection connection = mock(Connection.class);
        when(connection.getPeersNodeAddressOptional()).thenReturn(Optional.of(newerAddress));
        manager.onMessage(new Pong(ping.getValue().getNonce()), connection);
        assertEquals("SUCCESS", newerResult.get());
        verify(networkNode).removeMessageListener(manager);
    }
}
