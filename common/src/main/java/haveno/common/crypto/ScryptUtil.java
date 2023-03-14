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

import com.google.protobuf.ByteString;
import haveno.common.UserThread;
import haveno.common.util.Utilities;
import org.bitcoinj.crypto.KeyCrypterScrypt;
import org.bitcoinj.wallet.Protos;
import org.bouncycastle.crypto.params.KeyParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//TODO: Borrowed form BitcoinJ/Lighthouse. Remove Protos dependency, check complete code logic.
public class ScryptUtil {
    private static final Logger log = LoggerFactory.getLogger(ScryptUtil.class);

    public interface DeriveKeyResultHandler {
        void handleResult(KeyParameter aesKey);
    }

    public static KeyCrypterScrypt getKeyCrypterScrypt() {
        return getKeyCrypterScrypt(KeyCrypterScrypt.randomSalt());
    }

    public static KeyCrypterScrypt getKeyCrypterScrypt(byte[] salt) {
        Protos.ScryptParameters scryptParameters = Protos.ScryptParameters.newBuilder()
                .setP(6)
                .setR(8)
                .setN(32768)
                .setSalt(ByteString.copyFrom(salt))
                .build();
        return new KeyCrypterScrypt(scryptParameters);
    }

    public static KeyParameter deriveKeyWithScrypt(KeyCrypterScrypt keyCrypterScrypt, String password) {
        try {
            log.debug("Doing key derivation");
            long start = System.currentTimeMillis();
            KeyParameter aesKey = keyCrypterScrypt.deriveKey(password);
            long duration = System.currentTimeMillis() - start;
            log.debug("Key derivation took {} msec", duration);
            return aesKey;
        } catch (Throwable t) {
            t.printStackTrace();
            log.error("Key derivation failed. " + t.getMessage());
            throw t;
        }
    }

    public static void deriveKeyWithScrypt(KeyCrypterScrypt keyCrypterScrypt, String password, DeriveKeyResultHandler resultHandler) {
        Utilities.getThreadPoolExecutor("ScryptUtil:deriveKeyWithScrypt-%d", 1, 2, 5L).submit(() -> {
            try {
                KeyParameter aesKey = deriveKeyWithScrypt(keyCrypterScrypt, password);
                UserThread.execute(() -> {
                    try {
                        resultHandler.handleResult(aesKey);
                    } catch (Throwable t) {
                        t.printStackTrace();
                        log.error("Executing task failed. " + t.getMessage());
                        throw t;
                    }
                });
            } catch (Throwable t) {
                t.printStackTrace();
                log.error("Executing task failed. " + t.getMessage());
                throw t;
            }
        });
    }
}
