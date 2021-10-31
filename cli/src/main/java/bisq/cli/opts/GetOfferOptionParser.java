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

import static haveno.cli.opts.OptLabel.OPT_OFFER_ID;

public class GetOfferOptionParser extends AbstractMethodOptionParser implements MethodOpts {

    final OptionSpec<String> offerIdOpt = parser.accepts(OPT_OFFER_ID, "id of offer to get")
            .withRequiredArg();

    public GetOfferOptionParser(String[] args) {
        super(args);
    }

    public GetOfferOptionParser parse() {
        super.parse();

        // Short circuit opt validation if user just wants help.
        if (options.has(helpOpt))
            return this;

        if (!options.has(offerIdOpt) || options.valueOf(offerIdOpt).isEmpty())
            throw new IllegalArgumentException("no offer id specified");

        return this;
    }

    public String getOfferId() {
        return options.valueOf(offerIdOpt);
    }
}
