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

import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.Country;
import haveno.core.payment.CountryBasedPaymentAccount;
import haveno.core.payment.DomesticWireTransferAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.BankAccountPayload;
import haveno.core.payment.payload.DomesticWireTransferAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import javafx.scene.layout.GridPane;

public class DomesticWireTransferForm extends GeneralUsBankForm {

    public static int addFormForBuyer(GridPane gridPane, int gridRow, PaymentAccountPayload paymentAccountPayload) {
        DomesticWireTransferAccountPayload domesticWireTransferAccountPayload = (DomesticWireTransferAccountPayload) paymentAccountPayload;
        return addFormForBuyer(gridPane, gridRow, paymentAccountPayload, null,
                domesticWireTransferAccountPayload.getHolderAddress());
    }

    private final DomesticWireTransferAccount domesticWireTransferAccount;

    public DomesticWireTransferForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService, InputValidator inputValidator,
                           GridPane gridPane, int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.domesticWireTransferAccount = (DomesticWireTransferAccount) paymentAccount;
    }

    @Override
    public void addFormForEditAccount() {
        addFormForEditAccount(domesticWireTransferAccount.getPayload(), domesticWireTransferAccount.getPayload().getHolderAddress());
    }

    @Override
    public void addFormForAddAccount() {
        addFormForAddAccountInternal(domesticWireTransferAccount.getPayload(), domesticWireTransferAccount.getPayload().getHolderAddress());
    }

    @Override
    protected void setHolderAddress(String holderAddress) {
        domesticWireTransferAccount.getPayload().setHolderAddress(holderAddress);
    }

    @Override
    protected void maybeAddAccountTypeCombo(BankAccountPayload bankAccountPayload, Country country) {
        // DomesticWireTransfer does not use the account type combo
    }

    @Override
    public void updateAllInputsValid() {
        DomesticWireTransferAccountPayload domesticWireTransferAccountPayload = domesticWireTransferAccount.getPayload();
        boolean result = isAccountNameValid()
                && paymentAccount.getSingleTradeCurrency() != null
                && ((CountryBasedPaymentAccount) this.paymentAccount).getCountry() != null
                && inputValidator.validate(domesticWireTransferAccountPayload.getHolderName()).isValid
                && inputValidator.validate(domesticWireTransferAccountPayload.getHolderAddress()).isValid;

        result = getValidationResult(result,
                domesticWireTransferAccountPayload.getCountryCode(),
                domesticWireTransferAccountPayload.getBankName(),
                domesticWireTransferAccountPayload.getBankId(),
                domesticWireTransferAccountPayload.getBranchId(),
                domesticWireTransferAccountPayload.getAccountNr(),
                domesticWireTransferAccountPayload.getAccountNr(),
                domesticWireTransferAccountPayload.getHolderTaxId(),
                domesticWireTransferAccountPayload.getNationalAccountId());
        allInputsValid.set(result);
    }
}
