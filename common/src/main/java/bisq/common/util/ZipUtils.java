package bisq.common.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ZipUtils {
    
    public static void compressDirectory(File sourceDirectory, File zipFile) {
        List<String> fileList = new ArrayList<>();
        getFileList(sourceDirectory, fileList);
        try (
            FileOutputStream fileOutputStream = new FileOutputStream(zipFile);
            ZipOutputStream zipOutputStream = new ZipOutputStream(fileOutputStream)) {
            for (String filePath : fileList) {
                log.error("Compressing:  " + filePath);
                String name = filePath.substring(sourceDirectory.getAbsolutePath().length() + 1);
                ZipEntry zipEntry = new ZipEntry(name);
                zipOutputStream.putNextEntry(zipEntry);
                try (FileInputStream fileInputStream = new FileInputStream(filePath)) {
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = fileInputStream.read(buffer)) > 0) {
                        zipOutputStream.write(buffer, 0, length);
                    }
                    zipOutputStream.closeEntry();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            zipOutputStream.close();
            fileOutputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void getFileList(File sourceDirectory, List<String> fileList ) {
        File[] files = sourceDirectory.listFiles();
        if (files != null && files.length > 0) {
            for (File file : files) {
                if (file.isFile()) {
                    fileList.add(file.getAbsolutePath());
                } else {
                    getFileList(file, fileList);
                }
            }
        }
    }

    public static void decompress(File zipFilePath, File destinationDirectory) {
        if(!destinationDirectory.exists()) destinationDirectory.mkdirs();
        FileInputStream fileInputStream;
        byte[] buffer = new byte[1024];
        try {
            fileInputStream = new FileInputStream(zipFilePath);
            ZipInputStream zipInputputStream = new ZipInputStream(fileInputStream);
            ZipEntry zipEntry = zipInputputStream.getNextEntry();
            while(zipEntry != null){
                String fileName = zipEntry.getName();
                File newFile = new File(destinationDirectory + File.separator + fileName);
                new File(newFile.getParent()).mkdirs();
                FileOutputStream fileOutputStream = new FileOutputStream(newFile);
                int len;
                while ((len = zipInputputStream.read(buffer)) > 0) {
                fileOutputStream.write(buffer, 0, len);
                }
                fileOutputStream.close();
                zipInputputStream.closeEntry();
                zipEntry = zipInputputStream.getNextEntry();
            }
            zipInputputStream.closeEntry();
            zipInputputStream.close();
            fileInputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
