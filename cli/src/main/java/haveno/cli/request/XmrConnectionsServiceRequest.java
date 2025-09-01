package haveno.cli.request;

import haveno.cli.GrpcStubs;
import haveno.proto.grpc.AddConnectionRequest;
import haveno.proto.grpc.CheckConnectionReply;
import haveno.proto.grpc.CheckConnectionRequest;
import haveno.proto.grpc.CheckConnectionsReply;
import haveno.proto.grpc.CheckConnectionsRequest;
import haveno.proto.grpc.GetAutoSwitchReply;
import haveno.proto.grpc.GetAutoSwitchRequest;
import haveno.proto.grpc.GetBestConnectionReply;
import haveno.proto.grpc.GetBestConnectionRequest;
import haveno.proto.grpc.GetConnectionReply;
import haveno.proto.grpc.GetConnectionRequest;
import haveno.proto.grpc.GetConnectionsReply;
import haveno.proto.grpc.GetConnectionsRequest;
import haveno.proto.grpc.RemoveConnectionRequest;
import haveno.proto.grpc.SetAutoSwitchRequest;
import haveno.proto.grpc.SetConnectionRequest;
import haveno.proto.grpc.StartCheckingConnectionRequest;
import haveno.proto.grpc.StopCheckingConnectionRequest;
import haveno.proto.grpc.UrlConnection;

import java.util.List;

public class XmrConnectionsServiceRequest {

    private final GrpcStubs grpcStubs;

    public XmrConnectionsServiceRequest(GrpcStubs grpcStubs) {
        this.grpcStubs = grpcStubs;
    }

    public void addConnection(UrlConnection connection) {
        AddConnectionRequest request = AddConnectionRequest.newBuilder()
                .setConnection(connection)
                .build();
        grpcStubs.xmrConnectionsService.addConnection(request);
    }

    public void removeConnection(String url) {
        RemoveConnectionRequest request = RemoveConnectionRequest.newBuilder()
                .setUrl(url)
                .build();
        grpcStubs.xmrConnectionsService.removeConnection(request);
    }

    public UrlConnection getConnection() {
        GetConnectionRequest request = GetConnectionRequest.newBuilder().build();
        GetConnectionReply reply = grpcStubs.xmrConnectionsService.getConnection(request);
        return reply.getConnection();
    }

    public List<UrlConnection> getConnections() {
        GetConnectionsRequest request = GetConnectionsRequest.newBuilder().build();
        GetConnectionsReply reply = grpcStubs.xmrConnectionsService.getConnections(request);
        return reply.getConnectionsList();
    }

    public void setConnection(String url, UrlConnection connection) {
        SetConnectionRequest request = SetConnectionRequest.newBuilder()
                .setUrl(url)
                .setConnection(connection)
                .build();
        grpcStubs.xmrConnectionsService.setConnection(request);
    }

    public UrlConnection checkConnection() {
        CheckConnectionRequest request = CheckConnectionRequest.newBuilder().build();
        CheckConnectionReply reply = grpcStubs.xmrConnectionsService.checkConnection(request);
        return reply.getConnection();
    }

    public List<UrlConnection> checkConnections() {
        CheckConnectionsRequest request = CheckConnectionsRequest.newBuilder().build();
        CheckConnectionsReply reply = grpcStubs.xmrConnectionsService.checkConnections(request);
        return reply.getConnectionsList();
    }

    public void startCheckingConnection(int refreshPeriod) {
        StartCheckingConnectionRequest request = StartCheckingConnectionRequest.newBuilder()
                .setRefreshPeriod(refreshPeriod)
                .build();
        grpcStubs.xmrConnectionsService.startCheckingConnection(request);
    }

    public void stopCheckingConnection() {
        StopCheckingConnectionRequest request = StopCheckingConnectionRequest.newBuilder().build();
        grpcStubs.xmrConnectionsService.stopCheckingConnection(request);
    }

    public UrlConnection getBestConnection() {
        GetBestConnectionRequest request = GetBestConnectionRequest.newBuilder().build();
        GetBestConnectionReply reply = grpcStubs.xmrConnectionsService.getBestConnection(request);
        return reply.getConnection();
    }

    public void setAutoSwitch(boolean autoSwitch) {
        SetAutoSwitchRequest request = SetAutoSwitchRequest.newBuilder()
                .setAutoSwitch(autoSwitch)
                .build();
        grpcStubs.xmrConnectionsService.setAutoSwitch(request);
    }

    public boolean getAutoSwitch() {
        GetAutoSwitchRequest request = GetAutoSwitchRequest.newBuilder().build();
        GetAutoSwitchReply reply = grpcStubs.xmrConnectionsService.getAutoSwitch(request);
        return reply.getAutoSwitch();
    }
}