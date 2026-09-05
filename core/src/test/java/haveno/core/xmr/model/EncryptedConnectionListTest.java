package haveno.core.xmr.model;

import com.google.protobuf.ByteString;
import haveno.common.crypto.Encryption;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.crypto.ScryptUtil;
import haveno.common.persistence.PersistenceManager;
import haveno.core.api.CoreAccountService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;
import monero.common.MoneroRpcConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EncryptedConnectionListTest {
    @TempDir Path directory;

    @SuppressWarnings("unchecked")
    private EncryptedConnectionList load(protobuf.EncryptedConnectionList proto, KeyRing keyRing, String legacyPassword) {
        PersistenceManager<EncryptedConnectionList> manager = mock(PersistenceManager.class);
        CoreAccountService account = mock(CoreAccountService.class);
        when(account.getPasswordForLegacyData()).thenReturn(legacyPassword);
        doAnswer(call -> {
            Consumer<EncryptedConnectionList> reader = call.getArgument(0);
            reader.accept(EncryptedConnectionList.fromProto(proto));
            return null;
        }).when(manager).readPersisted(any(Consumer.class), any(Runnable.class));
        EncryptedConnectionList list = new EncryptedConnectionList(manager, account, keyRing);
        list.readPersisted(() -> {});
        return list;
    }

    private protobuf.EncryptedConnectionList legacy(String password) throws Exception {
        byte[] salt = new byte[16];
        Arrays.fill(salt, (byte) 7);
        byte[] text = "rpc-secret".getBytes(StandardCharsets.UTF_8);
        byte[] salted = Arrays.copyOf(text, text.length + salt.length);
        System.arraycopy(salt, 0, salted, text.length, salt.length);
        byte[] encrypted = password == null ? salted : Encryption.encrypt(salted,
                Encryption.getSecretKeyFromBytes(ScryptUtil.getKeyCrypterScrypt(salt).deriveKey(password).getKey()));
        return protobuf.EncryptedConnectionList.newBuilder().setSalt(ByteString.copyFrom(salt))
                .addItems(protobuf.EncryptedConnection.newBuilder().setUrl("http://127.0.0.1:18081")
                        .setUsername("rpc-user").setEncryptionSalt(ByteString.copyFrom(salt))
                        .setEncryptedPassword(ByteString.copyFrom(encrypted))).build();
    }

    @Test
    void legacyCredentialsMigrateAndNoLongerDependOnAccountPassword() throws Exception {
        KeyRing ring = new KeyRing(new KeyStorage(directory.toFile()), null, true);
        EncryptedConnectionList list = load(legacy("old-password"), ring, "old-password");
        assertEquals("rpc-secret", list.getConnections().get(0).getPassword());
        protobuf.EncryptedConnectionList modern = ((protobuf.PersistableEnvelope) list.toProtoMessage()).getEncryptedConnectionList();
        assertEquals(2, modern.getEncryptionVersion());
        EncryptedConnectionList reopened = load(modern, ring, "different-password");
        assertEquals("rpc-secret", reopened.getConnections().get(0).getPassword());
        reopened.addConnection(new MoneroRpcConnection("http://localhost:28081"));
        assertNull(reopened.getConnections().stream().filter(c -> c.getUri().equals("http://localhost:28081")).findFirst().orElseThrow().getPassword());
    }

    @Test
    void passwordlessLegacyCredentialsAlsoBecomeAuthenticated() throws Exception {
        KeyRing ring = new KeyRing(new KeyStorage(directory.toFile()), null, true);
        EncryptedConnectionList list = load(legacy(null), ring, null);
        assertEquals("rpc-secret", list.getConnections().get(0).getPassword());
        assertEquals(2, ((protobuf.PersistableEnvelope) list.toProtoMessage()).getEncryptedConnectionList().getEncryptionVersion());
    }

    @Test
    void tamperedOrMovedCredentialsAndUnknownVersionsAreRejected() throws Exception {
        KeyRing ring = new KeyRing(new KeyStorage(directory.toFile()), null, true);
        EncryptedConnectionList list = load(legacy(null), ring, null);
        protobuf.EncryptedConnectionList proto = ((protobuf.PersistableEnvelope) list.toProtoMessage()).getEncryptedConnectionList();
        protobuf.EncryptedConnectionList moved = proto.toBuilder().setItems(0,
                proto.getItems(0).toBuilder().setUrl("http://different-node:18081")).build();
        assertThrows(IllegalStateException.class, () -> load(moved, ring, null));
        assertThrows(IllegalArgumentException.class, () -> EncryptedConnectionList.fromProto(proto.toBuilder().setEncryptionVersion(3).build()));
    }
}
