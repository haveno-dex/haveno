package bisq.core.trade;

import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.offer.Offer;
import bisq.core.proto.CoreProtoResolver;
import bisq.core.trade.protocol.ProcessModel;
import bisq.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Coin;

/**
 * Trade in the context of an arbitrator.
 */
@Slf4j
public class ArbitratorTrade extends Trade {
  
  public ArbitratorTrade(Offer offer,
          Coin tradeAmount,
          Coin txFee,
          Coin takerFee,
          long tradePrice,
          NodeAddress makerNodeAddress,
          NodeAddress takerNodeAddress,
          NodeAddress arbitratorNodeAddress,
          XmrWalletService xmrWalletService,
          ProcessModel processModel) {
    super(offer, tradeAmount, txFee, takerFee, tradePrice, makerNodeAddress, takerNodeAddress, arbitratorNodeAddress, xmrWalletService, processModel);
  }
  
  @Override
  public Coin getPayoutAmount() {
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
      return fromProto(new SellerAsTakerTrade(
                      Offer.fromProto(proto.getOffer()),
                      Coin.valueOf(proto.getTradeAmountAsLong()),
                      Coin.valueOf(proto.getTxFeeAsLong()),
                      Coin.valueOf(proto.getTakerFeeAsLong()),
                      proto.getTradePrice(),
                      proto.hasMakerNodeAddress() ? NodeAddress.fromProto(proto.getMakerNodeAddress()) : null,
                      proto.hasTakerNodeAddress() ? NodeAddress.fromProto(proto.getTakerNodeAddress()) : null,
                      proto.hasArbitratorNodeAddress() ? NodeAddress.fromProto(proto.getArbitratorNodeAddress()) : null,
                      xmrWalletService,
                      processModel),
              proto,
              coreProtoResolver);
  }

  @Override
  public boolean confirmPermitted() {
    throw new RuntimeException("ArbitratorTrade.confirmPermitted() not implemented"); // TODO (woodser): implement
  }
}
