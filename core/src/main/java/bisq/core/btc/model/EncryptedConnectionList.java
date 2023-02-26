package bisq.core.btc.model;

import bisq.common.crypto.CryptoException;
import bisq.common.crypto.Encryption;
import bisq.common.crypto.ScryptUtil;
import bisq.common.persistence.PersistenceManager;
import bisq.common.proto.persistable.PersistableEnvelope;
import bisq.common.proto.persistable.PersistedDataHost;
import bisq.core.api.CoreAccountService;
import bisq.core.api.model.EncryptedConnection;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import javax.inject.Inject;
import lombok.NonNull;
import monero.common.MoneroRpcConnection;
import org.bitcoinj.crypto.KeyCrypterScrypt;


/**
 * Store for {@link EncryptedConnection}s.
 * <p>
 * Passwords are encrypted when stored onto disk, using the account password.
 * If a connection has no password, this is "hidden" by using some random value as fake password.
 *
 * @implNote The password encryption mechanism is handled as follows.
 * A random salt is generated and stored for each connection. If the connection has no password,
 * the salt is used as prefix and some random data is attached as fake password. If the connection has a password,
 * the salt is used as suffix to the actual password. When the password gets decrypted, it is checked whether the
 * salt is a prefix of the decrypted value. If it is a prefix, the connection has no password.
 * Otherwise, it is removed (from the end) and the remaining value is the actual password.
 */
public class EncryptedConnectionList implements PersistableEnvelope, PersistedDataHost {

    private static final int MIN_FAKE_PASSWORD_LENGTH = 5;
    private static final int MAX_FAKE_PASSWORD_LENGTH = 32;
    private static final int SALT_LENGTH = 16;

    transient private final ReadWriteLock lock = new ReentrantReadWriteLock();
    transient private final Lock readLock = lock.readLock();
    transient private final Lock writeLock = lock.writeLock();
    transient private final SecureRandom random = new SecureRandom();

    transient private KeyCrypterScrypt keyCrypterScrypt;
    transient private SecretKey encryptionKey;

    transient private CoreAccountService accountService;
    transient private PersistenceManager<EncryptedConnectionList> persistenceManager;

    private final Map<String, EncryptedConnection> items = new HashMap<>();
    private @NonNull String currentConnectionUrl = "";
    private long refreshPeriod;
    private boolean autoSwitch;

    @Inject
    public EncryptedConnectionList(PersistenceManager<EncryptedConnectionList> persistenceManager,
                             CoreAccountService accountService) {
        this.accountService = accountService;
        this.persistenceManager = persistenceManager;
        this.persistenceManager.initialize(this, "EncryptedConnectionList", PersistenceManager.Source.PRIVATE);
    }

    private EncryptedConnectionList(byte[] salt,
                              List<EncryptedConnection> items,
                              @NonNull String currentConnectionUrl,
                              long refreshPeriod,
                              boolean autoSwitch) {
        this.keyCrypterScrypt = ScryptUtil.getKeyCrypterScrypt(salt);
        this.items.putAll(items.stream().collect(Collectors.toMap(EncryptedConnection::getUrl, Function.identity())));
        this.currentConnectionUrl = currentConnectionUrl;
        this.refreshPeriod = refreshPeriod;
        this.autoSwitch = autoSwitch;
    }

    @Override
    public void readPersisted(Runnable completeHandler) {
        persistenceManager.readPersisted(persistedEncryptedConnectionList -> {
            writeLock.lock();
            try {
                initializeEncryption(persistedEncryptedConnectionList.keyCrypterScrypt);
                items.clear();
                items.putAll(persistedEncryptedConnectionList.items);
                currentConnectionUrl = persistedEncryptedConnectionList.currentConnectionUrl;
                refreshPeriod = persistedEncryptedConnectionList.refreshPeriod;
                autoSwitch = persistedEncryptedConnectionList.autoSwitch;
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                writeLock.unlock();
            }
            completeHandler.run();
        }, () -> {
            writeLock.lock();
            try {
                initializeEncryption(ScryptUtil.getKeyCrypterScrypt());
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                writeLock.unlock();
            }
            completeHandler.run();
        });
    }

    private void initializeEncryption(KeyCrypterScrypt keyCrypterScrypt) {
        this.keyCrypterScrypt = keyCrypterScrypt;
        encryptionKey = toSecretKey(accountService.getPassword());
    }

    public List<MoneroRpcConnection> getConnections() {
        readLock.lock();
        try {
            return items.values().stream().map(this::toMoneroRpcConnection).collect(Collectors.toList());
        } finally {
            readLock.unlock();
        }
    }

    public boolean hasConnection(String connection) {
        readLock.lock();
        try {
            return items.containsKey(connection);
        } finally {
            readLock.unlock();
        }
    }

    public void addConnection(MoneroRpcConnection connection) {
        EncryptedConnection currentValue;
        writeLock.lock();
        try {
            EncryptedConnection encryptedConnection = toEncryptedConnection(connection);
            currentValue = items.putIfAbsent(connection.getUri(), encryptedConnection);
        } finally {
            writeLock.unlock();
        }
        if (currentValue != null) {
            throw new IllegalStateException(String.format("There exists already an connection for \"%s\"", connection.getUri()));
        }
        requestPersistence();
    }

    public void removeConnection(String connection) {
        writeLock.lock();
        try {
            items.remove(connection);
        } finally {
            writeLock.unlock();
        }
        requestPersistence();
    }

    public void setAutoSwitch(boolean autoSwitch) {
        boolean changed;
        writeLock.lock();
        try {
            changed = this.autoSwitch != (this.autoSwitch = autoSwitch);
        } finally {
            writeLock.unlock();
        }
        if (changed) {
            requestPersistence();
        }
    }

    public boolean getAutoSwitch() {
        readLock.lock();
        try {
            return autoSwitch;
        } finally {
            readLock.unlock();
        }
    }

    public void setRefreshPeriod(Long refreshPeriod) {
        boolean changed;
        writeLock.lock();
        try {
            changed = this.refreshPeriod != (this.refreshPeriod = refreshPeriod == null ? 0L : refreshPeriod);
        } finally {
            writeLock.unlock();
        }
        if (changed) {
            requestPersistence();
        }
    }

    public long getRefreshPeriod() {
        readLock.lock();
        try {
            return refreshPeriod;
        } finally {
            readLock.unlock();
        }
    }

    public void setCurrentConnectionUri(String currentConnectionUrl) {
        boolean changed;
        writeLock.lock();
        try {
            changed = !this.currentConnectionUrl.equals(this.currentConnectionUrl = currentConnectionUrl == null ? "" : currentConnectionUrl);
        } finally {
            writeLock.unlock();
        }
        if (changed) {
            requestPersistence();
        }
    }

    public Optional<String> getCurrentConnectionUri() {
        readLock.lock();
        try {
            return Optional.of(currentConnectionUrl).filter(s -> !s.isEmpty());
        } finally {
            readLock.unlock();
        }
    }

    public void requestPersistence() {
        persistenceManager.requestPersistence();
    }
    
    @Override
    public Message toProtoMessage() {
        List<protobuf.EncryptedConnection> connections;
        ByteString saltString;
        String currentConnectionUrl;
        boolean autoSwitchEnabled;
        long refreshPeriod;
        readLock.lock();
        try {
            connections = items.values().stream()
                    .map(EncryptedConnection::toProtoMessage).collect(Collectors.toList());
            saltString = keyCrypterScrypt.getScryptParameters().getSalt();
            currentConnectionUrl = this.currentConnectionUrl;
            autoSwitchEnabled = this.autoSwitch;
            refreshPeriod = this.refreshPeriod;
        } finally {
            readLock.unlock();
        }
        return protobuf.PersistableEnvelope.newBuilder()
                .setEncryptedConnectionList(protobuf.EncryptedConnectionList.newBuilder()
                        .setSalt(saltString)
                        .addAllItems(connections)
                        .setCurrentConnectionUrl(currentConnectionUrl)
                        .setRefreshPeriod(refreshPeriod)
                        .setAutoSwitch(autoSwitchEnabled))
                .build();
    }

    public static EncryptedConnectionList fromProto(protobuf.EncryptedConnectionList proto) {
        List<EncryptedConnection> items = proto.getItemsList().stream()
                .map(EncryptedConnection::fromProto)
                .collect(Collectors.toList());
        return new EncryptedConnectionList(proto.getSalt().toByteArray(), items, proto.getCurrentConnectionUrl(), proto.getRefreshPeriod(), proto.getAutoSwitch());
    }
    
    // ----------------------------- HELPERS ----------------------------------
    
    public void changePassword(String oldPassword, String newPassword) {
        writeLock.lock();
        try {
            SecretKey oldSecret = encryptionKey;
            assert Objects.equals(oldSecret, toSecretKey(oldPassword)) : "Old secret does not match old password";
            encryptionKey = toSecretKey(newPassword);
            items.replaceAll((key, connection) -> reEncrypt(connection, oldSecret, encryptionKey));
        } finally {
            writeLock.unlock();
        }
        requestPersistence();
    }

    private SecretKey toSecretKey(String password) {
        if (password == null) return null;
        return Encryption.getSecretKeyFromBytes(keyCrypterScrypt.deriveKey(password).getKey());
    }

    private static EncryptedConnection reEncrypt(EncryptedConnection connection,
                                                    SecretKey oldSecret, SecretKey newSecret) {
        return connection.toBuilder()
                .encryptedPassword(reEncrypt(connection.getEncryptedPassword(), oldSecret, newSecret))
                .build();
    }

    private static byte[] reEncrypt(byte[] value,
                                    SecretKey oldSecret, SecretKey newSecret) {
        // was previously not encrypted if null
        byte[] decrypted = oldSecret == null ? value : decrypt(value, oldSecret);
        // should not be encrypted if null
        return newSecret == null ? decrypted : encrypt(decrypted, newSecret);
    }

    private static byte[] decrypt(byte[] encrypted, SecretKey secret) {
        if (secret == null) return encrypted; // no encryption
        try {
            return Encryption.decrypt(encrypted, secret);
        } catch (CryptoException e) {
            throw new IllegalArgumentException("Incorrect password", e);
        }
    }

    private static byte[] encrypt(byte[] unencrypted, SecretKey secretKey) {
        if (secretKey == null) return unencrypted; // no encryption
        try {
            return Encryption.encrypt(unencrypted, secretKey);
        } catch (CryptoException e) {
            throw new RuntimeException("Could not encrypt data with the provided secret", e);
        }
    }

    private EncryptedConnection toEncryptedConnection(MoneroRpcConnection connection) {
        String password = connection.getPassword();
        byte[] passwordBytes = password == null ? null : password.getBytes(StandardCharsets.UTF_8);
        byte[] passwordSalt = generateSalt(passwordBytes);
        byte[] encryptedPassword = encryptPassword(passwordBytes, passwordSalt);
        return EncryptedConnection.builder()
                .url(connection.getUri())
                .username(connection.getUsername() == null ? "" : connection.getUsername())
                .encryptedPassword(encryptedPassword)
                .encryptionSalt(passwordSalt)
                .priority(connection.getPriority())
                .build();
    }

    private MoneroRpcConnection toMoneroRpcConnection(EncryptedConnection connection) {
        byte[] decryptedPasswordBytes = decryptPassword(connection.getEncryptedPassword(), connection.getEncryptionSalt());
        String password = decryptedPasswordBytes == null ? null : new String(decryptedPasswordBytes, StandardCharsets.UTF_8);
        String username = connection.getUsername().isEmpty() ? null : connection.getUsername();
        MoneroRpcConnection moneroRpcConnection = new MoneroRpcConnection(connection.getUrl(), username, password);
        moneroRpcConnection.setPriority(connection.getPriority());
        return moneroRpcConnection;
    }


    private byte[] encryptPassword(byte[] password, byte[] salt) {
        byte[] saltedPassword;
        if (password == null) {
            // no password given, so use salt as prefix and add some random data, which disguises itself as password
            int fakePasswordLength = random.nextInt(MAX_FAKE_PASSWORD_LENGTH - MIN_FAKE_PASSWORD_LENGTH + 1)
                    + MIN_FAKE_PASSWORD_LENGTH;
            byte[] fakePassword = new byte[fakePasswordLength];
            random.nextBytes(fakePassword);
            saltedPassword = new byte[salt.length + fakePasswordLength];
            System.arraycopy(salt, 0, saltedPassword, 0, salt.length);
            System.arraycopy(fakePassword, 0, saltedPassword, salt.length, fakePassword.length);
        } else {
            // password given, so append salt to end
            saltedPassword = new byte[password.length + salt.length];
            System.arraycopy(password, 0, saltedPassword, 0, password.length);
            System.arraycopy(salt, 0, saltedPassword, password.length, salt.length);
        }
        return encrypt(saltedPassword, encryptionKey);
    }

    private byte[] decryptPassword(byte[] encryptedSaltedPassword, byte[] salt) {
        byte[] decryptedSaltedPassword = decrypt(encryptedSaltedPassword, encryptionKey);
        if (arrayStartsWith(decryptedSaltedPassword, salt)) {
            // salt is prefix, so no actual password set
            return null;
        } else {
            // remove salt suffix, the rest is the actual password
            byte[] decryptedPassword = new byte[decryptedSaltedPassword.length - salt.length];
            System.arraycopy(decryptedSaltedPassword, 0, decryptedPassword, 0, decryptedPassword.length);
            return decryptedPassword;
        }
    }

    private byte[] generateSalt(byte[] password) {
        byte[] salt = new byte[SALT_LENGTH];
        // Generate salt, that is guaranteed to be no prefix of the password
        do {
            random.nextBytes(salt);
        } while (password != null && arrayStartsWith(password, salt));
        return salt;
    }

    private static boolean arrayStartsWith(byte[] container, byte[] prefix) {
        if (container.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (container[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
