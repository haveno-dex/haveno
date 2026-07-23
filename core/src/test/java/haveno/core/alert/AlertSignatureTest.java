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

package haveno.core.alert;

import org.bitcoinj.core.ECKey;
import org.junit.jupiter.api.Test;

import java.security.SignatureException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class AlertSignatureTest {

    // Regression for the Alert metadata-forgery replay: the dev signature covered only the message, so a
    // captured (message, signature) pair could be rebroadcast with attacker-chosen version / update flags.
    // The signature must now cover the whole payload, so a captured signature no longer verifies once any
    // behavior-determining field is changed.
    @Test
    public void signatureCoversMetadataNotJustMessage() throws SignatureException {
        ECKey devKey = new ECKey();
        Alert genuine = new Alert("please update", true, false, "1.2.3");
        String signature = devKey.signMessage(genuine.getSignaturePayloadAsHex());

        // the genuine payload verifies
        assertDoesNotThrow(() -> devKey.verifyMessage(genuine.getSignaturePayloadAsHex(), signature));

        // same message, forged version -> captured signature must not verify
        Alert forgedVersion = new Alert("please update", true, false, "9.9.9");
        assertThrows(SignatureException.class,
                () -> devKey.verifyMessage(forgedVersion.getSignaturePayloadAsHex(), signature));

        // same message, forged update flags -> captured signature must not verify
        Alert forgedFlags = new Alert("please update", false, true, "1.2.3");
        assertThrows(SignatureException.class,
                () -> devKey.verifyMessage(forgedFlags.getSignaturePayloadAsHex(), signature));
    }
}
