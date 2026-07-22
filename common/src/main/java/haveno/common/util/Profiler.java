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

package haveno.common.util;

import haveno.common.UserThread;
import lombok.extern.slf4j.Slf4j;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class Profiler {

    private static final double HIGH_MEMORY_THRESHOLD = 0.9;

    public static void printSystemLoadPeriodically(long delay, TimeUnit timeUnit) {
        UserThread.runPeriodically(Profiler::printSystemLoad, delay, timeUnit);
    }

    public static void warnOnHighMemoryUsagePeriodically(long delay, TimeUnit timeUnit) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MemoryMonitor");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(Profiler::warnIfMemoryHigh, delay, delay, timeUnit);
    }

    private static void warnIfMemoryHigh() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long max = runtime.maxMemory();
            if (max <= 0) {
                return;
            }
            // measure usage after the last GC (the retained set); transient pre-GC peaks are normal
            long used = getUsedMemoryAfterLastGcInBytes();
            if (used < 0) {
                used = runtime.totalMemory() - runtime.freeMemory();
            }
            double ratio = (double) used / max;
            if (ratio >= HIGH_MEMORY_THRESHOLD) {
                log.warn("Memory usage critically high: {} of {} max ({}%) still used after garbage collection. " +
                        "An OutOfMemoryError may be imminent, which terminates the application immediately.",
                        Utilities.readableFileSize(used), Utilities.readableFileSize(max), Math.round(ratio * 100));
            }
        } catch (Throwable t) {
            log.warn("Error checking memory usage", t);
        }
    }

    // heap used after the most recent GC, or -1 if the JVM does not report it
    private static long getUsedMemoryAfterLastGcInBytes() {
        long sum = -1;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) {
                continue;
            }
            MemoryUsage usage = pool.getCollectionUsage();
            if (usage == null) {
                continue;
            }
            sum = Math.max(sum, 0) + usage.getUsed();
        }
        return sum;
    }

    public static void printSystemLoad() {
        Runtime runtime = Runtime.getRuntime();
        long free = runtime.freeMemory();
        long total = runtime.totalMemory();
        long used = total - free;

        log.info("Total memory: {}; Used memory: {}; Free memory: {}; Max memory: {}; No. of threads: {}",
                Utilities.readableFileSize(total),
                Utilities.readableFileSize(used),
                Utilities.readableFileSize(free),
                Utilities.readableFileSize(runtime.maxMemory()),
                Thread.activeCount());
    }

    public static long getUsedMemoryInMB() {
        return getUsedMemoryInBytes() / 1024 / 1024;
    }

    public static long getUsedMemoryInBytes() {
        Runtime runtime = Runtime.getRuntime();
        long free = runtime.freeMemory();
        long total = runtime.totalMemory();
        return total - free;
    }

    public static long getFreeMemoryInMB() {
        return Runtime.getRuntime().freeMemory() / 1024 / 1024;
    }

    public static long getTotalMemoryInMB() {
        return Runtime.getRuntime().totalMemory() / 1024 / 1024;
    }
}
