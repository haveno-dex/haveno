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

package haveno.desktop.app;

import haveno.common.UserThread;
import haveno.common.app.DevEnv;
import haveno.common.config.Config;
import haveno.common.crypto.IncorrectPasswordException;
import haveno.core.api.CoreAccountService;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HavenoAppMainLoginTest {
    private final LinkedBlockingQueue<Runnable> queued = new LinkedBlockingQueue<>();
    private final CountDownLatch releaseLogin = new CountDownLatch(1);
    private final CoreAccountService account = mock(CoreAccountService.class);
    private final HavenoApp application = mock(HavenoApp.class);
    private final TestAppMain main = new TestAppMain();
    private Executor previousExecutor;
    private MockedStatic<Platform> platform;
    private Thread userThread;

    @BeforeEach
    void setUp() throws Exception {
        previousExecutor = UserThread.getExecutor();
        UserThread.setExecutor(queued::add);
        platform = mockStatic(Platform.class);
        userThread = Thread.currentThread();
        when(account.accountExists()).thenReturn(true);
        when(account.isAccountOpen()).thenReturn(true);
        main.configure(account);
        Field field = HavenoAppMain.class.getDeclaredField("application");
        field.setAccessible(true);
        field.set(main, application);
    }

    @AfterEach
    void tearDown() {
        releaseLogin.countDown();
        platform.close();
        UserThread.setExecutor(previousExecutor);
    }

    @Test
    void defaultLoginKeepsUserThreadFreeAndCompletesOnIt() throws Exception {
        CountDownLatch started = blockDefaultLogin();
        CompletableFuture<Boolean> result = main.loginAccount();
        AtomicReference<Thread> completedOn = new AtomicReference<>();
        result.thenRun(() -> completedOn.set(Thread.currentThread()));
        assertTrue(started.await(10, TimeUnit.SECONDS));
        assertFalse(result.isDone());
        verify(application).showLoginProgress();

        CountDownLatch responsive = new CountDownLatch(1);
        UserThread.execute(responsive::countDown);
        runUserCallback();
        assertTrue(responsive.await(1, TimeUnit.SECONDS));
        assertFalse(result.isDone());

        releaseLogin.countDown();
        runUserCallback();
        assertTrue(result.isDone());
        assertTrue(result.join());
        assertSame(userThread, completedOn.get());
        verify(application, never()).showPasswordScreen(any(), any());
    }

    @Test
    void passwordPromptAndSuccessfulRetryStayOnUserThread() throws Exception {
        doThrow(new IncorrectPasswordException("Password required")).when(account).openAccount(null);
        doAnswer(invocation -> {
            assertNotSame(userThread, Thread.currentThread());
            return null;
        }).when(account).openAccount("password");
        CompletableFuture<Boolean> result = main.loginAccount();
        runUserCallback();
        assertFalse(result.isDone());
        ArgumentCaptor<HavenoApp.PasswordHandler> handler = ArgumentCaptor.forClass(HavenoApp.PasswordHandler.class);
        verify(application).showPasswordScreen(handler.capture(), any());
        handler.getValue().onPasswordEntered("password", error -> { });
        runUserCallback();
        assertTrue(result.isDone());
        assertTrue(result.join());
    }

    @Test
    void screenFailureReachesUserThreadExceptionHandling() throws Exception {
        doThrow(new IncorrectPasswordException("Password required")).when(account).openAccount(null);
        IllegalStateException failure = new IllegalStateException("Cannot build password screen");
        doThrow(failure).when(application).showPasswordScreen(any(), any());
        main.loginAccount();
        Runnable callback = queued.poll(10, TimeUnit.SECONDS);
        assertNotNull(callback);
        assertSame(failure, assertThrows(IllegalStateException.class, callback::run));
    }

    @Test
    void loginFailureIsShownBeforeShutdownAndDoesNotPromptForPassword() throws Exception {
        IllegalStateException failure = new IllegalStateException("Damaged identity key");
        doThrow(failure).when(account).openAccount(null);
        CompletableFuture<Boolean> result = main.loginAccount();
        AtomicReference<Thread> completedOn = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        result.whenComplete((opened, error) -> {
            completedOn.set(Thread.currentThread());
            completed.countDown();
        });
        runUserCallback();
        assertFalse(result.isDone());
        ArgumentCaptor<Runnable> quit = ArgumentCaptor.forClass(Runnable.class);
        verify(application).showLoginFailure(eq(failure), quit.capture());
        verify(application, never()).showPasswordScreen(any(), any());
        quit.getValue().run();
        assertTrue(completed.await(10, TimeUnit.SECONDS));
        assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
        assertNotSame(userThread, completedOn.get());
    }

    @Test
    void closingDuringLoginDiscardsItsResult() throws Exception {
        CountDownLatch started = blockDefaultLogin();
        CompletableFuture<Boolean> result = main.loginAccount();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        when(application.isShutDownRequested()).thenReturn(true);
        releaseLogin.countDown();
        runUserCallback();
        assertFalse(result.isDone());
        verify(application, never()).showPasswordScreen(any(), any());
        verify(application, never()).showLoginFailure(any(), any());
    }

    @Test
    void closingBeforeMainViewLoadsPreventsItsCreation() {
        when(application.isShutDownRequested()).thenReturn(true);
        main.startApplication();
        verify(application, never()).startApplication(any());
    }

    @Test
    void firstRunStillWaitsForStartupWizard() throws Exception {
        when(account.accountExists()).thenReturn(false);
        try (MockedStatic<DevEnv> devEnv = mockStatic(DevEnv.class)) {
            devEnv.when(DevEnv::isDevMode).thenReturn(false);
            CompletableFuture<Boolean> result = main.loginAccount();
            runUserCallback();
            verify(application).showStartupWizard(any(), any());
            verify(application, never()).showLoginProgress();
            verify(account, never()).createAccount(any());
            assertFalse(result.isDone());
        }
    }

    private CountDownLatch blockDefaultLogin() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertNotSame(userThread, Thread.currentThread());
            started.countDown();
            assertTrue(releaseLogin.await(10, TimeUnit.SECONDS));
            return null;
        }).when(account).openAccount(null);
        return started;
    }

    private void runUserCallback() throws Exception {
        Runnable callback = queued.poll(10, TimeUnit.SECONDS);
        assertNotNull(callback, "Login did not dispatch a user-thread callback");
        callback.run();
    }

    private static class TestAppMain extends HavenoAppMain {
        void configure(CoreAccountService account) {
            accountService = account;
            config = mock(Config.class);
        }
    }
}
