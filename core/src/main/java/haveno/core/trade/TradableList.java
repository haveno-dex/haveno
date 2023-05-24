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

import com.google.protobuf.Message;
import haveno.common.proto.ProtoUtil;
import haveno.common.proto.ProtobufferRuntimeException;
import haveno.common.proto.persistable.PersistableListAsObservable;
import haveno.core.offer.OpenOffer;
import haveno.core.proto.CoreProtoResolver;
import haveno.core.xmr.wallet.XmrWalletService;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public final class TradableList<T extends Tradable> extends PersistableListAsObservable<T> {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public TradableList() {
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected TradableList(Collection<T> collection) {
        super(collection);
    }

    @Override
    public Message toProtoMessage() {
        synchronized (getList()) {
            return protobuf.PersistableEnvelope.newBuilder()
                    .setTradableList(protobuf.TradableList.newBuilder()
                            .addAllTradable(ProtoUtil.collectionToProto(getList(), protobuf.Tradable.class)))
                    .build();
        }
    }

    public static TradableList<Tradable> fromProto(protobuf.TradableList proto,
                                                   CoreProtoResolver coreProtoResolver,
                                                   XmrWalletService xmrWalletService) {
        List<Tradable> list = proto.getTradableList().stream()
                .map(tradable -> {
                    switch (tradable.getMessageCase()) {
                        case OPEN_OFFER:
                            return OpenOffer.fromProto(tradable.getOpenOffer());
                        case BUYER_AS_MAKER_TRADE:
                            return BuyerAsMakerTrade.fromProto(tradable.getBuyerAsMakerTrade(), xmrWalletService, coreProtoResolver);
                        case BUYER_AS_TAKER_TRADE:
                            return BuyerAsTakerTrade.fromProto(tradable.getBuyerAsTakerTrade(), xmrWalletService, coreProtoResolver);
                        case SELLER_AS_MAKER_TRADE:
                            return SellerAsMakerTrade.fromProto(tradable.getSellerAsMakerTrade(), xmrWalletService, coreProtoResolver);
                        case SELLER_AS_TAKER_TRADE:
                            return SellerAsTakerTrade.fromProto(tradable.getSellerAsTakerTrade(), xmrWalletService, coreProtoResolver);
                        case ARBITRATOR_TRADE:
                            return ArbitratorTrade.fromProto(tradable.getArbitratorTrade(), xmrWalletService, coreProtoResolver);
                        default:
                            log.error("Unknown messageCase. tradable.getMessageCase() = " + tradable.getMessageCase());
                            throw new ProtobufferRuntimeException("Unknown messageCase. tradable.getMessageCase() = " +
                                    tradable.getMessageCase());
                    }
                })
                .collect(Collectors.toList());

        return new TradableList<>(list);
    }

    @Override
    public String toString() {
        return "TradableList{" +
                ",\n     list=" + getList() +
                "\n}";
    }
}
