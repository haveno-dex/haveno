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
import haveno.common.persistence.PersistenceManager;
import haveno.common.proto.persistable.NavigationPath;
import haveno.common.setup.CommonSetup;
import haveno.core.setup.CorePersistedDataHost;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
