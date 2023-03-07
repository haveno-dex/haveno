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

import haveno.core.offer.OfferPayload;
import haveno.network.p2p.DirectMessage;
import lombok.EqualsAndHashCode;
import lombok.Value;

@EqualsAndHashCode(callSuper = true)
@Value
public final class SignOfferResponse extends OfferMessage implements DirectMessage {
    private final OfferPayload signedOfferPayload;

    public SignOfferResponse(String offerId,
                                     String uid,
                                     String messageVersion,
                                     OfferPayload signedOfferPayload) {
        super(messageVersion, offerId, uid);
        this.signedOfferPayload = signedOfferPayload;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.SignOfferResponse.Builder builder = protobuf.SignOfferResponse.newBuilder()
                .setOfferId(offerId)
                .setUid(uid)
                .setSignedOfferPayload(signedOfferPayload.toProtoMessage().getOfferPayload());

        return getNetworkEnvelopeBuilder().setSignOfferResponse(builder).build();
    }

    public static SignOfferResponse fromProto(protobuf.SignOfferResponse proto,
                                                      String messageVersion) {
        return new SignOfferResponse(proto.getOfferId(),
                proto.getUid(),
                messageVersion,
                OfferPayload.fromProto(proto.getSignedOfferPayload()));
    }

    @Override
    public String toString() {
        return "SignOfferResponse {" +
                ",\n     arbitratorSignature='" + signedOfferPayload.getArbitratorSignature() +
                "\n} " + super.toString();
    }
}
