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

package haveno.desktop.util.validation;

import haveno.common.config.BaseCurrencyNetwork;
import haveno.common.config.Config;

import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.coin.ImmutableCoinFormatter;

import org.bitcoinj.core.Coin;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BtcValidatorTest {

    private final CoinFormatter coinFormatter = new ImmutableCoinFormatter(Config.baseCurrencyNetworkParameters().getMonetaryFormat());

    @Before
    public void setup() {
        final BaseCurrencyNetwork baseCurrencyNetwork = Config.baseCurrencyNetwork();
        final String currencyCode = baseCurrencyNetwork.getCurrencyCode();
        Res.setBaseCurrencyCode(currencyCode);
        Res.setBaseCurrencyName(baseCurrencyNetwork.getCurrencyName());
        CurrencyUtil.setBaseCurrencyCode(currencyCode);
    }

    @Test
    public void testIsValid() {
        BtcValidator validator = new BtcValidator(coinFormatter);

        assertTrue(validator.validate("1").isValid);
        assertTrue(validator.validate("0,1").isValid);
        assertTrue(validator.validate("0.1").isValid);
        assertTrue(validator.validate(",1").isValid);
        assertTrue(validator.validate(".1").isValid);
        assertTrue(validator.validate("0.12345678").isValid);
        assertFalse(validator.validate(Coin.SATOSHI.toPlainString()).isValid); // below dust

        assertFalse(validator.validate(null).isValid);
        assertFalse(validator.validate("").isValid);
        assertFalse(validator.validate("0").isValid);
        assertFalse(validator.validate("0.0").isValid);
        assertFalse(validator.validate("0,1,1").isValid);
        assertFalse(validator.validate("0.1.1").isValid);
        assertFalse(validator.validate("0,000.1").isValid);
        assertFalse(validator.validate("0.000,1").isValid);
        assertFalse(validator.validate("0.123456789").isValid);
        assertFalse(validator.validate("-1").isValid);
        // assertFalse(validator.validate(NetworkParameters.MAX_MONEY.toPlainString()).isValid);
    }

}
