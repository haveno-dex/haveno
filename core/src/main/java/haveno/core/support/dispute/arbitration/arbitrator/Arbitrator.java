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

package haveno.core.support.dispute.arbitration.arbitrator;

import com.google.protobuf.ByteString;
import haveno.common.crypto.PubKeyRing;
import haveno.common.proto.ProtoUtil;
import haveno.common.util.CollectionUtils;
import haveno.core.support.dispute.agent.DisputeAgent;
import haveno.network.p2p.NodeAddress;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@EqualsAndHashCode(callSuper = true)
@Slf4j
@Getter
public final class Arbitrator extends DisputeAgent {

    public Arbitrator(NodeAddress nodeAddress,
                      PubKeyRing pubKeyRing,
                      List<String> languageCodes,
                      long registrationDate,
                      byte[] registrationPubKey,
                      String registrationSignature,
                      @Nullable String emailAddress,
                      @Nullable String info,
                      @Nullable Map<String, String> extraDataMap) {

        super(nodeAddress,
                pubKeyRing,
                languageCodes,
                registrationDate,
                registrationPubKey,
                registrationSignature,
                emailAddress,
                info,
                extraDataMap);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.StoragePayload toProtoMessage() {
        protobuf.Arbitrator.Builder builder = protobuf.Arbitrator.newBuilder()
                .setNodeAddress(nodeAddress.toProtoMessage())
                .setPubKeyRing(pubKeyRing.toProtoMessage())
                .addAllLanguageCodes(languageCodes)
                .setRegistrationDate(registrationDate)
                .setRegistrationPubKey(ByteString.copyFrom(registrationPubKey))
                .setRegistrationSignature(registrationSignature);
        Optional.ofNullable(emailAddress).ifPresent(builder::setEmailAddress);
        Optional.ofNullable(info).ifPresent(builder::setInfo);
        Optional.ofNullable(extraDataMap).ifPresent(builder::putAllExtraData);
        return protobuf.StoragePayload.newBuilder().setArbitrator(builder).build();
    }

    public static Arbitrator fromProto(protobuf.Arbitrator proto) {
        return new Arbitrator(NodeAddress.fromProto(proto.getNodeAddress()),
                PubKeyRing.fromProto(proto.getPubKeyRing()),
                new ArrayList<>(proto.getLanguageCodesList()),
                proto.getRegistrationDate(),
                proto.getRegistrationPubKey().toByteArray(),
                proto.getRegistrationSignature(),
                ProtoUtil.stringOrNullFromProto(proto.getEmailAddress()),
                ProtoUtil.stringOrNullFromProto(proto.getInfo()),
                CollectionUtils.isEmpty(proto.getExtraDataMap()) ? null : proto.getExtraDataMap());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public String toString() {
        return "Arbitrator{} " + super.toString();
    }
}
