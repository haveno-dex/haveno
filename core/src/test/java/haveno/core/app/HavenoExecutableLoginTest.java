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

package haveno.core.app;

import com.google.inject.Injector;
import haveno.common.app.AppModule;
import haveno.common.setup.CommonSetup;
import haveno.core.api.AccountServiceListener;
import haveno.core.api.CoreAccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HavenoExecutableLoginTest {
    private final TestExecutable executable = new TestExecutable();
    private MockedStatic<CommonSetup> commonSetup;
    private MockedStatic<HavenoSetup> havenoSetup;

    @BeforeEach
    void setUp() {
        commonSetup = mockStatic(CommonSetup.class);
        havenoSetup = mockStatic(HavenoSetup.class);
        executable.onApplicationLaunched();
    }

    @AfterEach
    void tearDown() {
        havenoSetup.close();
        commonSetup.close();
    }

    @Test
    void loginMustFinishBeforeReadingAndStarting() {
        assertEquals(0, executable.reads);
        executable.login.complete(true);
        assertEquals(1, executable.reads);
        assertEquals(0, executable.starts);
        executable.completeRead.run();
        assertEquals(1, executable.starts);
    }

    @Test
    void shutdownDuringLoginPreventsPersistenceReadsAndStartup() {
        executable.requestShutdown();
        executable.login.complete(true);
        assertEquals(0, executable.reads);
        assertEquals(0, executable.starts);
    }

    @Test
    void shutdownDuringPersistencePreventsStartup() {
        executable.login.complete(true);
        executable.requestShutdown();
        executable.completeRead.run();
        assertEquals(0, executable.starts);
    }

    @Test
    void restartDuringLoginPreventsPersistenceReadsAndStartup() {
        ArgumentCaptor<AccountServiceListener> listener = ArgumentCaptor.forClass(AccountServiceListener.class);
        verify(executable.accountService).addListener(listener.capture());
        listener.getValue().onAccountDeleted(null);
        executable.login.complete(true);
        assertEquals(0, executable.reads);
        assertEquals(0, executable.starts);
    }

    private static class TestExecutable extends HavenoExecutable {
        private final CompletableFuture<Boolean> login = new CompletableFuture<>();
        private Runnable completeRead;
        private int reads;
        private int starts;

        TestExecutable() {
            super("test", "test", "test", "test");
        }

        void requestShutdown() {
            beginGracefulShutDown(null);
        }

        @Override protected void configUserThread() { }
        @Override protected void launchApplication() { }
        @Override protected AppModule getModule() { return null; }
        @Override protected void setupAvoidStandbyMode() { }
        @Override public void onSetupComplete() { }
        @Override protected CompletableFuture<Boolean> loginAccount() { return login; }
        @Override protected void startApplication() { starts++; }

        @Override
        protected void setupGuice() {
            injector = mock(Injector.class);
            when(injector.getInstance(CoreAccountService.class)).thenReturn(mock(CoreAccountService.class));
        }

        @Override
        protected void readAllPersisted(Runnable completeHandler) {
            reads++;
            completeRead = completeHandler;
        }
    }
}
