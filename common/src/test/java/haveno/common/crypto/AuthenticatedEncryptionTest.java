package haveno.common.crypto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticatedEncryptionTest {
    @TempDir Path directory;
    private final SecretKey key = Encryption.generateSecretKey(256);

    @Test
    void independentOpenSslAndRfc5869Vector() throws Exception {
        // Generated independently with Python hmac (RFC 5869 extract/expand) and OpenSSL aes-256-ctr.
        byte[] envelope = HexFormat.of().parseHex("48564e45ff000002000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f000102030405060708090a0b0c0d0e0fc42d75907d3004b913c63b7c4e21ba77a5ba841ecc4b9b191b21395c0c4d70805053c7f7e882bf05927c4bae8788adc7520c7966556ecf3ab76740fe0c3e38e354f2a8e336567356830c72e4adfe2be031f2eeb7b240f4ddcef4e0bc55c2684fe9");
        byte[] master = new byte[32];
        byte[] expected = new byte[65];
        for (int i = 0; i < master.length; i++) master[i] = (byte) i;
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) i;
        assertArrayEquals(expected, AuthenticatedEncryption.decrypt(envelope, Encryption.getSecretKeyFromBytes(master), "test/vector"));
    }

    @Test
    void arraysAndStreamsRoundTripWithFreshRandomness() throws Exception {
        for (int size : new int[]{0, 1, 15, 16, 17, 65535, 65536, 65537, 5_000_000}) {
            byte[] plaintext = new byte[size];
            new Random(size).nextBytes(plaintext);
            byte[] encrypted = AuthenticatedEncryption.encrypt(plaintext, key, "test/data");
            assertFalse(Arrays.equals(encrypted, AuthenticatedEncryption.encrypt(plaintext, key, "test/data")));
            assertArrayEquals(plaintext, AuthenticatedEncryption.decrypt(encrypted, key, "test/data"));
            Path path = directory.resolve("data");
            try (OutputStream out = Files.newOutputStream(path)) {
                AuthenticatedEncryption.encryptToStream(stream -> {
                    for (int off = 0; off < plaintext.length; off += 4093) {
                        stream.write(plaintext, off, Math.min(4093, plaintext.length - off));
                    }
                }, key, "test/data", out);
            }
            byte[] read = AuthenticatedEncryption.readFile(path, key, "test/data", in -> in.readAllBytes());
            assertArrayEquals(plaintext, read);
            assertArrayEquals(plaintext, AuthenticatedEncryption.decrypt(Files.readAllBytes(path), key, "test/data"));
        }
    }

    @Test
    void everyByteIsAuthenticatedAndAllTruncationsFail() throws Exception {
        byte[] encrypted = AuthenticatedEncryption.encrypt(new byte[65], key, "test/data");
        for (int i = 0; i < encrypted.length; i++) {
            byte[] changed = encrypted.clone();
            changed[i] ^= 1;
            assertThrows(CryptoException.class, () -> AuthenticatedEncryption.decrypt(changed, key, "test/data"));
            byte[] truncated = Arrays.copyOf(encrypted, i);
            assertThrows(CryptoException.class, () -> AuthenticatedEncryption.decrypt(truncated, key, "test/data"));
        }
        assertThrows(CryptoException.class, () -> AuthenticatedEncryption.decrypt(Arrays.copyOf(encrypted, encrypted.length + 1), key, "test/data"));
        assertThrows(CryptoException.class, () -> AuthenticatedEncryption.decrypt(encrypted, key, "test/other"));
        assertThrows(CryptoException.class, () -> AuthenticatedEncryption.decrypt(encrypted, Encryption.generateSecretKey(256), "test/data"));
        assertThrows(CryptoException.class, () -> AuthenticatedEncryption.decrypt(encrypted, Encryption.generateSecretKey(128), "test/data"));
    }

    @Test
    void corruptStreamNeverCallsParser() throws Exception {
        byte[] encrypted = AuthenticatedEncryption.encrypt(new byte[100], key, "test/data");
        encrypted[70] ^= 1;
        Path path = directory.resolve("data");
        Files.write(path, encrypted);
        AtomicBoolean parsed = new AtomicBoolean();
        assertThrows(IOException.class, () -> AuthenticatedEncryption.readFile(path, key, "test/data", in -> {
            parsed.set(true);
            return in.readAllBytes();
        }));
        assertFalse(parsed.get());
    }

    @Test
    void modificationDuringSecondPassCannotPublishResult() throws Exception {
        byte[] encrypted = AuthenticatedEncryption.encrypt(new byte[200_000], key, "test/data");
        Path path = directory.resolve("data");
        Files.write(path, encrypted);
        assertThrows(IOException.class, () -> AuthenticatedEncryption.readFile(path, key, "test/data", in -> {
            assertEquals(0, in.read());
            // Change a byte beyond both buffered streams after the first verification pass.
            try (var file = new java.io.RandomAccessFile(path.toFile(), "rw")) {
                file.seek(150_000);
                int b = file.read();
                file.seek(150_000);
                file.write(b ^ 1);
            }
            return in.readAllBytes();
        }));
    }

    @Test
    void outputFailureAndWriterFailureAreNotSuccessfulEnvelopes() {
        ByteArrayOutputStream partial = new ByteArrayOutputStream();
        assertThrows(CryptoException.class, () -> AuthenticatedEncryption.encryptToStream(out -> {
            out.write(new byte[100]);
            throw new IOException("injected serialization failure");
        }, key, "test/data", partial));
        assertThrows(CryptoException.class, () -> AuthenticatedEncryption.decrypt(partial.toByteArray(), key, "test/data"));
        assertThrows(CryptoException.class, () -> AuthenticatedEncryption.encryptToStream(out -> out.write(new byte[100]), key, "test/data", new OutputStream() {
            @Override public void write(int b) throws IOException { throw new IOException("injected disk failure"); }
        }));
    }

    @Test
    void streamingDoesNotCloseOutputAndUnknownVersionIsRecognizedButRejected() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        ByteArrayOutputStream out = new ByteArrayOutputStream() {
            @Override public void close() { closed.set(true); }
        };
        AuthenticatedEncryption.encryptToStream(stream -> stream.write(3), key, "test/data", out);
        assertFalse(closed.get());
        byte[] encrypted = out.toByteArray();
        encrypted[7] = 3;
        assertTrue(AuthenticatedEncryption.hasEnvelope(encrypted));
        assertThrows(CryptoException.class, () -> AuthenticatedEncryption.decrypt(encrypted, key, "test/data"));
    }
}
