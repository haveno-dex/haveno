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

package haveno.core.payment.validation;

import haveno.asset.AssetRegistry;
import haveno.common.config.BaseCurrencyNetwork;
import haveno.common.config.Config;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AltCoinAddressValidatorTest {

    @Test
    public void test() {
        AltCoinAddressValidator validator = new AltCoinAddressValidator(new AssetRegistry());

        BaseCurrencyNetwork baseCurrencyNetwork = Config.baseCurrencyNetwork();
        String currencyCode = baseCurrencyNetwork.getCurrencyCode();
        Res.setBaseCurrencyCode(currencyCode);
        Res.setBaseCurrencyName(baseCurrencyNetwork.getCurrencyName());
        CurrencyUtil.setBaseCurrencyCode(currencyCode);

        validator.setCurrencyCode("BTC");
        assertTrue(validator.validate("17VZNX1SN5NtKa8UQFxwQbFeFc3iqRYhem").isValid);

        validator.setCurrencyCode("XMR");
        assertTrue(validator.validate("4AuUM6PedofLWKfRCX1fP3SoNZUzq6FSAbpevHRR6tVuMpZc3HznVeudmNGkEB75apjE7WKVgZZh1YvPVxZoHFN88NCdmWw").isValid);

        validator.setCurrencyCode("LTC");
        assertTrue(validator.validate("Lg3PX8wRWmApFCoCMAsPF5P9dPHYQHEWKW").isValid);

        validator.setCurrencyCode("BOGUS");

        assertFalse(validator.validate("1BOGUSADDR").isValid);
    }
}
