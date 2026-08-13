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

import static haveno.cli.opts.OptLabel.OPT_MESSAGE;
import static haveno.cli.opts.OptLabel.OPT_TRADE_ID;

public class SendChatMessageOptionParser extends AbstractMethodOptionParser implements MethodOpts {

    final OptionSpec<String> tradeIdOpt = parser.accepts(OPT_TRADE_ID, "id of trade")
            .withRequiredArg();

    final OptionSpec<String> messageOpt = parser.accepts(OPT_MESSAGE, "chat message; multi word messages must be double quoted")
            .withRequiredArg();

    public SendChatMessageOptionParser(String[] args) {
        super(args);
    }

    public SendChatMessageOptionParser parse() {
        super.parse();

        // Short circuit opt validation if user just wants help.
        if (options.has(helpOpt))
            return this;

        if (!options.has(tradeIdOpt) || options.valueOf(tradeIdOpt).isEmpty())
            throw new IllegalArgumentException("no trade id specified");

        if (!options.has(messageOpt) || options.valueOf(messageOpt).isEmpty())
            throw new IllegalArgumentException("no message specified");

        return this;
    }

    public String getTradeId() {
        return options.valueOf(tradeIdOpt);
    }

    public String getMessage() {
        return options.valueOf(messageOpt);
    }
}
