package haveno.cli.request;

import haveno.cli.GrpcStubs;
import haveno.proto.grpc.GetXmrNodeSettingsReply;
import haveno.proto.grpc.GetXmrNodeSettingsRequest;
import haveno.proto.grpc.IsXmrNodeOnlineReply;
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
        IsXmrNodeOnlineRequest request = IsXmrNodeOnlineRequest.newBuilder().build();
        IsXmrNodeOnlineReply reply = grpcStubs.xmrNodeService.isXmrNodeOnline(request);
        return reply.getIsRunning();
    }

    public XmrNodeSettings getXmrNodeSettings() {
        GetXmrNodeSettingsRequest request = GetXmrNodeSettingsRequest.newBuilder().build();
        GetXmrNodeSettingsReply reply = grpcStubs.xmrNodeService.getXmrNodeSettings(request);
        return reply.getSettings();
    }

    public void startXmrNode(XmrNodeSettings settings) {
        StartXmrNodeRequest request = StartXmrNodeRequest.newBuilder()
                .setSettings(settings)
                .build();
        grpcStubs.xmrNodeService.startXmrNode(request);
    }

    public void stopXmrNode() {
        StopXmrNodeRequest request = StopXmrNodeRequest.newBuilder().build();
        grpcStubs.xmrNodeService.stopXmrNode(request);
    }
}