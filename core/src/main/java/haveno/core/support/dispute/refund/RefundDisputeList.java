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

package haveno.core.support.dispute.refund;

import com.google.protobuf.Message;
import haveno.common.proto.ProtoUtil;
import haveno.core.proto.CoreProtoResolver;
import haveno.core.support.SupportType;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeList;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
@ToString
/*
 * Holds a List of refund dispute objects.
 *
 * Calls to the List are delegated because this class intercepts the add/remove calls so changes
 * can be saved to disc.
 */
public final class RefundDisputeList extends DisputeList<Dispute> {

    RefundDisputeList() {
        super();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected RefundDisputeList(Collection<Dispute> collection) {
        super(collection);
    }

    @Override
    public Message toProtoMessage() {
        forEach(dispute -> checkArgument(dispute.getSupportType().equals(SupportType.REFUND), "Support type has to be REFUND"));
        synchronized (getList()) {
            return protobuf.PersistableEnvelope.newBuilder().setRefundDisputeList(protobuf.RefundDisputeList.newBuilder()
                    .addAllDispute(ProtoUtil.collectionToProto(getList(), protobuf.Dispute.class))).build();
        }
    }

    public static RefundDisputeList fromProto(protobuf.RefundDisputeList proto,
                                              CoreProtoResolver coreProtoResolver) {
        List<Dispute> list = proto.getDisputeList().stream()
                .map(disputeProto -> Dispute.fromProto(disputeProto, coreProtoResolver))
                .filter(e -> e.getSupportType().equals(SupportType.REFUND))
                .collect(Collectors.toList());
        return new RefundDisputeList(list);
    }
}
