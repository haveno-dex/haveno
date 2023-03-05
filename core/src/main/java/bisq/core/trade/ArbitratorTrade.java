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
  public BigInteger getPayoutAmount() {
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
                      BigInteger.valueOf(proto.getAmountAsLong()),
                      BigInteger.valueOf(proto.getTakerFeeAsLong()),
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
