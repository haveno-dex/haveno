package haveno.common.util;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static haveno.common.util.Preconditions.checkDir;
import static java.lang.String.format;
import static java.lang.System.getProperty;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PreconditionsTests {

    @Test
    public void whenDirIsValid_thenDirIsReturned() throws IOException {
        File dir = Files.createTempDirectory("TestDir").toFile();
        File ret = checkDir(dir);
        assertSame(dir, ret);
    }

    @Test
    public void whenDirDoesNotExist_thenThrow() {
        String filepath = getProperty("os.name").startsWith("Windows") ? "C:\\does\\not\\exist" : "/does/not/exist";
        Exception exception = assertThrows(IllegalArgumentException.class, () -> checkDir(new File(filepath)));

        String expectedMessage = format("Directory '%s' does not exist", filepath);
        String actualMessage = exception.getMessage();

        assertTrue(actualMessage.contains(expectedMessage));
    }
}
