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

import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.offer.Offer;
import bisq.core.proto.CoreProtoResolver;
import bisq.core.trade.protocol.ProcessModel;
import bisq.network.p2p.NodeAddress;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Coin;

@Slf4j
public final class SellerAsMakerTrade extends SellerTrade implements MakerTrade {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, initialization
    ///////////////////////////////////////////////////////////////////////////////////////////

    public SellerAsMakerTrade(Offer offer,
                              Coin txFee,
                              Coin takerFee,
                              @Nullable NodeAddress makerNodeAddress,
                              @Nullable NodeAddress takerNodeAddress,
                              @Nullable NodeAddress arbitratorNodeAddress,
                              XmrWalletService xmrWalletService,
                              ProcessModel processModel) {
        super(offer,
                txFee,
                takerFee,
                makerNodeAddress,
                takerNodeAddress,
                arbitratorNodeAddress,
                xmrWalletService,
                processModel);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.Tradable toProtoMessage() {
        return protobuf.Tradable.newBuilder()
                .setSellerAsMakerTrade(protobuf.SellerAsMakerTrade.newBuilder()
                        .setTrade((protobuf.Trade) super.toProtoMessage()))
                .build();
    }

    public static Tradable fromProto(protobuf.SellerAsMakerTrade sellerAsMakerTradeProto,
                                     XmrWalletService xmrWalletService,
                                     CoreProtoResolver coreProtoResolver) {
        protobuf.Trade proto = sellerAsMakerTradeProto.getTrade();
        ProcessModel processModel = ProcessModel.fromProto(proto.getProcessModel(), coreProtoResolver);
        SellerAsMakerTrade trade = new SellerAsMakerTrade(
                Offer.fromProto(proto.getOffer()),
                Coin.valueOf(proto.getTxFeeAsLong()),
                Coin.valueOf(proto.getTakerFeeAsLong()),
                proto.hasMakerNodeAddress() ? NodeAddress.fromProto(proto.getMakerNodeAddress()) : null,
                proto.hasTakerNodeAddress() ? NodeAddress.fromProto(proto.getTakerNodeAddress()) : null,
                proto.hasArbitratorNodeAddress() ? NodeAddress.fromProto(proto.getArbitratorNodeAddress()) : null,
                xmrWalletService,
                processModel);

        trade.setTradeAmountAsLong(proto.getTradeAmountAsLong());
        trade.setTradePrice(proto.getTradePrice());

        return fromProto(trade,
                proto,
                coreProtoResolver);
    }
}
