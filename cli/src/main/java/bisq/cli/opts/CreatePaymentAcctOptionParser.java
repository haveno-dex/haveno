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

import java.nio.file.Path;
import java.nio.file.Paths;

import static haveno.cli.opts.OptLabel.OPT_PAYMENT_ACCOUNT_FORM;
import static java.lang.String.format;

public class CreatePaymentAcctOptionParser extends AbstractMethodOptionParser implements MethodOpts {

    final OptionSpec<String> paymentAcctFormPathOpt = parser.accepts(OPT_PAYMENT_ACCOUNT_FORM,
            "path to json payment account form")
            .withRequiredArg();

    public CreatePaymentAcctOptionParser(String[] args) {
        super(args);
    }

    public CreatePaymentAcctOptionParser parse() {
        super.parse();

        // Short circuit opt validation if user just wants help.
        if (options.has(helpOpt))
            return this;

        if (!options.has(paymentAcctFormPathOpt) || options.valueOf(paymentAcctFormPathOpt).isEmpty())
            throw new IllegalArgumentException("no path to json payment account form specified");

        Path path = Paths.get(options.valueOf(paymentAcctFormPathOpt));
        if (!path.toFile().exists())
            throw new IllegalStateException(
                    format("json payment account form '%s' could not be found",
                            path));

        return this;
    }

    public Path getPaymentAcctForm() {
        return Paths.get(options.valueOf(paymentAcctFormPathOpt));
    }
}
