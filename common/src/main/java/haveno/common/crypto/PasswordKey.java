/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.common.crypto;

import java.nio.ByteBuffer;
import java.util.Arrays;
import javax.crypto.SecretKey;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** Password wrapping profile 1: Argon2id v1.3, 64 MiB, three passes, four lanes (RFC 9106). */
final class PasswordKey {
    private static final byte[] MAGIC = {'H', 'A', 'V', 'E', 'N', 'O', 'K', 'E', 'Y'};
    private static final int VERSION = 2;
    private static final int PROFILE = 1;
    private static final int SALT_LENGTH = 16;
    private static final int HEADER_LENGTH = MAGIC.length + 2 + SALT_LENGTH;
    private static final int FILE_LENGTH = HEADER_LENGTH + 53 + 32 + 16;
    private static final String CONTEXT = "account-master-key/argon2id-v13-m65536-t3-p4";

    private PasswordKey() {
    }

    static byte[] wrap(SecretKey masterKey, String password) throws CryptoException {
        byte[] salt = new byte[SALT_LENGTH];
        new java.security.SecureRandom().nextBytes(salt);
        SecretKey wrappingKey = derive(password, salt);
        byte[] master = masterKey.getEncoded();
        try {
            if (master.length != 32) throw new IllegalArgumentException("Expected 256-bit account key");
            byte[] encrypted = AuthenticatedEncryption.encrypt(master, wrappingKey, CONTEXT);
            return ByteBuffer.allocate(HEADER_LENGTH + encrypted.length)
                    .put(MAGIC).put((byte) VERSION).put((byte) PROFILE).put(salt).put(encrypted).array();
        } finally {
            Arrays.fill(master, (byte) 0);
        }
    }

    static SecretKey unwrap(byte[] file, String password) throws IncorrectPasswordException {
        // No file-controlled allocation or KDF parameters: new profiles require explicit code support.
        if (file.length != FILE_LENGTH || !Arrays.equals(MAGIC, Arrays.copyOf(file, MAGIC.length))
                || file[MAGIC.length] != VERSION || file[MAGIC.length + 1] != PROFILE) {
            throw new IllegalStateException("Invalid or unsupported account key file");
        }
        byte[] salt = Arrays.copyOfRange(file, MAGIC.length + 2, HEADER_LENGTH);
        SecretKey wrappingKey = derive(password, salt);
        byte[] master = null;
        try {
            master = AuthenticatedEncryption.decrypt(Arrays.copyOfRange(file, HEADER_LENGTH, file.length), wrappingKey, CONTEXT);
            if (master.length != 32) throw new IllegalStateException("Invalid account key length");
            return Encryption.getSecretKeyFromBytes(master);
        } catch (CryptoException e) {
            throw new IncorrectPasswordException("Incorrect password or damaged account key file");
        } finally {
            if (master != null) Arrays.fill(master, (byte) 0);
        }
    }

    private static SecretKey derive(String password, byte[] salt) {
        char[] chars = password == null ? new char[0] : password.toCharArray();
        byte[] derived = new byte[32];
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13).withMemoryAsKB(65536)
                .withIterations(3).withParallelism(4).withSalt(salt).build();
        try {
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(params);
            generator.generateBytes(chars, derived);
            return Encryption.getSecretKeyFromBytes(derived);
        } finally {
            Arrays.fill(chars, '\0');
            Arrays.fill(derived, (byte) 0);
            params.clear();
        }
    }
}
