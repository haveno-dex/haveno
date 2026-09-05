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

package haveno.common.crypto;

import haveno.common.file.FileUtil;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyStorageMigrationTest {
    @TempDir Path dir;
    private static final String PASSWORD = "old-password";

    private SecretKey legacyAccount() throws Exception {
        return legacyAccount(PASSWORD);
    }

    private SecretKey legacyAccount(String password) throws Exception {
        SecretKey master = Encryption.generateSecretKey(256);
        char[] passwordChars = password == null ? new char[0] : password.toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("sym", master, passwordChars, null);
        try (FileOutputStream out = new FileOutputStream(dir.resolve("sym.p12").toFile())) {
            store.store(out, passwordChars);
        }
        writeLegacyPrivateKey("sig.key", Sig.generateKeyPair(), master);
        writeLegacyPrivateKey("enc.key", Encryption.generateKeyPair(), master);
        return master;
    }

    @Test
    void injectedConstructorDefersPasswordlessUnlockAndMigrationUntilLogin() throws Exception {
        SecretKey master = legacyAccount(null);
        Map<Path, byte[]> legacy = snapshot();
        KeyStorage storage = new KeyStorage(dir.toFile());
        KeyRing ring = new KeyRing(storage);
        assertFalse(ring.isUnlocked());
        assertNull(ring.getSymmetricKey());
        assertSnapshot(legacy);

        assertTrue(ring.unlockKeys(null, false));
        assertArrayEquals(master.getEncoded(), ring.getSymmetricKey().getEncoded());
        assertTrue(Files.exists(dir.resolve("sym.key")));

        Map<Path, byte[]> migrated = snapshot();
        KeyRing restarted = new KeyRing(storage);
        assertFalse(restarted.isUnlocked());
        assertSnapshot(migrated);
        assertTrue(restarted.unlockKeys(null, false));
        assertEquals(ring.getPubKeyRing(), restarted.getPubKeyRing());
        assertArrayEquals(master.getEncoded(), restarted.getSymmetricKey().getEncoded());
        assertSnapshot(migrated);
    }

    // Independent baseline format fixture: AES-ECB(payload || HMAC-SHA256(payload)).
    private void writeLegacyPrivateKey(String name, KeyPair pair, SecretKey master) throws Exception {
        byte[] encoded = pair.getPrivate().getEncoded();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(master);
        byte[] clear = Arrays.copyOf(encoded, encoded.length + 32);
        System.arraycopy(mac.doFinal(encoded), 0, clear, encoded.length, 32);
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, master);
        Files.write(dir.resolve(name), cipher.doFinal(clear));
    }

    @Test
    void migratesWithoutChangingMasterOrIdentityAndRestarts() throws Exception {
        SecretKey master = legacyAccount();
        KeyStorage storage = new KeyStorage(dir.toFile());
        KeyPair signature = storage.loadKeyPair(KeyStorage.KeyEntry.MSG_SIGNATURE, master);
        KeyPair encryption = storage.loadKeyPair(KeyStorage.KeyEntry.MSG_ENCRYPTION, master);
        assertTrue(FileUtil.rollingBackup(dir.toFile(), "sym.p12", 20));
        byte[] legacyPayload = Encryption.encryptPayloadWithHmac(new byte[]{1, 2, 3}, master);
        KeyRing ring = new KeyRing(storage, PASSWORD, false);
        assertTrue(ring.isUnlocked());
        assertArrayEquals(master.getEncoded(), ring.getSymmetricKey().getEncoded());
        assertArrayEquals(signature.getPrivate().getEncoded(), ring.getSignatureKeyPair().getPrivate().getEncoded());
        assertArrayEquals(encryption.getPublic().getEncoded(), ring.getEncryptionKeyPair().getPublic().getEncoded());
        assertTrue(Files.exists(dir.resolve("sym.p12")));
        assertTrue(FileUtil.getBackupFiles(dir.toFile(), "sym.p12").isEmpty());
        assertTrue(AuthenticatedEncryption.isEnvelope(Files.readAllBytes(dir.resolve("sig.key"))));
        assertTrue(AuthenticatedEncryption.isEnvelope(Files.readAllBytes(dir.resolve("enc.key"))));
        KeyRing restarted = new KeyRing(new KeyStorage(dir.toFile()), PASSWORD, false);
        assertEquals(ring.getPubKeyRing(), restarted.getPubKeyRing());
        assertArrayEquals(new byte[]{1, 2, 3}, Encryption.decryptPayloadWithHmac(legacyPayload, restarted.getSymmetricKey()));
        assertArrayEquals(Files.readAllBytes(dir.resolve("sym.key")), Files.readAllBytes(dir.resolve("sym.key.bak")));
    }

    @Test
    void wrongPasswordNeverMutatesLegacyOrCurrentFiles() throws Exception {
        legacyAccount();
        assertWrongPasswordDoesNotWrite();
        assertTrue(new KeyRing(new KeyStorage(dir.toFile()), PASSWORD, false).isUnlocked());
        assertWrongPasswordDoesNotWrite();
    }

    private void assertWrongPasswordDoesNotWrite() throws Exception {
        Map<Path, byte[]> before = snapshot();
        KeyRing ring = new KeyRing(new KeyStorage(dir.toFile()));
        assertThrows(IncorrectPasswordException.class, () -> ring.unlockKeys("wrong-password", false));
        assertFalse(ring.isUnlocked());
        assertNull(ring.getSymmetricKey());
        assertSnapshot(before);
    }

    @Test
    void authoritativeNewWrapperNeverFallsBackToLegacy() throws Exception {
        SecretKey master = legacyAccount();
        byte[] wrapped = PasswordKey.wrap(master, PASSWORD);
        wrapped[wrapped.length - 1] ^= 1;
        Files.write(dir.resolve("sym.key"), wrapped);
        assertWrongPasswordDoesNotWrite();
        Map<Path, byte[]> before = snapshot();
        assertThrows(IncorrectPasswordException.class,
                () -> new KeyStorage(dir.toFile()).loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, PASSWORD));
        assertSnapshot(before);
    }

    @Test
    void interruptedMigrationResumesFromNewWrapperWithLegacyIdentities() throws Exception {
        SecretKey master = legacyAccount();
        Files.write(dir.resolve("sym.key"), PasswordKey.wrap(master, PASSWORD));
        KeyRing ring = new KeyRing(new KeyStorage(dir.toFile()), PASSWORD, false);
        assertTrue(ring.isUnlocked());
        assertArrayEquals(master.getEncoded(), ring.getSymmetricKey().getEncoded());
        assertTrue(Files.exists(dir.resolve("sym.p12")));
    }

    @Test
    void backupFailureDuringMigrationRetainsLegacyRecoveryAndResumes() throws Exception {
        SecretKey master = legacyAccount();
        byte[] legacyWrapper = Files.readAllBytes(dir.resolve("sym.p12"));
        Files.createDirectory(dir.resolve("sym.key.bak"));
        KeyRing ring = new KeyRing(new KeyStorage(dir.toFile()));
        assertThrows(RuntimeException.class, () -> ring.unlockKeys(PASSWORD, false));
        assertFalse(ring.isUnlocked());
        assertArrayEquals(legacyWrapper, Files.readAllBytes(dir.resolve("sym.p12")));
        assertTrue(Files.exists(dir.resolve("sym.key")));
        Files.delete(dir.resolve("sym.key.bak"));
        KeyRing restarted = new KeyRing(new KeyStorage(dir.toFile()), PASSWORD, false);
        assertTrue(restarted.isUnlocked());
        assertArrayEquals(master.getEncoded(), restarted.getSymmetricKey().getEncoded());
    }

    @Test
    void badIdentityPreventsMigrationAndLeavesRingLocked() throws Exception {
        legacyAccount();
        Files.write(dir.resolve("enc.key"), new byte[]{1, 2, 3});
        Map<Path, byte[]> before = snapshot();
        KeyRing ring = new KeyRing(new KeyStorage(dir.toFile()));
        assertThrows(RuntimeException.class, () -> ring.unlockKeys(PASSWORD, false));
        assertFalse(ring.isUnlocked());
        assertNull(ring.getSymmetricKey());
        assertSnapshot(before);
    }

    @Test
    void incompleteKeyDirectoryNeverRegeneratesKeys() throws Exception {
        Files.write(dir.resolve("sig.key"), new byte[]{1, 2, 3});
        Map<Path, byte[]> before = snapshot();
        assertThrows(IllegalStateException.class, () -> new KeyRing(new KeyStorage(dir.toFile()), null, true));
        assertSnapshot(before);
    }

    @Test
    void deletedKeyDirectoryIsNotAnExistingAccount() throws Exception {
        Path keys = Files.createDirectory(dir.resolve("keys"));
        KeyStorage storage = new KeyStorage(keys.toFile());
        Files.delete(keys);
        assertFalse(storage.anyKeyFilesExist());
    }

    @Test
    void downgradeGuardLocksOldBuildAndIdentityRecoveryCopiesRemainReadable() throws Exception {
        KeyRing ring = new KeyRing(new KeyStorage(dir.toFile()), PASSWORD, true);
        for (String name : new String[]{"sig.key", "enc.key"}) {
            assertArrayEquals(Files.readAllBytes(dir.resolve(name)), Files.readAllBytes(dir.resolve(name + ".bak")));
        }
        KeyStore oldStore = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(dir.resolve("sym.p12"))) {
            assertThrows(java.io.IOException.class, () -> oldStore.load(in, PASSWORD.toCharArray()));
        }
        Files.copy(dir.resolve("sig.key.bak"), dir.resolve("sig.key"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(ring.getPubKeyRing(), new KeyRing(new KeyStorage(dir.toFile()), PASSWORD, false).getPubKeyRing());
        Map<Path, byte[]> before = snapshot();
        Files.delete(dir.resolve("sym.key"));
        assertThrows(IllegalStateException.class, () -> new KeyRing(new KeyStorage(dir.toFile()), PASSWORD, true));
        assertArrayEquals(before.get(dir.resolve("sig.key")), Files.readAllBytes(dir.resolve("sig.key")));
    }

    @Test
    void unchangedMigratedKeysDoNotRewriteOnUnlock() throws Exception {
        new KeyRing(new KeyStorage(dir.toFile()), PASSWORD, true);
        Map<Path, java.nio.file.attribute.FileTime> times = new HashMap<>();
        for (Path p : snapshot().keySet()) {
            Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(1_000_000));
            times.put(p, Files.getLastModifiedTime(p));
        }
        assertTrue(new KeyRing(new KeyStorage(dir.toFile()), PASSWORD, false).isUnlocked());
        for (var entry : times.entrySet()) assertEquals(entry.getValue(), Files.getLastModifiedTime(entry.getKey()), entry.getKey().toString());
    }

    @Test
    void passwordChangesRetainIdentityAndRejectOldPassword() throws Exception {
        KeyStorage storage = new KeyStorage(dir.toFile());
        KeyRing ring = new KeyRing(storage, PASSWORD, true);
        byte[] signature = Files.readAllBytes(dir.resolve("sig.key"));
        byte[] master = ring.getSymmetricKey().getEncoded();
        storage.saveKeyRing(ring, PASSWORD, "new-password");
        assertArrayEquals(signature, Files.readAllBytes(dir.resolve("sig.key")));
        assertThrows(IncorrectPasswordException.class, () -> storage.loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, PASSWORD));
        assertArrayEquals(master, storage.loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, "new-password").getEncoded());
        storage.saveKeyRing(ring, "new-password", null);
        assertTrue(new KeyRing(new KeyStorage(dir.toFile()), null, false).isUnlocked());
    }

    @Test
    void unsupportedKdfProfileAndOversizeFileAreRejectedBeforeDerivation() throws Exception {
        byte[] file = PasswordKey.wrap(Encryption.generateSecretKey(256), PASSWORD);
        file[10] = 127;
        assertThrows(IllegalStateException.class, () -> PasswordKey.unwrap(file, PASSWORD));
        assertThrows(IllegalStateException.class, () -> PasswordKey.unwrap(new byte[20000], PASSWORD));
    }

    private Map<Path, byte[]> snapshot() throws Exception {
        Map<Path, byte[]> result = new HashMap<>();
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) result.put(p, Files.readAllBytes(p));
        }
        return result;
    }

    private void assertSnapshot(Map<Path, byte[]> before) throws Exception {
        Map<Path, byte[]> after = snapshot();
        assertEquals(before.keySet(), after.keySet());
        for (Path p : before.keySet()) assertArrayEquals(before.get(p), after.get(p), p.toString());
    }
}
