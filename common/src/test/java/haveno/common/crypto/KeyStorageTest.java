package haveno.common.crypto;

import haveno.common.persistence.LegacyStorageMigration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyStorageTest {
    @TempDir Path directory;
    private static final SecretKey MASTER = Encryption.generateSecretKey(256);
    private static final KeyPair SIGNING = Sig.generateKeyPair();
    private static final KeyPair ENCRYPTION = Encryption.generateKeyPair();

    private void legacy(String password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        char[] chars = password == null ? new char[0] : password.toCharArray();
        store.load(null, null);
        store.setKeyEntry("sym", MASTER, chars, null);
        try (var out = Files.newOutputStream(directory.resolve("sym.p12"))) {
            store.store(out, chars);
        }
        Files.write(directory.resolve("sig.key"), Encryption.encryptPayloadWithHmac(SIGNING.getPrivate().getEncoded(), MASTER));
        Files.write(directory.resolve("enc.key"), Encryption.encryptPayloadWithHmac(ENCRYPTION.getPrivate().getEncoded(), MASTER));
    }

    private KeyStorage storage() { return new KeyStorage(directory.toFile()); }

    @Test
    void migratesLegacyKeysWithoutChangingIdentityOrMasterKey() throws Exception {
        legacy("old-password");
        Path oldBackups = Files.createDirectories(directory.resolve("backup/backups_sym_p12"));
        Files.copy(directory.resolve("sym.p12"), oldBackups.resolve("1_sym.p12"));
        KeyRing ring = new KeyRing(storage(), "old-password", false);
        assertTrue(ring.isUnlocked());
        assertEquals(MASTER, ring.getSymmetricKey());
        assertEquals(SIGNING.getPublic(), ring.getPubKeyRing().getSignaturePubKey());
        assertEquals(ENCRYPTION.getPublic(), ring.getPubKeyRing().getEncryptionPubKey());
        assertFalse(Files.exists(directory.resolve("sym.p12")));
        assertFalse(Files.exists(oldBackups));
        assertArrayEquals(Files.readAllBytes(directory.resolve("sym.key")), Files.readAllBytes(directory.resolve("sym.key.backup")));
        assertTrue(AuthenticatedEncryption.hasEnvelope(directory.resolve("sig.key")));
        assertTrue(AuthenticatedEncryption.hasEnvelope(directory.resolve("enc.key")));
        assertEquals(ring.getPubKeyRing(), new KeyRing(storage(), "old-password", false).getPubKeyRing());
    }

    @Test
    void wrongPasswordDoesNotWriteOrMigrate() throws Exception {
        legacy("old-password");
        byte[] original = Files.readAllBytes(directory.resolve("sym.p12"));
        KeyRing ring = new KeyRing(storage(), "wrong-password", false);
        assertFalse(ring.isUnlocked());
        assertFalse(Files.exists(directory.resolve("sym.key")));
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("sym.p12")));
        assertFalse(AuthenticatedEncryption.hasEnvelope(directory.resolve("sig.key")));
    }

    @Test
    void interruptedPrivateKeyMigrationResumesWithLegacyWrapper() throws Exception {
        legacy(null);
        Files.write(directory.resolve("sig.key"), AuthenticatedEncryption.encrypt(SIGNING.getPrivate().getEncoded(), MASTER, "private-key/sig"));
        KeyRing ring = new KeyRing(storage(), null, false);
        assertTrue(ring.isUnlocked());
        assertEquals(MASTER, ring.getSymmetricKey());
        assertEquals(ENCRYPTION.getPublic(), ring.getPubKeyRing().getEncryptionPubKey());
    }

    @Test
    void failedBackupAfterWrapperCommitKeepsRecoverableLegacyKeys() throws Exception {
        legacy(null);
        Files.createDirectory(directory.resolve("sym.key.backup"));
        assertThrows(IllegalStateException.class, () -> new KeyRing(storage(), null, false));
        assertTrue(Files.exists(directory.resolve("sym.p12")));
        assertEquals(MASTER, storage().loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, null));
        Files.delete(directory.resolve("sym.key.backup"));
        assertTrue(new KeyRing(storage(), null, false).isUnlocked());
        assertFalse(Files.exists(directory.resolve("sym.p12")));
    }

    @Test
    void incompleteAccountsNeverRegenerateKeys() throws Exception {
        Files.write(directory.resolve("sig.key"), new byte[]{1, 2, 3});
        assertThrows(IllegalStateException.class, () -> new KeyRing(storage(), null, true));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(directory.resolve("sig.key")));
        assertFalse(Files.exists(directory.resolve("sym.key")));
    }

    @Test
    void damagedCurrentWrapperNeverFallsBackToLegacyOrBackup() throws Exception {
        legacy(null);
        byte[] old = Files.readAllBytes(directory.resolve("sym.p12"));
        new KeyRing(storage(), null, false);
        Files.write(directory.resolve("sym.p12"), old);
        byte[] damaged = Files.readAllBytes(directory.resolve("sym.key"));
        damaged[damaged.length - 1] ^= 1;
        Files.write(directory.resolve("sym.key"), damaged);
        assertFalse(new KeyRing(storage(), null, false).isUnlocked());
        assertArrayEquals(damaged, Files.readAllBytes(directory.resolve("sym.key")));
    }

    @Test
    void badPrivateKeyDoesNotPublishPartialUnlockedStateOrMigrateWrapper() throws Exception {
        legacy(null);
        Files.write(directory.resolve("enc.key"), new byte[]{1});
        KeyRing ring = new KeyRing(new KeyStorage(Files.createDirectory(directory.resolve("empty")).toFile()));
        assertFalse(ring.isUnlocked());
        assertThrows(IllegalStateException.class, () -> new KeyRing(storage(), null, false));
        assertTrue(Files.exists(directory.resolve("sym.p12")));
        assertFalse(Files.exists(directory.resolve("sym.key")));
    }

    @Test
    void plaintextMigrationRunsBeforeRetiringLegacyWrapper() throws Exception {
        legacy(null);
        Path db = Files.createDirectory(directory.resolve("db"));
        protobuf.PersistableEnvelope proto = protobuf.PersistableEnvelope.newBuilder()
                .setNavigationPath(protobuf.NavigationPath.newBuilder().addPath("private/account")) .build();
        try (var out = Files.newOutputStream(db.resolve("Navigation"))) { proto.writeDelimitedTo(out); }
        KeyRing ring = new KeyRing(new KeyStorage(directory.toFile(), db.toFile()), null, false);
        assertTrue(ring.isUnlocked());
        byte[] decrypted = AuthenticatedEncryption.decrypt(Files.readAllBytes(db.resolve("Navigation")), MASTER, LegacyStorageMigration.context("Navigation"));
        assertArrayEquals(proto.toByteArray(), decrypted);
    }

    @Test
    void passwordPrepareSurvivesRestartAndBothPasswordsUnlockSameIdentity() throws Exception {
        KeyStorage storage = storage();
        KeyRing ring = new KeyRing(storage, "old-password", true);
        storage.beginPasswordChange(ring.getSymmetricKey(), "old-password", "new-password");
        KeyRing viaOld = new KeyRing(storage(), "old-password", false);
        KeyRing viaNew = new KeyRing(storage(), "new-password", false);
        assertTrue(viaOld.isUnlocked());
        assertTrue(viaNew.isUnlocked());
        assertEquals(ring.getSymmetricKey(), viaOld.getSymmetricKey());
        assertEquals(ring.getPubKeyRing(), viaNew.getPubKeyRing());
        KeyStorage.PasswordChange change = storage.readPasswordChange(ring.getSymmetricKey());
        assertEquals("old-password", change.getOldPassword());
        assertEquals("new-password", change.getNewPassword());
        assertFalse(change.toString().contains("old-password"));
        storage.completePasswordChange(ring.getSymmetricKey());
        assertFalse(storage.hasPasswordChange());
        assertFalse(Files.exists(directory.resolve("sym.key.next")));
        assertFalse(new KeyRing(storage(), "old-password", false).isUnlocked());
        assertEquals(ring.getPubKeyRing(), new KeyRing(storage(), "new-password", false).getPubKeyRing());
    }

    @Test
    void passwordRemovalAndAdditionRecoverWithNullPasswords() throws Exception {
        KeyStorage storage = storage();
        KeyRing ring = new KeyRing(storage, null, true);
        storage.beginPasswordChange(ring.getSymmetricKey(), null, "new-password");
        assertTrue(new KeyRing(storage(), "new-password", false).isUnlocked());
        storage.completePasswordChange(ring.getSymmetricKey());
        storage.beginPasswordChange(ring.getSymmetricKey(), "new-password", null);
        assertTrue(new KeyRing(storage(), null, false).isUnlocked());
        storage.completePasswordChange(ring.getSymmetricKey());
        assertTrue(new KeyRing(storage(), null, false).isUnlocked());
    }

    @Test
    void invalidKdfProfilesAndOversizedWrappersFailBeforeKdf() throws Exception {
        byte[] wrapper = PasswordKeyEnvelope.wrap(MASTER, null);
        wrapper[8] = 127;
        assertThrows(IllegalArgumentException.class, () -> PasswordKeyEnvelope.unwrap(wrapper, "password"));
        assertThrows(IllegalArgumentException.class, () -> PasswordKeyEnvelope.unwrap(new byte[1024 * 1024], "password"));
    }
    @Test
    void backupOnlyAccountsCannotBeOverwrittenByGeneration() throws Exception {
        Path backup = Files.createDirectories(directory.resolve("backup/backups_sym_p12"));
        Files.write(backup.resolve("only-copy"), new byte[]{1, 2, 3});
        assertThrows(IllegalStateException.class, () -> new KeyRing(storage(), null, true));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(backup.resolve("only-copy")));
        assertFalse(Files.exists(directory.resolve("sym.key")));
    }
    @Test
    void interruptedPrepareBeforePendingWrapperRemainsRecoverableWithOldPassword() throws Exception {
        KeyStorage storage = storage();
        KeyRing ring = new KeyRing(storage, "old-password", true);
        Files.createDirectory(directory.resolve("sym.key.next")); // inject failure at pending-wrapper replacement
        assertThrows(IllegalStateException.class, () -> storage.beginPasswordChange(ring.getSymmetricKey(), "old-password", "new-password"));
        assertTrue(storage.hasPasswordChange());
        KeyRing recovered = new KeyRing(storage(), "old-password", false);
        assertTrue(recovered.isUnlocked());
        assertEquals("new-password", storage.readPasswordChange(recovered.getSymmetricKey()).getNewPassword());
        storage.completePasswordChange(recovered.getSymmetricKey());
        assertTrue(new KeyRing(storage(), "new-password", false).isUnlocked());
        assertFalse(storage.hasPasswordChange());
    }
}
