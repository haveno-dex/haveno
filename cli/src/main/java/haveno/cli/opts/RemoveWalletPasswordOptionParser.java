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


import static haveno.cli.opts.OptLabel.OPT_WALLET_PASSWORD;

import joptsimple.OptionSpec;

public class RemoveWalletPasswordOptionParser extends AbstractMethodOptionParser implements MethodOpts {

    final OptionSpec<String> passwordOpt = parser.accepts(OPT_WALLET_PASSWORD, "haveno wallet password")
            .withRequiredArg();

    public RemoveWalletPasswordOptionParser(String[] args) {
        super(args);
    }

    public RemoveWalletPasswordOptionParser parse() {
        super.parse();

        // Short circuit opt validation if user just wants help.
        if (options.has(helpOpt))
            return this;

        if (!options.has(passwordOpt) || options.valueOf(passwordOpt).isEmpty())
            throw new IllegalArgumentException("no password specified");

        return this;
    }

    public String getPassword() {
        return options.valueOf(passwordOpt);
    }
}
