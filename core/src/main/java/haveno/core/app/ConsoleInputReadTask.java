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
package haveno.core.app;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.Callable;

@Slf4j
public class ConsoleInputReadTask implements Callable<String> {
    public String call() throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        log.debug("ConsoleInputReadTask run() called.");
        String input;
        do {
            try {
                // wait until we have data to complete a readLine()
                while (!br.ready()) {
                    Thread.sleep(100);
                }
                // readline will always block until an input exists.
                input = br.readLine();
            } catch (InterruptedException e) {
                log.debug("ConsoleInputReadTask() cancelled");
                return null;
            }
        } while ("".equals(input));
        return input;
    }
}
