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
import haveno.proto.grpc.UrlConnection;

import static haveno.cli.opts.OptLabel.OPT_URL;
import static haveno.cli.opts.OptLabel.OPT_USERNAME;
import static haveno.cli.opts.OptLabel.OPT_PASSWORD;
import static haveno.cli.opts.OptLabel.OPT_PRIORITY;

public class AddConnectionOptionParser extends AbstractMethodOptionParser {

    @Getter
    private final OptionSpec<String> urlOpt = parser.accepts(OPT_URL, "Connection URL")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<String> usernameOpt = parser.accepts(OPT_USERNAME, "Username")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<String> passwordOpt = parser.accepts(OPT_PASSWORD, "Password")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<Integer> priorityOpt = parser.accepts(OPT_PRIORITY, "Priority")
            .withRequiredArg()
            .ofType(Integer.class)
            .required();

    public AddConnectionOptionParser(String[] args) {
        super(args);
    }

    public UrlConnection getConnection() {
        return UrlConnection.newBuilder()
                .setUrl(options.valueOf(urlOpt))
                .setUsername(options.valueOf(usernameOpt))
                .setPassword(options.valueOf(passwordOpt))
                .setPriority(options.valueOf(priorityOpt))
                .build();
    }
}
