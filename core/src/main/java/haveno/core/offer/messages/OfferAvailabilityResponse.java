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


import com.google.protobuf.ByteString;
import haveno.common.app.Capabilities;
import haveno.common.app.Version;
import haveno.common.proto.ProtoUtil;
import haveno.core.offer.AvailabilityResult;
import haveno.network.p2p.SupportedCapabilitiesMessage;
import lombok.EqualsAndHashCode;
import lombok.Value;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

// We add here the SupportedCapabilitiesMessage interface as that message always predates a direct connection
// to the trading peer
@EqualsAndHashCode(callSuper = true)
@Value
public final class OfferAvailabilityResponse extends OfferMessage implements SupportedCapabilitiesMessage {
    private final AvailabilityResult availabilityResult;
    @Nullable
    private final Capabilities supportedCapabilities;

    @Nullable
    private byte[] makerSignature;

    public OfferAvailabilityResponse(String offerId,
                                     AvailabilityResult availabilityResult,
                                     @Nullable byte[] makerSignature) {
        this(offerId,
                availabilityResult,
                Capabilities.app,
                Version.getP2PMessageVersion(),
                UUID.randomUUID().toString(),
                makerSignature);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private OfferAvailabilityResponse(String offerId,
                                      AvailabilityResult availabilityResult,
                                      @Nullable Capabilities supportedCapabilities,
                                      String messageVersion,
                                      @Nullable String uid,
                                      byte[] makerSignature) {
        super(messageVersion, offerId, uid);
        this.availabilityResult = availabilityResult;
        this.supportedCapabilities = supportedCapabilities;
        this.makerSignature = makerSignature;
    }

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        final protobuf.OfferAvailabilityResponse.Builder builder = protobuf.OfferAvailabilityResponse.newBuilder()
                .setOfferId(offerId)
                .setAvailabilityResult(protobuf.AvailabilityResult.valueOf(availabilityResult.name()));

        Optional.ofNullable(supportedCapabilities).ifPresent(e -> builder.addAllSupportedCapabilities(Capabilities.toIntList(supportedCapabilities)));
        Optional.ofNullable(uid).ifPresent(e -> builder.setUid(uid));
        Optional.ofNullable(makerSignature).ifPresent(e -> builder.setMakerSignature(ByteString.copyFrom(e)));

        return getNetworkEnvelopeBuilder()
                .setOfferAvailabilityResponse(builder)
                .build();
    }

    public static OfferAvailabilityResponse fromProto(protobuf.OfferAvailabilityResponse proto, String messageVersion) {
        return new OfferAvailabilityResponse(proto.getOfferId(),
                ProtoUtil.enumFromProto(AvailabilityResult.class, proto.getAvailabilityResult().name()),
                Capabilities.fromIntList(proto.getSupportedCapabilitiesList()),
                messageVersion,
                proto.getUid().isEmpty() ? null : proto.getUid(),
                ProtoUtil.byteArrayOrNullFromProto(proto.getMakerSignature()));
    }
}
