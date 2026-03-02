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

import static haveno.cli.opts.OptLabel.OPT_PAYMENT_ACCOUNT_ID;

public class DeletePaymentAccountOptionParser extends AbstractMethodOptionParser {

    @Getter
    private final OptionSpec<String> paymentAccountIdOpt = parser.accepts(OPT_PAYMENT_ACCOUNT_ID, "Payment Account ID")
            .withRequiredArg()
            .required();

    public DeletePaymentAccountOptionParser(String[] args) {
        super(args);
    }

    public String getPaymentAccountId() {
        return options.valueOf(paymentAccountIdOpt);
    }
}
