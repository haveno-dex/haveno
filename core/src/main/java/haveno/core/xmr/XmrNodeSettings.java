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
package haveno.core.xmr;

import haveno.common.proto.persistable.PersistableEnvelope;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

@Slf4j
@Data
@AllArgsConstructor
public class XmrNodeSettings implements PersistableEnvelope {

    @Nullable
    String blockchainPath;
    @Nullable
    String bootstrapUrl;
    @Nullable
    List<String> startupFlags;

    public XmrNodeSettings() {
    }

    public static XmrNodeSettings fromProto(protobuf.XmrNodeSettings proto) {
        return new XmrNodeSettings(
                proto.getBlockchainPath(),
                proto.getBootstrapUrl(),
                proto.getStartupFlagsList());
    }

    @Override
    public protobuf.XmrNodeSettings toProtoMessage() {
        protobuf.XmrNodeSettings.Builder builder = protobuf.XmrNodeSettings.newBuilder();
        Optional.ofNullable(blockchainPath).ifPresent(e -> builder.setBlockchainPath(blockchainPath));
        Optional.ofNullable(bootstrapUrl).ifPresent(e -> builder.setBootstrapUrl(bootstrapUrl));
        Optional.ofNullable(startupFlags).ifPresent(e -> builder.addAllStartupFlags(startupFlags));
        return builder.build();
    }
}
