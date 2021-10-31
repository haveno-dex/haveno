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

import haveno.desktop.Navigation;
import haveno.desktop.common.model.ViewModel;
import haveno.desktop.main.offer.MutableOfferViewModel;
import haveno.desktop.util.validation.AltcoinValidator;
import haveno.desktop.util.validation.BtcValidator;
import haveno.desktop.util.validation.FiatPriceValidator;
import haveno.desktop.util.validation.FiatVolumeValidator;
import haveno.desktop.util.validation.SecurityDepositValidator;

import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.offer.OfferUtil;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.user.Preferences;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;

import com.google.inject.Inject;

import javax.inject.Named;

class CreateOfferViewModel extends MutableOfferViewModel<CreateOfferDataModel> implements ViewModel {

    @Inject
    public CreateOfferViewModel(CreateOfferDataModel dataModel,
                                FiatVolumeValidator fiatVolumeValidator,
                                FiatPriceValidator fiatPriceValidator,
                                AltcoinValidator altcoinValidator,
                                BtcValidator btcValidator,
                                SecurityDepositValidator securityDepositValidator,
                                PriceFeedService priceFeedService,
                                AccountAgeWitnessService accountAgeWitnessService,
                                Navigation navigation,
                                Preferences preferences,
                                @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter btcFormatter,
                                OfferUtil offerUtil) {
        super(dataModel,
                fiatVolumeValidator,
                fiatPriceValidator,
                altcoinValidator,
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
