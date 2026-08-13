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

import java.util.List;

import static haveno.cli.opts.OptLabel.OPT_METADATAS;
import static java.util.Arrays.asList;

public class RelayXmrTxsOptionParser extends AbstractMethodOptionParser implements MethodOpts {

    final OptionSpec<String> metadatasOpt = parser.accepts(OPT_METADATAS,
                    "comma delimited list of tx metadata from createxmrtx or createxmrsweeptxs")
            .withRequiredArg();

    public RelayXmrTxsOptionParser(String[] args) {
        super(args);
    }

    public RelayXmrTxsOptionParser parse() {
        super.parse();

        // Short circuit opt validation if user just wants help.
        if (options.has(helpOpt))
            return this;

        if (!options.has(metadatasOpt) || options.valueOf(metadatasOpt).isEmpty())
            throw new IllegalArgumentException("no tx metadata specified");

        return this;
    }

    public List<String> getMetadatas() {
        return asList(options.valueOf(metadatasOpt).split(","));
    }
}
