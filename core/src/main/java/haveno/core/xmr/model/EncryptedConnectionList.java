package haveno.core.xmr.model;

import com.google.inject.Inject;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import haveno.common.crypto.CryptoException;
import haveno.common.crypto.Encryption;
import haveno.common.crypto.ScryptUtil;
import haveno.common.persistence.PersistenceManager;
import haveno.common.proto.persistable.PersistableEnvelope;
import haveno.common.proto.persistable.PersistedDataHost;
import haveno.core.api.CoreAccountService;
import haveno.core.api.model.EncryptedConnection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
    private long refreshPeriod; // -1 means no refresh, 0 means default, >0 means custom
    private boolean autoSwitch = true;

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
            boolean migrated = false;
            writeLock.lock();
            try {
                initializeEncryption(persistedEncryptedConnectionList.keyCrypterScrypt);
                items.clear();
                items.putAll(persistedEncryptedConnectionList.items);
                currentConnectionUrl = persistedEncryptedConnectionList.currentConnectionUrl;
                refreshPeriod = persistedEncryptedConnectionList.refreshPeriod;
                autoSwitch = persistedEncryptedConnectionList.autoSwitch;
                migrated = migrateLegacyEncryption();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                writeLock.unlock();
            }
            if (migrated) requestPersistence();
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

    // Re-encrypts entries persisted in an older format than the current one; must hold the write lock.
    private boolean migrateLegacyEncryption() {
        if (encryptionKey == null) return false;
        boolean migrated = false;
        for (Map.Entry<String, EncryptedConnection> entry : items.entrySet()) {
            byte[] encrypted = entry.getValue().getEncryptedPassword();
            if (encrypted != null && encrypted.length > 0 && Encryption.blobVersion(encrypted) < Encryption.CURRENT_BLOB_VERSION) {
                entry.setValue(reEncrypt(entry.getValue(), encryptionKey, encryptionKey));
                migrated = true;
            }
        }
        return migrated;
    }

    public List<MoneroRpcConnection> getConnections() {
        try {
            return doGetConnections();
        } catch (IllegalArgumentException e) {
            // like wallets healed on open, credentials still on the counterpart password of an
            // interrupted password change are converged here, so startup can proceed to the
            // deferred recovery instead of deadlocking on the decryption failure
            if (!healInterruptedPasswordChange()) throw e;
            return doGetConnections();
        }
    }

    private List<MoneroRpcConnection> doGetConnections() {
        readLock.lock();
        try {
            return items.values().stream().map(this::toMoneroRpcConnection).collect(Collectors.toList());
        } finally {
            readLock.unlock();
        }
    }

    private boolean healInterruptedPasswordChange() {
        String[] counterpart = accountService.getPendingPasswordChangeCounterpart();
        if (counterpart == null) return false;
        log.warn("Connection credentials do not match the account password; converging from the interrupted password change's counterpart password");
        changePassword(counterpart[0], accountService.getPassword());
        return true;
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
            SecretKey oldSecret = toSecretKey(oldPassword);
            SecretKey newSecret = toSecretKey(newPassword);
            encryptionKey = newSecret;
            items.replaceAll((key, connection) -> reEncryptIdempotent(connection, oldSecret, newSecret));
        } finally {
            writeLock.unlock();
        }
        // persist synchronously so the credentials are durable before the account password commits
        if (persistenceManager.readCalled.get()) {
            persistenceManager.persistNowSync();
            // replace rolling backups, whose credentials are still under the previous password
            try {
                persistenceManager.refreshBackups();
            } catch (IOException e) {
                throw new RuntimeException("Could not refresh backups of the connection list", e);
            }
        }
    }

    private SecretKey toSecretKey(String password) {
        if (password == null) return null;
        return Encryption.getSecretKeyFromBytes(keyCrypterScrypt.deriveKey(password).getKey());
    }

    private static EncryptedConnection reEncrypt(EncryptedConnection connection,
                                                    SecretKey oldSecret, SecretKey newSecret) {
        return connection.toBuilder()
                .encryptedPassword(reEncrypt(connection.getEncryptedPassword(), connection.getEncryptionSalt(), oldSecret, newSecret))
                .build();
    }

    private static EncryptedConnection reEncryptIdempotent(EncryptedConnection connection,
                                                    SecretKey oldSecret, SecretKey newSecret) {
        return connection.toBuilder()
                .encryptedPassword(reEncryptIdempotent(connection.getEncryptedPassword(), connection.getEncryptionSalt(), oldSecret, newSecret))
                .build();
    }

    // Re-encrypts a value from the old to the new secret, tolerating values already converged by an
    // interrupted password change.
    private static byte[] reEncryptIdempotent(byte[] value, byte[] salt, SecretKey oldSecret, SecretKey newSecret) {
        if (newSecret == null) {
            if (oldSecret == null) return value;
            try {
                return decryptChecked(value, salt, oldSecret);
            } catch (RuntimeException e) {
                if (!Encryption.isV2Format(value)) return value; // already plaintext
                throw e;
            }
        }
        if (oldSecret == null) {
            if (Encryption.isV2Format(value)) {
                try {
                    decryptChecked(value, salt, newSecret);
                    return value; // already encrypted with the new secret
                } catch (RuntimeException ignore) {
                }
            }
            return encrypt(value, newSecret);
        }
        try {
            return encrypt(decryptChecked(value, salt, oldSecret), newSecret);
        } catch (RuntimeException e) {
            try {
                decryptChecked(value, salt, newSecret);
                return value; // already encrypted with the new secret
            } catch (RuntimeException ignore) {
            }
            throw e;
        }
    }

    private static byte[] reEncrypt(byte[] value, byte[] salt,
                                    SecretKey oldSecret, SecretKey newSecret) {
        // was previously not encrypted if null
        byte[] decrypted = oldSecret == null ? value : decryptChecked(value, salt, oldSecret);
        // should not be encrypted if null
        return newSecret == null ? decrypted : encrypt(decrypted, newSecret);
    }

    // Decrypts and verifies the plaintext against the connection salt, so a spurious wrong-key
    // decrypt of a legacy blob (unauthenticated AES-ECB) is never re-sealed as authenticated data.
    private static byte[] decryptChecked(byte[] value, byte[] salt, SecretKey secret) {
        byte[] decrypted = decrypt(value, secret);
        // a planted empty or truncated salt must not weaken the check
        if (salt.length != SALT_LENGTH || (!arrayStartsWith(decrypted, salt) && !arrayEndsWith(decrypted, salt))) {
            throw new IllegalArgumentException("Could not authenticate decrypted connection password");
        }
        return decrypted;
    }

    private static byte[] decrypt(byte[] encrypted, SecretKey secret) {
        if (secret == null) return encrypted; // no encryption
        try {
            return Encryption.decryptAuto(encrypted, secret); // v2 or legacy AES-ECB
        } catch (CryptoException e) {
            throw new IllegalArgumentException("Incorrect password", e);
        }
    }

    private static byte[] encrypt(byte[] unencrypted, SecretKey secretKey) {
        if (secretKey == null) return unencrypted; // no encryption
        try {
            return Encryption.encryptV2(unencrypted, secretKey);
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
            // the salt must be the suffix, else the value failed authentication (e.g. the raw
            // legacy fallback of decryptAuto on a tampered v2 blob)
            if (decryptedSaltedPassword.length < salt.length || !arrayEndsWith(decryptedSaltedPassword, salt)) {
                throw new IllegalArgumentException("Could not authenticate decrypted connection password");
            }
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

    private static boolean arrayEndsWith(byte[] container, byte[] suffix) {
        if (container.length < suffix.length) {
            return false;
        }
        int offset = container.length - suffix.length;
        for (int i = 0; i < suffix.length; i++) {
            if (container[offset + i] != suffix[i]) {
                return false;
            }
        }
        return true;
    }
}
