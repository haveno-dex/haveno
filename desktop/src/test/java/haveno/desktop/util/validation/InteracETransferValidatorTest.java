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
import haveno.core.payment.validation.EmailValidator;
import haveno.core.payment.validation.InteracETransferAnswerValidator;
import haveno.core.payment.validation.InteracETransferQuestionValidator;
import haveno.core.payment.validation.InteracETransferValidator;
import haveno.core.payment.validation.LengthValidator;
import haveno.core.util.validation.RegexValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InteracETransferValidatorTest {

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
        InteracETransferValidator validator = new InteracETransferValidator(
                new EmailValidator(),
                new InteracETransferQuestionValidator(new LengthValidator(), new RegexValidator()),
                new InteracETransferAnswerValidator(new LengthValidator(), new RegexValidator())
        );

        assertTrue(validator.validate("name@domain.tld").isValid);
        assertTrue(validator.validate("n1.n2@c.dd").isValid);
        assertTrue(validator.validate("+1 236 123-4567").isValid);
        assertTrue(validator.validate("15061234567").isValid);
        assertTrue(validator.validate("1 289 784 2134").isValid);
        assertTrue(validator.validate("+1-514-654-7412").isValid);

        assertFalse(validator.validate("abc@.de").isValid); // Domain name missing
        assertFalse(validator.validate("abc@d.e").isValid); // TLD too short
        assertFalse(validator.validate("2361234567").isValid);  // Prefix for North America missing (often required for local calls as well)
        assertFalse(validator.validate("+150612345678").isValid); // Too long
        assertFalse(validator.validate("1289784213").isValid);  // Too short
        assertFalse(validator.validate("+1 555 123-4567").isValid); // Non-Canadian area code
        assertFalse(validator.validate("+1 236 1234-567").isValid); // Wrong grouping
    }

}
