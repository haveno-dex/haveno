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

package haveno.asset;

import org.bitcoinj.core.Base58;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;

import java.util.Arrays;

/**
 * Payment admission for ordinary Ergo mainnet P2PK addresses.
 * P2S, P2SH and testnet addresses are outside this payment policy.
 */
public class ErgoAddressValidator implements AddressValidator {

    private static final int CHECKSUM_LENGTH = 4;
    private static final int P2PK_LENGTH = 1 + 33 + CHECKSUM_LENGTH;
    private static final int MAX_ENCODED_LENGTH = 52; // Base58 ceiling for a 38-byte envelope
    private static final ECCurve CURVE = CustomNamedCurves.getByName("secp256k1").getCurve();

    @Override
    public AddressValidationResult validate(String address) {
        if (address == null || address.isEmpty())
            return AddressValidationResult.invalidStructure();
        if (address.length() > MAX_ENCODED_LENGTH)
            return unsupportedAddress();

        try {
            byte[] decoded = Base58.decode(address);
            if (decoded.length < 1 + 1 + CHECKSUM_LENGTH || !Base58.encode(decoded).equals(address))
                return AddressValidationResult.invalidStructure();

            int contentLength = decoded.length - CHECKSUM_LENGTH;
            Blake2bDigest digest = new Blake2bDigest(256);
            digest.update(decoded, 0, contentLength);
            byte[] hash = new byte[32];
            digest.doFinal(hash, 0);
            for (int i = 0; i < CHECKSUM_LENGTH; i++) {
                if (decoded[contentLength + i] != hash[i])
                    return AddressValidationResult.invalidStructure();
            }

            int prefix = Byte.toUnsignedInt(decoded[0]);
            if (prefix == 0x02 || prefix == 0x03 || prefix == 0x11 || prefix == 0x12 || prefix == 0x13)
                return unsupportedAddress();
            if (prefix != 0x01 || decoded.length != P2PK_LENGTH)
                return AddressValidationResult.invalidStructure();

            byte[] publicKey = Arrays.copyOfRange(decoded, 1, contentLength);
            // canonical identity is valid Ergo encoding, but is outside ordinary wallet payment policy
            if (Arrays.equals(publicKey, new byte[33]))
                return unsupportedAddress();
            if (publicKey[0] != 0x02 && publicKey[0] != 0x03)
                return AddressValidationResult.invalidStructure();

            ECPoint point = CURVE.decodePoint(publicKey);
            if (point.isInfinity() || !point.isValid() || !Arrays.equals(publicKey, point.getEncoded(true)))
                return AddressValidationResult.invalidStructure();

            return AddressValidationResult.validAddress();
        } catch (IllegalArgumentException ex) {
            return AddressValidationResult.invalidStructure();
        }
    }

    private AddressValidationResult unsupportedAddress() {
        return AddressValidationResult.invalidAddress("", "validation.crypto.ergo.mainnetP2pk");
    }
}
