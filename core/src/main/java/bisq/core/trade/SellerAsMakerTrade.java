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

import javax.annotation.Nullable;

import org.bitcoinj.core.Coin;

import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.storage.Storage;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.offer.Offer;
import bisq.core.proto.CoreProtoResolver;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.messages.InputsForDepositTxRequest;
import bisq.core.trade.messages.MakerReadyToFundMultisigRequest;
import bisq.core.trade.protocol.MakerProtocol;
import bisq.core.trade.protocol.SellerAsMakerProtocol;
import bisq.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

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
                              Storage<? extends TradableList> storage,
                              XmrWalletService xmrWalletService) {
        super(offer,
                txFee,
                takerFee,
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
                .setSellerAsMakerTrade(protobuf.SellerAsMakerTrade.newBuilder()
                        .setTrade((protobuf.Trade) super.toProtoMessage()))
                .build();
    }

    public static Tradable fromProto(protobuf.SellerAsMakerTrade sellerAsMakerTradeProto,
                                     Storage<? extends TradableList> storage,
                                     XmrWalletService xmrWalletService,
                                     CoreProtoResolver coreProtoResolver) {
        protobuf.Trade proto = sellerAsMakerTradeProto.getTrade();
        SellerAsMakerTrade trade = new SellerAsMakerTrade(
                Offer.fromProto(proto.getOffer()),
                Coin.valueOf(proto.getTxFeeAsLong()),
                Coin.valueOf(proto.getTakerFeeAsLong()),
                proto.hasMakerNodeAddress() ? NodeAddress.fromProto(proto.getMakerNodeAddress()) : null,
                proto.hasTakerNodeAddress() ? NodeAddress.fromProto(proto.getTakerNodeAddress()) : null,
                proto.hasArbitratorNodeAddress() ? NodeAddress.fromProto(proto.getArbitratorNodeAddress()) : null,
                storage,
                xmrWalletService);

        trade.setTradeAmountAsLong(proto.getTradeAmountAsLong());
        trade.setTradePrice(proto.getTradePrice());

        return fromProto(trade,
                proto,
                coreProtoResolver);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void createTradeProtocol() {
        tradeProtocol = new SellerAsMakerProtocol(this);
    }
    
    @Override
    public void handleInitTradeRequest(InitTradeRequest message, NodeAddress taker, ErrorMessageHandler errorMessageHandler) {
      ((MakerProtocol) tradeProtocol).handleInitTradeRequest(message, taker, errorMessageHandler);
    }

    @Override
    public void handleTakeOfferRequest(InputsForDepositTxRequest message, NodeAddress taker, ErrorMessageHandler errorMessageHandler) {
        ((MakerProtocol) tradeProtocol).handleTakeOfferRequest(message, taker, errorMessageHandler);
    }
}
