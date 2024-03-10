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

package haveno.desktop.main.portfolio.duplicateoffer;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import haveno.core.locale.CurrencyUtil;
import haveno.core.offer.OfferPayload;
import haveno.core.payment.PaymentAccount;
import haveno.core.user.Preferences;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.desktop.Navigation;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.main.offer.MutableOfferView;
import haveno.desktop.main.overlays.windows.OfferDetailsWindow;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;

@FxmlView
public class DuplicateOfferView extends MutableOfferView<DuplicateOfferViewModel> {

    @Inject
    private DuplicateOfferView(DuplicateOfferViewModel model,
                          Navigation navigation,
                          Preferences preferences,
                          OfferDetailsWindow offerDetailsWindow,
                          @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter btcFormatter) {
        super(model, navigation, preferences, offerDetailsWindow, btcFormatter);
    }

    @Override
    protected void initialize() {
        super.initialize();
    }

    @Override
    protected void doActivate() {
        super.doActivate();

        // Workaround to fix margin on top of amount group
        gridPane.setPadding(new Insets(-20, 25, -1, 25));

        updatePriceToggle();

        // To force re-validation of payment account validation
        onPaymentAccountsComboBoxSelected();
    }

    @Override
    protected ObservableList<PaymentAccount> filterPaymentAccounts(ObservableList<PaymentAccount> paymentAccounts) {
        return paymentAccounts;
    }

    public void initWithData(OfferPayload offerPayload) {
        initWithData(offerPayload.getDirection(),
                CurrencyUtil.getTradeCurrency(offerPayload.getCurrencyCode()).get(),
                null);
        model.initWithData(offerPayload);
    }
}
