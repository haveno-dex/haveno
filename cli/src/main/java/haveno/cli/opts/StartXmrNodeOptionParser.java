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
import protobuf.XmrNodeSettings;

import static haveno.cli.opts.OptLabel.OPT_BLOCKCHAIN_PATH;
import static haveno.cli.opts.OptLabel.OPT_BOOTSTRAP_URL;
import static haveno.cli.opts.OptLabel.OPT_STARTUP_FLAGS;
import static haveno.cli.opts.OptLabel.OPT_SYNC_BLOCKCHAIN;

public class StartXmrNodeOptionParser extends AbstractMethodOptionParser {

    @Getter
    private final OptionSpec<String> blockchainPathOpt = parser.accepts(OPT_BLOCKCHAIN_PATH, "Blockchain path")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<String> bootstrapUrlOpt = parser.accepts(OPT_BOOTSTRAP_URL, "Bootstrap URL")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<String> startupFlagsOpt = parser.accepts(OPT_STARTUP_FLAGS, "Startup flags")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<Boolean> syncBlockchainOpt = parser.accepts(OPT_SYNC_BLOCKCHAIN, "Sync blockchain")
            .withRequiredArg()
            .ofType(Boolean.class)
            .required();

    public StartXmrNodeOptionParser(String[] args) {
        super(args);
    }

    public XmrNodeSettings getSettings() {
        return XmrNodeSettings.newBuilder()
                .setBlockchainPath(options.valueOf(blockchainPathOpt))
                .setBootstrapUrl(options.valueOf(bootstrapUrlOpt))
                .addStartupFlags(options.valueOf(startupFlagsOpt))
                .setSyncBlockchain(options.valueOf(syncBlockchainOpt))
                .build();
    }
}
