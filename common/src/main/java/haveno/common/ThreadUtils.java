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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ThreadUtils {
    
    private static final Map<String, ExecutorService> EXECUTORS = new HashMap<>();
    private static final Map<String, Thread> THREAD_BY_ID = new HashMap<>();
    private static final int POOL_SIZE = 10;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(POOL_SIZE);


    public static void execute(Runnable command, String threadId) {
        synchronized (EXECUTORS) {
            if (!EXECUTORS.containsKey(threadId)) EXECUTORS.put(threadId, Executors.newFixedThreadPool(1));
            EXECUTORS.get(threadId).execute(() -> {
                synchronized (THREAD_BY_ID) {
                    THREAD_BY_ID.put(threadId, Thread.currentThread());
                }
                command.run();
            });
        }
    }

    public static void await(Runnable command, String threadId) {
        if (isCurrentThread(Thread.currentThread(), threadId)) {
            command.run();
        } else {
            CountDownLatch latch = new CountDownLatch(1);
            execute(command, threadId); // run task
            execute(() -> latch.countDown(), threadId); // await next tick
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static void shutDown(String threadId, long timeoutMs) {
            ExecutorService pool = null;
            synchronized (EXECUTORS) {
                if (!EXECUTORS.containsKey(threadId)) return; // thread not found
                pool = EXECUTORS.get(threadId);
            }
            pool.shutdown();
            try {
                if (!pool.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) pool.shutdownNow();
            } catch (InterruptedException e) {
                pool.shutdownNow();
                throw new RuntimeException(e);
            } finally {
                synchronized (EXECUTORS) {
                    EXECUTORS.remove(threadId);
                }
                synchronized (THREAD_BY_ID) {
                    THREAD_BY_ID.remove(threadId);
                }
            }
    }

    public static Future<?> submitToPool(Runnable task) {
        return submitToPool(Arrays.asList(task)).get(0);
    }

    public static List<Future<?>> submitToPool(List<Runnable> tasks) {
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable task : tasks) futures.add(POOL.submit(task));
        return futures;
    }

    // TODO: these are unused; remove? use monero-java awaitTasks() when updated

    public static Future<?> awaitTask(Runnable task) {
        return awaitTasks(Arrays.asList(task)).get(0);
    }

    public static List<Future<?>> awaitTasks(Collection<Runnable> tasks) {
        return awaitTasks(tasks, tasks.size());
    }

    public static List<Future<?>> awaitTasks(Collection<Runnable> tasks, int maxConcurrency) {
        return awaitTasks(tasks, maxConcurrency, null);
    }

    public static List<Future<?>> awaitTasks(Collection<Runnable> tasks, int maxConcurrency, Long timeoutSeconds) {
        List<Future<?>> futures = new ArrayList<>();
        if (tasks.isEmpty()) return futures;
        ExecutorService pool = Executors.newFixedThreadPool(maxConcurrency);
        for (Runnable task : tasks) futures.add(pool.submit(task));
        pool.shutdown();

        // interrupt after timeout
        if (timeoutSeconds != null) {
            try {
                if (!pool.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) pool.shutdownNow();
            } catch (InterruptedException e) {
                pool.shutdownNow();
                throw new RuntimeException(e);
            }
        }

        // throw exception from any tasks
        try {
            for (Future<?> future : futures) future.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return futures;
    }

    private static boolean isCurrentThread(Thread thread, String threadId) {
        synchronized (THREAD_BY_ID) {
            if (!THREAD_BY_ID.containsKey(threadId)) return false;
            return thread == THREAD_BY_ID.get(threadId);
        }
    }
}
