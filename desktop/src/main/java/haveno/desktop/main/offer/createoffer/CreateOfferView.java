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

package haveno.desktop.main.offer.createoffer;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.TradeCurrency;
import haveno.core.offer.OfferDirection;
import haveno.core.payment.PaymentAccount;
import haveno.core.user.Preferences;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.desktop.Navigation;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.main.offer.MutableOfferView;
import haveno.desktop.main.offer.OfferView;
import haveno.desktop.main.overlays.windows.OfferDetailsWindow;
import java.util.stream.Collectors;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

@FxmlView
public class CreateOfferView extends MutableOfferView<CreateOfferViewModel> {

    @Inject
    private CreateOfferView(CreateOfferViewModel model,
                            Navigation navigation,
                            Preferences preferences,
                            OfferDetailsWindow offerDetailsWindow,
                            @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter btcFormatter) {
        super(model, navigation, preferences, offerDetailsWindow, btcFormatter);
    }

    public void initWithData(OfferDirection direction,
                             TradeCurrency tradeCurrency,
                             OfferView.OfferActionHandler offerActionHandler) {
        super.initWithData(direction, tradeCurrency, true, offerActionHandler);
    }

    @Override
    protected ObservableList<PaymentAccount> filterPaymentAccounts(ObservableList<PaymentAccount> paymentAccounts) {
        return FXCollections.observableArrayList(
                paymentAccounts.stream().filter(paymentAccount -> {
                    if (CurrencyUtil.isFiatCurrency(model.getTradeCurrency().getCode())) {
                        return paymentAccount.isFiat();
                    } else if (CurrencyUtil.isCryptoCurrency(model.getTradeCurrency().getCode())) {
                        return paymentAccount.isCryptoCurrency();
                    } else {
                        return !paymentAccount.isFiat() && !paymentAccount.isCryptoCurrency();
                    }
                }).collect(Collectors.toList()));
    }
}
