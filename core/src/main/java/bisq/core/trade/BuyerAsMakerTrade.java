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
import bisq.core.proto.CoreProtoResolver;
import bisq.core.trade.protocol.ProcessModel;

import bisq.network.p2p.NodeAddress;

import bisq.common.proto.ProtoUtil;

import java.math.BigInteger;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class BuyerAsMakerTrade extends BuyerTrade implements MakerTrade {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, initialization
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BuyerAsMakerTrade(Offer offer,
                             BigInteger tradeAmount,
                             BigInteger takeOfferFee,
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
                BigInteger.valueOf(proto.getAmountAsLong()),
                BigInteger.valueOf(proto.getTakerFeeAsLong()),
                proto.getPrice(),
                xmrWalletService,
                processModel,
                uid,
                proto.getProcessModel().getMaker().hasNodeAddress() ? NodeAddress.fromProto(proto.getProcessModel().getMaker().getNodeAddress()) : null,
                proto.getProcessModel().getTaker().hasNodeAddress() ? NodeAddress.fromProto(proto.getProcessModel().getTaker().getNodeAddress()) : null,
                proto.getProcessModel().getArbitrator().hasNodeAddress() ? NodeAddress.fromProto(proto.getProcessModel().getArbitrator().getNodeAddress()) : null);

        trade.setAmountAsLong(proto.getAmountAsLong());
        trade.setPrice(proto.getPrice());

        return fromProto(trade,
                proto,
                coreProtoResolver);
    }
}
