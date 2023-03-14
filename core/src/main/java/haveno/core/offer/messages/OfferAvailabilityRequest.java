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

package haveno.core.offer.messages;

import haveno.common.app.Capabilities;
import haveno.common.app.Version;
import haveno.common.crypto.PubKeyRing;
import haveno.core.proto.CoreProtoResolver;
import haveno.core.trade.messages.InitTradeRequest;
import haveno.network.p2p.SupportedCapabilitiesMessage;
import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

// Here we add the SupportedCapabilitiesMessage interface as that message always predates a direct connection
// to the trading peer
@EqualsAndHashCode(callSuper = true)
@Value
@Slf4j
public final class OfferAvailabilityRequest extends OfferMessage implements SupportedCapabilitiesMessage {
    private final PubKeyRing pubKeyRing;
    private final long takersTradePrice;
    @Nullable
    private final Capabilities supportedCapabilities;
    private final boolean isTakerApiUser;
    private final InitTradeRequest tradeRequest;

    public OfferAvailabilityRequest(String offerId,
                                    PubKeyRing pubKeyRing,
                                    long takersTradePrice,
                                    boolean isTakerApiUser,
                                    InitTradeRequest tradeRequest) {
        this(offerId,
                pubKeyRing,
                takersTradePrice,
                isTakerApiUser,
                Capabilities.app,
                Version.getP2PMessageVersion(),
                UUID.randomUUID().toString(),
                tradeRequest);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private OfferAvailabilityRequest(String offerId,
                                     PubKeyRing pubKeyRing,
                                     long takersTradePrice,
                                     boolean isTakerApiUser,
                                     @Nullable Capabilities supportedCapabilities,
                                     String messageVersion,
                                     @Nullable String uid,
                                     InitTradeRequest tradeRequest) {
        super(messageVersion, offerId, uid);
        this.pubKeyRing = pubKeyRing;
        this.takersTradePrice = takersTradePrice;
        this.isTakerApiUser = isTakerApiUser;
        this.supportedCapabilities = supportedCapabilities;
        this.tradeRequest = tradeRequest;
    }

//    @Override
//    public protobuf.Offer toProtoMessage() {
//        return protobuf.Offer.newBuilder().setOfferPayload(offerPayload.toProtoMessage().getOfferPayload()).build();
//    }
//
//    public static Offer fromProto(protobuf.Offer proto) {
//        return new Offer(OfferPayload.fromProto(proto.getOfferPayload()));
//    }

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        final protobuf.OfferAvailabilityRequest.Builder builder = protobuf.OfferAvailabilityRequest.newBuilder()
                .setOfferId(offerId)
                .setPubKeyRing(pubKeyRing.toProtoMessage())
                .setTakersTradePrice(takersTradePrice)
                .setIsTakerApiUser(isTakerApiUser)
                .setTradeRequest(tradeRequest.toProtoNetworkEnvelope().getInitTradeRequest());
        Optional.ofNullable(supportedCapabilities).ifPresent(e -> builder.addAllSupportedCapabilities(Capabilities.toIntList(supportedCapabilities)));
        Optional.ofNullable(uid).ifPresent(e -> builder.setUid(uid));

        return getNetworkEnvelopeBuilder()
                .setOfferAvailabilityRequest(builder)
                .build();
    }

    public static OfferAvailabilityRequest fromProto(protobuf.OfferAvailabilityRequest proto, CoreProtoResolver coreProtoResolver, String messageVersion) {
        return new OfferAvailabilityRequest(proto.getOfferId(),
                PubKeyRing.fromProto(proto.getPubKeyRing()),
                proto.getTakersTradePrice(),
                proto.getIsTakerApiUser(),
                Capabilities.fromIntList(proto.getSupportedCapabilitiesList()),
                messageVersion,
                proto.getUid().isEmpty() ? null : proto.getUid(),
                InitTradeRequest.fromProto(proto.getTradeRequest(), coreProtoResolver, messageVersion));
    }
}
