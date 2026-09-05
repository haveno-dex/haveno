package haveno.core.xmr.model;

import com.google.inject.Inject;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import haveno.common.crypto.AuthenticatedEncryption;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.CryptoException;
import haveno.common.crypto.Encryption;
import haveno.common.crypto.ScryptUtil;
import haveno.common.persistence.PersistenceManager;
import haveno.common.proto.persistable.PersistableEnvelope;
import haveno.common.proto.persistable.PersistedDataHost;
import haveno.core.api.CoreAccountService;
import haveno.core.api.model.EncryptedConnection;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import lombok.NonNull;
import monero.common.MoneroRpcConnection;
import org.bitcoinj.crypto.KeyCrypterScrypt;


/**
 * Store for {@link EncryptedConnection}s.
 * <p>
 * Passwords use authenticated master-key encryption. Legacy password-derived entries migrate on load.
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
    transient private KeyRing keyRing;
    private int encryptionVersion = 2;
    transient private PersistenceManager<EncryptedConnectionList> persistenceManager;

    private final Map<String, EncryptedConnection> items = new HashMap<>();
    private @NonNull String currentConnectionUrl = "";
    private long refreshPeriod; // -1 means no refresh, 0 means default, >0 means custom
    private boolean autoSwitch = true;

    @Inject
    public EncryptedConnectionList(PersistenceManager<EncryptedConnectionList> persistenceManager,
                             CoreAccountService accountService, KeyRing keyRing) {
        this.keyRing = keyRing;
        this.accountService = accountService;
        this.persistenceManager = persistenceManager;
        this.persistenceManager.initialize(this, "EncryptedConnectionList", PersistenceManager.Source.PRIVATE);
    }

    private EncryptedConnectionList(byte[] salt,
                              List<EncryptedConnection> items,
                              @NonNull String currentConnectionUrl,
                              long refreshPeriod,
                              boolean autoSwitch, int encryptionVersion) {
        if (encryptionVersion != 0 && encryptionVersion != 2) throw new IllegalArgumentException("Unsupported connection encryption version");
        this.encryptionVersion = encryptionVersion;
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
                Map<String, EncryptedConnection> loaded = persistedEncryptedConnectionList.items;
                if (persistedEncryptedConnectionList.encryptionVersion == 0) {
                    // Build the entire replacement first. A failed credential must not leave a mixed-key map.
                    SecretKey legacyKey = toSecretKey(accountService.getPasswordForLegacyData());
                    Map<String, EncryptedConnection> migrated = new HashMap<>();
                    for (EncryptedConnection connection : loaded.values()) {
                        byte[] plaintext = legacyKey == null ? connection.getEncryptedPassword()
                                : Encryption.decrypt(connection.getEncryptedPassword(), legacyKey);
                        validateSaltedPassword(plaintext, connection.getEncryptionSalt());
                        migrated.put(connection.getUrl(), connection.toBuilder().encryptedPassword(
                                encrypt(plaintext, encryptionKey, connection.getUrl())).build());
                    }
                    loaded = migrated;
                }
                // Authenticate every current entry before publishing any of them.
                for (EncryptedConnection connection : loaded.values()) {
                    validateSaltedPassword(decrypt(connection.getEncryptedPassword(), encryptionKey, connection.getUrl()), connection.getEncryptionSalt());
                }
                items.clear();
                items.putAll(loaded);
                encryptionVersion = 2;
                currentConnectionUrl = persistedEncryptedConnectionList.currentConnectionUrl;
                refreshPeriod = persistedEncryptedConnectionList.refreshPeriod;
                autoSwitch = persistedEncryptedConnectionList.autoSwitch;
            } catch (Exception e) {
                throw new IllegalStateException("Could not load encrypted connection credentials; existing file preserved", e);
            } finally {
                writeLock.unlock();
            }
            requestPersistence();
            completeHandler.run();
        }, () -> {
            writeLock.lock();
            try {
                initializeEncryption(ScryptUtil.getKeyCrypterScrypt());
            } catch (Exception e) {
                throw new IllegalStateException("Could not load encrypted connection credentials; existing file preserved", e);
            } finally {
                writeLock.unlock();
            }
            completeHandler.run();
        });
    }

    private void initializeEncryption(KeyCrypterScrypt keyCrypterScrypt) {
        this.keyCrypterScrypt = keyCrypterScrypt;
        encryptionKey = keyRing.getSymmetricKey();
        if (encryptionKey == null) throw new IllegalStateException("Account is locked");
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
            throw new IllegalStateException(String.format("There exists already a connection for \"%s\"", connection.getUri()));
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
            if (!PersistenceManager.allServicesInitialized.get()) {
                persistenceManager.forcePersistNow(); // connection can be changed before all services initialized
            } else {
                requestPersistence();
            }
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
                        .setEncryptionVersion(encryptionVersion)
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
        return new EncryptedConnectionList(proto.getSalt().toByteArray(), items, proto.getCurrentConnectionUrl(), proto.getRefreshPeriod(), proto.getAutoSwitch(), proto.getEncryptionVersion());
    }

    // ----------------------------- HELPERS ----------------------------------

    public void changePassword(String oldPassword, String newPassword) {
        // Credentials use the stable master key. Flush the initial legacy conversion before the
        // old account password can be retired, ordered after any previously queued store writes.
        persistenceManager.persistNowAndWait();
    }

    private SecretKey toSecretKey(String password) {
        if (password == null) return null;
        return Encryption.getSecretKeyFromBytes(keyCrypterScrypt.deriveKey(password).getKey());
    }

    private static byte[] decrypt(byte[] encrypted, SecretKey secret, String url) {
        try {
            return AuthenticatedEncryption.decrypt(encrypted, secret, "connection-password/" + url);
        } catch (CryptoException e) {
            throw new IllegalArgumentException("Could not authenticate connection password", e);
        }
    }

    private static byte[] encrypt(byte[] plaintext, SecretKey secret, String url) {
        try {
            return AuthenticatedEncryption.encrypt(plaintext, secret, "connection-password/" + url);
        } catch (CryptoException e) {
            throw new IllegalStateException("Could not encrypt connection password", e);
        }
    }

    private EncryptedConnection toEncryptedConnection(MoneroRpcConnection connection) {
        String password = connection.getPassword();
        byte[] passwordBytes = password == null ? null : password.getBytes(StandardCharsets.UTF_8);
        byte[] passwordSalt = generateSalt(passwordBytes);
        byte[] encryptedPassword = encryptPassword(passwordBytes, passwordSalt, connection.getUri());
        return EncryptedConnection.builder()
                .url(connection.getUri())
                .username(connection.getUsername() == null ? "" : connection.getUsername())
                .encryptedPassword(encryptedPassword)
                .encryptionSalt(passwordSalt)
                .priority(connection.getPriority())
                .build();
    }

    private MoneroRpcConnection toMoneroRpcConnection(EncryptedConnection connection) {
        byte[] decryptedPasswordBytes = decryptPassword(connection.getEncryptedPassword(), connection.getEncryptionSalt(), connection.getUrl());
        String password = decryptedPasswordBytes == null ? null : new String(decryptedPasswordBytes, StandardCharsets.UTF_8);
        String username = connection.getUsername().isEmpty() ? null : connection.getUsername();
        MoneroRpcConnection moneroRpcConnection = new MoneroRpcConnection(connection.getUrl(), username, password);
        moneroRpcConnection.setPriority(connection.getPriority());
        return moneroRpcConnection;
    }


    private byte[] encryptPassword(byte[] password, byte[] salt, String url) {
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
        return encrypt(saltedPassword, encryptionKey, url);
    }

    private byte[] decryptPassword(byte[] encryptedSaltedPassword, byte[] salt, String url) {
        byte[] decryptedSaltedPassword = decrypt(encryptedSaltedPassword, encryptionKey, url);
        validateSaltedPassword(decryptedSaltedPassword, salt);
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

    private static void validateSaltedPassword(byte[] plaintext, byte[] salt) {
        if (salt.length != SALT_LENGTH || plaintext.length < salt.length
                || (!arrayStartsWith(plaintext, salt)
                && !Arrays.equals(salt, Arrays.copyOfRange(plaintext, plaintext.length - salt.length, plaintext.length)))) {
            throw new IllegalArgumentException("Invalid legacy credential or incorrect password");
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
