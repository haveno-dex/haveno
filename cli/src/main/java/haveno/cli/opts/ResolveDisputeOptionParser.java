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
import protobuf.DisputeResult;

import static haveno.cli.CurrencyFormat.toPiconeros;
import static haveno.cli.opts.OptLabel.OPT_CUSTOM_PAYOUT_AMOUNT;
import static haveno.cli.opts.OptLabel.OPT_REASON;
import static haveno.cli.opts.OptLabel.OPT_SUMMARY_NOTES;
import static haveno.cli.opts.OptLabel.OPT_TRADE_ID;
import static haveno.cli.opts.OptLabel.OPT_WINNER;
import static java.lang.String.format;
import static java.util.Arrays.stream;
import static joptsimple.internal.Strings.EMPTY;

public class ResolveDisputeOptionParser extends AbstractMethodOptionParser implements MethodOpts {

    final OptionSpec<String> tradeIdOpt = parser.accepts(OPT_TRADE_ID, "id of disputed trade")
            .withRequiredArg();

    final OptionSpec<String> winnerOpt = parser.accepts(OPT_WINNER, "dispute winner (buyer|seller)")
            .withRequiredArg();

    final OptionSpec<String> reasonOpt = parser.accepts(OPT_REASON,
                    "dispute reason (bank_problems|no_reply|scam|protocol_violation|other|...)")
            .withOptionalArg()
            .defaultsTo(DisputeResult.Reason.OTHER.name());

    final OptionSpec<String> summaryNotesOpt = parser.accepts(OPT_SUMMARY_NOTES,
                    "dispute summary notes; multi word notes must be double quoted")
            .withOptionalArg()
            .defaultsTo(EMPTY);

    final OptionSpec<String> customPayoutAmountOpt = parser.accepts(OPT_CUSTOM_PAYOUT_AMOUNT,
                    "optional custom xmr payout amount to the winner")
            .withOptionalArg()
            .defaultsTo("0");

    public ResolveDisputeOptionParser(String[] args) {
        super(args);
    }

    public ResolveDisputeOptionParser parse() {
        super.parse();

        // Short circuit opt validation if user just wants help.
        if (options.has(helpOpt))
            return this;

        if (!options.has(tradeIdOpt) || options.valueOf(tradeIdOpt).isEmpty())
            throw new IllegalArgumentException("no trade id specified");

        if (!options.has(winnerOpt) || options.valueOf(winnerOpt).isEmpty())
            throw new IllegalArgumentException("no dispute winner (buyer|seller) specified");

        getWinner();    // validate the winner opt
        getReason();    // validate the reason opt

        return this;
    }

    public String getTradeId() {
        return options.valueOf(tradeIdOpt);
    }

    public DisputeResult.Winner getWinner() {
        var winner = options.valueOf(winnerOpt).toUpperCase();
        if (!winner.equals(DisputeResult.Winner.BUYER.name()) && !winner.equals(DisputeResult.Winner.SELLER.name()))
            throw new IllegalArgumentException("winner must be buyer|seller");

        return DisputeResult.Winner.valueOf(winner);
    }

    public DisputeResult.Reason getReason() {
        var reason = options.valueOf(reasonOpt).toUpperCase();
        if (stream(DisputeResult.Reason.values()).noneMatch(r -> r.name().equals(reason)))
            throw new IllegalArgumentException(format("'%s' is not a dispute reason", options.valueOf(reasonOpt)));

        return DisputeResult.Reason.valueOf(reason);
    }

    public String getSummaryNotes() {
        return options.has(summaryNotesOpt) ? options.valueOf(summaryNotesOpt) : "";
    }

    public long getCustomPayoutAmount() {
        return options.has(customPayoutAmountOpt) ? toPiconeros(options.valueOf(customPayoutAmountOpt)) : 0;
    }
}
