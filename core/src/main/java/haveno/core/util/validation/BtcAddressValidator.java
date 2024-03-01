/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.core.util.validation;

import com.google.inject.Inject;
import haveno.common.config.Config;
import haveno.core.locale.Res;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;

public final class BtcAddressValidator extends InputValidator {

    @Inject
    public BtcAddressValidator() {
    }

    @Override
    public ValidationResult validate(String input) {

        ValidationResult result = validateIfNotEmpty(input);
        if (result.isValid)
            return validateBtcAddress(input);
        else
            return result;
    }

    private ValidationResult validateBtcAddress(String input) {
        try {
            Address.fromString(Config.baseCurrencyNetworkParameters(), input);
            return new ValidationResult(true);
        } catch (AddressFormatException e) {
            return new ValidationResult(false, Res.get("validation.btc.invalidFormat"));
        }
    }
}
