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

package haveno.core.offer;

import com.google.protobuf.Message;
import haveno.common.proto.ProtoUtil;
import haveno.common.proto.persistable.PersistableListAsObservable;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public final class SignedOfferList extends PersistableListAsObservable<SignedOffer> {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public SignedOfferList() {
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected SignedOfferList(Collection<SignedOffer> collection) {
        super(collection);
    }

    @Override
    public Message toProtoMessage() {
        synchronized (getList()) {
            return protobuf.PersistableEnvelope.newBuilder()
                    .setSignedOfferList(protobuf.SignedOfferList.newBuilder()
                            .addAllSignedOffer(ProtoUtil.collectionToProto(getList(), protobuf.SignedOffer.class)))
                    .build();
        }
    }

    public static SignedOfferList fromProto(protobuf.SignedOfferList proto) {
        List<SignedOffer> list = proto.getSignedOfferList().stream()
                .map(signedOffer -> {
                    return SignedOffer.fromProto(signedOffer);
                })
                .collect(Collectors.toList());

        return new SignedOfferList(list);
    }

    @Override
    public String toString() {
        return "SignedOfferList{" +
                ",\n     list=" + getList() +
                "\n}";
    }
}
