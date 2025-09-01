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

import static haveno.cli.opts.OptLabel.OPT_DISPUTE_ID;
import static haveno.cli.opts.OptLabel.OPT_MESSAGE;

public class SendDisputeChatMessageOptionParser extends AbstractMethodOptionParser {

    @Getter
    private final OptionSpec<String> disputeIdOpt = parser.accepts(OPT_DISPUTE_ID, "Dispute ID")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<String> messageOpt = parser.accepts(OPT_MESSAGE, "Message")
            .withRequiredArg()
            .required();

    public SendDisputeChatMessageOptionParser(String[] args) {
        super(args);
    }

    public String getDisputeId() {
        return options.valueOf(disputeIdOpt);
    }

    public String getMessage() {
        return options.valueOf(messageOpt);
    }
}
