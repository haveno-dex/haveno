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

package haveno.common.crypto;

import haveno.common.file.FileUtil;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class KeyStorageTest {

    private static final String PASSWORD = "correct horse battery";

    @Test
    public void testGenerateAndReopenWithPassword(@TempDir File dir) throws Exception {
        KeyStorage keyStorage = new KeyStorage(dir);
        KeyRing keyRing = new KeyRing(keyStorage, PASSWORD, true);
        assertTrue(keyRing.isUnlocked());

        // new accounts are written in the v2 format only
        assertTrue(new File(dir, "sym.key").exists());
        assertFalse(new File(dir, "sym.p12").exists());
        assertTrue(Encryption.isV2Format(Files.readAllBytes(new File(dir, "sig.key").toPath())));
        assertTrue(Encryption.isV2Format(Files.readAllBytes(new File(dir, "enc.key").toPath())));

        KeyRing reopened = new KeyRing(new KeyStorage(dir), PASSWORD, false);
        assertTrue(reopened.isUnlocked());
        assertEquals(keyRing.getPubKeyRing(), reopened.getPubKeyRing());
        assertArrayEquals(keyRing.getSymmetricKey().getEncoded(), reopened.getSymmetricKey().getEncoded());
    }

    @Test
    public void testCorruptSymKeyRecoveredFromBackup(@TempDir File dir) throws Exception {
        KeyRing keyRing = new KeyRing(new KeyStorage(dir), PASSWORD, true);
        byte[] symKeyBytes = keyRing.getSymmetricKey().getEncoded();
        assertFalse(FileUtil.getBackupFiles(dir, "sym.key").isEmpty());

        // flip a byte inside the wrapped blob; unlock must recover from the backup, not report
        // an incorrect password
        File symFile = new File(dir, "sym.key");
        byte[] corrupt = Files.readAllBytes(symFile.toPath());
        corrupt[corrupt.length - 1] ^= 0x01;
        Files.write(symFile.toPath(), corrupt);

        KeyRing recovered = new KeyRing(new KeyStorage(dir), PASSWORD, false);
        assertTrue(recovered.isUnlocked());
        assertArrayEquals(symKeyBytes, recovered.getSymmetricKey().getEncoded());
        assertFalse(Arrays.equals(corrupt, Files.readAllBytes(symFile.toPath()))); // live file restored

        // structural corruption (truncation) recovers too, not only authentication failures
        byte[] good = Files.readAllBytes(symFile.toPath());
        Files.write(symFile.toPath(), Arrays.copyOf(good, 74));
        KeyRing recoveredAgain = new KeyRing(new KeyStorage(dir), PASSWORD, false);
        assertArrayEquals(symKeyBytes, recoveredAgain.getSymmetricKey().getEncoded());

        // a wrong password still fails with backups present
        assertThrows(IncorrectPasswordException.class,
                () -> new KeyStorage(dir).loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, "wrong password"));
    }

    @Test
    public void testFreshAccountPreservesAnotherAccountsKeyMaterial(@TempDir File dir) throws Exception {
        // an account whose sig.key was lost (e.g. crash, av quarantine) becomes unopenable and
        // triggers a silent fresh account creation; the lost account's surviving live files and
        // backups may be its only remaining key copies, so they move to a lost_account folder
        // where the new account's saves, password changes and backup rotation can never purge them
        KeyRing lostKeyRing = new KeyRing(new KeyStorage(dir), PASSWORD, true);
        byte[] lostSymKey = lostKeyRing.getSymmetricKey().getEncoded();
        assertFalse(FileUtil.getBackupFiles(dir, "sym.key").isEmpty());
        assertTrue(new File(dir, "sig.key").delete());
        assertFalse(new KeyStorage(dir).allKeyFilesExist());

        new KeyRing(new KeyStorage(dir), PASSWORD, true);
        File[] preserved = dir.listFiles((d, name) -> name.startsWith("lost_account_"));
        assertNotNull(preserved);
        assertEquals(1, preserved.length);
        // the lost account's live wrapper still unlocks from the preserved folder
        KeyStorage preservedStorage = new KeyStorage(preserved[0]);
        assertArrayEquals(lostSymKey, preservedStorage.loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, PASSWORD).getEncoded());
        assertTrue(new File(preserved[0], "backup").isDirectory());
        // the fresh account works and has its own backups
        assertTrue(new KeyRing(new KeyStorage(dir), PASSWORD, false).isUnlocked());
        assertFalse(FileUtil.getBackupFiles(dir, "sym.key").isEmpty());
    }

    @Test
    public void testWrongPasswordThrows(@TempDir File dir) throws Exception {
        new KeyRing(new KeyStorage(dir), PASSWORD, true);
        KeyStorage keyStorage = new KeyStorage(dir);
        assertThrows(IncorrectPasswordException.class,
                () -> keyStorage.loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, "wrong password"));
        assertThrows(IncorrectPasswordException.class,
                () -> keyStorage.loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, null));
    }

    @Test
    public void testNullPasswordAccount(@TempDir File dir) {
        KeyRing keyRing = new KeyRing(new KeyStorage(dir), null, true);
        assertTrue(keyRing.isUnlocked());
        KeyRing reopened = new KeyRing(new KeyStorage(dir), null, false);
        assertTrue(reopened.isUnlocked());
        assertEquals(keyRing.getPubKeyRing(), reopened.getPubKeyRing());
    }

    @Test
    public void testCorruptKeyFileIsNotReportedAsIncorrectPassword(@TempDir File dir) throws Exception {
        new KeyRing(new KeyStorage(dir), PASSWORD, true);
        // remove the backups so recovery cannot mask the classification
        FileUtil.deleteRollingBackup(dir, "sym.key");
        File symFile = new File(dir, "sym.key");
        byte[] bytes = Files.readAllBytes(symFile.toPath());
        // keep the header but mangle the wrapped blob below its minimum valid length
        Files.write(symFile.toPath(), java.util.Arrays.copyOf(bytes, 74));
        KeyStorage keyStorage = new KeyStorage(dir);
        // must surface as corruption (RuntimeException), not IncorrectPasswordException (a checked exception)
        assertThrows(RuntimeException.class,
                () -> keyStorage.loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, PASSWORD));
    }

    @Test
    public void testBackupsSurviveFailedUnlockAttempts(@TempDir File dir) throws Exception {
        new KeyRing(new KeyStorage(dir), PASSWORD, true);
        // saving keeps a fresh backup so the wrapped key never exists as a single copy
        File backupDir = new File(dir, "backup/backups_sym_key");
        assertEquals(1, backupDir.listFiles().length);

        // failed unlock attempts must not rotate more copies into the backups (a corrupt key file
        // could otherwise churn out every good backup over repeated password retries)
        KeyStorage keyStorage = new KeyStorage(dir);
        for (int i = 0; i < 3; i++) {
            assertThrows(IncorrectPasswordException.class,
                    () -> keyStorage.loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, "wrong password"));
        }
        assertEquals(1, backupDir.listFiles().length);
    }

    @Test
    public void testUnicodePasswordIsNormalized(@TempDir File dir) throws Exception {
        // NFD (e + combining acute) and NFC (precomposed) forms must derive the same key
        String nfd = "cafe\u0301 password";
        String nfc = "caf\u00e9 password";
        new KeyRing(new KeyStorage(dir), nfd, true);

        KeyRing reopened = new KeyRing(new KeyStorage(dir), nfc, false);
        assertTrue(reopened.isUnlocked());
    }

    @Test
    public void testTamperedKdfHeaderIsRejectedBeforeDerivation(@TempDir File dir) throws Exception {
        new KeyRing(new KeyStorage(dir), PASSWORD, true);
        // remove the backups so recovery cannot mask the rejection
        FileUtil.deleteRollingBackup(dir, "sym.key");

        // inflate the mem cost field (offset 6: magic 4 + version 1 + kdf 1) far beyond the bound
        File symFile = new File(dir, "sym.key");
        byte[] bytes = Files.readAllBytes(symFile.toPath());
        ByteBuffer.wrap(bytes).putInt(6, Integer.MAX_VALUE);
        Files.write(symFile.toPath(), bytes);

        KeyStorage keyStorage = new KeyStorage(dir);
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> keyStorage.loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, PASSWORD));
        assertTrue(e.getCause().getMessage().contains("KDF parameters out of bounds"));
    }

    @Test
    public void testOversizedKeyFileIsRejectedBeforeReading(@TempDir File dir) throws Exception {
        new KeyRing(new KeyStorage(dir), PASSWORD, true);
        // remove the backups so recovery cannot mask the rejection
        FileUtil.deleteRollingBackup(dir, "sym.key");

        File symFile = new File(dir, "sym.key");
        byte[] padded = Arrays.copyOf(Files.readAllBytes(symFile.toPath()), 64 * 1024);
        Files.write(symFile.toPath(), padded);

        KeyStorage keyStorage = new KeyStorage(dir);
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> keyStorage.loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, PASSWORD));
        assertTrue(e.getCause().getMessage().contains("too large"));
    }

    @Test
    public void testLegacyFormatMigratesOnUnlock(@TempDir File dir) throws Exception {
        // write a keyring in the legacy format: PKCS#12 sym.p12 + AES-ECB-with-hmac key files
        SecretKey symKey = Encryption.generateSecretKey(256);
        var sigPair = Sig.generateKeyPair();
        var encPair = Encryption.generateKeyPair();

        char[] passwordChars = PASSWORD.toCharArray();
        KeyStore p12 = KeyStore.getInstance("PKCS12");
        p12.load(null, null);
        p12.setKeyEntry("sym", symKey, passwordChars, null);
        try (FileOutputStream fos = new FileOutputStream(new File(dir, "sym.p12"))) {
            p12.store(fos, passwordChars);
        }
        writeLegacyKeyFile(dir, "sig.key", sigPair.getPrivate().getEncoded(), symKey);
        writeLegacyKeyFile(dir, "enc.key", encPair.getPrivate().getEncoded(), symKey);

        // unlocking migrates to v2 and removes the PKCS#12 file
        KeyRing keyRing = new KeyRing(new KeyStorage(dir), PASSWORD, false);
        assertTrue(keyRing.isUnlocked());
        assertArrayEquals(symKey.getEncoded(), keyRing.getSymmetricKey().getEncoded());
        assertEquals(sigPair.getPublic(), keyRing.getSignatureKeyPair().getPublic());
        assertEquals(encPair.getPublic(), keyRing.getEncryptionKeyPair().getPublic());
        assertTrue(new File(dir, "sym.key").exists());
        assertFalse(new File(dir, "sym.p12").exists());
        assertTrue(Encryption.isV2Format(Files.readAllBytes(new File(dir, "sig.key").toPath())));
        assertTrue(Encryption.isV2Format(Files.readAllBytes(new File(dir, "enc.key").toPath())));

        // legacy-format backups of the key files are replaced by v2 backups
        for (String name : new String[]{"sig.key", "enc.key"}) {
            List<File> backups = FileUtil.getBackupFiles(dir, name);
            assertFalse(backups.isEmpty());
            for (File backup : backups) assertTrue(Encryption.isV2Format(Files.readAllBytes(backup.toPath())));
        }

        // and the migrated keyring reopens with the same keys
        KeyRing reopened = new KeyRing(new KeyStorage(dir), PASSWORD, false);
        assertTrue(reopened.isUnlocked());
        assertEquals(keyRing.getPubKeyRing(), reopened.getPubKeyRing());
        assertArrayEquals(symKey.getEncoded(), reopened.getSymmetricKey().getEncoded());
    }

    private static void writeLegacyKeyFile(File dir, String fileName, byte[] privateKeyEncoded, SecretKey symKey) throws Exception {
        byte[] pkcs8 = new PKCS8EncodedKeySpec(privateKeyEncoded).getEncoded();
        try (FileOutputStream fos = new FileOutputStream(new File(dir, fileName))) {
            fos.write(Encryption.encryptPayloadWithHmac(pkcs8, symKey));
        }
    }
}
