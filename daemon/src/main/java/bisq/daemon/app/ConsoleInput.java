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
package bisq.daemon.app;

import java.util.concurrent.*;

/**
 * A cancellable console input reader.
 * Derived from https://www.javaspecialists.eu/archive/Issue153-Timeout-on-Console-Input.html
 */
public class ConsoleInput {
    private final int tries;
    private final int timeout;
    private final TimeUnit unit;
    private Future<String> future;

    public ConsoleInput(int tries, int timeout, TimeUnit unit) {
        this.tries = tries;
        this.timeout = timeout;
        this.unit = unit;
    }

    public void cancel() {
        if (future != null)
            future.cancel(true);
    }

    public String readLine() throws InterruptedException {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        String input = null;
        try {
            for (int i = 0; i < tries; i++) {
                future = ex.submit(new ConsoleInputReadTask());
                try {
                    input = future.get(timeout, unit);
                    break;
                } catch (ExecutionException e) {
                    e.getCause().printStackTrace();
                } catch (TimeoutException e) {
                    future.cancel(true);
                } finally {
                    future = null;
                }
            }
        } finally {
            ex.shutdownNow();
        }
        return input;
    }
}
