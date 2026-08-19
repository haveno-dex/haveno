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

import static haveno.cli.opts.OptLabel.OPT_BACKUP_FILE;
import static joptsimple.internal.Strings.EMPTY;

public class BackupAccountOptionParser extends AbstractMethodOptionParser implements MethodOpts {

    final OptionSpec<String> backupFileOpt = parser.accepts(OPT_BACKUP_FILE, "optional path to write the account backup zip")
            .withOptionalArg()
            .defaultsTo(EMPTY);

    public BackupAccountOptionParser(String[] args) {
        super(args);
    }

    public BackupAccountOptionParser parse() {
        return (BackupAccountOptionParser) super.parse();
    }

    public String getBackupFile() {
        return options.has(backupFileOpt) ? options.valueOf(backupFileOpt) : "";
    }
}
