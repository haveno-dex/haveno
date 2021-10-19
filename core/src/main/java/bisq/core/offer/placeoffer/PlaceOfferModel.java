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

package bisq.core.offer.placeoffer;

import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.btc.wallet.TradeWalletService;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.filter.FilterManager;
import bisq.core.offer.Offer;
import bisq.core.offer.OfferBookService;
import bisq.core.offer.messages.SignOfferResponse;
import bisq.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import bisq.core.support.dispute.mediation.mediator.MediatorManager;
import bisq.core.trade.statistics.TradeStatisticsManager;
import bisq.core.user.User;
import bisq.common.crypto.KeyRing;
import bisq.common.taskrunner.Model;

import bisq.network.p2p.P2PService;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;



import monero.wallet.model.MoneroTxWallet;

@Slf4j
@Getter
public class PlaceOfferModel implements Model {
    // Immutable
    private final Offer offer;
    private final Coin reservedFundsForOffer;
    private final boolean useSavingsWallet;
    private final P2PService p2PService;
    private final BtcWalletService walletService;
    private final XmrWalletService xmrWalletService;
    private final TradeWalletService tradeWalletService;
    private final OfferBookService offerBookService;
    private final ArbitratorManager arbitratorManager;
    private final MediatorManager mediatorManager;
    private final TradeStatisticsManager tradeStatisticsManager;
    private final User user;
    private final KeyRing keyRing;
    @Getter
    private final FilterManager filterManager;

    // Mutable
    @Setter
    private boolean offerAddedToOfferBook;
    @Setter
    private Transaction transaction;
    @Setter
    private MoneroTxWallet reserveTx;
    @Setter
    private SignOfferResponse signOfferResponse;

    public PlaceOfferModel(Offer offer,
                           Coin reservedFundsForOffer,
                           boolean useSavingsWallet,
                           P2PService p2PService,
                           BtcWalletService walletService,
                           XmrWalletService xmrWalletService,
                           TradeWalletService tradeWalletService,
                           OfferBookService offerBookService,
                           ArbitratorManager arbitratorManager,
                           MediatorManager mediatorManager,
                           TradeStatisticsManager tradeStatisticsManager,
                           User user,
                           KeyRing keyRing,
                           FilterManager filterManager) {
        this.offer = offer;
        this.reservedFundsForOffer = reservedFundsForOffer;
        this.useSavingsWallet = useSavingsWallet;
        this.p2PService = p2PService;
        this.walletService = walletService;
        this.xmrWalletService = xmrWalletService;
        this.tradeWalletService = tradeWalletService;
        this.offerBookService = offerBookService;
        this.arbitratorManager = arbitratorManager;
        this.mediatorManager = mediatorManager;
        this.tradeStatisticsManager = tradeStatisticsManager;
        this.user = user;
        this.keyRing = keyRing;
        this.filterManager = filterManager;
    }

    @Override
    public void onComplete() {
    }
}
