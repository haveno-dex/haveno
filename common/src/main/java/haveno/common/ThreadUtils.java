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
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
 
public class ThreadUtils {
 
    private static final Logger logger = LoggerFactory.getLogger(ThreadUtils.class);
    private static final ConcurrentHashMap<String, Thread> VIRTUAL_THREADS = new ConcurrentHashMap<>();
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);
    private static final long DEFAULT_TIMEOUT_MS = 5000; // Default timeout for operations

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
                logger.error("Exception in thread: {} - {}", threadId, e.getMessage(), e);
                future.completeExceptionally(e);
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while awaiting command execution in thread: {} - {}", threadId, e.getMessage(), e);
            throw new RuntimeException("Interrupted while awaiting command execution", e);
        } catch (ExecutionException e) {
            logger.error("Execution exception while awaiting command execution in thread: {} - {}", threadId, e.getMessage(), e);
            throw new RuntimeException("Execution exception while awaiting command execution", e);
        }
    }


    public static void shutDown(String threadId) {
        shutDown(threadId, null);
    }

    public static void shutDown(String threadId, Long timeoutMs) {
        Thread thread = VIRTUAL_THREADS.remove(threadId);
        if (thread == null) {
            logger.warn("Thread not found: {}", threadId);
            return; // thread not found
        }
        thread.interrupt();
        if (timeoutMs != null) {
            try {
                thread.join(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Interrupted while waiting for thread to shut down: {} - {}", threadId, e.getMessage(), e);
                throw new RuntimeException("Interrupted while waiting for thread to shut down", e);
            }
        }
        logger.info("Shut down thread: {}", threadId);
    }


    public static void remove(String threadId) {
        VIRTUAL_THREADS.remove(threadId);
    }

    // TODO: consolidate and cleanup apis

    public static Future<?> submitToPool(Runnable task) {
        return submitToPool(Arrays.asList(task)).get(0);
    }

    public static <T> Future<T> submitToPool(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        execute(() -> {
            try {
                T result = task.call();
                future.complete(result);
            } catch (Exception e) {
                logger.error("Exception in callable task - {}", e.getMessage(), e);
                future.completeExceptionally(e);
            }
        }, "pool-task-" + THREAD_COUNTER.incrementAndGet());
        return future;
    }

    public static List<Future<?>> submitToPool(List<Runnable> tasks) {
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable task : tasks) {
            futures.add(execute(task, "pool-task-" + THREAD_COUNTER.incrementAndGet()));
        }
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
        if (timeoutMs == null) timeoutMs = DEFAULT_TIMEOUT_MS;
        if (tasks.isEmpty()) return new ArrayList<>();

        List<Future<?>> futures = new ArrayList<>();
        AtomicReference<List<Runnable>> remainingTasks = new AtomicReference<>(new ArrayList<>(tasks));

        while (true) {
            List<Runnable> batchTasks = remainingTasks.get().subList(0, Math.min(maxConcurrency, remainingTasks.get().size()));
            if (batchTasks.isEmpty()) break;

            List<Future<?>> batchFutures = new ArrayList<>();
            for (Runnable task : batchTasks) {
                batchFutures.add(execute(task, "await-task-" + THREAD_COUNTER.incrementAndGet()));
            }

            for (Future<?> future : batchFutures) {
                try {
                    future.get(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("Interrupted while awaiting task completion - {}", e.getMessage(), e);
                    throw new RuntimeException("Interrupted while awaiting task completion", e);
                } catch (ExecutionException e) {
                    logger.error("Execution exception while awaiting task completion - {}", e.getMessage(), e);
                    throw new RuntimeException("Execution exception while awaiting task completion", e);
                } catch (TimeoutException e) {
                    logger.error("Timeout while awaiting task completion - {}", e.getMessage(), e);
                    throw new RuntimeException("Timeout while awaiting task completion", e);
                }
            }

            futures.addAll(batchFutures);
            remainingTasks.set(remainingTasks.get().subList(Math.min(maxConcurrency, remainingTasks.get().size()), remainingTasks.get().size()));
        }

        return futures;
    }
}
