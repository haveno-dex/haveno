package haveno.core.payment.validation;

import com.google.inject.Inject;
import haveno.core.locale.Res;
import haveno.core.util.validation.InputValidator;
import haveno.core.util.validation.RegexValidator;

public class CapitualValidator extends InputValidator {
    private final RegexValidator regexValidator;

    @Inject
    public CapitualValidator(RegexValidator regexValidator) {
        regexValidator.setPattern("CAP-[A-Za-z0-9]{6}");
        regexValidator.setErrorMessage(Res.get("validation.capitual.invalidFormat"));
        this.regexValidator = regexValidator;
    }

    @Override
    public ValidationResult validate(String input) {

        return regexValidator.validate(input);
    }
}
