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
import haveno.core.util.validation.RegexValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RegexValidatorTest {

    @BeforeEach
    public void setup() {
        final BaseCurrencyNetwork baseCurrencyNetwork = Config.baseCurrencyNetwork();
        final String currencyCode = baseCurrencyNetwork.getCurrencyCode();
        Res.setBaseCurrencyCode(currencyCode);
        Res.setBaseCurrencyName(baseCurrencyNetwork.getCurrencyName());
        CurrencyUtil.setBaseCurrencyCode(currencyCode);
    }

    @Test
    public void validate() throws Exception {
        RegexValidator validator = new RegexValidator();

        assertTrue(validator.validate("").isValid);
        assertTrue(validator.validate(null).isValid);
        assertTrue(validator.validate("123456789").isValid);

        validator.setPattern("[a-z]*");

        assertTrue(validator.validate("abcdefghijklmnopqrstuvwxyz").isValid);
        assertTrue(validator.validate("").isValid);
        assertTrue(validator.validate(null).isValid);

        assertFalse(validator.validate("123").isValid); // invalid
        assertFalse(validator.validate("ABC").isValid); // invalid

        validator.setPattern("[a-z]+");

        assertTrue(validator.validate("abcdefghijklmnopqrstuvwxyz").isValid);

        assertFalse(validator.validate("123").isValid); // invalid
        assertFalse(validator.validate("ABC").isValid); // invalid
        assertFalse(validator.validate("").isValid); // invalid
        assertFalse(validator.validate(null).isValid); // invalid

    }

}
