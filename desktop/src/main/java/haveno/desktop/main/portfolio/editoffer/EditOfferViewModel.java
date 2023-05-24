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

package haveno.desktop.main.portfolio.editoffer;

import com.google.inject.Inject;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.offer.OfferUtil;
import haveno.core.offer.OpenOffer;
import haveno.core.payment.validation.FiatVolumeValidator;
import haveno.core.payment.validation.SecurityDepositValidator;
import haveno.core.payment.validation.XmrValidator;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.user.Preferences;
import haveno.core.util.FormattingUtils;
import haveno.core.util.PriceUtil;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.NonFiatPriceValidator;
import haveno.core.util.validation.FiatPriceValidator;
import haveno.desktop.Navigation;
import haveno.desktop.main.offer.MutableOfferViewModel;

import javax.inject.Named;

class EditOfferViewModel extends MutableOfferViewModel<EditOfferDataModel> {

    @Inject
    public EditOfferViewModel(EditOfferDataModel dataModel,
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

    @Override
    public void activate() {
        super.activate();

        dataModel.populateData();

        long triggerPriceAsLong = dataModel.getTriggerPrice();
        dataModel.setTriggerPrice(triggerPriceAsLong);
        if (triggerPriceAsLong > 0) {
            triggerPrice.set(PriceUtil.formatMarketPrice(triggerPriceAsLong, dataModel.getCurrencyCode()));
        } else {
            triggerPrice.set("");
        }
        onTriggerPriceTextFieldChanged();
    }

    public void applyOpenOffer(OpenOffer openOffer) {
        dataModel.reset();
        dataModel.applyOpenOffer(openOffer);
    }

    public void onStartEditOffer(ErrorMessageHandler errorMessageHandler) {
        dataModel.onStartEditOffer(errorMessageHandler);
    }

    public void onPublishOffer(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        dataModel.onPublishOffer(resultHandler, errorMessageHandler);
    }

    public void onCancelEditOffer(ErrorMessageHandler errorMessageHandler) {
        dataModel.onCancelEditOffer(errorMessageHandler);
    }

    public void onInvalidateMarketPriceMarginPct() {
        marketPriceMargin.set("0.00%");
        marketPriceMargin.set(FormattingUtils.formatToPercent(dataModel.getMarketPriceMarginPct()));
    }

    public void onInvalidatePrice() {
        price.set(FormattingUtils.formatPrice(null));
        price.set(FormattingUtils.formatPrice(dataModel.getPrice().get()));
    }

    public boolean isSecurityDepositValid() {
        return securityDepositValidator.validate(buyerSecurityDeposit.get()).isValid;
    }

    @Override
    public void triggerFocusOutOnAmountFields() {
        // do not update BTC Amount or minAmount here
        // issue 2798: "after a few edits of offer the BTC amount has increased"
    }
}
