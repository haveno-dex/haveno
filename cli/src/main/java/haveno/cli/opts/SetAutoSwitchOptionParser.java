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

import static haveno.cli.opts.OptLabel.OPT_AUTO_SWITCH;

public class SetAutoSwitchOptionParser extends AbstractMethodOptionParser implements MethodOpts {

    final OptionSpec<Boolean> autoSwitchOpt = parser.accepts(OPT_AUTO_SWITCH,
                    "auto switch to the best monero daemon connection (true|false)")
            .withRequiredArg()
            .ofType(boolean.class);

    public SetAutoSwitchOptionParser(String[] args) {
        super(args);
    }

    public SetAutoSwitchOptionParser parse() {
        super.parse();

        // Short circuit opt validation if user just wants help.
        if (options.has(helpOpt))
            return this;

        if (!options.has(autoSwitchOpt))
            throw new IllegalArgumentException("no auto-switch (true|false) specified");

        return this;
    }

    public boolean getAutoSwitch() {
        return options.valueOf(autoSwitchOpt);
    }
}
