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
import haveno.proto.grpc.AddConnectionRequest;
import haveno.proto.grpc.CheckConnectionRequest;
import haveno.proto.grpc.GetAutoSwitchRequest;
import haveno.proto.grpc.GetBestConnectionRequest;
import haveno.proto.grpc.GetConnectionRequest;
import haveno.proto.grpc.GetConnectionsRequest;
import haveno.proto.grpc.RemoveConnectionRequest;
import haveno.proto.grpc.SetAutoSwitchRequest;
import haveno.proto.grpc.SetConnectionRequest;
import haveno.proto.grpc.UrlConnection;

import java.util.List;

public class XmrConnectionsServiceRequest {

    private final GrpcStubs grpcStubs;

    public XmrConnectionsServiceRequest(GrpcStubs grpcStubs) {
        this.grpcStubs = grpcStubs;
    }

    public void addConnection(UrlConnection connection) {
        var request = AddConnectionRequest.newBuilder()
                .setConnection(connection)
                .build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.xmrConnectionsService.addConnection(request);
    }

    public void removeConnection(String url) {
        var request = RemoveConnectionRequest.newBuilder()
                .setUrl(url)
                .build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.xmrConnectionsService.removeConnection(request);
    }

    public UrlConnection getConnection() {
        var request = GetConnectionRequest.newBuilder().build();
        return grpcStubs.xmrConnectionsService.getConnection(request).getConnection();
    }

    public List<UrlConnection> getConnections() {
        var request = GetConnectionsRequest.newBuilder().build();
        return grpcStubs.xmrConnectionsService.getConnections(request).getConnectionsList();
    }

    public void setConnection(String url) {
        var request = SetConnectionRequest.newBuilder()
                .setUrl(url)
                .build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.xmrConnectionsService.setConnection(request);
    }

    public UrlConnection checkConnection() {
        var request = CheckConnectionRequest.newBuilder().build();
        return grpcStubs.xmrConnectionsService.checkConnection(request).getConnection();
    }

    public UrlConnection getBestConnection() {
        var request = GetBestConnectionRequest.newBuilder().build();
        return grpcStubs.xmrConnectionsService.getBestConnection(request).getConnection();
    }

    public void setAutoSwitch(boolean autoSwitch) {
        var request = SetAutoSwitchRequest.newBuilder()
                .setAutoSwitch(autoSwitch)
                .build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.xmrConnectionsService.setAutoSwitch(request);
    }

    public boolean getAutoSwitch() {
        var request = GetAutoSwitchRequest.newBuilder().build();
        return grpcStubs.xmrConnectionsService.getAutoSwitch(request).getAutoSwitch();
    }
}
