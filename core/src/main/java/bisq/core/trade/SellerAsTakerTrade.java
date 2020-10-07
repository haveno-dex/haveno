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

package bisq.core.trade;

import static com.google.common.base.Preconditions.checkArgument;

import javax.annotation.Nullable;

import org.bitcoinj.core.Coin;

import bisq.common.handlers.ResultHandler;
import bisq.common.storage.Storage;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.offer.Offer;
import bisq.core.proto.CoreProtoResolver;
import bisq.core.trade.protocol.SellerAsTakerProtocol;
import bisq.core.trade.protocol.TakerProtocol;
import bisq.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class SellerAsTakerTrade extends SellerTrade implements TakerTrade {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, initialization
    ///////////////////////////////////////////////////////////////////////////////////////////

    public SellerAsTakerTrade(Offer offer,
                              Coin tradeAmount,
                              Coin txFee,
                              Coin takerFee,
                              long tradePrice,
                              @Nullable NodeAddress makerNodeAddress,
                              @Nullable NodeAddress takerNodeAddress,
                              @Nullable NodeAddress arbitratorNodeAddress,
                              Storage<? extends TradableList> storage,
                              XmrWalletService xmrWalletService) {
        super(offer,
                tradeAmount,
                txFee,
                takerFee,
                tradePrice,
                makerNodeAddress,
                takerNodeAddress,
                arbitratorNodeAddress,
                storage,
                xmrWalletService);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.Tradable toProtoMessage() {
        return protobuf.Tradable.newBuilder()
                .setSellerAsTakerTrade(protobuf.SellerAsTakerTrade.newBuilder()
                        .setTrade((protobuf.Trade) super.toProtoMessage()))
                .build();
    }

    public static Tradable fromProto(protobuf.SellerAsTakerTrade sellerAsTakerTradeProto,
                                     Storage<? extends TradableList> storage,
                                     XmrWalletService xmrWalletService,
                                     CoreProtoResolver coreProtoResolver) {
        protobuf.Trade proto = sellerAsTakerTradeProto.getTrade();
        return fromProto(new SellerAsTakerTrade(
                        Offer.fromProto(proto.getOffer()),
                        Coin.valueOf(proto.getTradeAmountAsLong()),
                        Coin.valueOf(proto.getTxFeeAsLong()),
                        Coin.valueOf(proto.getTakerFeeAsLong()),
                        proto.getTradePrice(),
                        proto.hasMakerNodeAddress() ? NodeAddress.fromProto(proto.getMakerNodeAddress()) : null,
                        proto.hasTakerNodeAddress() ? NodeAddress.fromProto(proto.getTakerNodeAddress()) : null,
                        proto.hasArbitratorNodeAddress() ? NodeAddress.fromProto(proto.getArbitratorNodeAddress()) : null,
                        storage,
                        xmrWalletService),
                proto,
                coreProtoResolver);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void createTradeProtocol() {
        tradeProtocol = new SellerAsTakerProtocol(this);
    }

    @Override
    public void takeAvailableOffer(ResultHandler handler) {
        checkArgument(tradeProtocol instanceof TakerProtocol, "tradeProtocol NOT instanceof TakerProtocol");
        ((TakerProtocol) tradeProtocol).takeAvailableOffer(handler);
    }
}
