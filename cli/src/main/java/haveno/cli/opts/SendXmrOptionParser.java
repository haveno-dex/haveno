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

import static haveno.cli.opts.OptLabel.OPT_ADDRESS;
import static haveno.cli.opts.OptLabel.OPT_AMOUNT;
import static haveno.cli.opts.OptLabel.OPT_TX_FEE_RATE;
import static haveno.cli.opts.OptLabel.OPT_MEMO;

public class SendXmrOptionParser extends AbstractMethodOptionParser {

    @Getter
    private final OptionSpec<String> addressOpt = parser.accepts(OPT_ADDRESS, "Destination Address")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<String> amountOpt = parser.accepts(OPT_AMOUNT, "Amount")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<String> txFeeRateOpt = parser.accepts(OPT_TX_FEE_RATE, "Transaction Fee Rate")
            .withRequiredArg()
            .defaultsTo("");

    @Getter
    private final OptionSpec<String> memoOpt = parser.accepts(OPT_MEMO, "Memo")
            .withRequiredArg()
            .defaultsTo("");

    public SendXmrOptionParser(String[] args) {
        super(args);
    }

    public String getAddress() {
        return options.valueOf(addressOpt);
    }

    public String getAmount() {
        return options.valueOf(amountOpt);
    }

    public String getTxFeeRate() {
        return options.valueOf(txFeeRateOpt);
    }

    public String getMemo() {
        return options.valueOf(memoOpt);
    }
}
