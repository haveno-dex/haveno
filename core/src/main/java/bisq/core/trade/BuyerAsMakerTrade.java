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
import bisq.core.trade.protocol.BuyerAsMakerProtocol;
import bisq.core.trade.protocol.MakerProtocol;
import bisq.network.p2p.NodeAddress;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class BuyerAsMakerTrade extends BuyerTrade implements MakerTrade {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, initialization
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BuyerAsMakerTrade(Offer offer,
                             Coin txFee,
                             Coin takeOfferFee,
                             @Nullable NodeAddress takerNodeAddress,
                             @Nullable NodeAddress makerNodeAddress,
                             @Nullable NodeAddress arbitratorNodeAddress,
                             Storage<? extends TradableList> storage,
                             XmrWalletService xmrWalletService) {
        super(offer,
                txFee,
                takeOfferFee,
                takerNodeAddress,
                makerNodeAddress,
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
                .setBuyerAsMakerTrade(protobuf.BuyerAsMakerTrade.newBuilder()
                        .setTrade((protobuf.Trade) super.toProtoMessage()))
                .build();
    }

    public static Tradable fromProto(protobuf.BuyerAsMakerTrade buyerAsMakerTradeProto,
                                     Storage<? extends TradableList> storage,
                                     XmrWalletService xmrWalletService,
                                     CoreProtoResolver coreProtoResolver) {
        protobuf.Trade proto = buyerAsMakerTradeProto.getTrade();
        BuyerAsMakerTrade trade = new BuyerAsMakerTrade(
                Offer.fromProto(proto.getOffer()),
                Coin.valueOf(proto.getTxFeeAsLong()),
                Coin.valueOf(proto.getTakerFeeAsLong()),
                proto.hasTakerNodeAddress() ? NodeAddress.fromProto(proto.getTakerNodeAddress()) : null,
                proto.hasMakerNodeAddress() ? NodeAddress.fromProto(proto.getMakerNodeAddress()) : null,
                proto.hasArbitratorNodeAddress() ? NodeAddress.fromProto(proto.getArbitratorNodeAddress()) : null,
                storage,
                xmrWalletService);

        trade.setTradeAmountAsLong(proto.getTradeAmountAsLong());
        trade.setTradePrice(proto.getTradePrice());
        
        trade.setMakerNodeAddress(proto.hasMakerNodeAddress() ? NodeAddress.fromProto(proto.getMakerNodeAddress()) : null);
        trade.setTakerNodeAddress(proto.hasTakerNodeAddress() ? NodeAddress.fromProto(proto.getTakerNodeAddress()) : null);
        trade.setArbitratorNodeAddress(proto.hasArbitratorNodeAddress() ? NodeAddress.fromProto(proto.getArbitratorNodeAddress()) : null);

        return fromProto(trade,
                proto,
                coreProtoResolver);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void createTradeProtocol() {
        tradeProtocol = new BuyerAsMakerProtocol(this);
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
