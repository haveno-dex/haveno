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

import static haveno.cli.opts.OptLabel.OPT_DISPUTE_ID;
import static haveno.cli.opts.OptLabel.OPT_MESSAGE;

public class SendDisputeChatMessageOptionParser extends AbstractMethodOptionParser implements MethodOpts {

    final OptionSpec<String> disputeIdOpt = parser.accepts(OPT_DISPUTE_ID, "id of dispute")
            .withRequiredArg();

    final OptionSpec<String> messageOpt = parser.accepts(OPT_MESSAGE, "chat message; multi word messages must be double quoted")
            .withRequiredArg();

    public SendDisputeChatMessageOptionParser(String[] args) {
        super(args);
    }

    public SendDisputeChatMessageOptionParser parse() {
        super.parse();

        // Short circuit opt validation if user just wants help.
        if (options.has(helpOpt))
            return this;

        if (!options.has(disputeIdOpt) || options.valueOf(disputeIdOpt).isEmpty())
            throw new IllegalArgumentException("no dispute id specified");

        if (!options.has(messageOpt) || options.valueOf(messageOpt).isEmpty())
            throw new IllegalArgumentException("no message specified");

        return this;
    }

    public String getDisputeId() {
        return options.valueOf(disputeIdOpt);
    }

    public String getMessage() {
        return options.valueOf(messageOpt);
    }
}
