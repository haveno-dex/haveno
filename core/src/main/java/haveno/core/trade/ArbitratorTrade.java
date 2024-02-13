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

package haveno.core.trade;

import haveno.common.proto.ProtoUtil;
import haveno.core.offer.Offer;
import haveno.core.proto.CoreProtoResolver;
import haveno.core.trade.protocol.ProcessModel;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.UUID;

/**
 * Trade in the context of an arbitrator.
 */
@Slf4j
public class ArbitratorTrade extends Trade {

  public ArbitratorTrade(Offer offer,
          BigInteger tradeAmount,
          BigInteger takerFee,
          long tradePrice,
          XmrWalletService xmrWalletService,
          ProcessModel processModel,
          String uid,
          NodeAddress makerNodeAddress,
          NodeAddress takerNodeAddress,
          NodeAddress arbitratorNodeAddress) {
    super(offer, tradeAmount, takerFee, tradePrice, xmrWalletService, processModel, uid, makerNodeAddress, takerNodeAddress, arbitratorNodeAddress);
  }

  @Override
  public BigInteger getPayoutAmountBeforeCost() {
    throw new RuntimeException("Arbitrator does not have a payout amount");
  }

  ///////////////////////////////////////////////////////////////////////////////////////////
  // PROTO BUFFER
  ///////////////////////////////////////////////////////////////////////////////////////////

  @Override
  public protobuf.Tradable toProtoMessage() {
      return protobuf.Tradable.newBuilder()
              .setArbitratorTrade(protobuf.ArbitratorTrade.newBuilder()
                      .setTrade((protobuf.Trade) super.toProtoMessage()))
              .build();
  }

  public static Tradable fromProto(protobuf.ArbitratorTrade arbitratorTradeProto,
                                   XmrWalletService xmrWalletService,
                                   CoreProtoResolver coreProtoResolver) {
      protobuf.Trade proto = arbitratorTradeProto.getTrade();
      ProcessModel processModel = ProcessModel.fromProto(proto.getProcessModel(), coreProtoResolver);
      String uid = ProtoUtil.stringOrNullFromProto(proto.getUid());
      if (uid == null) {
          uid = UUID.randomUUID().toString();
      }
      return fromProto(new ArbitratorTrade(
                      Offer.fromProto(proto.getOffer()),
                      BigInteger.valueOf(proto.getAmount()),
                      BigInteger.valueOf(proto.getTakerFee()),
                      proto.getPrice(),
                      xmrWalletService,
                      processModel,
                      uid,
                      proto.getProcessModel().getMaker().hasNodeAddress() ? NodeAddress.fromProto(proto.getProcessModel().getMaker().getNodeAddress()) : null,
                      proto.getProcessModel().getTaker().hasNodeAddress() ? NodeAddress.fromProto(proto.getProcessModel().getTaker().getNodeAddress()) : null,
                      proto.getProcessModel().getArbitrator().hasNodeAddress() ? NodeAddress.fromProto(proto.getProcessModel().getArbitrator().getNodeAddress()) : null),
              proto,
              coreProtoResolver);
  }

  @Override
  public boolean confirmPermitted() {
    throw new RuntimeException("ArbitratorTrade.confirmPermitted() not implemented"); // TODO (woodser): implement
  }
}
