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

import bisq.common.proto.ProtoUtil;

import org.bitcoinj.core.Coin;

import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

@Slf4j
public final class BuyerAsMakerTrade extends BuyerTrade implements MakerTrade {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, initialization
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BuyerAsMakerTrade(Offer offer,
                             Coin tradeAmount,
                             Coin takeOfferFee,
                             long tradePrice,
                             XmrWalletService xmrWalletService,
                             ProcessModel processModel,
                             String uid,
                             NodeAddress makerNodeAddress,
                             NodeAddress takerNodeAddress,
                             NodeAddress arbitratorNodeAddress) {
        super(offer,
                tradeAmount,
                takeOfferFee,
                tradePrice,
                xmrWalletService,
                processModel,
                uid,
                makerNodeAddress,
                takerNodeAddress,
                arbitratorNodeAddress);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.Tradable toProtoMessage() {
        return protobuf.Tradable.newBuilder()
                .setBuyerAsMakerTrade(protobuf.BuyerAsMakerTrade.newBuilder()
                        .setTrade((protobuf.Trade) super.toProtoMessage()))
                .build();
    }

    public static Tradable fromProto(protobuf.BuyerAsMakerTrade buyerAsMakerTradeProto,
                                     XmrWalletService xmrWalletService,
                                     CoreProtoResolver coreProtoResolver) {
        protobuf.Trade proto = buyerAsMakerTradeProto.getTrade();
        ProcessModel processModel = ProcessModel.fromProto(proto.getProcessModel(), coreProtoResolver);
        String uid = ProtoUtil.stringOrNullFromProto(proto.getUid());
        if (uid == null) {
            uid = UUID.randomUUID().toString();
        }
        BuyerAsMakerTrade trade = new BuyerAsMakerTrade(
                Offer.fromProto(proto.getOffer()),
                Coin.valueOf(proto.getTradeAmountAsLong()),
                Coin.valueOf(proto.getTakerFeeAsLong()),
                proto.getTradePrice(),
                xmrWalletService,
                processModel,
                uid,
                proto.hasMakerNodeAddress() ? NodeAddress.fromProto(proto.getMakerNodeAddress()) : null,
                proto.hasTakerNodeAddress() ? NodeAddress.fromProto(proto.getTakerNodeAddress()) : null,
                proto.hasArbitratorNodeAddress() ? NodeAddress.fromProto(proto.getArbitratorNodeAddress()) : null);

        trade.setTradeAmountAsLong(proto.getTradeAmountAsLong());
        trade.setTradePrice(proto.getTradePrice());

        trade.setMakerNodeAddress(proto.hasMakerNodeAddress() ? NodeAddress.fromProto(proto.getMakerNodeAddress()) : null);
        trade.setTakerNodeAddress(proto.hasTakerNodeAddress() ? NodeAddress.fromProto(proto.getTakerNodeAddress()) : null);
        trade.setArbitratorNodeAddress(proto.hasArbitratorNodeAddress() ? NodeAddress.fromProto(proto.getArbitratorNodeAddress()) : null);

        return fromProto(trade,
                proto,
                coreProtoResolver);
    }
}
