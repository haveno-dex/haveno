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
package bisq.common.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ZipUtils {

    /**
     * Zips directory into the output stream. Empty directories are not included.
     * 
     * @param dir The directory to create the zip from.
     * @param out The stream to write to.
     */
    public static void zipDirToStream(File dir, OutputStream out, int bufferSize) throws Exception {

        // Get all files in directory and subdirectories.
        ArrayList<String> fileList = new ArrayList<>();
        getFilesRecursive(dir, fileList);
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (String filePath : fileList) {
                log.info("Compressing: " + filePath);

                // Creates a zip entry.
                String name = filePath.substring(dir.getAbsolutePath().length() + 1);

                ZipEntry zipEntry = new ZipEntry(name);
                zos.putNextEntry(zipEntry);

                // Read file content and write to zip output stream.
                try (FileInputStream fis = new FileInputStream(filePath)) {
                    byte[] buffer = new byte[bufferSize];
                    int length;
                    while ((length = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, length);
                    }

                    // Close the zip entry.
                    zos.closeEntry();
                }
            }
        }
    }

    /**
     * Get files list from the directory recursive to the subdirectory.
     */
    public static void getFilesRecursive(File directory, List<String> fileList) {
        File[] files = directory.listFiles();
        if (files != null && files.length > 0) {
            for (File file : files) {
                if (file.isFile()) {
                    fileList.add(file.getAbsolutePath());
                } else {
                    getFilesRecursive(file, fileList);
                }
            }
        }
    }

    /**
     * Unzips the zipStream into the specified directory, overwriting any files.
     * Existing files are preserved.
     * 
     * @param dir The directory to write to.
     * @param inputStream The raw stream assumed to be in zip format.
     * @param bufferSize The buffer used to read from efficiently.
     */
    public static void unzipToDir(File dir, InputStream inputStream, int bufferSize) throws Exception {
        try (ZipInputStream zipStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            byte[] buffer = new byte[bufferSize];
            int count;
            while ((entry = zipStream.getNextEntry()) != null) {
                File file = new File(dir, entry.getName());
                if (entry.isDirectory()) {
                    file.mkdirs();
                } else {

                    // Make sure folder exists.
                    file.getParentFile().mkdirs();

                    log.info("Unzipped file: " + file.getAbsolutePath());
                    // Don't overwrite the current logs
                    if ("bisq.log".equals(file.getName())) {
                        file = new File(file.getParent() + "/" + "bisq.backup.log");
                        log.info("Unzipped logfile to backup path: " + file.getAbsolutePath());
                    }

                    try (FileOutputStream fileOutput = new FileOutputStream(file)) {
                        while ((count = zipStream.read(buffer)) != -1) {
                            fileOutput.write(buffer, 0, count);
                        }
                    }
                }
                zipStream.closeEntry();
            }
        }
    }

}
