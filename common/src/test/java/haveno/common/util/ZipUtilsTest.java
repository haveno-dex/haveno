package haveno.common.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.file.Files.createTempDirectory;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ZipUtilsTest {

    @Test
    public void unzipToDirSkipsMatchingEntries() throws Exception {
        File dir = createTempDirectory("ZipUtilsTest").toFile();
        byte[] zip = zip("net/keys/key", "net/tor/torrc", "net\\tor\\hiddenservice\\private_key", "net/tor/hiddenservice/api/private_key",
                "./net/tor/hiddenservice/api/hostname", "net/keys/../tor/hiddenservice/api/backup");

        ZipUtils.unzipToDir(dir, new ByteArrayInputStream(zip), 1024, name -> name.matches("[^/]+/tor/(?!hiddenservice/).*") || name.matches("[^/]+/tor/hiddenservice/api/.*"));

        assertTrue(new File(dir, "net/keys/key").exists());
        assertFalse(new File(dir, "net/tor/torrc").exists());
        assertTrue(new File(dir, "net/tor/hiddenservice/private_key").exists());
        assertFalse(new File(dir, "net/tor/hiddenservice/api").exists());
    }

    @Test
    public void unzipToDirRejectsEntriesOutsideOfDir() throws Exception {
        File dir = createTempDirectory("ZipUtilsTest").toFile();
        byte[] zip = zip("../evil");

        assertThrows(IOException.class, () -> ZipUtils.unzipToDir(dir, new ByteArrayInputStream(zip), 1024, null));

        assertFalse(new File(dir.getParentFile(), "evil").exists());
    }

    private static byte[] zip(String... names) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (String name : names) {
                zos.putNextEntry(new ZipEntry(name));
                zos.write(name.getBytes());
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
