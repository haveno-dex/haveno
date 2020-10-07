package bisq.core.trade;

import org.bitcoinj.core.Coin;

import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.storage.Storage;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.offer.Offer;
import bisq.core.proto.CoreProtoResolver;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.protocol.ArbitratorProtocol;
import bisq.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

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
          Storage<? extends TradableList> storage,
          XmrWalletService xmrWalletService) {
    super(offer, tradeAmount, txFee, takerFee, tradePrice, makerNodeAddress, takerNodeAddress, arbitratorNodeAddress, storage, xmrWalletService);
  }

  public void handleInitTradeRequest(InitTradeRequest message, NodeAddress taker, ErrorMessageHandler errorMessageHandler) {
    ((ArbitratorProtocol) tradeProtocol).handleInitTradeRequest(message, taker, errorMessageHandler);
  }
  
  @Override
  protected void createTradeProtocol() {
      tradeProtocol = new ArbitratorProtocol(this);
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
                                   Storage<? extends TradableList> storage,
                                   XmrWalletService xmrWalletService,
                                   CoreProtoResolver coreProtoResolver) {
      protobuf.Trade proto = arbitratorTradeProto.getTrade();
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
}
