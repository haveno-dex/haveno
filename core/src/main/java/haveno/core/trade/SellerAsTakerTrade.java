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

package haveno.core.trade;

import haveno.common.proto.ProtoUtil;
import haveno.core.offer.Offer;
import haveno.core.proto.CoreProtoResolver;
import haveno.core.trade.protocol.ProcessModel;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.UUID;

@Slf4j
public final class SellerAsTakerTrade extends SellerTrade implements TakerTrade {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, initialization
    ///////////////////////////////////////////////////////////////////////////////////////////

    public SellerAsTakerTrade(Offer offer,
                              BigInteger tradeAmount,
                              long tradePrice,
                              XmrWalletService xmrWalletService,
                              ProcessModel processModel,
                              String uid,
                              @Nullable NodeAddress makerNodeAddress,
                              @Nullable NodeAddress takerNodeAddress,
                              @Nullable NodeAddress arbitratorNodeAddress,
                              @Nullable String challenge) {
        super(offer,
                tradeAmount,
                tradePrice,
                xmrWalletService,
                processModel,
                uid,
                makerNodeAddress,
                takerNodeAddress,
                arbitratorNodeAddress,
                challenge);
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
                                     XmrWalletService xmrWalletService,
                                     CoreProtoResolver coreProtoResolver) {
        protobuf.Trade proto = sellerAsTakerTradeProto.getTrade();
        ProcessModel processModel = ProcessModel.fromProto(proto.getProcessModel(), coreProtoResolver);
        String uid = ProtoUtil.stringOrNullFromProto(proto.getUid());
        if (uid == null) {
            uid = UUID.randomUUID().toString();
        }
        return fromProto(new SellerAsTakerTrade(
                        Offer.fromProto(proto.getOffer()),
                        BigInteger.valueOf(proto.getAmount()),
                        proto.getPrice(),
                        xmrWalletService,
                        processModel,
                        uid,
                        proto.getProcessModel().getMaker().hasNodeAddress() ? NodeAddress.fromProto(proto.getProcessModel().getMaker().getNodeAddress()) : null,
                        proto.getProcessModel().getTaker().hasNodeAddress() ? NodeAddress.fromProto(proto.getProcessModel().getTaker().getNodeAddress()) : null,
                        proto.getProcessModel().getArbitrator().hasNodeAddress() ? NodeAddress.fromProto(proto.getProcessModel().getArbitrator().getNodeAddress()) : null,
                        ProtoUtil.stringOrNullFromProto(proto.getChallenge())),
                proto,
                coreProtoResolver);
    }
}
