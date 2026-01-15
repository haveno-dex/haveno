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

package haveno.common;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ThreadUtils {
    
    private static final Map<String, ExecutorService> EXECUTORS = new HashMap<>();
    private static final Map<String, Thread> THREADS = new HashMap<>();
    private static final int POOL_SIZE = 10;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(POOL_SIZE);


    /**
     * Execute the given command in a thread with the given id.
     * 
     * @param command the command to execute
     * @param threadId the thread id
     */
    public static Future<?> execute(Runnable command, String threadId) {
        synchronized (EXECUTORS) {
            if (!EXECUTORS.containsKey(threadId)) EXECUTORS.put(threadId, Executors.newFixedThreadPool(1));
            ExecutorService executor = EXECUTORS.get(threadId);
            if (executor.isShutdown()) throw new IllegalStateException("Cannot execute thread because it's shut down: " + threadId);
            return EXECUTORS.get(threadId).submit(() -> {
                synchronized (THREADS) {
                    THREADS.put(threadId, Thread.currentThread());
                }
                Thread.currentThread().setName(threadId);
                command.run();
            });
        }
    }

    /**
     * Awaits execution of the given command, but does not throw its exception.
     * 
     * @param command the command to execute
     * @param threadId the thread id
     */
    public static void await(Runnable command, String threadId) {
        try {
            execute(command, threadId).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void shutDown(String threadId) {
        shutDown(threadId, null);
    }

    public static void shutDown(String threadId, Long timeoutMs) {
        if (timeoutMs == null) timeoutMs = Long.MAX_VALUE;
        ExecutorService pool = null;
        synchronized (EXECUTORS) {
            pool = EXECUTORS.get(threadId);
            if (pool == null) return; // thread not found
            if (pool.isShutdown()) throw new IllegalStateException("Cannot shut down thread because it's already shut down: " + threadId);
            pool.shutdown();
        }
        try {
            if (!pool.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            throw new RuntimeException(e);
        }
    }

    /**
     * Reset the thread with the given id so it's ready for use. Does not shut it down.
     * 
     * @param threadId the thread id
     */
    public static void reset(String threadId) {
        remove(threadId);
    }

    public static void remove(String threadId) {
        synchronized (EXECUTORS) {
            EXECUTORS.remove(threadId);
        }
        synchronized (THREADS) {
            THREADS.remove(threadId);
        }
    }

    // TODO: consolidate and cleanup apis

    public static Future<?> submitToPool(Runnable task) {
        return submitToPool(Arrays.asList(task)).get(0);
    }

    public static List<Future<?>> submitToPool(List<Runnable> tasks) {
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable task : tasks) futures.add(POOL.submit(task));
        return futures;
    }

    public static Future<?> awaitTask(Runnable task) {
        return awaitTask(task, null);
    }

    public static Future<?> awaitTask(Runnable task, Long timeoutMs) {
        return awaitTasks(Arrays.asList(task), 1, timeoutMs).get(0);
    }

    public static List<Future<?>> awaitTasks(Collection<Runnable> tasks) {
        return awaitTasks(tasks, tasks.size());
    }

    public static List<Future<?>> awaitTasks(Collection<Runnable> tasks, int maxConcurrency) {
        return awaitTasks(tasks, maxConcurrency, null);
    }

    public static List<Future<?>> awaitTasks(Collection<Runnable> tasks, int maxConcurrency, Long timeoutMs) {
        if (timeoutMs == null) timeoutMs = Long.MAX_VALUE;
        if (tasks.isEmpty()) return new ArrayList<>();
        ExecutorService executorService = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable task : tasks) futures.add(executorService.submit(task, null));
            for (Future<?> future : futures) future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return futures;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            executorService.shutdownNow();
        }
    }

    private static boolean isCurrentThread(Thread thread, String threadId) {
        synchronized (THREADS) {
            if (!THREADS.containsKey(threadId)) return false;
            return thread == THREADS.get(threadId);
        }
    }
}
