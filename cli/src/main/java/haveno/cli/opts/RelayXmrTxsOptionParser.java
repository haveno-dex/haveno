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
import java.util.List;

import static haveno.cli.opts.OptLabel.OPT_METADATAS;

public class RelayXmrTxsOptionParser extends AbstractMethodOptionParser {

    @Getter
    private final OptionSpec<String> metadatasOpt = parser.accepts(OPT_METADATAS, "Transaction metadatas (metadata1,metadata2,...)")
            .withRequiredArg()
            .required();

    public RelayXmrTxsOptionParser(String[] args) {
        super(args);
    }

    public List<String> getMetadatas() {
        String metadatas = options.valueOf(metadatasOpt);
        return List.of(metadatas.split(","));
    }
}
