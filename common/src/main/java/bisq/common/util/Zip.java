package bisq.common.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class Zip {
	private final List<String> fileList = new ArrayList<>();
//	
//	public void zipDirectory(File fileToZip, String zipFile) throws IOException {
//		compressDirectory(fileToZip.getAbsolutePath(), zipFile;)
//	}
	
    public void compressDirectory(String dir, String zipFile) {
        File directory = new File(dir);
        getFileList(directory);

        try (
        	FileOutputStream fos = new FileOutputStream(zipFile);
        	ZipOutputStream zos = new ZipOutputStream(fos)) {

            for (String filePath : fileList) {
                System.out.println("Compressing: " + filePath);

                // Creates a zip entry.
                String name = filePath.substring(directory.getAbsolutePath().length() + 1);

                ZipEntry zipEntry = new ZipEntry(name);
                zos.putNextEntry(zipEntry);

                // Read file content and write to zip output stream.
                try (FileInputStream fis = new FileInputStream(filePath)) {
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, length);
                    }

                    // Close the zip entry.
                    zos.closeEntry();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            zos.close();
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private void getFileList(File directory) {
        File[] files = directory.listFiles();
        if (files != null && files.length > 0) {
            for (File file : files) {
                if (file.isFile()) {
                    fileList.add(file.getAbsolutePath());
                } else {
                    getFileList(file);
                }
            }
        }

    }
	
//	private static void zipFile(File fileToZip, String fileName, ZipOutputStream zipOut) throws IOException {
//        if (fileToZip.isHidden()) {
//            return;
//        }
//        if (!fileToZip.exists()) return;
//        if (fileToZip.isDirectory()) {
//            if (fileName.endsWith("/")) {
//                zipOut.putNextEntry(new ZipEntry(fileName));
//                zipOut.closeEntry();
//            } else {
//                zipOut.putNextEntry(new ZipEntry(fileName + "/"));
//                zipOut.closeEntry();
//            }
//            File[] children = fileToZip.listFiles();
//            for (File childFile : children) {
//                zipFile(childFile, fileName + "/" + childFile.getName(), zipOut);
//            }
//            return;
//        }
//        FileInputStream fis = new FileInputStream(fileToZip);
//        ZipEntry zipEntry = new ZipEntry(fileName);
//        zipOut.putNextEntry(zipEntry);
//        byte[] bytes = new byte[1024];
//        int length;
//        while ((length = fis.read(bytes)) >= 0) {
//            zipOut.write(bytes, 0, length);
//        }
//        fis.close();
//    }
}
