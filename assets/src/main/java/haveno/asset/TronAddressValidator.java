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

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Validates a Tron address.
 */
public class TronAddressValidator implements AddressValidator {

    private static final String BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final byte MAINNET_PREFIX = 0x41;

    public TronAddressValidator() {
    }

    @Override
    public AddressValidationResult validate(String address) {
        if (!isValidTronAddress(address)) {
            return AddressValidationResult.invalidStructure();
        }
        return AddressValidationResult.validAddress();
    }

    /**
     * Checks if the given address is a valid Solana address.
     * 
     * This code is AI-generated and has been tested with a variety of addresses.
     * 
     * @param addr the address to validate
     * @return true if the address is valid, false otherwise
     */
    private static boolean isValidTronAddress(String address) {
        if (address == null || address.length() != 34) return false;

        byte[] decoded = decodeBase58(address);
        if (decoded == null || decoded.length != 25) return false; // 21 bytes data + 4 bytes checksum

        // Check checksum
        byte[] data = Arrays.copyOfRange(decoded, 0, 21);
        byte[] checksum = Arrays.copyOfRange(decoded, 21, 25);
        byte[] calculatedChecksum = Arrays.copyOfRange(doubleSHA256(data), 0, 4);

        if (!Arrays.equals(checksum, calculatedChecksum)) return false;

        // Check mainnet prefix
        return data[0] == MAINNET_PREFIX;
    }

    private static byte[] decodeBase58(String input) {
        BigInteger num = BigInteger.ZERO;
        BigInteger base = BigInteger.valueOf(58);

        for (char c : input.toCharArray()) {
            int digit = BASE58_ALPHABET.indexOf(c);
            if (digit < 0) return null;
            num = num.multiply(base).add(BigInteger.valueOf(digit));
        }

        // Convert BigInteger to byte array
        byte[] bytes = num.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }

        // Add leading zero bytes for '1's
        int leadingZeros = 0;
        for (char c : input.toCharArray()) {
            if (c == '1') leadingZeros++;
            else break;
        }

        byte[] result = new byte[leadingZeros + bytes.length];
        System.arraycopy(bytes, 0, result, leadingZeros, bytes.length);
        return result;
    }

    private static byte[] doubleSHA256(byte[] data) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return sha256.digest(sha256.digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
