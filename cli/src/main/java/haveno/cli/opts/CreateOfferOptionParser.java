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
import static haveno.cli.opts.OptLabel.OPT_DIRECTION;
import static haveno.cli.opts.OptLabel.OPT_CURRENCY_CODE;
import static haveno.cli.opts.OptLabel.OPT_AMOUNT;
import static haveno.cli.opts.OptLabel.OPT_MIN_AMOUNT;
import static haveno.cli.opts.OptLabel.OPT_FIXED_PRICE;
import static haveno.cli.opts.OptLabel.OPT_MKT_PRICE_MARGIN;
import static haveno.cli.opts.OptLabel.OPT_SECURITY_DEPOSIT;
import static haveno.cli.opts.OptLabel.OPT_TRIGGER_PRICE;

public class CreateOfferOptionParser extends AbstractMethodOptionParser {

    @Getter
    private final OptionSpec<String> paymentAccountIdOpt = parser.accepts(OPT_PAYMENT_ACCOUNT_ID, "Payment Account ID")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<String> directionOpt = parser.accepts(OPT_DIRECTION, "Direction (buy|sell)")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<String> currencyCodeOpt = parser.accepts(OPT_CURRENCY_CODE, "Currency Code")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<String> amountOpt = parser.accepts(OPT_AMOUNT, "Amount")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<String> minAmountOpt = parser.accepts(OPT_MIN_AMOUNT, "Minimum Amount")
            .withRequiredArg()
            .defaultsTo("");

    @Getter
    private final OptionSpec<String> fixedPriceOpt = parser.accepts(OPT_FIXED_PRICE, "Fixed Price")
            .withRequiredArg()
            .defaultsTo("");

    @Getter
    private final OptionSpec<String> marketPriceMarginOpt = parser.accepts(OPT_MKT_PRICE_MARGIN, "Market Price Margin")
            .withRequiredArg()
            .defaultsTo("");

    @Getter
    private final OptionSpec<String> securityDepositOpt = parser.accepts(OPT_SECURITY_DEPOSIT, "Security Deposit")
            .withRequiredArg()
            .defaultsTo("");

    @Getter
    private final OptionSpec<String> triggerPriceOpt = parser.accepts(OPT_TRIGGER_PRICE, "Trigger Price")
            .withRequiredArg()
            .defaultsTo("");

    public CreateOfferOptionParser(String[] args) {
        super(args);
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
        return options.valueOf(minAmountOpt);
    }

    public String getFixedPrice() {
        return options.valueOf(fixedPriceOpt);
    }

    public String getMarketPriceMargin() {
        return options.valueOf(marketPriceMarginOpt);
    }

    public String getSecurityDeposit() {
        return options.valueOf(securityDepositOpt);
    }

    public String getTriggerPrice() {
        return options.valueOf(triggerPriceOpt);
    }

    public boolean isUsingMktPriceMargin() {
        return !options.valueOf(marketPriceMarginOpt).isEmpty();
    }
}
