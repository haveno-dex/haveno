package haveno.common.file;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

import static java.nio.file.Files.createTempDirectory;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FileUtilTest {

    @Test
    public void deleteDirectoryKeepsNestedExcludeAndItsAncestors() throws IOException {
        File root = createTempDirectory("FileUtilTest").toFile();
        File keep = new File(root, "a/b/keep");
        assertTrue(keep.mkdirs());
        assertTrue(new File(keep, "key").createNewFile());
        assertTrue(new File(root, "a/b/other").createNewFile());
        assertTrue(new File(root, "a/sibling").mkdirs());
        assertTrue(new File(root, "top").createNewFile());

        assertFalse(FileUtil.deleteDirectory(root, keep, false));

        assertTrue(new File(keep, "key").exists());
        assertFalse(new File(root, "a/b/other").exists());
        assertFalse(new File(root, "a/sibling").exists());
        assertFalse(new File(root, "top").exists());
        FileUtil.deleteDirectory(root);
    }

    @Test
    public void deleteDirectoryDeletesRootWhenExcludeIsAbsent() throws IOException {
        File root = createTempDirectory("FileUtilTest").toFile();
        assertTrue(new File(root, "a/b").mkdirs());
        assertTrue(new File(root, "a/b/file").createNewFile());

        assertTrue(FileUtil.deleteDirectory(root, new File(root, "missing"), false));

        assertFalse(root.exists());
    }
}
