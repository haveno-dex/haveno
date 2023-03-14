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
