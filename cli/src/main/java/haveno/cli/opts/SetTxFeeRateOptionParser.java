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


import static haveno.cli.opts.OptLabel.OPT_TX_FEE_RATE;

import joptsimple.OptionSpec;

public class SetTxFeeRateOptionParser extends AbstractMethodOptionParser implements MethodOpts {

    final OptionSpec<String> feeRateOpt = parser.accepts(OPT_TX_FEE_RATE,
                    "tx fee rate preference (sats/byte)")
            .withRequiredArg();

    public SetTxFeeRateOptionParser(String[] args) {
        super(args);
    }

    public SetTxFeeRateOptionParser parse() {
        super.parse();

        // Short circuit opt validation if user just wants help.
        if (options.has(helpOpt))
            return this;

        if (!options.has(feeRateOpt) || options.valueOf(feeRateOpt).isEmpty())
            throw new IllegalArgumentException("no tx fee rate specified");

        return this;
    }

    public String getFeeRate() {
        return options.valueOf(feeRateOpt);
    }
}
