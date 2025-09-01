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

package haveno.cli.opts;

import joptsimple.OptionSpec;
import lombok.Getter;

import static haveno.cli.opts.OptLabel.OPT_WALLET_PASSWORD;
import static haveno.cli.opts.OptLabel.OPT_NEW_WALLET_PASSWORD;

public class SetWalletPasswordOptionParser extends AbstractMethodOptionParser {

    @Getter
    private final OptionSpec<String> walletPasswordOpt = parser.accepts(OPT_WALLET_PASSWORD, "Wallet Password")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<String> newWalletPasswordOpt = parser.accepts(OPT_NEW_WALLET_PASSWORD, "New Wallet Password")
            .withRequiredArg();

    public SetWalletPasswordOptionParser(String[] args) {
        super(args);
    }

    public String getPassword() {
        return options.valueOf(walletPasswordOpt);
    }

    public String getNewPassword() {
        return options.has(newWalletPasswordOpt) ? options.valueOf(newWalletPasswordOpt) : "";
    }
}
