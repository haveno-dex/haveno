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
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.offer.OfferUtil;
import haveno.core.payment.validation.FiatVolumeValidator;
import haveno.core.payment.validation.SecurityDepositValidator;
import haveno.core.payment.validation.XmrValidator;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.user.Preferences;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.AmountValidator4Decimals;
import haveno.core.util.validation.AmountValidator8Decimals;
import haveno.desktop.Navigation;
import haveno.desktop.common.model.ViewModel;
import haveno.desktop.main.offer.MutableOfferViewModel;

class CreateOfferViewModel extends MutableOfferViewModel<CreateOfferDataModel> implements ViewModel {

    @Inject
    public CreateOfferViewModel(CreateOfferDataModel dataModel,
                                FiatVolumeValidator fiatVolumeValidator,
                                AmountValidator4Decimals priceValidator4Decimals,
                                AmountValidator8Decimals priceValidator8Decimals,
                                XmrValidator btcValidator,
                                SecurityDepositValidator securityDepositValidator,
                                PriceFeedService priceFeedService,
                                AccountAgeWitnessService accountAgeWitnessService,
                                Navigation navigation,
                                Preferences preferences,
                                @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter btcFormatter,
                                OfferUtil offerUtil) {
        super(dataModel,
                fiatVolumeValidator,
                priceValidator4Decimals,
                priceValidator8Decimals,
                btcValidator,
                securityDepositValidator,
                priceFeedService,
                accountAgeWitnessService,
                navigation,
                preferences,
                btcFormatter,
                offerUtil);
    }
}
