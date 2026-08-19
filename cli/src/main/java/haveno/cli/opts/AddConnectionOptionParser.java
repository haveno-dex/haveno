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

package haveno.cli.opts;


import joptsimple.OptionSpec;

import static haveno.cli.opts.OptLabel.OPT_CONNECTION_PASSWORD;
import static haveno.cli.opts.OptLabel.OPT_CONNECTION_USER;
import static haveno.cli.opts.OptLabel.OPT_PRIORITY;
import static haveno.cli.opts.OptLabel.OPT_URL;
import static joptsimple.internal.Strings.EMPTY;

public class AddConnectionOptionParser extends AbstractMethodOptionParser implements MethodOpts {

    final OptionSpec<String> urlOpt = parser.accepts(OPT_URL, "monero daemon url")
            .withRequiredArg();

    final OptionSpec<String> connectionUserOpt = parser.accepts(OPT_CONNECTION_USER, "optional monero daemon username")
            .withOptionalArg()
            .defaultsTo(EMPTY);

    final OptionSpec<String> connectionPasswordOpt = parser.accepts(OPT_CONNECTION_PASSWORD, "optional monero daemon password")
            .withOptionalArg()
            .defaultsTo(EMPTY);

    final OptionSpec<Integer> priorityOpt = parser.accepts(OPT_PRIORITY, "optional connection priority")
            .withOptionalArg()
            .ofType(Integer.class)
            .defaultsTo(0);

    public AddConnectionOptionParser(String[] args) {
        super(args);
    }

    public AddConnectionOptionParser parse() {
        super.parse();

        // Short circuit opt validation if user just wants help.
        if (options.has(helpOpt))
            return this;

        if (!options.has(urlOpt) || options.valueOf(urlOpt).isEmpty())
            throw new IllegalArgumentException("no monero daemon url specified");

        return this;
    }

    public String getUrl() {
        return options.valueOf(urlOpt);
    }

    public String getConnectionUser() {
        return options.has(connectionUserOpt) ? options.valueOf(connectionUserOpt) : "";
    }

    public String getConnectionPassword() {
        return options.has(connectionPasswordOpt) ? options.valueOf(connectionPasswordOpt) : "";
    }

    public int getPriority() {
        return options.has(priorityOpt) ? options.valueOf(priorityOpt) : 0;
    }
}
