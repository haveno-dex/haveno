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

package haveno.network.p2p.peers.getdata.messages;

import haveno.common.app.Capabilities;
import haveno.common.app.Version;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.common.proto.network.NetworkProtoResolver;
import haveno.common.util.Utilities;
import haveno.network.p2p.ExtendedDataSizePermission;
import haveno.network.p2p.InitialDataRequest;
import haveno.network.p2p.InitialDataResponse;
import haveno.network.p2p.SupportedCapabilitiesMessage;
import haveno.network.p2p.storage.payload.PersistableNetworkPayload;
import haveno.network.p2p.storage.payload.ProtectedMailboxStorageEntry;
import haveno.network.p2p.storage.payload.ProtectedStorageEntry;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@Value
public final class GetDataResponse extends NetworkEnvelope implements SupportedCapabilitiesMessage,
        ExtendedDataSizePermission, InitialDataResponse {
    // Set of ProtectedStorageEntry objects
    private final Set<ProtectedStorageEntry> dataSet;

    // Set of PersistableNetworkPayload objects
    // We added that in v 0.6 and the fromProto code will create an empty HashSet if it doesn't exist
    private final Set<PersistableNetworkPayload> persistableNetworkPayloadSet;

    private final int requestNonce;
    private final boolean isGetUpdatedDataResponse;
    private final Capabilities supportedCapabilities;

    public GetDataResponse(@NotNull Set<ProtectedStorageEntry> dataSet,
                           @NotNull Set<PersistableNetworkPayload> persistableNetworkPayloadSet,
                           int requestNonce,
                           boolean isGetUpdatedDataResponse) {
        this(dataSet,
                persistableNetworkPayloadSet,
                requestNonce,
                isGetUpdatedDataResponse,
                Capabilities.app,
                Version.getP2PMessageVersion());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private GetDataResponse(@NotNull Set<ProtectedStorageEntry> dataSet,
                            @NotNull Set<PersistableNetworkPayload> persistableNetworkPayloadSet,
                            int requestNonce,
                            boolean isGetUpdatedDataResponse,
                            @NotNull Capabilities supportedCapabilities,
                            String messageVersion) {
        super(messageVersion);

        this.dataSet = dataSet;
        this.persistableNetworkPayloadSet = persistableNetworkPayloadSet;
        this.requestNonce = requestNonce;
        this.isGetUpdatedDataResponse = isGetUpdatedDataResponse;
        this.supportedCapabilities = supportedCapabilities;
    }

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        final protobuf.GetDataResponse.Builder builder = protobuf.GetDataResponse.newBuilder()
                .addAllDataSet(dataSet.stream()
                        .map(protectedStorageEntry -> protectedStorageEntry instanceof ProtectedMailboxStorageEntry ?
                                protobuf.StorageEntryWrapper.newBuilder()
                                        .setProtectedMailboxStorageEntry((protobuf.ProtectedMailboxStorageEntry) protectedStorageEntry.toProtoMessage())
                                        .build()
                                :
                                protobuf.StorageEntryWrapper.newBuilder()
                                        .setProtectedStorageEntry((protobuf.ProtectedStorageEntry) protectedStorageEntry.toProtoMessage())
                                        .build())
                        .collect(Collectors.toList()))
                .addAllPersistableNetworkPayloadItems(persistableNetworkPayloadSet.stream()
                        .map(PersistableNetworkPayload::toProtoMessage)
                        .collect(Collectors.toList()))
                .setRequestNonce(requestNonce)
                .setIsGetUpdatedDataResponse(isGetUpdatedDataResponse)
                .addAllSupportedCapabilities(Capabilities.toIntList(supportedCapabilities));

        protobuf.NetworkEnvelope proto = getNetworkEnvelopeBuilder()
                .setGetDataResponse(builder)
                .build();
        log.info("Sending a GetDataResponse with {}", Utilities.readableFileSize(proto.getSerializedSize()));
        return proto;
    }

    public static GetDataResponse fromProto(protobuf.GetDataResponse proto,
                                            NetworkProtoResolver resolver,
                                            String messageVersion) {
        log.info("Received a GetDataResponse with {}", Utilities.readableFileSize(proto.getSerializedSize()));
        Set<ProtectedStorageEntry> dataSet = proto.getDataSetList().stream()
                .map(entry -> (ProtectedStorageEntry) resolver.fromProto(entry)).collect(Collectors.toSet());
        Set<PersistableNetworkPayload> persistableNetworkPayloadSet = proto.getPersistableNetworkPayloadItemsList().stream()
                .map(e -> (PersistableNetworkPayload) resolver.fromProto(e)).collect(Collectors.toSet());
        return new GetDataResponse(dataSet,
                persistableNetworkPayloadSet,
                proto.getRequestNonce(),
                proto.getIsGetUpdatedDataResponse(),
                Capabilities.fromIntList(proto.getSupportedCapabilitiesList()),
                messageVersion);
    }

    @Override
    public Class<? extends InitialDataRequest> associatedRequest() {
        return isGetUpdatedDataResponse ? GetUpdatedDataRequest.class : PreliminaryGetDataRequest.class;
    }
}
