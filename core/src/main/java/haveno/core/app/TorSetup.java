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

package haveno.core.app;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import haveno.common.config.Config;
import haveno.common.file.FileUtil;
import haveno.common.handlers.ErrorMessageHandler;
import static haveno.common.util.Preconditions.checkDir;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class TorSetup {
    private final File torDir;

    @Inject
    public TorSetup(@Named(Config.TOR_DIR) File torDir) {
        this.torDir = checkDir(torDir);
    }

    // Should only be called if needed. Slows down Tor startup from about 5 sec. to 30 sec. if it gets deleted.
    public void cleanupTorFiles(@Nullable Runnable resultHandler, @Nullable ErrorMessageHandler errorMessageHandler) {
        File hiddenservice = new File(Paths.get(torDir.getAbsolutePath(), "hiddenservice").toString());
        try {
            FileUtil.deleteDirectory(torDir, hiddenservice, true);
            if (resultHandler != null)
                resultHandler.run();
        } catch (IOException e) {
            e.printStackTrace();
            log.error(e.toString());
            if (errorMessageHandler != null)
                errorMessageHandler.handleErrorMessage(e.toString());
        }
    }
}
