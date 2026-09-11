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

package haveno.core.xmr.model;

import com.google.protobuf.ByteString;
import haveno.common.crypto.AuthenticatedEncryption;
import haveno.common.crypto.Encryption;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.ScryptUtil;
import haveno.common.persistence.PersistenceManager;
import haveno.core.api.CoreAccountService;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import monero.common.MoneroRpcConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EncryptedConnectionListMigrationTest {
    private final SecretKey master = Encryption.generateSecretKey(256);

    @Test
    void legacyPasswordAndNoPasswordCredentialsMigrateAndRestart() throws Exception {
        for (String password : new String[]{null, "account-password"}) {
            protobuf.EncryptedConnectionList.Builder legacy = protobuf.EncryptedConnectionList.newBuilder()
                    .setSalt(ByteString.copyFrom(new byte[8])).setCurrentConnectionUrl("http://node")
                    .setAutoSwitch(true).setRefreshPeriod(123);
            for (String rpcPassword : new String[]{null, "", "rpc-secret"}) {
                byte[] salt = new byte[16];
                Arrays.fill(salt, (byte) 0x6a);
                byte[] clear;
                if (rpcPassword == null) {
                    clear = Arrays.copyOf(salt, 23);
                } else {
                    byte[] bytes = rpcPassword.getBytes(StandardCharsets.UTF_8);
                    clear = Arrays.copyOf(bytes, bytes.length + 16);
                    System.arraycopy(salt, 0, clear, bytes.length, salt.length);
                    // Legacy writer chooses a salt that is not a prefix of an empty password;
                    // its decoder nonetheless represents empty as null, so test that historical rule.
                }
                if (password != null) {
                    Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
                    cipher.init(Cipher.ENCRYPT_MODE, Encryption.getSecretKeyFromBytes(
                            ScryptUtil.getKeyCrypterScrypt(new byte[8]).deriveKey(password).getKey()));
                    clear = cipher.doFinal(clear);
                }
                legacy.addItems(protobuf.EncryptedConnection.newBuilder().setUrl("http://node-" + (rpcPassword == null ? "null" : rpcPassword.isEmpty() ? "empty" : rpcPassword))
                        .setUsername(rpcPassword == null || rpcPassword.isEmpty() ? "" : "user").setEncryptedPassword(ByteString.copyFrom(clear))
                        .setEncryptionSalt(ByteString.copyFrom(salt)).setPriority(2));
            }
            EncryptedConnectionList list = load(legacy.build(), password);
            protobuf.EncryptedConnectionList migrated = encode(list);
            assertEquals(1, migrated.getEncryptionVersion());
            assertTrue(migrated.getSalt().isEmpty());
            assertEquals(123, migrated.getRefreshPeriod());
            for (protobuf.EncryptedConnection connection : migrated.getItemsList()) {
                assertTrue(AuthenticatedEncryption.isEnvelope(connection.getEncryptedPassword().toByteArray()));
            }
            EncryptedConnectionList restarted = load(migrated, "changed-account-password");
            List<MoneroRpcConnection> connections = restarted.getConnections();
            assertEquals(3, connections.size());
            assertEquals("rpc-secret", connections.stream().filter(c -> c.getUri().endsWith("rpc-secret")).findFirst().get().getPassword());
            assertNull(connections.stream().filter(c -> c.getUri().endsWith("null")).findFirst().get().getPassword());
        }
    }

    @Test
    void newlyAddedCredentialsAreEncryptedEvenWithoutAccountPassword() throws Exception {
        EncryptedConnectionList list = load(protobuf.EncryptedConnectionList.newBuilder().setEncryptionVersion(1).build(), null);
        list.addConnection(new MoneroRpcConnection("http://node", "rpc-user", "rpc-secret"));
        byte[] encrypted = encode(list).getItems(0).getEncryptedPassword().toByteArray();
        assertTrue(AuthenticatedEncryption.isEnvelope(encrypted));
        assertEquals("rpc-secret", load(encode(list), null).getConnections().get(0).getPassword());
    }

    @Test
    void corruptedCurrentCredentialAndUnknownVersionFailClosed() throws Exception {
        EncryptedConnectionList list = load(protobuf.EncryptedConnectionList.newBuilder().setEncryptionVersion(1).build(), null);
        list.addConnection(new MoneroRpcConnection("http://node", "user", "secret"));
        protobuf.EncryptedConnectionList current = encode(list);
        byte[] bytes = current.getItems(0).getEncryptedPassword().toByteArray();
        bytes[bytes.length - 1] ^= 1;
        protobuf.EncryptedConnectionList corrupt = current.toBuilder().setItems(0, current.getItems(0).toBuilder()
                .setEncryptedPassword(ByteString.copyFrom(bytes))).build();
        assertThrows(IllegalArgumentException.class, () -> load(corrupt, null));
        assertThrows(IllegalArgumentException.class, () -> EncryptedConnectionList.fromProto(current.toBuilder().setEncryptionVersion(99).build()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void migrationCompletionWaitsForTheForcedWrite() {
        PersistenceManager<EncryptedConnectionList> persistence = mock(PersistenceManager.class);
        CoreAccountService account = mock(CoreAccountService.class);
        KeyRing ring = mock(KeyRing.class);
        when(ring.getSymmetricKey()).thenReturn(master);
        protobuf.EncryptedConnectionList legacy = protobuf.EncryptedConnectionList.newBuilder()
                .setSalt(ByteString.copyFrom(new byte[8])).build();
        doAnswer(invocation -> {
            ((Consumer<EncryptedConnectionList>) invocation.getArgument(0)).accept(EncryptedConnectionList.fromProto(legacy));
            return null;
        }).when(persistence).readPersisted(any(Consumer.class), any(Runnable.class));
        EncryptedConnectionList list = new EncryptedConnectionList(persistence, account, ring);
        boolean[] completed = {false};
        list.readPersisted(() -> completed[0] = true);
        assertFalse(completed[0]);
        org.mockito.ArgumentCaptor<Runnable> committed = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(persistence).forcePersistNow(committed.capture(), any(Consumer.class));
        assertEquals(1, encode(list).getEncryptionVersion());
        committed.getValue().run();
        assertTrue(completed[0]);
    }

    @SuppressWarnings("unchecked")
    private EncryptedConnectionList load(protobuf.EncryptedConnectionList proto, String password) {
        PersistenceManager<EncryptedConnectionList> persistence = mock(PersistenceManager.class);
        CoreAccountService account = mock(CoreAccountService.class);
        when(account.getPassword()).thenReturn(password);
        KeyRing ring = mock(KeyRing.class);
        when(ring.getSymmetricKey()).thenReturn(master);
        doAnswer(invocation -> {
            ((Consumer<EncryptedConnectionList>) invocation.getArgument(0)).accept(EncryptedConnectionList.fromProto(proto));
            return null;
        }).when(persistence).readPersisted(any(Consumer.class), any(Runnable.class));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(persistence).forcePersistNow(any(Runnable.class), any(Consumer.class));
        EncryptedConnectionList list = new EncryptedConnectionList(persistence, account, ring);
        boolean[] completed = {false};
        list.readPersisted(() -> completed[0] = true);
        assertTrue(completed[0]);
        return list;
    }

    private static protobuf.EncryptedConnectionList encode(EncryptedConnectionList list) {
        return ((protobuf.PersistableEnvelope) list.toProtoMessage()).getEncryptedConnectionList();
    }
}
