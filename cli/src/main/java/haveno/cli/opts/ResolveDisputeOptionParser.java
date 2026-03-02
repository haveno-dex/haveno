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
import protobuf.DisputeResult;

import static haveno.cli.opts.OptLabel.OPT_TRADE_ID;
import static haveno.cli.opts.OptLabel.OPT_WINNER;
import static haveno.cli.opts.OptLabel.OPT_REASON;
import static haveno.cli.opts.OptLabel.OPT_SUMMARY_NOTES;
import static haveno.cli.opts.OptLabel.OPT_CUSTOM_PAYOUT_AMOUNT;

public class ResolveDisputeOptionParser extends AbstractMethodOptionParser {

    @Getter
    private final OptionSpec<String> tradeIdOpt = parser.accepts(OPT_TRADE_ID, "Trade ID")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<String> winnerOpt = parser.accepts(OPT_WINNER, "Winner (BUYER/SELLER)")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<String> reasonOpt = parser.accepts(OPT_REASON, "Reason")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<String> summaryNotesOpt = parser.accepts(OPT_SUMMARY_NOTES, "Summary notes")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<Long> customPayoutAmountOpt = parser.accepts(OPT_CUSTOM_PAYOUT_AMOUNT, "Custom payout amount")
            .withRequiredArg()
            .ofType(Long.class)
            .required();

    public ResolveDisputeOptionParser(String[] args) {
        super(args);
    }

    public String getTradeId() {
        return options.valueOf(tradeIdOpt);
    }

    public DisputeResult.Winner getWinner() {
        return DisputeResult.Winner.valueOf(options.valueOf(winnerOpt).toUpperCase());
    }

    public DisputeResult.Reason getReason() {
        return DisputeResult.Reason.valueOf(options.valueOf(reasonOpt).toUpperCase());
    }

    public String getSummaryNotes() {
        return options.valueOf(summaryNotesOpt);
    }

    public long getCustomPayoutAmount() {
        return options.valueOf(customPayoutAmountOpt);
    }
}
