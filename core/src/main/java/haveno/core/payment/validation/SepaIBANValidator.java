package haveno.core.payment.validation;

import haveno.core.locale.Country;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.Res;

import java.util.List;
import java.util.Optional;

public class SepaIBANValidator extends IBANValidator {

    @Override
    public ValidationResult validate(String input) {
        ValidationResult result = super.validate(input);

        if (result.isValid) {
            List<Country> sepaCountries = CountryUtil.getAllSepaCountries();
            String ibanCountryCode = input.substring(0, 2).toUpperCase();
            Optional<Country> ibanCountry = sepaCountries
                    .stream()
                    .filter(c -> c.code.equals(ibanCountryCode))
                    .findFirst();

            if (!ibanCountry.isPresent()) {
                return new ValidationResult(false, Res.get("validation.iban.sepaNotSupported"));
            }
        }

        return result;
    }
}
