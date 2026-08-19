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
import protobuf.XmrNodeSettings;

import static haveno.cli.opts.OptLabel.OPT_BLOCKCHAIN_PATH;
import static haveno.cli.opts.OptLabel.OPT_BOOTSTRAP_URL;
import static haveno.cli.opts.OptLabel.OPT_STARTUP_FLAGS;
import static haveno.cli.opts.OptLabel.OPT_SYNC_BLOCKCHAIN;
import static java.util.Arrays.asList;
import static joptsimple.internal.Strings.EMPTY;

public class StartXmrNodeOptionParser extends AbstractMethodOptionParser implements MethodOpts {

    final OptionSpec<String> blockchainPathOpt = parser.accepts(OPT_BLOCKCHAIN_PATH, "optional path to monero blockchain")
            .withOptionalArg()
            .defaultsTo(EMPTY);

    final OptionSpec<String> bootstrapUrlOpt = parser.accepts(OPT_BOOTSTRAP_URL, "optional bootstrap daemon url")
            .withOptionalArg()
            .defaultsTo(EMPTY);

    final OptionSpec<String> startupFlagsOpt = parser.accepts(OPT_STARTUP_FLAGS, "optional comma delimited list of monerod startup flags")
            .withOptionalArg()
            .defaultsTo(EMPTY);

    final OptionSpec<Boolean> syncBlockchainOpt = parser.accepts(OPT_SYNC_BLOCKCHAIN, "sync the blockchain (true|false)")
            .withOptionalArg()
            .ofType(boolean.class)
            .defaultsTo(Boolean.TRUE);

    public StartXmrNodeOptionParser(String[] args) {
        super(args);
    }

    public StartXmrNodeOptionParser parse() {
        return (StartXmrNodeOptionParser) super.parse();
    }

    public XmrNodeSettings getXmrNodeSettings() {
        var settings = XmrNodeSettings.newBuilder();
        if (options.has(blockchainPathOpt) && !options.valueOf(blockchainPathOpt).isEmpty())
            settings.setBlockchainPath(options.valueOf(blockchainPathOpt));

        if (options.has(bootstrapUrlOpt) && !options.valueOf(bootstrapUrlOpt).isEmpty())
            settings.setBootstrapUrl(options.valueOf(bootstrapUrlOpt));

        if (options.has(startupFlagsOpt) && !options.valueOf(startupFlagsOpt).isEmpty())
            settings.addAllStartupFlags(asList(options.valueOf(startupFlagsOpt).split(",")));

        settings.setSyncBlockchain(options.has(syncBlockchainOpt) ? options.valueOf(syncBlockchainOpt) : true);
        return settings.build();
    }
}
