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

package haveno.desktop.main.portfolio.openoffer;

import static com.google.common.base.Preconditions.checkNotNull;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.monetary.Price;
import haveno.core.offer.Offer;
import haveno.core.offer.OpenOffer;
import haveno.core.trade.HavenoUtils;
import haveno.core.util.FormattingUtils;
import haveno.core.util.PriceUtil;
import haveno.core.util.VolumeUtil;
import haveno.core.util.coin.CoinFormatter;
import haveno.desktop.common.model.ActivatableWithDataModel;
import haveno.desktop.common.model.ViewModel;
import haveno.desktop.util.DisplayUtils;
import haveno.desktop.util.GUIUtil;
import haveno.network.p2p.P2PService;
import javafx.collections.ObservableList;

class OpenOffersViewModel extends ActivatableWithDataModel<OpenOffersDataModel> implements ViewModel {
    private final P2PService p2PService;
    private final PriceUtil priceUtil;
    private final CoinFormatter btcFormatter;


    @Inject
    public OpenOffersViewModel(OpenOffersDataModel dataModel,
                               P2PService p2PService,
                               PriceUtil priceUtil,
                               @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter btcFormatter) {
        super(dataModel);

        this.p2PService = p2PService;
        this.priceUtil = priceUtil;
        this.btcFormatter = btcFormatter;
    }

    @Override
    protected void activate() {
    }

    void onActivateOpenOffer(OpenOffer openOffer,
                             ResultHandler resultHandler,
                             ErrorMessageHandler errorMessageHandler) {
        dataModel.onActivateOpenOffer(openOffer, resultHandler, errorMessageHandler);
    }

    void onDeactivateOpenOffer(OpenOffer openOffer,
                               ResultHandler resultHandler,
                               ErrorMessageHandler errorMessageHandler) {
        dataModel.onDeactivateOpenOffer(openOffer, resultHandler, errorMessageHandler);
    }

    void onRemoveOpenOffer(OpenOffer openOffer, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        dataModel.onRemoveOpenOffer(openOffer, resultHandler, errorMessageHandler);
    }

    public ObservableList<OpenOfferListItem> getList() {
        return dataModel.getList();
    }

    String getOfferId(OpenOfferListItem item) {
        return item.getOffer().getShortId();
    }

    String getAmount(OpenOfferListItem item) {
        return (item != null) ? DisplayUtils.formatAmount(item.getOffer(), btcFormatter) : "";
    }

    String getPrice(OpenOfferListItem item) {
        if ((item == null))
            return "";

        Offer offer = item.getOffer();
        Price price = offer.getPrice();
        if (price != null) {
            return FormattingUtils.formatPrice(price);
        } else {
            return Res.get("shared.na");
        }
    }

    String getPriceDeviation(OpenOfferListItem item) {
        Offer offer = item.getOffer();
        return priceUtil.getMarketBasedPrice(offer, offer.getMirroredDirection())
                .map(FormattingUtils::formatPercentagePrice)
                .orElse("");
    }

    Double getPriceDeviationAsDouble(OpenOfferListItem item) {
        Offer offer = item.getOffer();
        return priceUtil.getMarketBasedPrice(offer, offer.getMirroredDirection()).orElse(0d);
    }

    String getVolume(OpenOfferListItem item) {
        return (item != null) ? VolumeUtil.formatVolume(item.getOffer(), false, 0) + " " + item.getOffer().getCurrencyCode() : "";
    }

    String getDirectionLabel(OpenOfferListItem item) {
        if ((item == null))
            return "";

        return DisplayUtils.getDirectionWithCode(dataModel.getDirection(item.getOffer()), item.getOffer().getCurrencyCode());
    }

    String getMarketLabel(OpenOfferListItem item) {
        if ((item == null))
            return "";

        return CurrencyUtil.getCurrencyPair(item.getOffer().getCurrencyCode());
    }

    String getPaymentMethod(OpenOfferListItem item) {
        String result = "";
        if (item != null) {
            Offer offer = item.getOffer();
            checkNotNull(offer);
            checkNotNull(offer.getPaymentMethod());
            result = offer.getPaymentMethodNameWithCountryCode();
        }
        return result;
    }

    String getDate(OpenOfferListItem item) {
        return DisplayUtils.formatDateTime(item.getOffer().getDate());
    }

    boolean isDeactivated(OpenOfferListItem item) {
        return item != null && item.getOpenOffer() != null && item.getOpenOffer().isDeactivated();
    }

    boolean isBootstrappedOrShowPopup() {
        return GUIUtil.isBootstrappedOrShowPopup(p2PService);
    }

    public String getMakerFeeAsString(OpenOffer openOffer) {
        Offer offer = openOffer.getOffer();
        return HavenoUtils.formatXmr(offer.getMakerFee(), true);
    }

    String getTriggerPrice(OpenOfferListItem item) {
        if ((item == null)) {
            return "";
        }

        Offer offer = item.getOffer();
        long triggerPrice = item.getOpenOffer().getTriggerPrice();
        if (!offer.isUseMarketBasedPrice() || triggerPrice <= 0) {
            return Res.get("shared.na");
        } else {
            return PriceUtil.formatMarketPrice(triggerPrice, offer.getCurrencyCode());
        }
    }
}
