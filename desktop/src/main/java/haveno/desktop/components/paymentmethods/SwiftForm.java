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

package haveno.desktop.components.paymentmethods;

import com.jfoenix.controls.JFXTextArea;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.Country;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.Res;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.SwiftAccount;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.SwiftAccountPayload;
import haveno.core.payment.validation.LengthValidator;
import haveno.core.trade.Trade;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipCheckBox;
import haveno.desktop.components.InputTextField;
import haveno.desktop.main.overlays.windows.SwiftPaymentDetails;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.Layout;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;

import java.util.function.Consumer;

import static haveno.common.util.Utilities.cleanString;
import static haveno.core.payment.payload.SwiftAccountPayload.ADDRESS;
import static haveno.core.payment.payload.SwiftAccountPayload.BANKPOSTFIX;
import static haveno.core.payment.payload.SwiftAccountPayload.BENEFICIARYPOSTFIX;
import static haveno.core.payment.payload.SwiftAccountPayload.BRANCH;
import static haveno.core.payment.payload.SwiftAccountPayload.COUNTRY;
import static haveno.core.payment.payload.SwiftAccountPayload.INTERMEDIARYPOSTFIX;
import static haveno.core.payment.payload.SwiftAccountPayload.PHONE;
import static haveno.core.payment.payload.SwiftAccountPayload.SNAME;
import static haveno.core.payment.payload.SwiftAccountPayload.SWIFT_ACCOUNT;
import static haveno.core.payment.payload.SwiftAccountPayload.SWIFT_CODE;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addInputTextField;
import static haveno.desktop.util.FormBuilder.addTopLabelTextArea;

public class SwiftForm extends PaymentMethodForm {
    private final SwiftAccountPayload formData;
    private final AutoTooltipCheckBox useIntermediaryCheck;
    private final LengthValidator defaultValidator = new LengthValidator(2, 34);
    private final LengthValidator swiftValidator = new LengthValidator(11, 11);
    private final LengthValidator accountNrValidator = new LengthValidator(2, 40);
    private final LengthValidator addressValidator = new LengthValidator(1, 100);

    public SwiftForm(PaymentAccount paymentAccount,
                     AccountAgeWitnessService accountAgeWitnessService,
                     InputValidator defaultValidator, GridPane gridPane, int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, defaultValidator, gridPane, gridRow, formatter);
        this.formData = ((SwiftAccount) paymentAccount).getPayload();
        this.useIntermediaryCheck = new AutoTooltipCheckBox(Res.get("payment.swift.use.intermediary"));
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;
        addFieldsForBankEdit(true, this::setBankSwiftCode, this::setBankName, this::setBankBranch, this::setBankAddress);
        addFieldsForBankEdit(false, this::setIntermediarySwiftCode, this::setIntermediaryName, this::setIntermediaryBranch, this::setIntermediaryAddress);
        addFieldsForBeneficiaryEdit();
        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(formData.getBeneficiaryName());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"), Res.get(paymentAccount.getPaymentMethod().getId()));

        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get(SWIFT_CODE + BANKPOSTFIX), formData.getBankSwiftCode());
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get(COUNTRY + BANKPOSTFIX), CountryUtil.getNameAndCode(formData.getBankCountryCode()));
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get(SNAME + BANKPOSTFIX), formData.getBankName());
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get(BRANCH + BANKPOSTFIX), formData.getBankBranch());
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get(ADDRESS + BANKPOSTFIX), cleanString(formData.getBankAddress()));

        if (formData.usesIntermediaryBank()) {
            addCompactTopLabelTextField(gridPane, ++gridRow, Res.get(SWIFT_CODE + INTERMEDIARYPOSTFIX), formData.getIntermediarySwiftCode(), Layout.GROUP_DISTANCE_WITHOUT_SEPARATOR);
            addCompactTopLabelTextField(gridPane, ++gridRow, Res.get(COUNTRY + INTERMEDIARYPOSTFIX), CountryUtil.getNameAndCode(formData.getIntermediaryCountryCode()));
            addCompactTopLabelTextField(gridPane, ++gridRow, Res.get(SNAME + INTERMEDIARYPOSTFIX), formData.getIntermediaryName());
            addCompactTopLabelTextField(gridPane, ++gridRow, Res.get(BRANCH + INTERMEDIARYPOSTFIX), formData.getIntermediaryBranch());
            addCompactTopLabelTextField(gridPane, ++gridRow, Res.get(ADDRESS + INTERMEDIARYPOSTFIX), cleanString(formData.getIntermediaryAddress()));
        }

        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.owner"), formData.getBeneficiaryName(), Layout.GROUP_DISTANCE_WITHOUT_SEPARATOR);
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get(SWIFT_ACCOUNT), formData.getBeneficiaryAccountNr());
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get(ADDRESS + BENEFICIARYPOSTFIX), cleanString(formData.getBeneficiaryAddress()));
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get(PHONE + BENEFICIARYPOSTFIX), formData.getBeneficiaryPhone());
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.account.city"), formData.getBeneficiaryCity());
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.country"), CountryUtil.getNameAndCode(formData.getBankCountryCode()));    // same as receiving bank country
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.shared.extraInfo"), cleanString(formData.getSpecialInstructions()));

        gridPane.add(new Label(""), 0, ++gridRow);  // spacer
        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        SwiftAccountPayload data = formData;

        // intermediary bank details are optional, but if specified must be valid
        boolean intermediaryValidIfSpecified = !useIntermediaryCheck.isSelected() && !data.usesIntermediaryBank() ||
                data.usesIntermediaryBank() && (swiftValidator.validate(data.getIntermediarySwiftCode()).isValid
                        && defaultValidator.validate(data.getIntermediaryCountryCode()).isValid
                        && defaultValidator.validate(data.getIntermediaryName()).isValid
                        && defaultValidator.validate(data.getIntermediaryBranch()).isValid
                        && addressValidator.validate(data.getIntermediaryAddress()).isValid
                );

        allInputsValid.set(isAccountNameValid()
                && swiftValidator.validate(data.getBankSwiftCode()).isValid
                && defaultValidator.validate(data.getBankCountryCode()).isValid
                && defaultValidator.validate(data.getBankName()).isValid
                && defaultValidator.validate(data.getBankBranch()).isValid
                && addressValidator.validate(data.getBankAddress()).isValid
                && defaultValidator.validate(data.getBeneficiaryName()).isValid
                && accountNrValidator.validate(data.getBeneficiaryAccountNr()).isValid
                && addressValidator.validate(data.getBeneficiaryAddress()).isValid
                && defaultValidator.validate(data.getBeneficiaryPhone()).isValid
                && defaultValidator.validate(data.getBeneficiaryCity()).isValid
                && paymentAccount.getTradeCurrencies().size() > 0
                && intermediaryValidIfSpecified);
    }

    // Here we need to show information to buyer so they can make the fiat payment, however there is only enough space
    // on the trade screen for ~4 fields.
    // Since SWIFT has an unusually large number of fields, it will be better to offer a button which will show
    // the SWIFT information in a popup screen.
    public static int addFormForBuyer(GridPane gridPane, int gridRow,
                                      PaymentAccountPayload paymentAccountPayload, Trade trade) {
        SwiftAccountPayload swiftAccountPayload = (SwiftAccountPayload) paymentAccountPayload;
        Button button = new AutoTooltipButton(Res.get("payment.swift.showPaymentInfo"));
        GridPane.setRowIndex(button, gridRow);
        GridPane.setColumnIndex(button, 1);
        gridPane.getChildren().add(button);
        GridPane.setMargin(button, new Insets(Layout.TWICE_FIRST_ROW_AND_GROUP_DISTANCE, 0, 0, Layout.FLOATING_LABEL_DISTANCE));
        button.setOnAction((e) -> new SwiftPaymentDetails(swiftAccountPayload, trade).show());
        return gridRow;
    }

    private void addFieldsForBankEdit(boolean isPrimary,
                                      Consumer<String> onSwiftCodeSelected,
                                      Consumer<String> onNameSelected,
                                      Consumer<String> onBranchSelected,
                                      Consumer<String> onAddressSelected) {
        GridPane gridPane2 = new GridPane();
        gridPane2.getColumnConstraints().add(gridPane.getColumnConstraints().get(0));
        TitledPane titledPane = new TitledPane(isPrimary ? Res.get("payment.swift.title" + BANKPOSTFIX) : Res.get("payment.swift.title" + INTERMEDIARYPOSTFIX), gridPane2);
        titledPane.setExpanded(isPrimary);
        gridPane.add(titledPane, 0, ++gridRow);

        int gridRow2 = 0;
        if (!isPrimary) {
            // secondary bank (optional) has a checkbox to specify if it is being used
            gridPane2.add(useIntermediaryCheck, 0, ++gridRow2);
        }
        String label = isPrimary ? Res.get(SWIFT_CODE + BANKPOSTFIX) : Res.get(SWIFT_CODE + INTERMEDIARYPOSTFIX);
        InputTextField bankSwiftCodeField = addInputTextField(gridPane2, ++gridRow2, label);
        bankSwiftCodeField.setPromptText(label);
        bankSwiftCodeField.setValidator(swiftValidator);
        bankSwiftCodeField.textProperty().addListener((ov, oldValue, newValue) -> onSwiftCodeSelected.accept(newValue));

        if (isPrimary) {
            gridRow2 = GUIUtil.addRegionCountry(gridPane2, gridRow2, this::setBankCountry);
        } else {
            gridRow2 = GUIUtil.addRegionCountry(gridPane2, ++gridRow2, this::setIntermediaryCountry);
        }

        label = isPrimary ? Res.get(SNAME + BANKPOSTFIX) : Res.get(SNAME + INTERMEDIARYPOSTFIX);
        InputTextField bankNameField = addInputTextField(gridPane2, ++gridRow2, label);
        bankNameField.setPromptText(label);
        bankNameField.setValidator(defaultValidator);
        bankNameField.textProperty().addListener((ov, oldValue, newValue) -> onNameSelected.accept(newValue));

        label = isPrimary ? Res.get(BRANCH + BANKPOSTFIX) : Res.get(BRANCH + INTERMEDIARYPOSTFIX);
        InputTextField bankBranchField = addInputTextField(gridPane2, ++gridRow2, label);
        bankBranchField.setPromptText(label);
        bankBranchField.setValidator(defaultValidator);
        bankBranchField.textProperty().addListener((ov, oldValue, newValue) -> onBranchSelected.accept(newValue));

        label = isPrimary ? Res.get(ADDRESS + BANKPOSTFIX) : Res.get(ADDRESS + INTERMEDIARYPOSTFIX);
        TextArea bankAddressTextArea = addTopLabelTextArea(gridPane2, ++gridRow2, label, label).second;
        bankAddressTextArea.setMinHeight(70);
        bankAddressTextArea.textProperty().addListener((ov, oldValue, newValue) -> onAddressSelected.accept(newValue));

        // intermediary bank can be enabled/disabled via checkbox
        if (!isPrimary) {
            useIntermediaryCheck.setOnAction((e) -> {
                for (Node x : gridPane2.getChildren()) {
                    if (x == useIntermediaryCheck)
                        continue;
                    x.setDisable(!useIntermediaryCheck.isSelected());
                }
                if (!useIntermediaryCheck.isSelected()) {
                    bankSwiftCodeField.setText("");
                    bankNameField.setText("");
                    bankBranchField.setText("");
                    bankAddressTextArea.setText("");
                }
                updateFromInputs();
            });
            // make the intermediary fields initially greyed out
            for (Node x : gridPane2.getChildren()) {
                if (x == useIntermediaryCheck)
                    continue;
                x.setDisable(!useIntermediaryCheck.isSelected());
            }
        }
    }

    private void addFieldsForBeneficiaryEdit() {
        String label = Res.get("payment.account.owner");
        InputTextField beneficiaryNameField = addInputTextField(gridPane, ++gridRow, label);
        beneficiaryNameField.setPromptText(label);
        beneficiaryNameField.setValidator(defaultValidator);
        beneficiaryNameField.textProperty().addListener((ov, oldValue, newValue) -> {
            formData.setBeneficiaryName(newValue.trim());
            updateFromInputs();
        });

        label = Res.get(SWIFT_ACCOUNT);
        InputTextField beneficiaryAccountNrField = addInputTextField(gridPane, ++gridRow, label);
        beneficiaryAccountNrField.setPromptText(label);
        beneficiaryAccountNrField.setValidator(defaultValidator);
        beneficiaryAccountNrField.setValidator(accountNrValidator);
        beneficiaryAccountNrField.textProperty().addListener((ov, oldValue, newValue) -> {
            formData.setBeneficiaryAccountNr(newValue.trim());
            updateFromInputs();
        });

        label = Res.get(ADDRESS + BENEFICIARYPOSTFIX);
        TextArea beneficiaryAddressTextArea = addTopLabelTextArea(gridPane, ++gridRow, label, label).second;
        beneficiaryAddressTextArea.setMinHeight(70);
        beneficiaryAddressTextArea.textProperty().addListener((ov, oldValue, newValue) -> {
            formData.setBeneficiaryAddress(newValue.trim());
            updateFromInputs();
        });

        label = Res.get("payment.account.city");
        InputTextField beneficiaryCityField = addInputTextField(gridPane, ++gridRow, label);
        beneficiaryCityField.setPromptText(label);
        beneficiaryCityField.setValidator(defaultValidator);
        beneficiaryCityField.textProperty().addListener((ov, oldValue, newValue) -> {
            formData.setBeneficiaryCity(newValue.trim());
            updateFromInputs();
        });

        label = Res.get(PHONE + BENEFICIARYPOSTFIX);
        InputTextField beneficiaryPhoneField = addInputTextField(gridPane, ++gridRow, label);
        beneficiaryPhoneField.setPromptText(label);
        beneficiaryPhoneField.setValidator(defaultValidator);
        beneficiaryPhoneField.textProperty().addListener((ov, oldValue, newValue) -> {
            formData.setBeneficiaryPhone(newValue.trim());
            updateFromInputs();
        });

        label = Res.get("payment.shared.optionalExtra");
        TextArea extraTextArea = addTopLabelTextArea(gridPane, ++gridRow, label, label).second;
        extraTextArea.setMinHeight(70);
        ((JFXTextArea) extraTextArea).setLabelFloat(false);
        extraTextArea.textProperty().addListener((ov, oldValue, newValue) -> {
            formData.setSpecialInstructions(newValue.trim());
            updateFromInputs();
        });
    }

    private void setBankSwiftCode(String value) {
        formData.setBankSwiftCode(value.trim());
        updateFromInputs();
    }

    private void setBankName(String value) {
        formData.setBankName(value.trim());
        updateFromInputs();
    }

    private void setBankBranch(String value) {
        formData.setBankBranch(value.trim());
        updateFromInputs();
    }

    private void setBankAddress(String value) {
        formData.setBankAddress(value.trim());
        updateFromInputs();
    }

    private void setIntermediarySwiftCode(String value) {
        formData.setIntermediarySwiftCode(value.trim());
        updateFromInputs();
    }

    private void setIntermediaryName(String value) {
        formData.setIntermediaryName(value.trim());
        updateFromInputs();
    }

    private void setIntermediaryBranch(String value) {
        formData.setIntermediaryBranch(value.trim());
        updateFromInputs();
    }

    private void setIntermediaryAddress(String value) {
        formData.setIntermediaryAddress(value.trim());
        updateFromInputs();
    }

    private void setBankCountry(Country country) {
        if (country == null)
            return;
        formData.setBankCountryCode(country.code);
        updateFromInputs();
    }

    private void setIntermediaryCountry(Country country) {
        if (country == null)
            return;
        formData.setIntermediaryCountryCode(country.code);
        updateFromInputs();
    }
}
