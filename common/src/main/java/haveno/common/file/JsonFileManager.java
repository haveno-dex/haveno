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

package haveno.common.file;

import haveno.common.util.Utilities;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
public class JsonFileManager {
    private final static List<JsonFileManager> INSTANCES = new ArrayList<>();

    public static void shutDownAllInstances() {
        INSTANCES.forEach(JsonFileManager::shutDown);
    }


    @Nullable
    private ThreadPoolExecutor executor;
    private final File dir;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public JsonFileManager(File dir) {
        this.dir = dir;

        if (!dir.exists() && !dir.mkdir()) {
            log.warn("make dir failed");
        }

        INSTANCES.add(this);
    }

    @NotNull
    protected ThreadPoolExecutor getExecutor() {
        if (executor == null) {
            executor = Utilities.getThreadPoolExecutor("JsonFileManagerExecutor", 5, 50, 60);
        }
        return executor;
    }

    public void shutDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    public void writeToDiscThreaded(String json, String fileName) {
        try {
            getExecutor().execute(() -> writeToDisc(json, fileName));
        } catch (RejectedExecutionException e) {
            // Depending on shutdown ordering, a write scheduled before shutdown can arrive after
            // the executor has been shut down. Losing that write is fine; propagating is not.
            log.warn("Skipping JSON write of {} because the executor has been shut down", fileName);
        }
    }

    public void writeToDisc(String json, String fileName) {
        File jsonFile = new File(Paths.get(dir.getAbsolutePath(), fileName + ".json").toString());
        File tempFile = null;
        PrintWriter printWriter = null;
        try {
            // We must not use tempFile.deleteOnExit() here: every write creates a temp file with a
            // new unique name, and each deleteOnExit call adds a path to a JVM-global set which is
            // never purged, growing without bound over the lifetime of the process. The temp file
            // is renamed on success and deleted in the finally block on failure, and deleteOnExit
            // would not run on an abnormal termination anyway.
            tempFile = File.createTempFile("temp", null, dir);

            printWriter = new PrintWriter(tempFile);
            printWriter.println(json);

            // This close call and comment is borrowed from FileManager. Not 100% sure it that is really needed but
            // seems that had fixed in the past and we got reported issues on Windows so that fix might be still
            // required.
            // Close resources before replacing file with temp file because otherwise it causes problems on windows
            // when rename temp file
            printWriter.close();

            FileUtil.renameFile(tempFile, jsonFile);
        } catch (Throwable t) {
            log.error("storageFile " + jsonFile.toString());
            t.printStackTrace();
        } finally {
            if (tempFile != null && tempFile.exists()) {
                log.warn("Temp file still exists after failed save. We will delete it now. storageFile=" + fileName);
                if (!tempFile.delete())
                    log.error("Cannot delete temp file.");
            }

            if (printWriter != null)
                printWriter.close();
        }
    }
}
