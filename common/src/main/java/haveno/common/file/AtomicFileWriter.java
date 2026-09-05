/*
 * This file is part of Haveno.
 * See LICENSE for licensing information.
 */
package haveno.common.file;

import haveno.common.crypto.Encryption;
import haveno.common.util.Utilities;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Verified, fsynced replacement. An unsupported atomic move is an error, never delete-then-rename. */
public final class AtomicFileWriter {
    private AtomicFileWriter() {}

    @FunctionalInterface
    public interface Verifier {
        void verify(Path candidate) throws Exception;
    }

    public static void write(Path target, Encryption.PayloadWriter writer, Verifier verifier) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        // createTempFile creates owner-only files on POSIX systems, before any key bytes are written.
        Path temp = Files.createTempFile(parent, ".haveno-write-", ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temp.toFile())) {
                writer.writeTo(out);
                out.flush();
                out.getFD().sync();
            }
            verifier.verify(temp);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            syncDirectory(parent);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not verify replacement of " + target.getFileName(), e);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static void write(Path target, byte[] bytes) throws IOException {
        write(target, out -> out.write(bytes), candidate -> {
            if (Files.size(candidate) != bytes.length || !java.security.MessageDigest.isEqual(bytes, Files.readAllBytes(candidate))) {
                throw new IOException("Replacement differs from intended bytes");
            }
        });
    }

    public static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (AccessDeniedException e) {
            // The Windows JDK cannot open directories as FileChannels. The file is still fsynced
            // and atomically replaced; directory-entry durability there is filesystem-dependent.
            if (!Utilities.isWindows()) throw e;
        }
    }
}
