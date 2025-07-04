package haveno.daemon.grpc;

import com.google.inject.Inject;
import com.google.protobuf.ByteString;

import io.grpc.Context;
import io.grpc.ServerInterceptor;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

import haveno.core.api.CoreApi;
import haveno.core.api.NetworkListener;
import haveno.core.filter.Filter;
import haveno.core.filter.PaymentAccountFilter;
import haveno.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import haveno.daemon.grpc.interceptor.GrpcCallRateMeter;

import static haveno.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import haveno.proto.grpc.NetworkMessage;
import static haveno.proto.grpc.NetworkGrpc.getRegisterNetworkListenerMethod;
import static haveno.proto.grpc.NetworkGrpc.getGetSeednodesMethod;
import static haveno.proto.grpc.NetworkGrpc.getGetOnlinePeersMethod;
import static haveno.proto.grpc.NetworkGrpc.getGetRegisteredArbitratorsMethod;
import haveno.proto.grpc.GetRegisteredArbitratorsReply;
import haveno.proto.grpc.GetRegisteredArbitratorsRequest;
import haveno.proto.grpc.GetOnlinePeersRequest;
import haveno.proto.grpc.GetSeednodesReply;
import haveno.proto.grpc.GetOnlinePeersReply;
import haveno.proto.grpc.GetSeednodesRequest;
import haveno.network.p2p.peers.peerexchange.Peer;
import haveno.proto.grpc.NetworkGrpc.NetworkImplBase;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.ArrayList;

import haveno.core.support.dispute.arbitration.arbitrator.Arbitrator;
import haveno.proto.grpc.GetNetworkFilterReply;
import haveno.proto.grpc.GetNetworkFilterRequest;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import protobuf.StoragePayload;

@Slf4j
class GrpcNetworkService extends NetworkImplBase {

    private final CoreApi coreApi;
    private final GrpcExceptionHandler exceptionHandler;

    @Inject
    public GrpcNetworkService(CoreApi coreApi, GrpcExceptionHandler exceptionHandler) {
        this.coreApi = coreApi;
        this.exceptionHandler = exceptionHandler;
    }

    //@Override
    //public void registerNetworkListener(RegisterNetworkListenerRequest request,
    //                                         StreamObserver<NetworkMessage> responseObserver) {
    //    Context ctx = Context.current().fork(); // context is independent for long-lived request
    //    ctx.run(() -> {
    //        try {
    //            coreApi.addNetworkListener(new GrpcNetworkListener(responseObserver));
    //            // No onNext / onCompleted, as the response observer should be kept open
    //        } catch (Throwable t) {
    //            exceptionHandler.handleException(log, t, responseObserver);
    //        }
    //    });
    //}

    @Override
    public void getOnlinePeers(GetOnlinePeersRequest request,
                            StreamObserver<GetOnlinePeersReply> responseObserver) {
        try {
            GetOnlinePeersReply.Builder replyBuilder = GetOnlinePeersReply.newBuilder();

            for (Peer peer : coreApi.getOnlinePeers()) {
                replyBuilder.addPeers(
                        haveno.proto.grpc.PeerInfo.newBuilder()
                                .setNodeAddress(peer.getNodeAddress().getFullAddress())
                                //.addAllCapabilities(peer.getCapabilities().stream().map(Integer::intValue).toList())
                                .build());
            }

            responseObserver.onNext(replyBuilder.build());
            responseObserver.onCompleted();
        } catch (Throwable t) {
            exceptionHandler.handleException(log, t, responseObserver);
        }
    }

    @Override
    public void getSeednodes(GetSeednodesRequest request,
                            StreamObserver<GetSeednodesReply> responseObserver) {
        try {
            GetSeednodesReply.Builder replyBuilder = GetSeednodesReply.newBuilder();

            for (Peer peer : coreApi.getOnlineSeedNodes()) {
                replyBuilder.addPeers(
                        haveno.proto.grpc.PeerInfo.newBuilder()
                                .setNodeAddress(peer.getNodeAddress().getFullAddress())
                                //.addAllCapabilities(peer.getCapabilities().stream().map(Integer::intValue).toList())
                                .build());
            }

            responseObserver.onNext(replyBuilder.build());
            responseObserver.onCompleted();
        } catch (Throwable t) {
            exceptionHandler.handleException(log, t, responseObserver);
        }
    }

    @Override
    public void getNetworkFilter(GetNetworkFilterRequest request,
                                StreamObserver<GetNetworkFilterReply> responseObserver) {
        try {
            haveno.core.filter.Filter modelFilter = coreApi.getFilter();

            protobuf.Filter.Builder grpcFilter = protobuf.Filter.newBuilder()
                .addAllNodeAddressesBannedFromTrading(modelFilter.getNodeAddressesBannedFromTrading())
                .addAllBannedOfferIds(modelFilter.getBannedOfferIds())
                .addAllArbitrators(modelFilter.getArbitrators().stream().map(a -> a.toString()).toList())
                .addAllSeedNodes(modelFilter.getSeedNodes().stream().map(n -> n.toString()).toList())
                .addAllBannedPaymentAccounts(
                    modelFilter.getBannedPaymentAccounts().stream()
                        .map(PaymentAccountFilter::toProtoMessage)
                        .toList())
                .setSignatureAsBase64(modelFilter.getSignatureAsBase64())
                .setOwnerPubKeyBytes(ByteString.copyFrom(modelFilter.getOwnerPubKeyBytes()))
                .putAllExtraData(modelFilter.getExtraDataMap())
                .addAllBannedCurrencies(modelFilter.getBannedCurrencies())
                .addAllBannedPaymentMethods(modelFilter.getBannedPaymentMethods())
                .addAllPriceRelayNodes(modelFilter.getPriceRelayNodes())
                .setPreventPublicXmrNetwork(modelFilter.isPreventPublicXmrNetwork())
                .addAllXmrNodes(modelFilter.getXmrNodes())
                .setDisableTradeBelowVersion(modelFilter.getDisableTradeBelowVersion())
                .addAllMediators(modelFilter.getMediators())
                .addAllRefundAgents(modelFilter.getRefundAgents())
                .addAllBannedSignerPubKeys(modelFilter.getBannedAccountWitnessSignerPubKeys())
                .addAllXmrFeeReceiverAddresses(modelFilter.getXmrFeeReceiverAddresses())
                .setCreationDate(modelFilter.getCreationDate())
                .setSignerPubKeyAsHex(modelFilter.getSignerPubKeyAsHex())
                .addAllBannedPrivilegedDevPubKeys(modelFilter.getBannedPrivilegedDevPubKeys())
                .setDisableAutoConf(modelFilter.isDisableAutoConf())
                .addAllBannedAutoConfExplorers(modelFilter.getBannedAutoConfExplorers())
                .addAllNodeAddressesBannedFromNetwork(modelFilter.getNodeAddressesBannedFromNetwork())
                .setDisableApi(modelFilter.isDisableApi())
                .setDisableMempoolValidation(modelFilter.isDisableMempoolValidation());

            GetNetworkFilterReply reply = GetNetworkFilterReply.newBuilder()
                .setFilter(grpcFilter)
                .build();

            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable t) {
            exceptionHandler.handleException(log, t, responseObserver);
        }
    }

//    @Override
//    public void setNetworkFilter() {
//
//    }

    @Value
    private static class GrpcNetworkListener implements NetworkListener {

        @NonNull
        StreamObserver<NetworkMessage> responseObserver;

        @Override
        public void onMessage(@NonNull NetworkMessage network_message) {
            if (!((ServerCallStreamObserver<NetworkMessage>) responseObserver).isCancelled()) {
                responseObserver.onNext(network_message);
            }
        }
    }

    final ServerInterceptor[] interceptors() {
        Optional<ServerInterceptor> rateMeteringInterceptor = rateMeteringInterceptor();
        return rateMeteringInterceptor.map(serverInterceptor ->
                new ServerInterceptor[]{serverInterceptor}).orElseGet(() -> new ServerInterceptor[0]);
    }

    final Optional<ServerInterceptor> rateMeteringInterceptor() {
        return getCustomRateMeteringInterceptor(coreApi.getConfig().appDataDir, this.getClass())
                .or(() -> Optional.of(CallRateMeteringInterceptor.valueOf(
                        new HashMap<>() {{
                            put(getRegisterNetworkListenerMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                        }}
                )));
    }
}
