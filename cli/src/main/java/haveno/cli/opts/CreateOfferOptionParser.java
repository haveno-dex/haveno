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

import static haveno.cli.opts.OptLabel.OPT_AMOUNT;
import static haveno.cli.opts.OptLabel.OPT_CURRENCY_CODE;
import static haveno.cli.opts.OptLabel.OPT_DIRECTION;
import static haveno.cli.opts.OptLabel.OPT_FIXED_PRICE;
import static haveno.cli.opts.OptLabel.OPT_MIN_AMOUNT;
import static haveno.cli.opts.OptLabel.OPT_MKT_PRICE_MARGIN;
import static haveno.cli.opts.OptLabel.OPT_PAYMENT_ACCOUNT_ID;
import static haveno.cli.opts.OptLabel.OPT_SECURITY_DEPOSIT;
import static joptsimple.internal.Strings.EMPTY;

public class CreateOfferOptionParser extends AbstractMethodOptionParser implements MethodOpts {

    final OptionSpec<String> paymentAccountIdOpt = parser.accepts(OPT_PAYMENT_ACCOUNT_ID,
                    "id of payment account used for offer")
            .withRequiredArg()
            .defaultsTo(EMPTY);

    final OptionSpec<String> directionOpt = parser.accepts(OPT_DIRECTION, "offer direction (buy|sell)")
            .withRequiredArg();

    final OptionSpec<String> currencyCodeOpt = parser.accepts(OPT_CURRENCY_CODE, "currency code (xmr|eur|usd|...)")
            .withRequiredArg();

    final OptionSpec<String> amountOpt = parser.accepts(OPT_AMOUNT, "amount of btc to buy or sell")
            .withRequiredArg();

    final OptionSpec<String> minAmountOpt = parser.accepts(OPT_MIN_AMOUNT, "minimum amount of btc to buy or sell")
            .withOptionalArg();

    final OptionSpec<String> mktPriceMarginPctOpt = parser.accepts(OPT_MKT_PRICE_MARGIN, "market btc price margin (%)")
            .withOptionalArg()
            .defaultsTo("0.00");

    final OptionSpec<String> fixedPriceOpt = parser.accepts(OPT_FIXED_PRICE, "fixed btc price")
            .withOptionalArg()
            .defaultsTo("0");

    final OptionSpec<String> securityDepositPctOpt = parser.accepts(OPT_SECURITY_DEPOSIT, "maker security deposit (%)")
            .withRequiredArg();

    public CreateOfferOptionParser(String[] args) {
        super(args);
    }

    @Override
    public CreateOfferOptionParser parse() {
        super.parse();

        // Short circuit opt validation if user just wants help.
        if (options.has(helpOpt))
            return this;

        if (!options.has(directionOpt) || options.valueOf(directionOpt).isEmpty())
            throw new IllegalArgumentException("no direction (buy|sell) specified");

        if (!options.has(currencyCodeOpt) || options.valueOf(currencyCodeOpt).isEmpty())
            throw new IllegalArgumentException("no currency code specified");

        if (!options.has(amountOpt) || options.valueOf(amountOpt).isEmpty())
            throw new IllegalArgumentException("no btc amount specified");

        if (!options.has(paymentAccountIdOpt) || options.valueOf(paymentAccountIdOpt).isEmpty())
            throw new IllegalArgumentException("no payment account id specified");

        if (!options.has(mktPriceMarginPctOpt) && !options.has(fixedPriceOpt))
            throw new IllegalArgumentException("no market price margin or fixed price specified");

        if (options.has(mktPriceMarginPctOpt)) {
            var mktPriceMarginPctString = options.valueOf(mktPriceMarginPctOpt);
            if (mktPriceMarginPctString.isEmpty())
                throw new IllegalArgumentException("no market price margin specified");
            else
                verifyStringIsValidDouble(mktPriceMarginPctString);
        }

        if (options.has(fixedPriceOpt) && options.valueOf(fixedPriceOpt).isEmpty())
            throw new IllegalArgumentException("no fixed price specified");

        if (!options.has(securityDepositPctOpt) || options.valueOf(securityDepositPctOpt).isEmpty())
            throw new IllegalArgumentException("no security deposit specified");
        else
            verifyStringIsValidDouble(options.valueOf(securityDepositPctOpt));

        return this;
    }

    public String getPaymentAccountId() {
        return options.valueOf(paymentAccountIdOpt);
    }

    public String getDirection() {
        return options.valueOf(directionOpt);
    }

    public String getCurrencyCode() {
        return options.valueOf(currencyCodeOpt);
    }

    public String getAmount() {
        return options.valueOf(amountOpt);
    }

    public String getMinAmount() {
        return options.has(minAmountOpt) ? options.valueOf(minAmountOpt) : getAmount();
    }

    public boolean isUsingMktPriceMargin() {
        return options.has(mktPriceMarginPctOpt);
    }

    public double getMktPriceMarginPct() {
        return isUsingMktPriceMargin() ? Double.parseDouble(options.valueOf(mktPriceMarginPctOpt)) : 0.00d;
    }

    public String getFixedPrice() {
        return options.has(fixedPriceOpt) ? options.valueOf(fixedPriceOpt) : "0.00";
    }

    public double getSecurityDepositPct() {
        return Double.valueOf(options.valueOf(securityDepositPctOpt));
    }
}
