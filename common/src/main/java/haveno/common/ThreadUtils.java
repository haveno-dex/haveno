public class ThreadUtils {

    private static final Map<String, ExecutorService> EXECUTORS = new HashMap<>();
    private static final Map<String, Thread> THREADS = new HashMap<>();
    private static final int POOL_SIZE = 10;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(POOL_SIZE);

    // Validate thread ID to avoid null or empty values
    private static void validateThreadId(String threadId) {
        if (threadId == null || threadId.isEmpty()) {
            throw new IllegalArgumentException("Thread ID cannot be null or empty");
        }
    }

    /**
     * Execute the given command in a thread with the specified threadId.
     *
     * @param command the command to execute
     * @param threadId the thread ID to associate with this task
     * @return a Future representing the execution of the command
     */
    public static Future<?> execute(Runnable command, String threadId) {
        validateThreadId(threadId);  // Ensure valid threadId

        ExecutorService executorService;
        synchronized (EXECUTORS) {
            executorService = EXECUTORS.computeIfAbsent(threadId, id -> Executors.newFixedThreadPool(1));
        }

        return executorService.submit(() -> {
            Thread.currentThread().setName(threadId);
            synchronized (THREADS) {
                THREADS.put(threadId, Thread.currentThread());
            }
            command.run();
        });
    }

    /**
     * Awaits execution of the given command, blocking until it's finished, but does not throw its exception.
     *
     * @param command the command to execute
     * @param threadId the thread ID to associate with this task
     */
    public static void await(Runnable command, String threadId) {
        try {
            execute(command, threadId).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Shuts down the executor associated with the given threadId.
     *
     * @param threadId the thread ID to shut down
     */
    public static void shutDown(String threadId) {
        shutDown(threadId, null);
    }

    /**
     * Shuts down the executor associated with the given threadId, with a specific timeout.
     *
     * @param threadId the thread ID to shut down
     * @param timeoutMs the timeout in milliseconds to wait for termination
     */
    public static void shutDown(String threadId, Long timeoutMs) {
        validateThreadId(threadId);  // Ensure valid threadId

        if (timeoutMs == null) timeoutMs = Long.MAX_VALUE;

        ExecutorService pool = null;
        synchronized (EXECUTORS) {
            pool = EXECUTORS.get(threadId);
        }
        if (pool == null) return; // Thread not found, no need to shut down

        pool.shutdown();
        try {
            if (!pool.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            // Preserve the interrupt status after handling the exception
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            throw new RuntimeException(e);
        } finally {
            remove(threadId);
        }
    }

    /**
     * Removes the executor and thread associated with the given threadId from the maps.
     *
     * @param threadId the thread ID to remove
     */
    public static void remove(String threadId) {
        synchronized (EXECUTORS) {
            EXECUTORS.remove(threadId);
        }
        synchronized (THREADS) {
            THREADS.remove(threadId);
        }
    }

    // TODO: consolidate and cleanup apis
    
    // Task submission to the default pool
    public static Future<?> submitToPool(Runnable task) {
        return submitToPool(Arrays.asList(task)).get(0);
    }

    // Task submission to the default pool with a list of tasks
    public static List<Future<?>> submitToPool(List<Runnable> tasks) {
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable task : tasks) {
            futures.add(POOL.submit(task));
        }
        return futures;
    }

    /**
     * Awaits the execution of a single task.
     *
     * @param task the task to execute
     * @return the Future representing the task execution
     */
    public static Future<?> awaitTask(Runnable task) {
        return awaitTask(task, null);
    }

    /**
     * Awaits the execution of a single task with an optional timeout.
     *
     * @param task the task to execute
     * @param timeoutMs the timeout in milliseconds for waiting for the task to finish
     * @return the Future representing the task execution
     */
    public static Future<?> awaitTask(Runnable task, Long timeoutMs) {
        return awaitTasks(Arrays.asList(task), 1, timeoutMs).get(0);
    }

    /**
     * Awaits the execution of multiple tasks, blocking until all tasks are finished.
     *
     * @param tasks the tasks to execute
     * @return a list of Futures representing the execution of each task
     */
    public static List<Future<?>> awaitTasks(Collection<Runnable> tasks) {
        return awaitTasks(tasks, tasks.size());
    }

    /**
     * Awaits the execution of multiple tasks with a limit on concurrent executions.
     *
     * @param tasks the tasks to execute
     * @param maxConcurrency the maximum number of concurrent tasks to run
     * @return a list of Futures representing the execution of each task
     */
    public static List<Future<?>> awaitTasks(Collection<Runnable> tasks, int maxConcurrency) {
        return awaitTasks(tasks, maxConcurrency, null);
    }

    /**
     * Awaits the execution of multiple tasks with a limit on concurrent executions and an optional timeout.
     *
     * @param tasks the tasks to execute
     * @param maxConcurrency the maximum number of concurrent tasks to run
     * @param timeoutMs the timeout in milliseconds for waiting for the tasks to finish
     * @return a list of Futures representing the execution of each task
     */
    public static List<Future<?>> awaitTasks(Collection<Runnable> tasks, int maxConcurrency, Long timeoutMs) {
        if (timeoutMs == null) timeoutMs = Long.MAX_VALUE;

        if (tasks.isEmpty()) return new ArrayList<>();
        ExecutorService executorService = Executors.newFixedThreadPool(Math.min(tasks.size(), maxConcurrency));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable task : tasks) {
                futures.add(executorService.submit(task));
            }
            for (Future<?> future : futures) {
                future.get(timeoutMs, TimeUnit.MILLISECONDS);
            }
            return futures;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            executorService.shutdownNow();
        }
    }

    /**
     * Checks whether the current thread is the one associated with the given threadId.
     *
     * @param thread the thread to check
     * @param threadId the thread ID to check against
     * @return true if the given thread is associated with the threadId
     */
    private static boolean isCurrentThread(Thread thread, String threadId) {
        synchronized (THREADS) {
            if (!THREADS.containsKey(threadId)) return false;
            return thread == THREADS.get(threadId);
        }
    }
}
