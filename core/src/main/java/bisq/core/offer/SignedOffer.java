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

package bisq.core.offer;

import bisq.common.proto.persistable.PersistablePayload;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@EqualsAndHashCode
@Slf4j
public final class SignedOffer implements PersistablePayload {
    
    @Getter
    private final String offerId;
    @Getter
    private final String reserveTxHash;
    @Getter
    private final String reserveTxHex;
    @Getter
    private final String arbitratorSignature;
    
    public SignedOffer(String offerId, String reserveTxHash, String reserveTxHex, String arbitratorSignature) {
        this.offerId = offerId;
        this.reserveTxHash = reserveTxHash;
        this.reserveTxHex = reserveTxHex;
        this.arbitratorSignature = arbitratorSignature;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.SignedOffer toProtoMessage() {
        protobuf.SignedOffer.Builder builder = protobuf.SignedOffer.newBuilder()
                .setOfferId(offerId)
                .setReserveTxHash(reserveTxHash)
                .setReserveTxHex(reserveTxHex)
                .setArbitratorSignature(arbitratorSignature);
        
        return builder.build();
    }

    public static SignedOffer fromProto(protobuf.SignedOffer proto) {
        return new SignedOffer(proto.getOfferId(), proto.getReserveTxHash(), proto.getReserveTxHex(), proto.getArbitratorSignature());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public String toString() {
        return "SignedOffer{" +
                ",\n     offerId=" + offerId +
                ",\n     reserveTxHash=" + reserveTxHash +
                ",\n     reserveTxHex=" + reserveTxHex +
                ",\n     arbitratorSignature=" + arbitratorSignature +
                "\n}";
    }
}

