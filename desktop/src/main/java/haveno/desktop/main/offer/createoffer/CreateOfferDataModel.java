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
import haveno.core.offer.CreateOfferService;
import haveno.core.offer.OfferUtil;
import haveno.core.offer.OpenOfferManager;
import haveno.core.payment.PaymentAccount;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.Navigation;
import haveno.desktop.main.offer.MutableOfferDataModel;
import haveno.network.p2p.P2PService;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Domain for that UI element.
 * Note that the create offer domain has a deeper scope in the application domain (TradeManager).
 * That model is just responsible for the domain specific parts displayed needed in that UI element.
 */
class CreateOfferDataModel extends MutableOfferDataModel {

    @Inject
    public CreateOfferDataModel(CreateOfferService createOfferService,
                                OpenOfferManager openOfferManager,
                                OfferUtil offerUtil,
                                XmrWalletService xmrWalletService,
                                Preferences preferences,
                                User user,
                                P2PService p2PService,
                                PriceFeedService priceFeedService,
                                AccountAgeWitnessService accountAgeWitnessService,
                                @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter btcFormatter,
                                TradeStatisticsManager tradeStatisticsManager,
                                Navigation navigation) {
        super(createOfferService,
                openOfferManager,
                offerUtil,
                xmrWalletService,
                preferences,
                user,
                p2PService,
                priceFeedService,
                accountAgeWitnessService,
                btcFormatter,
                tradeStatisticsManager,
                navigation);
    }

    @Override
    protected Set<PaymentAccount> getUserPaymentAccounts() {
        return Objects.requireNonNull(user.getPaymentAccounts()).stream()
                .collect(Collectors.toSet());
    }
}
