package haveno.desktop.components.paymentmethods;

import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.BankUtil;
import haveno.core.locale.Res;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.BankAccountPayload;
import haveno.core.payment.validation.AccountNrValidator;
import haveno.core.payment.validation.BankIdValidator;
import haveno.core.payment.validation.BranchIdValidator;
import haveno.core.payment.validation.NationalAccountIdValidator;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import javafx.scene.layout.GridPane;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class GeneralBankForm extends PaymentMethodForm {
    private static final Logger log = LoggerFactory.getLogger(GeneralBankForm.class);

    static boolean accountNrAccountTypeCombined = false;
    static boolean nationalAccountIdAccountNrCombined = false;
    static boolean bankNameBankIdCombined = false;
    static boolean bankIdBranchIdCombined = false;
    static boolean bankNameBranchIdCombined = false;
    static boolean branchIdAccountNrCombined = false;

    boolean validatorsApplied;
    boolean useHolderID;
    InputTextField bankNameInputTextField, bankIdInputTextField, branchIdInputTextField, accountNrInputTextField,
            holderIdInputTextField, nationalAccountIdInputTextField;

    boolean accountNrInputTextFieldEdited;

    public GeneralBankForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService, InputValidator inputValidator, GridPane gridPane, int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
    }

    static int getIndexOfColumn(int colIndex) {
        return colIndex % 2;
    }

    static void prepareFormLayoutFlags(String countryCode, int currentNumberOfRows) {
        int nrRows = currentNumberOfRows;

        if (nrRows > 2) {
            // Try combine AccountNr + AccountType
            accountNrAccountTypeCombined = BankUtil.isAccountNrRequired(countryCode) && BankUtil.isAccountTypeRequired(countryCode);
            if (accountNrAccountTypeCombined)
                nrRows--;

            if (nrRows > 2) {

                nationalAccountIdAccountNrCombined = BankUtil.isAccountNrRequired(countryCode) &&
                        BankUtil.isNationalAccountIdRequired(countryCode);

                if (nationalAccountIdAccountNrCombined)
                    nrRows--;

                if (nrRows > 2) {
                    // Next we try BankName + BankId
                    bankNameBankIdCombined = BankUtil.isBankNameRequired(countryCode) && BankUtil.isBankIdRequired(countryCode);
                    if (bankNameBankIdCombined)
                        nrRows--;

                    if (nrRows > 2) {
                        // Next we try BankId + BranchId
                        bankIdBranchIdCombined = !bankNameBankIdCombined && BankUtil.isBankIdRequired(countryCode) &&
                                BankUtil.isBranchIdRequired(countryCode);
                        if (bankIdBranchIdCombined)
                            nrRows--;

                        if (nrRows > 2) {
                            // Next we try BankId + BranchId
                            bankNameBranchIdCombined = !bankNameBankIdCombined && !bankIdBranchIdCombined &&
                                    BankUtil.isBankNameRequired(countryCode) && BankUtil.isBranchIdRequired(countryCode);
                            if (bankNameBranchIdCombined)
                                nrRows--;

                            if (nrRows > 2) {
                                branchIdAccountNrCombined = !bankNameBranchIdCombined && !bankIdBranchIdCombined &&
                                        !accountNrAccountTypeCombined &&
                                        BankUtil.isBranchIdRequired(countryCode) && BankUtil.isAccountNrRequired(countryCode);
                                if (branchIdAccountNrCombined)
                                    nrRows--;

                                if (nrRows > 2)
                                    log.warn("We still have too many rows....");
                            }
                        }
                    }
                }
            }
        }
    }

    void validateInput(String countryCode) {
        if (BankUtil.useValidation(countryCode)) {
            validatorsApplied = true;
            if (useHolderID)
                holderIdInputTextField.setValidator(inputValidator);
            bankNameInputTextField.setValidator(inputValidator);
            bankIdInputTextField.setValidator(new BankIdValidator(countryCode));
            branchIdInputTextField.setValidator(new BranchIdValidator(countryCode));
            accountNrInputTextField.setValidator(new AccountNrValidator(countryCode));
            nationalAccountIdInputTextField.setValidator(new NationalAccountIdValidator(countryCode));
        } else {
            validatorsApplied = false;
            if (useHolderID)
                holderIdInputTextField.setValidator(null);
            bankNameInputTextField.setValidator(null);
            bankIdInputTextField.setValidator(null);
            branchIdInputTextField.setValidator(null);
            accountNrInputTextField.setValidator(inputValidator);
            nationalAccountIdInputTextField.setValidator(null);
        }
    }

    void updateHolderIDInput(String countryCode, boolean requiresHolderId) {
        if (useHolderID) {
            if (!requiresHolderId)
                holderIdInputTextField.setText("");

            holderIdInputTextField.resetValidation();
            holderIdInputTextField.setVisible(requiresHolderId);
            holderIdInputTextField.setManaged(requiresHolderId);

            holderIdInputTextField.setPromptText(BankUtil.getHolderIdLabel(countryCode));
        }
    }

    @Override
    protected void autoFillNameTextField() {
        if (useCustomAccountNameToggleButton != null && !useCustomAccountNameToggleButton.isSelected()) {
            BankAccountPayload payload = (BankAccountPayload) paymentAccount.paymentAccountPayload;
            String bankId = null;
            String countryCode = payload.getCountryCode();
            if (countryCode == null)
                countryCode = "";
            if (BankUtil.isBankIdRequired(countryCode)) {
                bankId = payload.getBankId();
                if (bankId.length() > 9)
                    bankId = StringUtils.abbreviate(bankId, 9);
            } else if (BankUtil.isBranchIdRequired(countryCode)) {
                bankId = payload.getBranchId();
                if (bankId.length() > 9)
                    bankId = StringUtils.abbreviate(bankId, 9);
            } else if (BankUtil.isBankNameRequired(countryCode)) {
                bankId = payload.getBankName();
                if (bankId.length() > 9)
                    bankId = StringUtils.abbreviate(bankId, 9);
            }

            String accountNr = payload.getAccountNr();
            if (accountNr.length() > 9)
                accountNr = StringUtils.abbreviate(accountNr, 9);

            String method = Res.get(paymentAccount.getPaymentMethod().getId());
            if (bankId != null && !bankId.isEmpty())
                accountNameTextField.setText(method.concat(": ").concat(bankId).concat(", ").concat(accountNr));
            else
                accountNameTextField.setText(method.concat(": ").concat(accountNr));

            if (BankUtil.isNationalAccountIdRequired(countryCode)) {
                String nationalAccountId = nationalAccountIdInputTextField.getText();

                if (countryCode.equals("AR") && nationalAccountId.length() == 22 && !accountNrInputTextFieldEdited) {
                    branchIdInputTextField.setText(nationalAccountId.substring(3, 7));
                    accountNrInputTextField.setText(nationalAccountId.substring(8, 21));
                }
            }
        }
    }

    boolean getValidationResult(boolean result, String countryCode, String bankName, String bankId,
                                String branchId, String accountNr, String accountType, String holderTaxId,
                                String nationalAccountId) {
        if (validatorsApplied && BankUtil.useValidation(countryCode)) {
            if (BankUtil.isBankNameRequired(countryCode))
                result = result && bankNameInputTextField.getValidator().validate(bankName).isValid;

            if (BankUtil.isBankIdRequired(countryCode))
                result = result && bankIdInputTextField.getValidator().validate(bankId).isValid;

            if (BankUtil.isBranchIdRequired(countryCode))
                result = result && branchIdInputTextField.getValidator().validate(branchId).isValid;

            if (BankUtil.isAccountNrRequired(countryCode))
                result = result && accountNrInputTextField.getValidator().validate(accountNr).isValid;

            if (BankUtil.isAccountTypeRequired(countryCode))
                result = result && accountType != null;

            if (useHolderID && BankUtil.isHolderIdRequired(countryCode))
                result = result && holderIdInputTextField.getValidator().validate(holderTaxId).isValid;

            if (BankUtil.isNationalAccountIdRequired(countryCode))
                result = result && nationalAccountIdInputTextField.getValidator().validate(nationalAccountId).isValid;
        } else {   // only account number not empty validation
            result = result && (accountNrInputTextField == null || accountNrInputTextField.getValidator().validate(accountNr).isValid);
        }

        return result;
    }
}
