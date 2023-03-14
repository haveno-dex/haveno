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

package haveno.common.proto.persistable;

import com.google.protobuf.Message;
import haveno.common.util.CollectionUtils;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@EqualsAndHashCode
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class NavigationPath implements PersistableEnvelope {
    private List<String> path = List.of();

    @Override
    public Message toProtoMessage() {
        final protobuf.NavigationPath.Builder builder = protobuf.NavigationPath.newBuilder();
        if (!CollectionUtils.isEmpty(path)) builder.addAllPath(path);
        return protobuf.PersistableEnvelope.newBuilder().setNavigationPath(builder).build();
    }

    public static NavigationPath fromProto(protobuf.NavigationPath proto) {
        return new NavigationPath(List.copyOf(proto.getPathList()));
    }
}
