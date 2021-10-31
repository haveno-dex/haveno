package haveno.desktop.util.validation;

import haveno.core.locale.BankUtil;
import haveno.core.locale.Res;

public class NationalAccountIdValidator extends BankValidator {

    public NationalAccountIdValidator(String countryCode) {
        super(countryCode);
    }

    @Override
    public ValidationResult validate(String input) {
        int length;

        switch (countryCode) {
            case "AR":
                length = 22;
                if (isNumberWithFixedLength(input, length))
                    return super.validate(input);
                else {
                    String nationalAccountIdLabel = BankUtil.getNationalAccountIdLabel(countryCode);
                    return new ValidationResult(false, Res.get("validation.nationalAccountId", nationalAccountIdLabel, length));
                }

                default:
                    return super.validate(input);

        }
    }
}
