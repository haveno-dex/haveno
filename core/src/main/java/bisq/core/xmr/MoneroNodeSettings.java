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
package bisq.core.xmr;

import bisq.common.proto.persistable.PersistableEnvelope;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
@AllArgsConstructor
public class MoneroNodeSettings implements PersistableEnvelope {

    String blockchainPath;
    String bootstrapUrl;
    List<String> startupFlags;

    public static MoneroNodeSettings fromProto(protobuf.MoneroNodeSettings proto) {
        return new MoneroNodeSettings(
                proto.getBlockchainPath(),
                proto.getBootstrapUrl(),
                proto.getStartupFlagsList());
    }

    @Override
    public protobuf.MoneroNodeSettings toProtoMessage() {
        return protobuf.MoneroNodeSettings.newBuilder()
                .setBlockchainPath(blockchainPath)
                .setBootstrapUrl(bootstrapUrl)
                .addAllStartupFlags(startupFlags).build();
    }
}
