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


import haveno.desktop.Navigation;
import haveno.desktop.main.offer.MutableOfferDataModel;

import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.btc.wallet.XmrWalletService;
import haveno.core.offer.CreateOfferService;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferUtil;
import haveno.core.offer.OpenOfferManager;
import haveno.core.provider.fee.FeeService;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;

import haveno.network.p2p.P2PService;

import com.google.inject.Inject;

import javax.inject.Named;

class DuplicateOfferDataModel extends MutableOfferDataModel {

    @Inject
    DuplicateOfferDataModel(CreateOfferService createOfferService,
                       OpenOfferManager openOfferManager,
                       OfferUtil offerUtil,
                       XmrWalletService btcWalletService,
                       Preferences preferences,
                       User user,
                       P2PService p2PService,
                       PriceFeedService priceFeedService,
                       AccountAgeWitnessService accountAgeWitnessService,
                       FeeService feeService,
                       @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter btcFormatter,
                       TradeStatisticsManager tradeStatisticsManager,
                       Navigation navigation) {

        super(createOfferService,
                openOfferManager,
                offerUtil,
                btcWalletService,
                preferences,
                user,
                p2PService,
                priceFeedService,
                accountAgeWitnessService,
                feeService,
                btcFormatter,
                tradeStatisticsManager,
                navigation);
    }

    public void populateData(Offer offer) {
        if (offer == null)
            return;
        paymentAccount = user.getPaymentAccount(offer.getMakerPaymentAccountId());
        setMinAmount(offer.getMinAmount());
        setAmount(offer.getAmount());
        setPrice(offer.getPrice());
        setVolume(offer.getVolume());
        setUseMarketBasedPrice(offer.isUseMarketBasedPrice());
        if (offer.isUseMarketBasedPrice()) {
            setMarketPriceMargin(offer.getMarketPriceMargin());
        }
    }
}
