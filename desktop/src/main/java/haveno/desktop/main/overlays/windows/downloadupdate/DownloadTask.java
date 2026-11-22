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

package haveno.desktop.main.overlays.windows.downloadupdate;

import com.google.common.collect.Lists;
import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;
import haveno.common.file.FileUtil;
import haveno.network.Socks5ProxyProvider;
import haveno.network.http.Socks5FileDownloader;
import javafx.concurrent.Task;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Getter
public class DownloadTask extends Task<List<HavenoInstaller.FileDescriptor>> {
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private String fileName = null;
    private final List<HavenoInstaller.FileDescriptor> fileDescriptors;
    private final String saveDir;
    @Nullable
    private final Socks5ProxyProvider socks5ProxyProvider;

    /**
     * Prepares a task to download a file from {@code fileDescriptors} to the system's download dir.
     */
    public DownloadTask(final HavenoInstaller.FileDescriptor fileDescriptor) {
        this(Lists.newArrayList(fileDescriptor), System.getProperty("java.io.tmpdir"), null);
    }

    public DownloadTask(final HavenoInstaller.FileDescriptor fileDescriptor, final String saveDir) {
        this(Lists.newArrayList(fileDescriptor), saveDir, null);
    }

    public DownloadTask(final List<HavenoInstaller.FileDescriptor> fileDescriptors) {
        this(Lists.newArrayList(fileDescriptors), System.getProperty("java.io.tmpdir"), null);
    }

    /**
     * Prepares a task to download a file from {@code fileDescriptors} to {@code saveDir}.
     *
     * @param fileDescriptors HTTP URL of the file to be downloaded
     * @param saveDir         path of the directory to save the file
     */
    public DownloadTask(final List<HavenoInstaller.FileDescriptor> fileDescriptors, final String saveDir,
                        @Nullable final Socks5ProxyProvider socks5ProxyProvider) {
        super();
        this.fileDescriptors = fileDescriptors;
        this.saveDir = saveDir;
        this.socks5ProxyProvider = socks5ProxyProvider;
        log.info("Starting DownloadTask with file:{}, saveDir:{}, nr of files: {}", fileDescriptors, saveDir, fileDescriptors.size());
    }

    /**
     * Starts the task and therefore the actual download.
     *
     * @return A reference to the created file or {@code null} if no file could be found at the provided URL
     * @throws IOException Forwarded exceotions from HttpURLConnection and file handling methods
     */
    @Override
    protected List<HavenoInstaller.FileDescriptor> call() throws IOException {
        log.debug("DownloadTask started...");

        String partialSaveFilePath = saveDir + (saveDir.endsWith(File.separator) ? "" : File.separator);

        // go twice over the fileDescriptors: first fill in the saveFile, then download the file and fill in the status
        return fileDescriptors.stream()
                .map(fileDescriptor -> {
                    fileDescriptor.setSaveFile(new File(partialSaveFilePath + fileDescriptor.getFileName()));

                    log.info("Downloading {}", fileDescriptor.getLoadUrl());
                    try {
                        updateMessage(fileDescriptor.getFileName());
                        download(new URL(fileDescriptor.getLoadUrl()), fileDescriptor.getSaveFile());
                        log.info("Download for {} done", fileDescriptor.getLoadUrl());
                        fileDescriptor.setDownloadStatus(HavenoInstaller.DownloadStatusEnum.OK);
                    } catch (Exception e) {
                        fileDescriptor.setDownloadStatus(HavenoInstaller.DownloadStatusEnum.FAIL);
                        log.error("Error downloading file:" + fileDescriptor.toString(), e);
                        e.printStackTrace();
                    }
                    return fileDescriptor;
                })
                .collect(Collectors.toList());
    }

    private void download(URL url, File outputFile) throws IOException {
        if (outputFile.exists()) {
            log.info("We found an existing file and rename it as *.backup.");
            FileUtil.renameFile(outputFile, new File(outputFile.getAbsolutePath() + ".backup"));
        }

        boolean isHttp = Socks5FileDownloader.isHttpOrHttps(url);
        Socks5Proxy socks5Proxy = isHttp ? getSocks5Proxy() : null;
        if (isHttp && socks5Proxy == null) {
            log.warn("No SOCKS5/Tor proxy available; downloading update file directly, which exposes the real IP address.");
        }
        Socks5FileDownloader.download(url, outputFile, socks5Proxy, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS,
                (bytesRead, totalBytes) -> {
                    log.trace("Progress: {}/{}", bytesRead, totalBytes);
                    updateProgress(bytesRead, totalBytes);
                });
    }

    @Nullable
    private Socks5Proxy getSocks5Proxy() {
        return socks5ProxyProvider == null ? null : socks5ProxyProvider.getSocks5ProxyForHttp();
    }
}

