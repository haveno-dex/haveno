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

package haveno.core.offer.placeoffer;

import haveno.common.crypto.KeyRing;
import haveno.common.taskrunner.Model;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.filter.FilterManager;
import haveno.core.offer.OfferBookService;
import haveno.core.offer.OpenOffer;
import haveno.core.offer.messages.SignOfferResponse;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.support.dispute.mediation.mediator.MediatorManager;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.user.User;
import haveno.core.xmr.wallet.BtcWalletService;
import haveno.core.xmr.wallet.TradeWalletService;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.P2PService;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.model.MoneroTxWallet;
import org.bitcoinj.core.Transaction;

import java.math.BigInteger;

@Slf4j
@Getter
public class PlaceOfferModel implements Model {
    // Immutable
    private final OpenOffer openOffer;
    private final BigInteger reservedFundsForOffer;
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
    @Getter
    private final AccountAgeWitnessService accountAgeWitnessService;

    // Mutable
    @Setter
    private boolean offerAddedToOfferBook;
    @Setter
    private Transaction transaction;
    @Setter
    private MoneroTxWallet reserveTx;
    @Setter
    private SignOfferResponse signOfferResponse;

    public PlaceOfferModel(OpenOffer openOffer,
                           BigInteger reservedFundsForOffer,
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
                           FilterManager filterManager,
                           AccountAgeWitnessService accountAgeWitnessService) {
        this.openOffer = openOffer;
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
        this.accountAgeWitnessService = accountAgeWitnessService;
    }

    @Override
    public void onComplete() {
    }
}
