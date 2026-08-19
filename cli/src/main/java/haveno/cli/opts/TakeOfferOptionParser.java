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

import static haveno.cli.CurrencyFormat.toPiconeros;
import static haveno.cli.opts.OptLabel.OPT_AMOUNT;
import static haveno.cli.opts.OptLabel.OPT_CHALLENGE;
import static haveno.cli.opts.OptLabel.OPT_PAYMENT_ACCOUNT_ID;
import static joptsimple.internal.Strings.EMPTY;

public class TakeOfferOptionParser extends OfferIdOptionParser implements MethodOpts {

    final OptionSpec<String> paymentAccountIdOpt = parser.accepts(OPT_PAYMENT_ACCOUNT_ID, "id of payment account used for trade")
            .withRequiredArg();

    final OptionSpec<String> amountOpt = parser.accepts(OPT_AMOUNT, "optional xmr amount to take from a range offer")
            .withOptionalArg()
            .defaultsTo(EMPTY);

    final OptionSpec<String> challengeOpt = parser.accepts(OPT_CHALLENGE, "optional passphrase for taking a private offer")
            .withOptionalArg()
            .defaultsTo(EMPTY);

    public TakeOfferOptionParser(String[] args) {
        super(args, true);
    }

    public TakeOfferOptionParser parse() {
        super.parse();

        // Super class will short-circuit parsing if help option is present.

        if (!options.has(paymentAccountIdOpt) || options.valueOf(paymentAccountIdOpt).isEmpty())
            throw new IllegalArgumentException("no payment account id specified");

        return this;
    }

    public String getPaymentAccountId() {
        return options.valueOf(paymentAccountIdOpt);
    }

    // Returns 0 if no amount opt is present, meaning the full offer amount is taken.
    public long getAmount() {
        return options.has(amountOpt) && !options.valueOf(amountOpt).isEmpty()
                ? toPiconeros(options.valueOf(amountOpt))
                : 0;
    }

    public String getChallenge() {
        return options.has(challengeOpt) ? options.valueOf(challengeOpt) : "";
    }
}
