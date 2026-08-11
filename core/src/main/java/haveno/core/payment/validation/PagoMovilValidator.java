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

public final class PagoMovilValidator extends PhoneNumberValidator {

    // Public no-arg constructor required by Guice injector.
    // Superclass' isoCountryCode must be set before validation.
    public PagoMovilValidator() {
        this.setIsoCountryCode("VE");
    }

    @Override
    public ValidationResult validate(String input) {
        return super.validate(input);
    }

    // Venezuelan numbers are conventionally written with a trunk '0' (e.g. 0412...), which E.164 drops
    @Override
    public String getNormalizedPhoneNumber() {
        String normalized = super.getNormalizedPhoneNumber();
        return normalized != null && normalized.startsWith("+580") ? "+58" + normalized.substring(4) : normalized;
    }
}
