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

package haveno.core.api;

import com.google.protobuf.ByteString;
import haveno.common.Payload;
import haveno.common.crypto.Encryption;
import haveno.common.crypto.IncorrectPasswordException;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.crypto.ScryptUtil;
import haveno.common.file.FileUtil;
import haveno.common.persistence.PersistenceManager;
import haveno.common.proto.persistable.PersistableEnvelope;
import haveno.common.proto.persistable.PersistablePayload;
import haveno.common.proto.persistable.PersistenceProtoResolver;
import haveno.common.util.Utilities;
import haveno.core.xmr.model.EncryptedConnectionList;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.SecretKey;
import monero.common.MoneroRpcConnection;
import org.bitcoinj.crypto.KeyCrypterScrypt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fault-injection and restart-recovery tests for the transactional password change. Components
 * (wallets, credentials) are simulated by listeners with the same idempotent ensure-semantics as
 * the production handlers: converging to a password the component already holds succeeds.
 */
public class CoreAccountServicePasswordChangeTest {

    private static final String OLD_PASSWORD = "old password 123";
    private static final String NEW_PASSWORD = "new password 456";
    private static final String JOURNAL_FILE = "password_change";
    private static final String PENDING_SYM_FILE = "sym.key.new";

    private File dir;

    private static class ComponentListener extends AccountServiceListener {
        final List<String[]> calls = new ArrayList<>();
        String componentPassword;
        int failuresToInject;

        ComponentListener(String initialPassword) {
            this.componentPassword = initialPassword;
        }

        @Override
        public void onPasswordChanged(String oldPassword, String newPassword) {
            calls.add(new String[]{oldPassword, newPassword});
            if (failuresToInject > 0) {
                failuresToInject--;
                throw new RuntimeException("injected component failure");
            }
            if (Objects.equals(componentPassword, newPassword)) return; // already converged
            if (!Objects.equals(componentPassword, oldPassword)) throw new IllegalStateException("Component is on an unexpected password");
            componentPassword = newPassword;
        }
    }

    @BeforeEach
    public void setup(@TempDir File dir) {
        this.dir = dir;
        PersistenceManager.allServicesInitialized.set(true); // let recovery run immediately
    }

    @AfterEach
    public void tearDown() {
        PersistenceManager.allServicesInitialized.set(false);
    }

    private CoreAccountService createAccount() throws Exception {
        KeyStorage keyStorage = new KeyStorage(dir);
        KeyRing keyRing = new KeyRing(keyStorage, dir, OLD_PASSWORD, true);
        CoreAccountService service = new CoreAccountService(null, keyStorage, keyRing);
        service.openAccount(OLD_PASSWORD);
        return service;
    }

    // Simulates a process restart: fresh key storage, key ring and account service over the same dir.
    private CoreAccountService restart() {
        KeyStorage keyStorage = new KeyStorage(dir);
        return new CoreAccountService(null, keyStorage, new KeyRing(keyStorage, dir, null, false));
    }

    // Recovery runs on a background thread; wait for it to commit (journal removal).
    private void awaitRecoveryCommit() throws InterruptedException {
        for (int i = 0; i < 100 && new File(dir, JOURNAL_FILE).exists(); i++) Thread.sleep(50);
        assertFalse(new File(dir, JOURNAL_FILE).exists(), "recovery did not commit");
    }

    private void interruptChangeMidway(CoreAccountService service) {
        interruptChangeMidway(service, OLD_PASSWORD, NEW_PASSWORD);
    }

    private void interruptChangeMidway(CoreAccountService service, String fromPassword, String toPassword) {
        ComponentListener crashing = new ComponentListener(fromPassword);
        crashing.failuresToInject = 2; // fail the forward pass and the in-process revert, like a crash
        service.addListener(crashing);
        assertThrows(RuntimeException.class, () -> service.changePassword(fromPassword, toPassword));
        service.removeListener(crashing);
        assertTrue(new File(dir, JOURNAL_FILE).exists());
    }

    @Test
    public void testChangePasswordCommitsAndCleansUp() throws Exception {
        CoreAccountService service = createAccount();
        ComponentListener component = new ComponentListener(OLD_PASSWORD);
        service.addListener(component);

        service.changePassword(OLD_PASSWORD, NEW_PASSWORD);

        assertEquals(NEW_PASSWORD, service.getPassword());
        assertEquals(NEW_PASSWORD, component.componentPassword);
        assertFalse(new File(dir, PENDING_SYM_FILE).exists());
        assertFalse(new File(dir, JOURNAL_FILE).exists());

        // only the new password unlocks after commit
        assertThrows(IncorrectPasswordException.class, () -> restart().openAccount(OLD_PASSWORD));
        restart().openAccount(NEW_PASSWORD);
    }

    @Test
    public void testComponentFailureRevertsAndCleansUp() throws Exception {
        CoreAccountService service = createAccount();
        ComponentListener healthy = new ComponentListener(OLD_PASSWORD);
        ComponentListener failing = new ComponentListener(OLD_PASSWORD);
        failing.failuresToInject = 1;
        service.addListener(healthy);
        service.addListener(failing);

        assertThrows(RuntimeException.class, () -> service.changePassword(OLD_PASSWORD, NEW_PASSWORD));

        // the healthy component was converged back to the old password and no journal remains
        assertEquals(OLD_PASSWORD, service.getPassword());
        assertEquals(2, healthy.calls.size());
        assertEquals(OLD_PASSWORD, healthy.componentPassword);
        assertFalse(new File(dir, PENDING_SYM_FILE).exists());
        assertFalse(new File(dir, JOURNAL_FILE).exists());
        restart().openAccount(OLD_PASSWORD);
    }

    @Test
    public void testInterruptedChangeLeavesBothPasswordsUnlockable() throws Exception {
        interruptChangeMidway(createAccount());

        // both wrappers unlock while the journal is pending (checked without triggering recovery)
        new KeyStorage(dir).loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, OLD_PASSWORD);
        new KeyStorage(dir).loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, NEW_PASSWORD);
    }

    @Test
    public void testRecoveryConvergesForwardToNewPassword() throws Exception {
        interruptChangeMidway(createAccount());

        // restart and open with the new password: recovery converges components forward and commits
        CoreAccountService restarted = restart();
        ComponentListener component = new ComponentListener(OLD_PASSWORD); // e.g. a wallet not yet changed
        restarted.addListener(component);
        restarted.openAccount(NEW_PASSWORD);
        awaitRecoveryCommit();

        assertEquals(1, component.calls.size());
        assertEquals(OLD_PASSWORD, component.calls.get(0)[0]);
        assertEquals(NEW_PASSWORD, component.calls.get(0)[1]);
        assertEquals(NEW_PASSWORD, component.componentPassword);
        assertFalse(new File(dir, PENDING_SYM_FILE).exists());

        assertThrows(IncorrectPasswordException.class, () -> restart().openAccount(OLD_PASSWORD));
        restart().openAccount(NEW_PASSWORD);
    }

    @Test
    public void testRecoveryConvergesBackToOldPassword() throws Exception {
        interruptChangeMidway(createAccount());

        // restart and open with the old password: recovery converges components back and commits
        CoreAccountService restarted = restart();
        ComponentListener component = new ComponentListener(NEW_PASSWORD); // e.g. a wallet already changed
        restarted.addListener(component);
        restarted.openAccount(OLD_PASSWORD);
        awaitRecoveryCommit();

        assertEquals(1, component.calls.size());
        assertEquals(NEW_PASSWORD, component.calls.get(0)[0]);
        assertEquals(OLD_PASSWORD, component.calls.get(0)[1]);
        assertEquals(OLD_PASSWORD, component.componentPassword);

        assertThrows(IncorrectPasswordException.class, () -> restart().openAccount(NEW_PASSWORD));
        restart().openAccount(OLD_PASSWORD);
    }

    @Test
    public void testPendingCounterpartExposedForWalletHealing() throws Exception {
        CoreAccountService service = createAccount();
        interruptChangeMidway(service);

        // wallets opened before recovery completes can query the counterpart password
        String[] counterpart = service.getPendingPasswordChangeCounterpart();
        assertEquals(NEW_PASSWORD, counterpart[0]);

        CoreAccountService restarted = restart();
        restarted.openAccount(NEW_PASSWORD);
        awaitRecoveryCommit();
        assertNull(restarted.getPendingPasswordChangeCounterpart());
    }

    @Test
    public void testChangeRejectedWhileRecoveryPending() throws Exception {
        CoreAccountService service = createAccount();
        interruptChangeMidway(service);

        // a new change is refused until the pending one is recovered
        assertThrows(IllegalStateException.class, () -> service.changePassword(OLD_PASSWORD, "another password"));
    }

    @Test
    public void testUnreadableJournalPreservesBothWrappers() throws Exception {
        interruptChangeMidway(createAccount());

        // corrupt the journal so it fails authentication
        File journal = new File(dir, JOURNAL_FILE);
        byte[] bytes = java.nio.file.Files.readAllBytes(journal.toPath());
        bytes[bytes.length - 1] ^= 0x01;
        java.nio.file.Files.write(journal.toPath(), bytes);

        // opening with the new password relies on the pending wrapper; recovery must not delete it
        CoreAccountService restarted = restart();
        restarted.openAccount(NEW_PASSWORD);
        Thread.sleep(1000); // give deferred recovery a chance to run
        assertTrue(new File(dir, JOURNAL_FILE).exists());
        assertTrue(new File(dir, PENDING_SYM_FILE).exists());

        // both passwords still unlock
        new KeyStorage(dir).loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, OLD_PASSWORD);
        new KeyStorage(dir).loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, NEW_PASSWORD);
    }

    @Test
    public void testNonAsciiPasswordChangeSucceeds() throws Exception {
        CoreAccountService service = createAccount();
        ComponentListener component = new ComponentListener(OLD_PASSWORD);
        service.addListener(component);

        String unicodePassword = "pässwörd éè 123";
        service.changePassword(OLD_PASSWORD, unicodePassword);

        assertEquals(unicodePassword, component.componentPassword);
        assertFalse(new File(dir, JOURNAL_FILE).exists());
        restart().openAccount(unicodePassword);
        assertThrows(IncorrectPasswordException.class, () -> restart().openAccount(OLD_PASSWORD));
    }

    @Test
    public void testPasswordCanonicalizedForAllComponents() throws Exception {
        CoreAccountService service = createAccount();
        ComponentListener component = new ComponentListener(OLD_PASSWORD);
        service.addListener(component);

        String nfd = "passwo\u0301rd 123"; // o + combining acute
        String nfc = java.text.Normalizer.normalize(nfd, java.text.Normalizer.Form.NFC);
        assertNotEquals(nfd, nfc);
        service.changePassword(OLD_PASSWORD, nfd);

        // components (wallets, credentials) and account state receive the canonical NFC form
        assertEquals(nfc, component.componentPassword);
        assertEquals(nfc, service.getPassword());

        // either spelling opens the account, both normalizing to the same form
        restart().openAccount(nfc);
        restart().openAccount(nfd);
    }

    @Test
    public void testControlCharacterPasswordRejectedWithoutArtifacts() throws Exception {
        CoreAccountService service = createAccount();

        assertThrows(IllegalStateException.class, () -> service.changePassword(OLD_PASSWORD, "password\nwith newline"));

        // nothing durable was written and a valid change still works
        assertFalse(new File(dir, JOURNAL_FILE).exists());
        assertFalse(new File(dir, PENDING_SYM_FILE).exists());
        service.changePassword(OLD_PASSWORD, NEW_PASSWORD);
    }

    @Test
    public void testCleanupFailureAfterCommitKeepsNewPassword() throws Exception {
        Assumptions.assumeFalse(Utilities.isWindows()); // POSIX permissions
        CoreAccountService service = createAccount();
        File symBackups = new File(dir, "backup/backups_sym_key");
        assertTrue(symBackups.exists());
        assertTrue(symBackups.setReadable(false));
        Assumptions.assumeTrue(symBackups.listFiles() == null, "permissions not enforced (running as root?)");
        try {
            // cleanup fails after the commit point, but the change must still report success
            service.changePassword(OLD_PASSWORD, NEW_PASSWORD);
        } finally {
            assertTrue(symBackups.setReadable(true));
        }
        assertEquals(NEW_PASSWORD, service.getPassword());
        assertTrue(new File(dir, JOURNAL_FILE).exists()); // cleanup pending until recovered

        // only the new password matches durable state; reopening finishes the cleanup
        assertThrows(IncorrectPasswordException.class, () -> restart().openAccount(OLD_PASSWORD));
        restart().openAccount(NEW_PASSWORD);
        awaitRecoveryCommit();
        assertTrue(FileUtil.hasBackups(dir, "sym.key")); // cleanup ends with a verified fresh backup
    }

    @Test
    public void testMissingFreshBackupKeepsChangePending() throws Exception {
        CoreAccountService service = createAccount();
        // a regular file in place of the backup tree makes the fresh wrapper backup impossible
        File backupDir = new File(dir, "backup");
        FileUtil.deleteDirectory(backupDir);
        assertTrue(backupDir.createNewFile());

        service.changePassword(OLD_PASSWORD, NEW_PASSWORD);

        // the change commits, but the transaction stays visibly pending: the pending wrapper and
        // journal must not be cleared while the live sym.key is the only durable copy
        assertEquals(NEW_PASSWORD, service.getPassword());
        assertTrue(new File(dir, JOURNAL_FILE).exists());
        assertTrue(new File(dir, PENDING_SYM_FILE).exists());
        assertFalse(FileUtil.hasBackups(dir, "sym.key"));

        // once backups are possible again, reopening completes cleanup with a verified backup
        assertTrue(backupDir.delete());
        restart().openAccount(NEW_PASSWORD);
        awaitRecoveryCommit();
        assertTrue(FileUtil.hasBackups(dir, "sym.key"));
    }

    @Test
    public void testChangeRejectedBeforeServicesInitialized() throws Exception {
        // wallets and credentials converge through listeners that only all exist once services
        // are initialized; an earlier change would commit with components left on the old password
        CoreAccountService service = createAccount();
        PersistenceManager.allServicesInitialized.set(false);
        try {
            assertThrows(IllegalStateException.class, () -> service.changePassword(OLD_PASSWORD, NEW_PASSWORD));
            assertFalse(new File(dir, JOURNAL_FILE).exists());
            assertFalse(new File(dir, PENDING_SYM_FILE).exists());
        } finally {
            PersistenceManager.allServicesInitialized.set(true);
        }
        service.changePassword(OLD_PASSWORD, NEW_PASSWORD);
    }

    @Test
    public void testAccountPasswordStaysOldDuringChangeNotifications() throws Exception {
        // components must key wallet reopens on the listener's target argument: the account
        // password does not switch until the commit point, and deriving a reopen password from it
        // mid-change would let counterpart healing silently reverse the change
        CoreAccountService service = createAccount();
        List<String> observed = new ArrayList<>();
        service.addListener(new AccountServiceListener() {
            @Override
            public void onPasswordChanged(String oldPassword, String newPassword) {
                observed.add(service.getPassword());
            }
        });

        service.changePassword(OLD_PASSWORD, NEW_PASSWORD);

        assertEquals(List.of(OLD_PASSWORD), observed);
        assertEquals(NEW_PASSWORD, service.getPassword());
    }

    @Test
    public void testCommitFailureRevertsAndCleansUp() throws Exception {
        CoreAccountService service = createAccount();
        ComponentListener component = new ComponentListener(OLD_PASSWORD);
        service.addListener(component);

        // block the commit-point sym.key rewrite (non-empty dir survives the temp cleanup); the
        // components already on the new password must be rolled back instead of leaving a split state
        File blocker = new File(dir, "sym.key.tmp");
        assertTrue(blocker.mkdir());
        File inner = new File(blocker, "inner");
        assertTrue(inner.createNewFile());
        try {
            assertThrows(RuntimeException.class, () -> service.changePassword(OLD_PASSWORD, NEW_PASSWORD));
        } finally {
            assertTrue(inner.delete());
            assertTrue(blocker.delete());
        }

        assertEquals(OLD_PASSWORD, service.getPassword());
        assertEquals(OLD_PASSWORD, component.componentPassword);
        assertFalse(new File(dir, JOURNAL_FILE).exists());
        assertFalse(new File(dir, PENDING_SYM_FILE).exists());
        restart().openAccount(OLD_PASSWORD);

        // once the fault clears the change works
        service.changePassword(OLD_PASSWORD, NEW_PASSWORD);
        assertEquals(NEW_PASSWORD, component.componentPassword);
    }

    @Test
    public void testBackupGuardExcludesPasswordChange() throws Exception {
        CoreAccountService service = createAccount();

        // any backup entry point (API stream or desktop copy) shares this guard
        service.beginBackup();
        assertThrows(IllegalStateException.class, service::beginBackup);
        assertThrows(IllegalStateException.class, () -> service.changePassword(OLD_PASSWORD, NEW_PASSWORD));
        service.endBackup();
        service.changePassword(OLD_PASSWORD, NEW_PASSWORD);

        // and a pending password change blocks new backups until recovered
        CoreAccountService interrupted = createInterrupted();
        assertThrows(IllegalStateException.class, interrupted::beginBackup);
    }

    // An account with an interrupted password change (pending journal), reusing the same dir.
    private CoreAccountService createInterrupted() throws Exception {
        CoreAccountService service = restart();
        service.openAccount(NEW_PASSWORD);
        interruptChangeMidway(service, NEW_PASSWORD, "another password 789");
        return service;
    }

    @Test
    public void testConnectionCredentialsHealedFromCounterpartPassword() throws Exception {
        CoreAccountService service = createAccount();

        // connection list whose credentials the interrupted change already converged to the new password
        PersistenceManager<EncryptedConnectionList> manager = new PersistenceManager<>(dir, CONNECTIONS_RESOLVER, null, null);
        try {
            EncryptedConnectionList connections = new EncryptedConnectionList(manager, service);
            CountDownLatch latch = new CountDownLatch(1);
            connections.readPersisted(latch::countDown);
            assertTrue(latch.await(10, TimeUnit.SECONDS));
            connections.addConnection(new MoneroRpcConnection("http://node:18081", "user", "pass"));
            connections.changePassword(OLD_PASSWORD, NEW_PASSWORD);
            interruptChangeMidway(service); // journal pending; the account stays on the old password
            manager.shutdown(); // deregister so the restarted instance can register

            // a restart reads the credentials with the account password; instead of failing (and
            // deadlocking startup before recovery can run) they converge from the counterpart
            PersistenceManager<EncryptedConnectionList> restartedManager = new PersistenceManager<>(dir, CONNECTIONS_RESOLVER, null, null);
            manager = restartedManager;
            EncryptedConnectionList restarted = new EncryptedConnectionList(restartedManager, service);
            CountDownLatch restartedLatch = new CountDownLatch(1);
            restarted.readPersisted(restartedLatch::countDown);
            assertTrue(restartedLatch.await(10, TimeUnit.SECONDS));
            assertEquals("pass", restarted.getConnections().get(0).getPassword());
            assertEquals("pass", restarted.getConnections().get(0).getPassword()); // stays readable once converged
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void testLegacyConnectionMigratesToV2() throws Exception {
        CoreAccountService service = createAccount();
        KeyCrypterScrypt kcs = ScryptUtil.getKeyCrypterScrypt();
        SecretKey legacyKey = Encryption.getSecretKeyFromBytes(kcs.deriveKey(OLD_PASSWORD).getKey());
        byte[] connSalt = new byte[16];
        java.util.Arrays.fill(connSalt, (byte) 0x55);
        byte[] password = "pass".getBytes(StandardCharsets.UTF_8);
        byte[] salted = new byte[password.length + connSalt.length];
        System.arraycopy(password, 0, salted, 0, password.length);
        System.arraycopy(connSalt, 0, salted, password.length, connSalt.length);
        writeConnectionList(kcs, Encryption.encrypt(salted, legacyKey), connSalt);

        PersistenceManager<EncryptedConnectionList> manager = new PersistenceManager<>(dir, CONNECTIONS_RESOLVER, null, null);
        try {
            EncryptedConnectionList connections = new EncryptedConnectionList(manager, service);
            CountDownLatch latch = new CountDownLatch(1);
            connections.readPersisted(latch::countDown);
            assertTrue(latch.await(10, TimeUnit.SECONDS));
            assertEquals("pass", connections.getConnections().get(0).getPassword());
            assertTrue(Encryption.isV2Format(storedEncryptedPassword(connections)));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void testLegacyConnectionMigrationRejectsUnsaltedPlaintext() throws Exception {
        CoreAccountService service = createAccount();
        KeyCrypterScrypt kcs = ScryptUtil.getKeyCrypterScrypt();
        SecretKey legacyKey = Encryption.getSecretKeyFromBytes(kcs.deriveKey(OLD_PASSWORD).getKey());
        byte[] connSalt = new byte[16];
        java.util.Arrays.fill(connSalt, (byte) 0x55);
        // simulates a wrong-key ECB decrypt that spuriously succeeds: valid padding, no salt
        byte[] legacyBlob = Encryption.encrypt(new byte[24], legacyKey);
        writeConnectionList(kcs, legacyBlob, connSalt);

        PersistenceManager<EncryptedConnectionList> manager = new PersistenceManager<>(dir, CONNECTIONS_RESOLVER, null, null);
        try {
            EncryptedConnectionList connections = new EncryptedConnectionList(manager, service);
            CountDownLatch latch = new CountDownLatch(1);
            connections.readPersisted(latch::countDown);
            assertTrue(latch.await(10, TimeUnit.SECONDS));

            // the value must stay in the legacy format, not be re-sealed as authenticated v2
            assertArrayEquals(legacyBlob, storedEncryptedPassword(connections));
            assertThrows(IllegalArgumentException.class, connections::getConnections);
        } finally {
            manager.shutdown();
        }
    }

    private void writeConnectionList(KeyCrypterScrypt kcs, byte[] encryptedPassword, byte[] encryptionSalt) throws Exception {
        protobuf.PersistableEnvelope envelope = protobuf.PersistableEnvelope.newBuilder()
                .setEncryptedConnectionList(protobuf.EncryptedConnectionList.newBuilder()
                        .setSalt(kcs.getScryptParameters().getSalt())
                        .addItems(protobuf.EncryptedConnection.newBuilder()
                                .setUrl("http://node:18081")
                                .setUsername("user")
                                .setEncryptedPassword(ByteString.copyFrom(encryptedPassword))
                                .setEncryptionSalt(ByteString.copyFrom(encryptionSalt)))
                        .setCurrentConnectionUrl("")
                        .setAutoSwitch(true))
                .build();
        try (FileOutputStream fos = new FileOutputStream(new File(dir, "EncryptedConnectionList"))) {
            envelope.writeDelimitedTo(fos);
        }
    }

    private static byte[] storedEncryptedPassword(EncryptedConnectionList connections) {
        protobuf.PersistableEnvelope proto = (protobuf.PersistableEnvelope) connections.toProtoMessage();
        return proto.getEncryptedConnectionList().getItems(0).getEncryptedPassword().toByteArray();
    }

    private static final PersistenceProtoResolver CONNECTIONS_RESOLVER = new PersistenceProtoResolver() {
        @Override
        public PersistableEnvelope fromProto(protobuf.PersistableEnvelope proto) {
            return EncryptedConnectionList.fromProto(proto.getEncryptedConnectionList());
        }

        @Override
        public Payload fromProto(protobuf.PaymentAccountPayload proto) {
            return null;
        }

        @Override
        public PersistablePayload fromProto(protobuf.PersistableNetworkPayload proto) {
            return null;
        }
    };

    @Test
    public void testMissingLiveWrapperRecoveredFromPendingWrapper() throws Exception {
        interruptChangeMidway(createAccount());

        // a crash during a non-atomic sym.key replacement can lose the live wrapper while the
        // journaled pending wrapper remains; the account must still exist and unlock with the
        // pending wrapper's password, and recovery must recommit the live wrapper
        assertTrue(new File(dir, "sym.key").delete());
        CoreAccountService restarted = restart();
        assertTrue(restarted.accountExists());
        restarted.openAccount(NEW_PASSWORD);
        awaitRecoveryCommit();

        assertTrue(new File(dir, "sym.key").exists());
        assertFalse(new File(dir, PENDING_SYM_FILE).exists());
        restart().openAccount(NEW_PASSWORD);
    }

    @Test
    public void testOrphanPendingWrapperRemovedOnUnlock() throws Exception {
        createAccount();
        // simulate a failed transaction initialization that left a pending wrapper but no journal
        java.nio.file.Files.copy(new File(dir, "sym.key").toPath(), new File(dir, PENDING_SYM_FILE).toPath());

        restart().openAccount(OLD_PASSWORD);
        assertFalse(new File(dir, PENDING_SYM_FILE).exists());
    }

    @Test
    public void testPasswordRemovalAndAddition() throws Exception {
        CoreAccountService service = createAccount();
        ComponentListener component = new ComponentListener(OLD_PASSWORD);
        service.addListener(component);

        service.changePassword(OLD_PASSWORD, null); // remove
        assertNull(service.getPassword());
        assertNull(component.componentPassword);
        restart().openAccount(null);

        service.changePassword(null, NEW_PASSWORD); // add
        assertEquals(NEW_PASSWORD, service.getPassword());
        assertEquals(NEW_PASSWORD, component.componentPassword);
        restart().openAccount(NEW_PASSWORD);
        assertThrows(IncorrectPasswordException.class, () -> restart().openAccount(null));
    }

    @Test
    public void testWalletCreationRejectedDuringPasswordChange() throws Exception {
        CoreAccountService service = createAccount();
        AtomicBoolean rejected = new AtomicBoolean();
        service.addListener(new AccountServiceListener() {
            @Override
            public void onPasswordChanged(String oldPassword, String newPassword) {
                try {
                    service.beginWalletCreation();
                    service.endWalletCreation();
                } catch (IllegalStateException e) {
                    rejected.set(true);
                }
            }
        });
        service.changePassword(OLD_PASSWORD, NEW_PASSWORD);
        assertTrue(rejected.get());

        // creations are accepted again once the transaction resolves
        service.beginWalletCreation();
        service.endWalletCreation();
    }

    @Test
    public void testPasswordChangeRejectedDuringWalletCreation() throws Exception {
        CoreAccountService service = createAccount();
        service.beginWalletCreation();
        assertThrows(IllegalStateException.class, () -> service.changePassword(OLD_PASSWORD, NEW_PASSWORD));
        assertFalse(new File(dir, JOURNAL_FILE).exists()); // refused before any durable mutation
        service.endWalletCreation();

        service.changePassword(OLD_PASSWORD, NEW_PASSWORD);
        assertEquals(NEW_PASSWORD, service.getPassword());
    }
}
