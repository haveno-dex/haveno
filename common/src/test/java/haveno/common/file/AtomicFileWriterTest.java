package haveno.common.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

class AtomicFileWriterTest {
    @TempDir Path directory;

    @Test
    void serializationAndVerificationFailuresLeaveOriginalUntouched() throws Exception {
        Path file = directory.resolve("key");
        Files.writeString(file, "original");
        assertThrows(IOException.class, () -> AtomicFileWriter.write(file, out -> {
            out.write(1);
            throw new IOException("disk full");
        }, candidate -> fail("must not verify incomplete writes")));
        assertEquals("original", Files.readString(file));
        assertThrows(IOException.class, () -> AtomicFileWriter.write(file, out -> out.write(2), candidate -> {
            throw new IOException("read-back mismatch");
        }));
        assertEquals("original", Files.readString(file));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void verifiedReplacementIsDurableAndPrivate() throws Exception {
        Path file = directory.resolve("key");
        AtomicFileWriter.write(file, new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(file));
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        }
    }
}
