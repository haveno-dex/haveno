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

package haveno.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserThreadTest {
    private ExecutorService userExecutor;
    private Executor previousExecutor;

    @BeforeEach
    void setUp() throws InterruptedException {
        userExecutor = Executors.newSingleThreadExecutor();
        previousExecutor = UserThread.getExecutor();
        UserThread.setExecutor(userExecutor);
        // bind the user thread identity to the fresh executor so await never runs inline on the test thread
        CountDownLatch bound = new CountDownLatch(1);
        UserThread.execute(bound::countDown);
        bound.await();
    }

    @AfterEach
    void tearDown() {
        UserThread.setExecutor(previousExecutor);
        userExecutor.shutdownNow();
    }

    @Test
    void awaitRethrowsCommandFailureOnCaller() {
        IllegalStateException failure = new IllegalStateException("boom");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> UserThread.await(() -> {
            throw failure;
        })));

        // the user thread survives the failure and a nested await from it runs inline
        AtomicReference<Thread> nested = new AtomicReference<>();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                UserThread.await(() -> UserThread.await(() -> nested.set(Thread.currentThread()))));
        assertTrue(UserThread.isUserThread(nested.get()));
    }

    @Test
    void awaitWrapsCheckedFailureOnCaller() {
        Exception failure = new Exception("boom");
        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertSame(failure, assertThrows(RuntimeException.class, () ->
                        UserThread.await(() -> UserThreadTest.<RuntimeException>throwUnchecked(failure))).getCause()));
    }

    @Test
    void awaitKeepsInterruptFlag() {
        Thread.currentThread().interrupt();
        assertThrows(RuntimeException.class, () -> UserThread.await(() -> {
        }));
        assertTrue(Thread.interrupted());
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }
}
