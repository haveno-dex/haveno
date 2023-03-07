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

import javafx.scene.layout.GridPane;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextFieldWithCopyIcon;

import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.Res;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.WeChatPayAccount;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.WeChatPayAccountPayload;
import haveno.core.payment.validation.WeChatPayValidator;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;

public class WeChatPayForm extends GeneralAccountNumberForm {

    private final WeChatPayAccount weChatPayAccount;

    public static int addFormForBuyer(GridPane gridPane, int gridRow, PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.account.no"), ((WeChatPayAccountPayload) paymentAccountPayload).getAccountNr());
        return gridRow;
    }

    public WeChatPayForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService, WeChatPayValidator weChatPayValidator, InputValidator inputValidator, GridPane gridPane, int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.weChatPayAccount = (WeChatPayAccount) paymentAccount;
    }

    @Override
    void setAccountNumber(String newValue) {
        weChatPayAccount.setAccountNr(newValue);
    }

    @Override
    String getAccountNr() {
        return weChatPayAccount.getAccountNr();
    }
}
