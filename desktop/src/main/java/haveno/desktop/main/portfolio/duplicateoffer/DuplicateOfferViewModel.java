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

package haveno.desktop.main.portfolio.duplicateoffer;

import com.google.inject.Inject;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferPayload;
import haveno.core.offer.OfferUtil;
import haveno.core.payment.validation.FiatVolumeValidator;
import haveno.core.payment.validation.SecurityDepositValidator;
import haveno.core.payment.validation.XmrValidator;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.user.Preferences;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.NonFiatPriceValidator;
import haveno.core.util.validation.FiatPriceValidator;
import haveno.desktop.Navigation;
import haveno.desktop.main.offer.MutableOfferViewModel;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Named;

@Slf4j
class DuplicateOfferViewModel extends MutableOfferViewModel<DuplicateOfferDataModel> {

    @Inject
    public DuplicateOfferViewModel(DuplicateOfferDataModel dataModel,
                              FiatVolumeValidator fiatVolumeValidator,
                              FiatPriceValidator fiatPriceValidator,
                              NonFiatPriceValidator nonFiatPriceValidator,
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
                fiatPriceValidator,
                nonFiatPriceValidator,
                btcValidator,
                securityDepositValidator,
                priceFeedService,
                accountAgeWitnessService,
                navigation,
                preferences,
                btcFormatter,
                offerUtil);
        syncMinAmountWithAmount = false;
    }

    public void initWithData(OfferPayload offerPayload) {
        this.offer = new Offer(offerPayload);
        offer.setPriceFeedService(priceFeedService);
    }

    @Override
    public void activate() {
        super.activate();
        dataModel.populateData(offer);
        triggerFocusOutOnAmountFields();
        onFocusOutPriceAsPercentageTextField(true, false);
    }
}
