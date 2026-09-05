/*
 * This file is part of Haveno.
 * See LICENSE for licensing information.
 */
package haveno.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** A bounded, versioned master-key wrapper with fixed, version-specific Argon2id costs. */
final class PasswordKeyEnvelope {
    private static final byte[] MAGIC = {'H', 'V', 'N', 'K', (byte) 0xff, 0, 0, 1};
    private static final int HEADER_LENGTH = 25; // magic, profile, 16-byte salt
    static final int LENGTH = HEADER_LENGTH + AuthenticatedEncryption.HEADER_LENGTH + 32 + AuthenticatedEncryption.TAG_LENGTH;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordKeyEnvelope() {}

    static byte[] wrap(SecretKey key, String password) throws CryptoException {
        byte[] header = new byte[HEADER_LENGTH];
        RANDOM.nextBytes(header);
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        header[8] = (byte) (password == null || password.isEmpty() ? 0 : 1);
        byte[] encoded = key.getEncoded();
        try {
            if (encoded.length != 32) throw new IllegalArgumentException("Expected a 256-bit master key");
            byte[] encrypted = AuthenticatedEncryption.encrypt(encoded, derive(password, header), context(header));
            byte[] result = Arrays.copyOf(header, HEADER_LENGTH + encrypted.length);
            System.arraycopy(encrypted, 0, result, HEADER_LENGTH, encrypted.length);
            return result;
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    static SecretKey unwrap(byte[] bytes, String password) throws CryptoException {
        if (bytes.length != LENGTH || !Arrays.equals(MAGIC, Arrays.copyOf(bytes, MAGIC.length))) {
            throw new IllegalArgumentException("Unsupported or truncated master-key wrapper");
        }
        byte[] header = Arrays.copyOf(bytes, HEADER_LENGTH);
        if (header[8] != 0 && header[8] != 1) throw new IllegalArgumentException("Unsupported password KDF profile");
        if (header[8] == 0 && password != null && !password.isEmpty()) throw new CryptoException("Incorrect password");
        byte[] encoded = AuthenticatedEncryption.decrypt(Arrays.copyOfRange(bytes, HEADER_LENGTH, bytes.length),
                derive(password, header), context(header));
        try {
            if (encoded.length != 32) throw new CryptoException("Invalid master key");
            return new SecretKeySpec(encoded, "AES");
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private static String context(byte[] header) {
        return "key-wrapper/" + Base64.getEncoder().encodeToString(header);
    }

    private static SecretKey derive(String password, byte[] header) {
        byte[] derived = new byte[32];
        byte[] passwordBytes = (password == null ? "" : password).getBytes(StandardCharsets.UTF_8);
        try {
            if (header[8] == 1) {
                Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                        .withMemoryAsKB(65536).withIterations(3).withParallelism(1)
                        .withSalt(Arrays.copyOfRange(header, 9, HEADER_LENGTH)).build();
                try {
                    Argon2BytesGenerator generator = new Argon2BytesGenerator();
                    generator.init(params);
                    generator.generateBytes(passwordBytes, derived);
                } finally {
                    params.clear();
                }
            }
            return new SecretKeySpec(derived, "AES");
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
            Arrays.fill(derived, (byte) 0);
        }
    }
}
