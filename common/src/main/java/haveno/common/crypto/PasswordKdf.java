/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Memory-hard password-based key derivation (Argon2id) for protecting keys at rest,
 * so brute-forcing the account password costs real time and memory per guess.
 */
public class PasswordKdf {

    public static final int KDF_ARGON2ID = 1;

    // ~0.5-1 s and 64 MiB per guess on typical hardware.
    public static final int DEFAULT_MEM_KIB = 65536;
    public static final int DEFAULT_ITERATIONS = 3;
    public static final int DEFAULT_PARALLELISM = 1;

    // Cheap cost when no password is set; hardening adds nothing without a secret.
    public static final int UNPROTECTED_MEM_KIB = 64;
    public static final int UNPROTECTED_ITERATIONS = 1;

    public static final int SALT_LENGTH = 16;
    public static final int KEY_LENGTH = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    public static byte[] generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return salt;
    }

    public static byte[] deriveKey(String password, byte[] salt, int memKib, int iterations, int parallelism) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memKib)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] key = new byte[KEY_LENGTH];
        generator.generateBytes((password == null ? "" : password).getBytes(StandardCharsets.UTF_8), key, 0, KEY_LENGTH);
        return key;
    }
}
