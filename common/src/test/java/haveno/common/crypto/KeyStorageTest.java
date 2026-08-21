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

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.spec.PKCS8EncodedKeySpec;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
