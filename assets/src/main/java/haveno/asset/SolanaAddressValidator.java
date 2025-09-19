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

/**
 * Validates a Solana address.
 */
public class SolanaAddressValidator implements AddressValidator {

    private static final String BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    public SolanaAddressValidator() {
    }

    @Override
    public AddressValidationResult validate(String address) {
        if (!isValidSolanaAddress(address)) {
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
    private static boolean isValidSolanaAddress(String address) {
        if (address == null) return false;
        if (address.length() < 32 || address.length() > 44) return false; // typical Solana length range

        // Check all chars are base58 valid
        for (char c : address.toCharArray()) {
            if (BASE58_ALPHABET.indexOf(c) == -1) return false;
        }

        // Decode from base58 and ensure exactly 32 bytes
        byte[] decoded = decodeBase58(address);
        return decoded != null && decoded.length == 32;
    }

    private static byte[] decodeBase58(String input) {
        BigInteger num = BigInteger.ZERO;
        BigInteger base = BigInteger.valueOf(58);

        for (char c : input.toCharArray()) {
            int digit = BASE58_ALPHABET.indexOf(c);
            if (digit < 0) return null; // invalid char
            num = num.multiply(base).add(BigInteger.valueOf(digit));
        }

        // Convert BigInteger to byte array
        byte[] bytes = num.toByteArray();

        // Remove sign byte if present
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] tmp = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, tmp, 0, tmp.length);
            bytes = tmp;
        }

        // Count leading '1's and add leading zero bytes
        int leadingZeros = 0;
        for (char c : input.toCharArray()) {
            if (c == '1') leadingZeros++;
            else break;
        }

        byte[] result = new byte[leadingZeros + bytes.length];
        System.arraycopy(bytes, 0, result, leadingZeros, bytes.length);

        return result;
    }
}
