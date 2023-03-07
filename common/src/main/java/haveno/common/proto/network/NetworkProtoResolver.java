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

package haveno.common.proto.network;

import haveno.common.proto.ProtoResolver;
import haveno.common.proto.ProtobufferException;
import java.time.Clock;


public interface NetworkProtoResolver extends ProtoResolver {
    NetworkEnvelope fromProto(protobuf.NetworkEnvelope proto) throws ProtobufferException;

    NetworkPayload fromProto(protobuf.StoragePayload proto);

    NetworkPayload fromProto(protobuf.StorageEntryWrapper proto);

    Clock getClock();
}
