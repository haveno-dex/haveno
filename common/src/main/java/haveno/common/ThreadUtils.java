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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ThreadUtils {

    private static final Map<String, Thread> VIRTUAL_THREADS = new ConcurrentHashMap<>();

    /**
     * Execute the given command in a virtual thread with the given id.
     *
     * @param command the command to execute
     * @param threadId the thread id
     */
    public static Future<?> execute(Runnable command, String threadId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Thread virtualThread = Thread.ofVirtual().name(threadId).start(() -> {
            try {
                command.run();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            } finally {
                VIRTUAL_THREADS.remove(threadId);
            }
        });
        VIRTUAL_THREADS.put(threadId, virtualThread);
        return future;
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

    /**
     * Shuts down the virtual thread with the given id.
     *
     * @param threadId the thread id
     */
    public static void shutDown(String threadId) {
        shutDown(threadId, null);
    }

    /**
     * Shuts down the virtual thread with the given id and an optional timeout.
     *
     * @param threadId the thread id
     * @param timeoutMs the timeout in milliseconds
     */
    public static void shutDown(String threadId, Long timeoutMs) {
        Thread thread = VIRTUAL_THREADS.get(threadId);
        if (thread == null) return; // thread not found
        thread.interrupt();
        if (timeoutMs != null) {
            try {
                thread.join(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Removes the virtual thread with the given id from the tracking map.
     *
     * @param threadId the thread id
     */
    public static void remove(String threadId) {
        VIRTUAL_THREADS.remove(threadId);
    }

    /**
     * Submit a single task to be executed in a virtual thread.
     *
     * @param task the task to execute
     * @return a Future representing the pending completion of the task
     */
    public static Future<?> submitToPool(Runnable task) {
        return submitToPool(Arrays.asList(task)).get(0);
    }

    /**
     * Submit a single callable task to be executed in a virtual thread.
     *
     * @param task the task to execute
     * @return a Future representing the pending completion of the task
     */
    public static <T> Future<T> submitToPool(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        execute(() -> {
            try {
                T result = task.call();
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }, "pool-task-" + task.hashCode());
        return future;
    }

    /**
     * Submit a list of tasks to be executed in virtual threads.
     *
     * @param tasks the tasks to execute
     * @return a list of Futures representing the pending completion of the tasks
     */
    public static List<Future<?>> submitToPool(List<Runnable> tasks) {
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable task : tasks) {
            futures.add(execute(task, "pool-task-" + task.hashCode()));
        }
        return futures;
    }

    /**
     * Await the completion of a single task.
     *
     * @param task the task to execute
     * @return a Future representing the pending completion of the task
     */
    public static Future<?> awaitTask(Runnable task) {
        return awaitTask(task, null);
    }

    /**
     * Await the completion of a single task with an optional timeout.
     *
     * @param task the task to execute
     * @param timeoutMs the timeout in milliseconds
     * @return a Future representing the pending completion of the task
     */
    public static Future<?> awaitTask(Runnable task, Long timeoutMs) {
        return awaitTasks(Arrays.asList(task), 1, timeoutMs).get(0);
    }

    /**
     * Await the completion of a collection of tasks.
     *
     * @param tasks the tasks to execute
     * @return a list of Futures representing the pending completion of the tasks
     */
    public static List<Future<?>> awaitTasks(Collection<Runnable> tasks) {
        return awaitTasks(tasks, tasks.size());
    }

    /**
     * Await the completion of a collection of tasks with a specified maximum concurrency.
     *
     * @param tasks the tasks to execute
     * @param maxConcurrency the maximum number of concurrent tasks
     * @return a list of Futures representing the pending completion of the tasks
     */
    public static List<Future<?>> awaitTasks(Collection<Runnable> tasks, int maxConcurrency) {
        return awaitTasks(tasks, maxConcurrency, null);
    }

    /**
     * Await the completion of a collection of tasks with a specified maximum concurrency and optional timeout.
     *
     * @param tasks the tasks to execute
     * @param maxConcurrency the maximum number of concurrent tasks
     * @param timeoutMs the timeout in milliseconds
     * @return a list of Futures representing the pending completion of the tasks
     */
    public static List<Future<?>> awaitTasks(Collection<Runnable> tasks, int maxConcurrency, Long timeoutMs) {
        if (timeoutMs == null) timeoutMs = Long.MAX_VALUE;
        if (tasks.isEmpty()) return new ArrayList<>();

        List<Future<?>> futures = new ArrayList<>();
        for (Runnable task : tasks) {
            futures.add(execute(task, "await-task-" + task.hashCode()));
        }
        for (Future<?> future : futures) {
            try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new RuntimeException(e);
            }
        }
        return futures;
    }
}
