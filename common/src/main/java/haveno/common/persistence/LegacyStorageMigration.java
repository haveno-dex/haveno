/*
 * This file is part of Haveno.
 * See LICENSE for licensing information.
 */
package haveno.common.persistence;

import com.google.common.io.ByteStreams;
import com.google.protobuf.CodedInputStream;
import haveno.common.crypto.AuthenticatedEncryption;
import haveno.common.crypto.CryptoException;
import haveno.common.crypto.Encryption;
import haveno.common.file.AtomicFileWriter;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * Runs only while an authenticated legacy key wrapper still exists, before it is replaced.
 * Live plaintext protobuf stores are converted once. Existing backups/exports are preserved
 * for recovery, not silently deleted or rewritten. Subsequent normal reads never accept plaintext.
 */
public final class LegacyStorageMigration {
    private LegacyStorageMigration() {}

    public static String context(String fileName) {
        // Archiving a monolithic store must preserve its logical identity when read for recovery.
        String logicalName = fileName.endsWith(".legacy-backup")
                ? fileName.substring(0, fileName.length() - ".legacy-backup".length()) : fileName;
        return "store/" + logicalName;
    }

    public static void migrate(File directory, SecretKey key) throws IOException {
        if (directory == null || !directory.exists()) return;
        try (var files = Files.newDirectoryStream(directory.toPath())) {
            for (Path path : files) {
                if (Files.isSymbolicLink(path)) throw new IOException("Cannot migrate symlink " + path.getFileName());
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                String name = path.getFileName().toString();
                // Logs have their own encrypted record framing. Exports and write temps are not stores.
                if (name.endsWith(".log") || name.endsWith(".log.pending") || name.endsWith(".tmp")
                        || name.endsWith(".json") || name.startsWith("temp_") || name.startsWith(".haveno-")) continue;
                if (AuthenticatedEncryption.hasEnvelope(path)) continue;
                try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
                    Encryption.verifyPayloadWithHmacStream(in, key);
                    continue; // authenticated legacy stores migrate after a successful normal read
                } catch (CryptoException ignored) {
                    // Plaintext is allowed only here, during an authenticated legacy-account upgrade.
                }
                protobuf.PersistableEnvelope proto;
                try {
                    proto = readPlaintext(path);
                } catch (IOException e) {
                    // Unknown/corrupt files are preserved byte for byte for the normal recovery path.
                    continue;
                }
                AtomicFileWriter.write(path, out -> {
                    try {
                        AuthenticatedEncryption.encryptToStream(proto::writeTo, key, context(name), out);
                    } catch (CryptoException e) {
                        throw new IOException(e);
                    }
                }, candidate -> AuthenticatedEncryption.verifyFile(candidate, key, context(name)));
            }
        }
    }

    private static protobuf.PersistableEnvelope readPlaintext(Path path) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            CodedInputStream coded = CodedInputStream.newInstance(in);
            coded.setSizeLimit(Integer.MAX_VALUE);
            int length = coded.readRawVarint32();
            if (length < 0 || length != Files.size(path) - coded.getTotalBytesRead()) throw new IOException("Invalid plaintext framing");
            int limit = coded.pushLimit(length);
            protobuf.PersistableEnvelope proto = protobuf.PersistableEnvelope.parseFrom(coded);
            if (proto.getMessageCase() == protobuf.PersistableEnvelope.MessageCase.MESSAGE_NOT_SET || !coded.isAtEnd()) {
                throw new IOException("Not a persisted envelope");
            }
            coded.popLimit(limit);
            if (!coded.isAtEnd()) throw new IOException("Trailing plaintext data");
            return proto;
        }
    }

    /** Two authenticated passes over one descriptor, for legacy encrypted stores only. */
    public static <T> T readEncrypted(Path path, SecretKey key, AuthenticatedEncryption.Reader<T> reader)
            throws Exception {
        // verifyPayloadWithHmacStream closes its input, so shield the descriptor for the second pass.
        try (FileInputStream file = new FileInputStream(path.toFile())) {
            InputStream shield = new FilterInputStream(file) {
                @Override public void close() {}
            };
            long length = Encryption.verifyPayloadWithHmacStream(new BufferedInputStream(shield), key);
            file.getChannel().position(0);
            InputStream decrypted = Encryption.decryptStream(new BufferedInputStream(shield), key);
            Mac mac = Encryption.createHmac(key);
            InputStream payload = new FilterInputStream(ByteStreams.limit(decrypted, length)) {
                @Override public int read() throws IOException {
                    int b = in.read();
                    if (b >= 0) mac.update((byte) b);
                    return b;
                }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    int n = in.read(b, off, len);
                    if (n > 0) mac.update(b, off, n);
                    return n;
                }
                @Override public long skip(long n) throws IOException {
                    if (n <= 0) return 0;
                    byte[] buffer = new byte[(int) Math.min(8192, n)];
                    long remaining = n;
                    while (remaining > 0) {
                        int count = read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (count == -1) break;
                        remaining -= count;
                    }
                    return n - remaining;
                }
            };
            T value = reader.read(payload);
            payload.transferTo(OutputStream.nullOutputStream());
            byte[] tag = decrypted.readNBytes(32);
            if (tag.length != 32 || !MessageDigest.isEqual(mac.doFinal(), tag) || decrypted.read() != -1) {
                throw new IOException("Legacy store authentication failed");
            }
            return value;
        }
    }
}
