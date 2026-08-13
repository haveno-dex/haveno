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


import haveno.proto.grpc.XmrDestination;
import joptsimple.OptionSpec;

import java.util.ArrayList;
import java.util.List;

import static haveno.cli.CurrencyFormat.toPiconeros;
import static haveno.cli.opts.OptLabel.OPT_DESTINATIONS;
import static java.lang.String.format;

public class CreateXmrTxOptionParser extends AbstractMethodOptionParser implements MethodOpts {

    final OptionSpec<String> destinationsOpt = parser.accepts(OPT_DESTINATIONS,
                    "comma delimited list of destinations (address:xmr-amount)")
            .withRequiredArg();

    public CreateXmrTxOptionParser(String[] args) {
        super(args);
    }

    public CreateXmrTxOptionParser parse() {
        super.parse();

        // Short circuit opt validation if user just wants help.
        if (options.has(helpOpt))
            return this;

        if (!options.has(destinationsOpt) || options.valueOf(destinationsOpt).isEmpty())
            throw new IllegalArgumentException("no destinations specified");

        getDestinations(); // validate the destinations opt format

        return this;
    }

    public List<XmrDestination> getDestinations() {
        List<XmrDestination> destinations = new ArrayList<>();
        for (String destination : options.valueOf(destinationsOpt).split(",")) {
            String[] addressAndAmount = destination.split(":");
            if (addressAndAmount.length != 2)
                throw new IllegalArgumentException(format("'%s' is not an address:xmr-amount pair", destination));

            destinations.add(XmrDestination.newBuilder()
                    .setAddress(addressAndAmount[0])
                    .setAmount(String.valueOf(toPiconeros(addressAndAmount[1])))
                    .build());
        }
        return destinations;
    }
}
