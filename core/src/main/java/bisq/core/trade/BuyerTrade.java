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

package bisq.core.trade;

import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.offer.Offer;
import bisq.core.trade.protocol.ProcessModel;

import bisq.network.p2p.NodeAddress;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;

import java.math.BigInteger;

@Slf4j
public abstract class BuyerTrade extends Trade {
    BuyerTrade(Offer offer,
               BigInteger tradeAmount,
               BigInteger takerFee,
               long tradePrice,
               XmrWalletService xmrWalletService,
               ProcessModel processModel,
               String uid,
               @Nullable NodeAddress takerNodeAddress,
               @Nullable NodeAddress makerNodeAddress,
               @Nullable NodeAddress arbitratorNodeAddress) {
        super(offer,
                tradeAmount,
                takerFee,
                tradePrice,
                xmrWalletService,
                processModel,
                uid,
                takerNodeAddress,
                makerNodeAddress,
                arbitratorNodeAddress);
    }

    @Override
    public BigInteger getPayoutAmount() {
        checkNotNull(getAmount(), "Invalid state: getTradeAmount() = null");
        return checkNotNull(getOffer()).getBuyerSecurityDeposit().add(getAmount());
    }

    @Override
    public boolean confirmPermitted() {
        return true;
    }
}
