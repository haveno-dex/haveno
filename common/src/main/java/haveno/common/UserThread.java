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
 * You should have received a copy of the GNU Affero General Public
 * License along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.common;

import com.google.common.util.concurrent.MoreExecutors;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Defines which thread is used as the user thread. The user thread is the main thread in the single-threaded context.
 * For JavaFX it is usually the Platform::RunLater executor, for a headless application it is any single-threaded
 * executor. Additionally sets a timer class so JavaFX and headless applications can set different timers (UITimer for JavaFX
 * otherwise we use the default FrameRateTimer).
 * <p>
 * Provides also methods for delayed and periodic executions.
 */
@Slf4j
public class UserThread {
    private static Class<? extends Timer> timerClass;
    @Getter
    @Setter
    private static Executor executor;
    private static Thread USER_THREAD;

    static {
        executor = MoreExecutors.directExecutor();
        timerClass = FrameRateTimer.class;
    }

    public static void setTimerClass(Class<? extends Timer> timerClass) {
        UserThread.timerClass = timerClass;
    }

    public static void execute(Runnable command) {
        if (command == null) {
            throw new IllegalArgumentException("Command must not be null.");
        }
        executor.execute(() -> {
            synchronized (executor) {
                USER_THREAD = Thread.currentThread();
                try {
                    command.run();
                } catch (Exception e) {
                    log.error("Error executing command: ", e);
                }
            }
        });
    }

    public static void await(Runnable command) {
        if (command == null) {
            throw new IllegalArgumentException("Command must not be null.");
        }
        if (isUserThread(Thread.currentThread())) {
            command.run();
        } else {
            CountDownLatch latch = new CountDownLatch(1);
            execute(() -> {
                try {
                    command.run();
                } catch (Exception e) {
                    log.error("Error executing await command: ", e);
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupt status
                log.error("Await was interrupted", e);
            }
        }
    }

    public static boolean isUserThread(Thread thread) {
        return thread == USER_THREAD;
    }

    public static Timer runAfterRandomDelay(Runnable runnable, long minDelayInSec, long maxDelayInSec) {
        return runAfterRandomDelay(runnable, minDelayInSec, maxDelayInSec, TimeUnit.SECONDS);
    }

    public static Timer runAfterRandomDelay(Runnable runnable, long minDelay, long maxDelay, TimeUnit timeUnit) {
        validateDelayParameters(minDelay, maxDelay);
        long randomDelay = new Random().nextLong(maxDelay - minDelay) + minDelay;
        return runAfter(runnable, randomDelay, timeUnit);
    }

    public static Timer runAfter(Runnable runnable, long delayInSec) {
        return runAfter(runnable, delayInSec, TimeUnit.SECONDS);
    }

    public static Timer runAfter(Runnable runnable, long delay, TimeUnit timeUnit) {
        if (runnable == null || delay < 0 || timeUnit == null) {
            throw new IllegalArgumentException("Runnable must not be null, delay must be non-negative, and timeUnit must not be null.");
        }
        return getTimer().runLater(Duration.ofMillis(timeUnit.toMillis(delay)), () -> execute(runnable));
    }

    public static Timer runPeriodically(Runnable runnable, long intervalInSec) {
        return runPeriodically(runnable, intervalInSec, TimeUnit.SECONDS);
    }

    public static Timer runPeriodically(Runnable runnable, long interval, TimeUnit timeUnit) {
        if (runnable == null || interval < 0 || timeUnit == null) {
            throw new IllegalArgumentException("Runnable must not be null, interval must be non-negative, and timeUnit must not be null.");
        }
        return getTimer().runPeriodically(Duration.ofMillis(timeUnit.toMillis(interval)), () -> execute(runnable));
    }

    private static Timer getTimer() {
        try {
            return timerClass.getDeclaredConstructor().newInstance();
        } catch (InstantiationException | NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            String message = "Could not instantiate timer timerClass=" + timerClass;
            log.error(message, e);
        }
    }

    private static void validateDelayParameters(long minDelay, long maxDelay) {
        if (minDelay < 0 || maxDelay < 0 || minDelay > maxDelay) {
            throw new IllegalArgumentException("Min and max delays must be non-negative and min must be less than or equal to max.");
        }
    }
}
