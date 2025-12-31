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

import static haveno.cli.opts.OptLabel.OPT_ZIP_BYTES;
import static haveno.cli.opts.OptLabel.OPT_OFFSET;
import static haveno.cli.opts.OptLabel.OPT_TOTAL_LENGTH;
import static haveno.cli.opts.OptLabel.OPT_HAS_MORE;

public class RestoreAccountOptionParser extends AbstractMethodOptionParser {

    @Getter
    private final OptionSpec<String> zipBytesOpt = parser.accepts(OPT_ZIP_BYTES, "Backup zip bytes (base64 encoded)")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<Long> offsetOpt = parser.accepts(OPT_OFFSET, "Offset")
            .withRequiredArg()
            .ofType(Long.class)
            .required();

    @Getter
    private final OptionSpec<Long> totalLengthOpt = parser.accepts(OPT_TOTAL_LENGTH, "Total length")
            .withRequiredArg()
            .ofType(Long.class)
            .required();

    @Getter
    private final OptionSpec<Boolean> hasMoreOpt = parser.accepts(OPT_HAS_MORE, "Has more")
            .withRequiredArg()
            .ofType(Boolean.class)
            .required();

    public RestoreAccountOptionParser(String[] args) {
        super(args);
    }

    public byte[] getZipBytes() {
        // Convert base64 string to byte array
        return java.util.Base64.getDecoder().decode(options.valueOf(zipBytesOpt));
    }

    public long getOffset() {
        return options.valueOf(offsetOpt);
    }

    public long getTotalLength() {
        return options.valueOf(totalLengthOpt);
    }

    public boolean getHasMore() {
        return options.valueOf(hasMoreOpt);
    }
}
