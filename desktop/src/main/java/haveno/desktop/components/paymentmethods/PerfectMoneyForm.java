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
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.Res;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PerfectMoneyAccount;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PerfectMoneyAccountPayload;
import haveno.core.payment.validation.PerfectMoneyValidator;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import javafx.collections.FXCollections;
import javafx.scene.layout.GridPane;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextFieldWithCopyIcon;

public class PerfectMoneyForm extends GeneralAccountNumberForm {

    private final PerfectMoneyAccount perfectMoneyAccount;

    public static int addFormForBuyer(GridPane gridPane, int gridRow, PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.account.no"), ((PerfectMoneyAccountPayload) paymentAccountPayload).getAccountNr());
        return gridRow;
    }

    public PerfectMoneyForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService, PerfectMoneyValidator perfectMoneyValidator, InputValidator inputValidator, GridPane gridPane, int
            gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.perfectMoneyAccount = (PerfectMoneyAccount) paymentAccount;
    }

    @Override
    public void addTradeCurrency() {
        addTradeCurrencyComboBox();
        currencyComboBox.setItems(FXCollections.observableArrayList(new TraditionalCurrency("USD"), new TraditionalCurrency("EUR")));
    }

    @Override
    void setAccountNumber(String newValue) {
        perfectMoneyAccount.setAccountNr(newValue);
    }

    @Override
    String getAccountNr() {
        return perfectMoneyAccount.getAccountNr();
    }
}
