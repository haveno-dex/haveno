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

import static haveno.cli.opts.OptLabel.OPT_ACCOUNT_NAME;
import static haveno.cli.opts.OptLabel.OPT_CURRENCY_CODE;
import static haveno.cli.opts.OptLabel.OPT_ADDRESS;
import static haveno.cli.opts.OptLabel.OPT_TRADE_INSTANT;

public class CreateCryptoCurrencyPaymentAcctOptionParser extends AbstractMethodOptionParser {

    @Getter
    private final OptionSpec<String> accountNameOpt = parser.accepts(OPT_ACCOUNT_NAME, "Account Name")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<String> currencyCodeOpt = parser.accepts(OPT_CURRENCY_CODE, "Currency Code")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<String> addressOpt = parser.accepts(OPT_ADDRESS, "Address")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<Boolean> tradeInstantOpt = parser.accepts(OPT_TRADE_INSTANT, "Trade Instant")
            .withRequiredArg()
            .ofType(Boolean.class)
            .defaultsTo(false);

    public CreateCryptoCurrencyPaymentAcctOptionParser(String[] args) {
        super(args);
    }

    public String getAccountName() {
        return options.valueOf(accountNameOpt);
    }

    public String getCurrencyCode() {
        return options.valueOf(currencyCodeOpt);
    }

    public String getAddress() {
        return options.valueOf(addressOpt);
    }

    public boolean getIsTradeInstant() {
        return options.valueOf(tradeInstantOpt);
    }
}
