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

package haveno.core.crypto;

import haveno.common.crypto.CryptoException;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.crypto.Sig;
import haveno.common.file.FileUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class SigTest {
    private static final Logger log = LoggerFactory.getLogger(SigTest.class);
    private KeyRing keyRing;
    private File dir;

    @BeforeEach
    public void setup() throws IOException {

        dir = File.createTempFile("temp_tests", "");
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdir();
        KeyStorage keyStorage = new KeyStorage(dir);
        keyRing = new KeyRing(keyStorage, null, true);
    }

    @AfterEach
    public void tearDown() throws IOException {
        FileUtil.deleteDirectory(dir);
    }


    @Test
    public void testSignature() {
        long ts = System.currentTimeMillis();
        log.trace("start ");
        for (int i = 0; i < 100; i++) {
            String msg = String.valueOf(new Random().nextInt());
            String sig = null;
            try {
                sig = Sig.sign(keyRing.getSignatureKeyPair().getPrivate(), msg);
            } catch (CryptoException e) {
                log.error("sign failed");
                e.printStackTrace();
                assertTrue(false);
            }
            try {
                assertTrue(Sig.verify(keyRing.getSignatureKeyPair().getPublic(), msg, sig));
            } catch (CryptoException e) {
                log.error("verify failed");
                e.printStackTrace();
                assertTrue(false);
            }
        }
        log.trace("took {} ms.", System.currentTimeMillis() - ts);
    }
}


