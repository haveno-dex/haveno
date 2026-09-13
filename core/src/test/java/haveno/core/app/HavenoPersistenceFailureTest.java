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

package haveno.core.app;

import haveno.common.UserThread;
import haveno.common.app.AppModule;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.AuthenticatedEncryption;
import haveno.common.crypto.KeyStorage;
import haveno.common.file.CorruptedStorageFileHandler;
import haveno.common.persistence.PersistenceManager;
import haveno.common.proto.persistable.NavigationPath;
import haveno.common.setup.CommonSetup;
import haveno.core.setup.CorePersistedDataHost;
import haveno.network.p2p.mailbox.IgnoredMailboxMap;
import haveno.network.p2p.mailbox.IgnoredMailboxService;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

class HavenoPersistenceFailureTest {
    @TempDir java.nio.file.Path dir;

    @Test
    void concurrentStartupWarningsKeepConsistentSnapshots() throws Exception {
        CorruptedStorageFileHandler handler = new CorruptedStorageFileHandler();
        for (int i = 0; i < 1000; i++) handler.addFile("cache");
        List<String> snapshot = handler.getFiles().orElseThrow();
        var workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var generic = workers.submit(() -> {
                start.await();
                for (int i = 0; i < 1000; i++) handler.addFile("cache");
                return null;
            });
            var preserved = workers.submit(() -> {
                start.await();
                for (int i = 0; i < 1000; i++) handler.addPreservedFile("ClosedTrades");
                return null;
            });
            start.countDown();
            generic.get(15, TimeUnit.SECONDS);
            preserved.get(15, TimeUnit.SECONDS);
            assertEquals(1000, snapshot.size());
            assertEquals(2000, handler.getFiles().orElseThrow().size());
            assertEquals(List.of("ClosedTrades"), handler.getPreservedFiles().orElseThrow());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void unreadableIgnoredMailboxCacheDoesNotFailStartup() throws Exception {
        Executor previousExecutor = UserThread.getExecutor();
        UserThread.setExecutor(Runnable::run);
        KeyRing keyRing = new KeyRing(new KeyStorage(dir.toFile()), null, true);
        CorruptedStorageFileHandler corrupted = new CorruptedStorageFileHandler();
        PersistenceManager<IgnoredMailboxMap> persistence = new PersistenceManager<>(dir.toFile(), null, corrupted, keyRing);
        try {
            IgnoredMailboxService service = new IgnoredMailboxService(persistence);
            String filename = new IgnoredMailboxMap().getDefaultStorageFileName();
            byte[] damaged = {1, 2, 3};
            Files.write(dir.resolve(filename), damaged);
            CountDownLatch done = new CountDownLatch(1);
            AtomicBoolean completed = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            PersistenceManager.setReadFailureHandler(error -> { failure.set(error); done.countDown(); });

            service.readPersisted(() -> { completed.set(true); done.countDown(); });

            assertTrue(done.await(15, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertTrue(completed.get());
            assertFalse(Files.exists(dir.resolve(filename)));
            assertTrue(corrupted.getFiles().orElseThrow().contains(filename));
            try (var backups = Files.walk(dir.resolve("backup_of_corrupted_data"))) {
                var backup = backups.filter(Files::isRegularFile).findFirst().orElseThrow();
                assertArrayEquals(damaged, Files.readAllBytes(backup));
            }
            service.ignore("retry", System.currentTimeMillis());
            assertTrue(service.isIgnored("retry"));
            CountDownLatch written = new CountDownLatch(1);
            persistence.forcePersistNow(written::countDown, error -> { failure.set(error); written.countDown(); });
            assertTrue(written.await(15, TimeUnit.SECONDS));
            assertNull(failure.get());
            byte[] rebuilt = AuthenticatedEncryption.decrypt(Files.readAllBytes(dir.resolve(filename)),
                    keyRing.getSymmetricKey(), "store/" + filename);
            assertTrue(protobuf.PersistableEnvelope.parseFrom(rebuilt).getIgnoredMailboxMap().getDataMap().containsKey("retry"));
        } finally {
            persistence.shutdown();
            PersistenceManager.setReadFailureHandler(null);
            UserThread.setExecutor(previousExecutor);
        }
    }

    @Test
    void failedStartupReportsFailureExitsNonzeroAndDoesNotFlush() {
        Executor previousExecutor = UserThread.getExecutor();
        UserThread.setExecutor(Runnable::run);
        try (var hosts = mockStatic(CorePersistedDataHost.class);
             var persistence = mockStatic(PersistenceManager.class);
             var setup = mockStatic(CommonSetup.class)) {
            hosts.when(() -> CorePersistedDataHost.getPersistedDataHosts(null)).thenReturn(new ArrayList<>());
            TestExecutable executable = new TestExecutable();
            IllegalStateException failure = new IllegalStateException("Cannot read required account store");
            AtomicBoolean completed = new AtomicBoolean();
            executable.readAllPersisted(List.of(done -> { throw failure; }), () -> completed.set(true));
            assertSame(failure, executable.reportedFailure);
            assertFalse(completed.get());
            setup.verify(() -> CommonSetup.exitAfter(HavenoExecutable.EXIT_FAILURE, 100, TimeUnit.MILLISECONDS));
            persistence.verify(() -> PersistenceManager.flushAllDataToDiskAtShutdown(any()), never());
        } finally {
            UserThread.setExecutor(previousExecutor);
        }
    }

    @Test
    void completedStartupDoesNotHandleLaterImportCallbackFailures() throws Exception {
        Executor previousExecutor = UserThread.getExecutor();
        LinkedBlockingQueue<Runnable> queued = new LinkedBlockingQueue<>();
        UserThread.setExecutor(queued::add);
        PersistenceManager<NavigationPath> imports = new PersistenceManager<>(dir.toFile(), null, null, null);
        try (var hosts = mockStatic(CorePersistedDataHost.class)) {
            hosts.when(() -> CorePersistedDataHost.getPersistedDataHosts(null)).thenReturn(new ArrayList<>());
            TestExecutable executable = new TestExecutable();
            AtomicBoolean completed = new AtomicBoolean();
            executable.readAllPersisted(List.of(Runnable::run), () -> completed.set(true));
            queued.remove().run();
            assertTrue(completed.get());

            IllegalStateException failure = new IllegalStateException("Import callback failed");
            imports.readPersisted("MissingImport", value -> { }, () -> { throw failure; });
            Runnable callback = queued.poll(15, TimeUnit.SECONDS);
            assertTrue(callback != null, "Import callback was not dispatched");
            assertSame(failure, assertThrows(IllegalStateException.class, callback::run));
            assertNull(executable.reportedFailure);
            assertTrue(queued.isEmpty());
        } finally {
            imports.shutdown();
            PersistenceManager.setReadFailureHandler(null);
            UserThread.setExecutor(previousExecutor);
        }
    }

    private static class TestExecutable extends HavenoExecutable {
        private Throwable reportedFailure;

        private TestExecutable() {
            super("test", "test", "test", "test");
        }

        @Override protected void configUserThread() { }
        @Override protected void launchApplication() { }
        @Override protected AppModule getModule() { return null; }
        @Override protected void startApplication() { }
        @Override public void onSetupComplete() { }

        @Override
        protected void onPersistenceReadFailure(Throwable failure) {
            reportedFailure = failure;
            super.onPersistenceReadFailure(failure);
        }
    }
}
