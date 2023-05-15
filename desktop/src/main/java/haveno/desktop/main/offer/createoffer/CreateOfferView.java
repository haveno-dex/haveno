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

package haveno.desktop.main.offer.createoffer;

import com.google.inject.Inject;
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
import haveno.desktop.util.GUIUtil;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import javax.inject.Named;
import java.util.Objects;
import java.util.stream.Collectors;

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

    @Override
    public void initWithData(OfferDirection direction,
                             TradeCurrency tradeCurrency,
                             OfferView.OfferActionHandler offerActionHandler) {
        // Invert direction for non-Fiat trade currencies -> BUY BSQ is to SELL Bitcoin
        OfferDirection offerDirection = CurrencyUtil.isTraditionalCurrency(tradeCurrency.getCode()) ? direction :
                direction == OfferDirection.BUY ? OfferDirection.SELL : OfferDirection.BUY;
        super.initWithData(offerDirection, tradeCurrency, offerActionHandler);
    }

    @Override
    protected ObservableList<PaymentAccount> filterPaymentAccounts(ObservableList<PaymentAccount> paymentAccounts) {
        return FXCollections.observableArrayList(
                paymentAccounts.stream().filter(paymentAccount -> {
                    if (model.getTradeCurrency().equals(GUIUtil.TOP_CRYPTO)) {
                        return Objects.equals(paymentAccount.getSingleTradeCurrency(), GUIUtil.TOP_CRYPTO);
                    } else if (CurrencyUtil.isTraditionalCurrency(model.getTradeCurrency().getCode())) {
                        return !paymentAccount.getPaymentMethod().isCrypto();
                    } else {
                        return paymentAccount.getPaymentMethod().isCrypto() &&
                                !Objects.equals(paymentAccount.getSingleTradeCurrency(), GUIUtil.TOP_CRYPTO);
                    }
                }).collect(Collectors.toList()));
    }
}
