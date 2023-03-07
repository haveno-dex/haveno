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

import haveno.common.crypto.PubKeyRing;
import haveno.core.offer.OfferPayload;
import haveno.network.p2p.DirectMessage;
import haveno.network.p2p.NodeAddress;
import java.util.ArrayList;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Value;

@EqualsAndHashCode(callSuper = true)
@Value
public final class SignOfferRequest extends OfferMessage implements DirectMessage {
    private final NodeAddress senderNodeAddress;
    private final PubKeyRing pubKeyRing;
    private final String senderAccountId;
    private final OfferPayload offerPayload;
    private final long currentDate;
    private final String reserveTxHash;
    private final String reserveTxHex;
    private final String reserveTxKey;
    private final List<String> reserveTxKeyImages;
    private final String payoutAddress;

    public SignOfferRequest(String offerId,
                                     NodeAddress senderNodeAddress,
                                     PubKeyRing pubKeyRing,
                                     String senderAccountId,
                                     OfferPayload offerPayload,
                                     String uid,
                                     String messageVersion,
                                     long currentDate,
                                     String reserveTxHash,
                                     String reserveTxHex,
                                     String reserveTxKey,
                                     List<String> reserveTxKeyImages,
                                     String payoutAddress) {
        super(messageVersion, offerId, uid);
        this.senderNodeAddress = senderNodeAddress;
        this.pubKeyRing = pubKeyRing;
        this.senderAccountId = senderAccountId;
        this.offerPayload = offerPayload;
        this.currentDate = currentDate;
        this.reserveTxHash = reserveTxHash;
        this.reserveTxHex = reserveTxHex;
        this.reserveTxKey = reserveTxKey;
        this.reserveTxKeyImages = reserveTxKeyImages;
        this.payoutAddress = payoutAddress;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.SignOfferRequest.Builder builder = protobuf.SignOfferRequest.newBuilder()
                .setOfferId(offerId)
                .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                .setPubKeyRing(pubKeyRing.toProtoMessage())
                .setSenderAccountId(senderAccountId)
                .setOfferPayload(offerPayload.toProtoMessage().getOfferPayload())
                .setUid(uid)
                .setCurrentDate(currentDate)
                .setReserveTxHash(reserveTxHash)
                .setReserveTxHex(reserveTxHex)
                .setReserveTxKey(reserveTxKey)
                .addAllReserveTxKeyImages(reserveTxKeyImages)
                .setPayoutAddress(payoutAddress);

        return getNetworkEnvelopeBuilder().setSignOfferRequest(builder).build();
    }

    public static SignOfferRequest fromProto(protobuf.SignOfferRequest proto,
                                                      String messageVersion) {
        return new SignOfferRequest(proto.getOfferId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                PubKeyRing.fromProto(proto.getPubKeyRing()),
                proto.getSenderAccountId(),
                OfferPayload.fromProto(proto.getOfferPayload()),
                proto.getUid(),
                messageVersion,
                proto.getCurrentDate(),
                proto.getReserveTxHash(),
                proto.getReserveTxHex(),
                proto.getReserveTxKey(),
                new ArrayList<String>(proto.getReserveTxKeyImagesList()),
                proto.getPayoutAddress());
    }

    @Override
    public String toString() {
        return "SignOfferRequest {" +
                "\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     pubKeyRing=" + pubKeyRing +
                ",\n     currentDate=" + currentDate +
                ",\n     reserveTxHash='" + reserveTxHash +
                ",\n     reserveTxHex='" + reserveTxHex +
                ",\n     reserveTxKey='" + reserveTxKey +
                ",\n     reserveTxKeyImages='" + reserveTxKeyImages +
                ",\n     payoutAddress='" + payoutAddress +
                "\n} " + super.toString();
    }
}
