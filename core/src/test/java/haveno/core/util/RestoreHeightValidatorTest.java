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

package haveno.core.util;

import haveno.core.locale.GlobalSettings;
import haveno.core.locale.Res;
import haveno.core.util.validation.RestoreHeightValidator;
import java.time.LocalDate;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RestoreHeightValidatorTest {

    private final RestoreHeightValidator validator = new RestoreHeightValidator();

    @BeforeEach
    public void setup() {
        Locale.setDefault(new Locale("en", "US"));
        GlobalSettings.setLocale(new Locale("en", "US"));
        Res.setBaseCurrencyCode("XMR");
        Res.setBaseCurrencyName("Monero");
    }

    @Test
    public void testRestoreHeightValidator() {
        assertTrue(validator.validate(null).isValid);
        assertTrue(validator.validate("").isValid);
        assertTrue(validator.validate("0").isValid);
        assertTrue(validator.validate("3000000").isValid);
        assertTrue(validator.validate("2023-10-28").isValid);
        assertTrue(validator.validate(LocalDate.now().toString()).isValid);

        // partial date still being typed is not flagged
        assertTrue(validator.validate("2025-").isValid);
        assertTrue(validator.validate("2025-0").isValid);
        assertTrue(validator.validate("2025-07").isValid);
        assertTrue(validator.validate("2025-07-").isValid);
        assertTrue(validator.validate("2025-07-0").isValid);

        assertFalse(validator.validate("asdfasdf").isValid);
        assertFalse(validator.validate("99999999999999999999").isValid);
        assertFalse(validator.validate("2025-13-01").isValid);
        assertFalse(validator.validate("2025-7-1").isValid);
        assertFalse(validator.validate("202-").isValid);
        assertFalse(validator.validate(LocalDate.now().plusDays(1).toString()).isValid);
    }
}
