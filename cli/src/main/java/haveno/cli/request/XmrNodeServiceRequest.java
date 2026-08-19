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

package haveno.cli.request;

import haveno.cli.GrpcStubs;
import haveno.proto.grpc.GetXmrNodeSettingsRequest;
import haveno.proto.grpc.IsXmrNodeOnlineRequest;
import haveno.proto.grpc.StartXmrNodeRequest;
import haveno.proto.grpc.StopXmrNodeRequest;
import protobuf.XmrNodeSettings;

public class XmrNodeServiceRequest {

    private final GrpcStubs grpcStubs;

    public XmrNodeServiceRequest(GrpcStubs grpcStubs) {
        this.grpcStubs = grpcStubs;
    }

    public boolean isXmrNodeOnline() {
        var request = IsXmrNodeOnlineRequest.newBuilder().build();
        return grpcStubs.xmrNodeService.isXmrNodeOnline(request).getIsRunning();
    }

    public XmrNodeSettings getXmrNodeSettings() {
        var request = GetXmrNodeSettingsRequest.newBuilder().build();
        return grpcStubs.xmrNodeService.getXmrNodeSettings(request).getSettings();
    }

    public void startXmrNode(XmrNodeSettings settings) {
        var request = StartXmrNodeRequest.newBuilder()
                .setSettings(settings)
                .build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.xmrNodeService.startXmrNode(request);
    }

    public void stopXmrNode() {
        var request = StopXmrNodeRequest.newBuilder().build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.xmrNodeService.stopXmrNode(request);
    }
}
