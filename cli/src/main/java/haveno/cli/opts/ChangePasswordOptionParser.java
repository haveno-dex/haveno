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

import static haveno.cli.opts.OptLabel.OPT_ACCOUNT_PASSWORD;
import static haveno.cli.opts.OptLabel.OPT_NEW_ACCOUNT_PASSWORD;

public class ChangePasswordOptionParser extends AbstractMethodOptionParser implements MethodOpts {

    final OptionSpec<String> accountPasswordOpt = parser.accepts(OPT_ACCOUNT_PASSWORD, "current haveno account password")
            .withRequiredArg();

    final OptionSpec<String> newAccountPasswordOpt = parser.accepts(OPT_NEW_ACCOUNT_PASSWORD, "new haveno account password")
            .withRequiredArg();

    public ChangePasswordOptionParser(String[] args) {
        super(args);
    }

    public ChangePasswordOptionParser parse() {
        super.parse();

        // Short circuit opt validation if user just wants help.
        if (options.has(helpOpt))
            return this;

        if (!options.has(accountPasswordOpt) || options.valueOf(accountPasswordOpt).isEmpty())
            throw new IllegalArgumentException("no account password specified");

        if (!options.has(newAccountPasswordOpt) || options.valueOf(newAccountPasswordOpt).isEmpty())
            throw new IllegalArgumentException("no new account password specified");

        return this;
    }

    public String getAccountPassword() {
        return options.valueOf(accountPasswordOpt);
    }

    public String getNewAccountPassword() {
        return options.valueOf(newAccountPasswordOpt);
    }
}
