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

import static haveno.cli.opts.OptLabel.OPT_CURRENCY_CODE;
import static haveno.cli.opts.OptLabel.OPT_EXTRA_INFO;
import static haveno.cli.opts.OptLabel.OPT_FIXED_PRICE;
import static haveno.cli.opts.OptLabel.OPT_MKT_PRICE_MARGIN;
import static haveno.cli.opts.OptLabel.OPT_PAYMENT_ACCOUNT_ID;
import static haveno.cli.opts.OptLabel.OPT_TRIGGER_PRICE;
import static joptsimple.internal.Strings.EMPTY;

public class EditOfferOptionParser extends OfferIdOptionParser implements MethodOpts {

    final OptionSpec<String> fixedPriceOpt = parser.accepts(OPT_FIXED_PRICE, "fixed offer price")
            .withOptionalArg()
            .defaultsTo(EMPTY);

    final OptionSpec<String> mktPriceMarginPctOpt = parser.accepts(OPT_MKT_PRICE_MARGIN, "market price margin (%)")
            .withOptionalArg()
            .defaultsTo(EMPTY);

    final OptionSpec<String> triggerPriceOpt = parser.accepts(OPT_TRIGGER_PRICE,
                    "trigger price to deactivate market priced offer")
            .withOptionalArg()
            .defaultsTo(EMPTY);

    final OptionSpec<String> currencyCodeOpt = parser.accepts(OPT_CURRENCY_CODE, "currency code (eur|usd|...)")
            .withOptionalArg()
            .defaultsTo(EMPTY);

    final OptionSpec<String> paymentAccountIdOpt = parser.accepts(OPT_PAYMENT_ACCOUNT_ID,
                    "id of payment account used for offer")
            .withOptionalArg()
            .defaultsTo(EMPTY);

    final OptionSpec<String> extraInfoOpt = parser.accepts(OPT_EXTRA_INFO,
                    "extra terms and info for the offer; multi word terms must be double quoted")
            .withOptionalArg()
            .defaultsTo(EMPTY);

    public EditOfferOptionParser(String[] args) {
        super(args, true);
    }

    public EditOfferOptionParser parse() {
        super.parse();

        // Super class will short-circuit parsing if help option is present.
        if (options.has(helpOpt))
            return this;

        boolean hasNoEditOpts = !options.has(fixedPriceOpt)
                && !options.has(mktPriceMarginPctOpt)
                && !options.has(triggerPriceOpt)
                && !options.has(currencyCodeOpt)
                && !options.has(paymentAccountIdOpt)
                && !options.has(extraInfoOpt);
        if (hasNoEditOpts)
            throw new IllegalArgumentException("no edit details specified");

        if (options.has(fixedPriceOpt) && options.has(mktPriceMarginPctOpt))
            throw new IllegalArgumentException("cannot specify both a fixed price and a market price margin");

        if (options.has(fixedPriceOpt) && options.valueOf(fixedPriceOpt).isEmpty())
            throw new IllegalArgumentException("no fixed price specified");

        if (options.has(mktPriceMarginPctOpt)) {
            if (options.valueOf(mktPriceMarginPctOpt).isEmpty())
                throw new IllegalArgumentException("no market price margin specified");
            else
                verifyStringIsValidDouble(options.valueOf(mktPriceMarginPctOpt));
        }

        return this;
    }

    public boolean isUsingMktPriceMargin() {
        return options.has(mktPriceMarginPctOpt);
    }

    public String getFixedPrice() {
        return options.has(fixedPriceOpt) ? options.valueOf(fixedPriceOpt) : "";
    }

    public double getMktPriceMarginPct() {
        return isUsingMktPriceMargin() ? Double.parseDouble(options.valueOf(mktPriceMarginPctOpt)) : 0.00d;
    }

    public String getTriggerPrice() {
        return options.has(triggerPriceOpt) ? options.valueOf(triggerPriceOpt) : "";
    }

    public String getCurrencyCode() {
        return options.has(currencyCodeOpt) ? options.valueOf(currencyCodeOpt) : "";
    }

    public String getPaymentAccountId() {
        return options.has(paymentAccountIdOpt) ? options.valueOf(paymentAccountIdOpt) : "";
    }

    public String getExtraInfo() {
        return options.has(extraInfoOpt) ? options.valueOf(extraInfoOpt) : "";
    }
}
