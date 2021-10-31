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

package haveno.core.support.dispute.arbitration;

import haveno.core.proto.CoreProtoResolver;
import haveno.core.support.SupportType;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeList;

import haveno.common.proto.ProtoUtil;

import com.google.protobuf.Message;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
@ToString
/*
 * Holds a List of arbitration dispute objects.
 *
 * Calls to the List are delegated because this class intercepts the add/remove calls so changes
 * can be saved to disc.
 */
public final class ArbitrationDisputeList extends DisputeList<Dispute> {

    ArbitrationDisputeList() {
        super();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected ArbitrationDisputeList(Collection<Dispute> collection) {
        super(collection);
    }

    @Override
    public Message toProtoMessage() {

        forEach(dispute -> checkArgument(dispute.getSupportType().equals(SupportType.ARBITRATION), "Support type has to be ARBITRATION"));

        return protobuf.PersistableEnvelope.newBuilder().setArbitrationDisputeList(protobuf.ArbitrationDisputeList.newBuilder()
                .addAllDispute(ProtoUtil.collectionToProto(getList(), protobuf.Dispute.class))).build();
    }

    public static ArbitrationDisputeList fromProto(protobuf.ArbitrationDisputeList proto,
                                                   CoreProtoResolver coreProtoResolver) {
        List<Dispute> list = proto.getDisputeList().stream()
                .map(disputeProto -> Dispute.fromProto(disputeProto, coreProtoResolver))
                .filter(e -> e.getSupportType().equals(SupportType.ARBITRATION))
                .collect(Collectors.toList());

        return new ArbitrationDisputeList(list);
    }
}
