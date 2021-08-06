package bisq.core.offer;

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

import bisq.common.proto.ProtoUtil;
import bisq.common.proto.persistable.PersistableListAsObservable;

import com.google.protobuf.Message;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

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
        return protobuf.PersistableEnvelope.newBuilder()
                .setSignedOfferList(protobuf.SignedOfferList.newBuilder()
                        .addAllSignedOffer(ProtoUtil.collectionToProto(getList(), protobuf.SignedOffer.class)))
                .build();
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
