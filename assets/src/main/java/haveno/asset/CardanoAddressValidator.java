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

/**
 * Validates a Shelley-era mainnet Cardano address.
 */
public class CardanoAddressValidator extends RegexAddressValidator {

    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int BECH32_CONST  = 1;
    private static final int BECH32M_CONST = 0x2bc830a3;
    private static final int MAX_LEN = 104; // bech32 / bech32m max for Cardano

    public CardanoAddressValidator() {
        super("^addr1[0-9a-z]{20,98}$");
    }

    public CardanoAddressValidator(String errorMessageI18nKey) {
        super("^addr1[0-9a-z]{20,98}$", errorMessageI18nKey);
    }

    @Override
    public AddressValidationResult validate(String address) {
        if (!isValidShelleyMainnet(address)) {
            return AddressValidationResult.invalidStructure();
        }
        return super.validate(address);
    }

    /**
     * Checks if the given address is a valid Shelley-era mainnet Cardano address.
     * 
     * This code is AI-generated and has been tested with a variety of addresses.
     * 
     * @param addr the address to validate
     * @return true if the address is valid, false otherwise
     */
    private static boolean isValidShelleyMainnet(String addr) {
        if (addr == null) return false;
        String lower = addr.toLowerCase();

        // must start addr1 and not be absurdly long
        if (!lower.startsWith("addr1") || lower.length() > MAX_LEN) return false;

        int sep = lower.lastIndexOf('1');
        if (sep < 1) return false; // no separator or empty HRP
        String hrp = lower.substring(0, sep);
        if (!"addr".equals(hrp)) return false; // mainnet only

        String dataPart = lower.substring(sep + 1);
        if (dataPart.length() < 6) return false; // checksum is 6 chars minimum

        int[] data = new int[dataPart.length()];
        for (int i = 0; i < dataPart.length(); i++) {
            int v = CHARSET.indexOf(dataPart.charAt(i));
            if (v == -1) return false;
            data[i] = v;
        }

        int[] hrpExp = hrpExpand(hrp);
        int[] combined = new int[hrpExp.length + data.length];
        System.arraycopy(hrpExp, 0, combined, 0, hrpExp.length);
        System.arraycopy(data, 0, combined, hrpExp.length, data.length);

        int chk = polymod(combined);
        return chk == BECH32_CONST || chk == BECH32M_CONST; // accept either legacy Bech32 (1) or Bech32m (0x2bc830a3)
    }

    private static int[] hrpExpand(String hrp) {
        int[] ret = new int[hrp.length() * 2 + 1];
        int idx = 0;
        for (char c : hrp.toCharArray()) ret[idx++] = c >> 5;
        ret[idx++] = 0;
        for (char c : hrp.toCharArray()) ret[idx++] = c & 31;
        return ret;
    }

    private static int polymod(int[] values) {
        int chk = 1;
        int[] GEN = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
        for (int v : values) {
            int b = chk >>> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ v;
            for (int i = 0; i < 5; i++) {
                if (((b >>> i) & 1) != 0) chk ^= GEN[i];
            }
        }
        return chk;
    }
}
