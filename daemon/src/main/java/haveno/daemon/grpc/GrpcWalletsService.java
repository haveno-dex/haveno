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

package haveno.daemon.grpc;

import com.google.inject.Inject;
import haveno.common.UserThread;
import haveno.common.config.Config;
import haveno.core.api.CoreApi;
import haveno.core.api.model.AddressBalanceInfo;
import static haveno.core.api.model.XmrTx.toXmrTx;
import haveno.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import haveno.daemon.grpc.interceptor.GrpcCallRateMeter;
import static haveno.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import haveno.proto.grpc.CreateXmrTxReply;
import haveno.proto.grpc.CreateXmrTxRequest;
import haveno.proto.grpc.GetAddressBalanceReply;
import haveno.proto.grpc.GetAddressBalanceRequest;
import haveno.proto.grpc.GetBalancesReply;
import haveno.proto.grpc.GetBalancesRequest;
import haveno.proto.grpc.GetFundingAddressesReply;
import haveno.proto.grpc.GetFundingAddressesRequest;
import haveno.proto.grpc.GetXmrNewSubaddressReply;
import haveno.proto.grpc.GetXmrNewSubaddressRequest;
import haveno.proto.grpc.GetXmrPrimaryAddressReply;
import haveno.proto.grpc.GetXmrPrimaryAddressRequest;
import haveno.proto.grpc.GetXmrSeedReply;
import haveno.proto.grpc.GetXmrSeedRequest;
import haveno.proto.grpc.GetXmrTxsReply;
import haveno.proto.grpc.GetXmrTxsRequest;
import haveno.proto.grpc.LockWalletReply;
import haveno.proto.grpc.LockWalletRequest;
import haveno.proto.grpc.RelayXmrTxReply;
import haveno.proto.grpc.RelayXmrTxRequest;
import haveno.proto.grpc.RemoveWalletPasswordReply;
import haveno.proto.grpc.RemoveWalletPasswordRequest;
import haveno.proto.grpc.SetWalletPasswordReply;
import haveno.proto.grpc.SetWalletPasswordRequest;
import haveno.proto.grpc.UnlockWalletReply;
import haveno.proto.grpc.UnlockWalletRequest;
import haveno.proto.grpc.WalletsGrpc.WalletsImplBase;
import static haveno.proto.grpc.WalletsGrpc.getGetAddressBalanceMethod;
import static haveno.proto.grpc.WalletsGrpc.getGetBalancesMethod;
import static haveno.proto.grpc.WalletsGrpc.getGetFundingAddressesMethod;
import static haveno.proto.grpc.WalletsGrpc.getLockWalletMethod;
import static haveno.proto.grpc.WalletsGrpc.getRemoveWalletPasswordMethod;
import static haveno.proto.grpc.WalletsGrpc.getSetWalletPasswordMethod;
import static haveno.proto.grpc.WalletsGrpc.getUnlockWalletMethod;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import static java.util.concurrent.TimeUnit.SECONDS;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroTxWallet;

@Slf4j
class GrpcWalletsService extends WalletsImplBase {

    private final CoreApi coreApi;
    private final GrpcExceptionHandler exceptionHandler;

    @Inject
    public GrpcWalletsService(CoreApi coreApi, GrpcExceptionHandler exceptionHandler) {
        this.coreApi = coreApi;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void getBalances(GetBalancesRequest req, StreamObserver<GetBalancesReply> responseObserver) {
        UserThread.execute(() -> { // TODO (woodser): Balances.updateBalances() runs on UserThread for JFX components, so call from user thread, else the properties may not be updated. remove JFX properties or push delay into CoreWalletsService.getXmrBalances()?
            try {
                var balances = coreApi.getBalances(req.getCurrencyCode());
                var reply = GetBalancesReply.newBuilder()
                        .setBalances(balances.toProtoMessage())
                        .build();
                responseObserver.onNext(reply);
                responseObserver.onCompleted();
            } catch (Throwable cause) {
                exceptionHandler.handleException(log, cause, responseObserver);
            }
        });
    }

    @Override
    public void getXmrSeed(GetXmrSeedRequest req,
                                    StreamObserver<GetXmrSeedReply> responseObserver) {
        try {
            var reply = GetXmrSeedReply.newBuilder()
                    .setSeed(coreApi.getXmrSeed())
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void getXmrPrimaryAddress(GetXmrPrimaryAddressRequest req,
                                     StreamObserver<GetXmrPrimaryAddressReply> responseObserver) {
        try {
            var reply = GetXmrPrimaryAddressReply.newBuilder()
                    .setPrimaryAddress(coreApi.getXmrPrimaryAddress())
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void getXmrNewSubaddress(GetXmrNewSubaddressRequest req,
                                    StreamObserver<GetXmrNewSubaddressReply> responseObserver) {
        try {
            String subaddress = coreApi.getXmrNewSubaddress();
            var reply = GetXmrNewSubaddressReply.newBuilder()
                    .setSubaddress(subaddress)
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void getXmrTxs(GetXmrTxsRequest req, StreamObserver<GetXmrTxsReply> responseObserver) {
        try {
            List<MoneroTxWallet> xmrTxs = coreApi.getXmrTxs();
            var reply = GetXmrTxsReply.newBuilder()
                    .addAllTxs(xmrTxs.stream()
                            .map(s -> toXmrTx(s).toProtoMessage())
                            .collect(Collectors.toList()))
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void createXmrTx(CreateXmrTxRequest req,
                            StreamObserver<CreateXmrTxReply> responseObserver) {
        try {
            MoneroTxWallet tx = coreApi.createXmrTx(
                    req.getDestinationsList()
                    .stream()
                    .map(s -> new MoneroDestination(s.getAddress(), new BigInteger(s.getAmount())))
                    .collect(Collectors.toList()));
            log.info("Successfully created XMR tx: hash {}", tx.getHash());
            var reply = CreateXmrTxReply.newBuilder()
                    .setTx(toXmrTx(tx).toProtoMessage())
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void relayXmrTx(RelayXmrTxRequest req,
                            StreamObserver<RelayXmrTxReply> responseObserver) {
        try {
            String txHash = coreApi.relayXmrTx(req.getMetadata());
            var reply = RelayXmrTxReply.newBuilder()
                    .setHash(txHash)
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void getAddressBalance(GetAddressBalanceRequest req,
                                  StreamObserver<GetAddressBalanceReply> responseObserver) {
        try {
            AddressBalanceInfo balanceInfo = coreApi.getAddressBalanceInfo(req.getAddress());
            var reply = GetAddressBalanceReply.newBuilder()
                    .setAddressBalanceInfo(balanceInfo.toProtoMessage()).build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void getFundingAddresses(GetFundingAddressesRequest req,
                                    StreamObserver<GetFundingAddressesReply> responseObserver) {
        try {
            List<AddressBalanceInfo> balanceInfo = coreApi.getFundingAddresses();
            var reply = GetFundingAddressesReply.newBuilder()
                    .addAllAddressBalanceInfo(
                            balanceInfo.stream()
                                    .map(AddressBalanceInfo::toProtoMessage)
                                    .collect(Collectors.toList()))
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void setWalletPassword(SetWalletPasswordRequest req,
                                  StreamObserver<SetWalletPasswordReply> responseObserver) {
        try {
            coreApi.setWalletPassword(req.getPassword(), req.getNewPassword());
            var reply = SetWalletPasswordReply.newBuilder().build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void removeWalletPassword(RemoveWalletPasswordRequest req,
                                     StreamObserver<RemoveWalletPasswordReply> responseObserver) {
        try {
            coreApi.removeWalletPassword(req.getPassword());
            var reply = RemoveWalletPasswordReply.newBuilder().build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void lockWallet(LockWalletRequest req,
                           StreamObserver<LockWalletReply> responseObserver) {
        try {
            coreApi.lockWallet();
            var reply = LockWalletReply.newBuilder().build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void unlockWallet(UnlockWalletRequest req,
                             StreamObserver<UnlockWalletReply> responseObserver) {
        try {
            coreApi.unlockWallet(req.getPassword(), req.getTimeout());
            var reply = UnlockWalletReply.newBuilder().build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
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
                            put(getGetBalancesMethod().getFullMethodName(), new GrpcCallRateMeter(Config.baseCurrencyNetwork().isTestnet() ? 100 : 1, SECONDS)); // TODO: why do tests make so many calls to get balances?
                            put(getGetAddressBalanceMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));
                            put(getGetFundingAddressesMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));

                            // Trying to set or remove a wallet password several times before the 1st attempt has time to
                            // persist the change to disk may corrupt the wallet, so allow only 1 attempt per 5 seconds.
                            put(getSetWalletPasswordMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS, 5));
                            put(getRemoveWalletPasswordMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS, 5));

                            put(getLockWalletMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));
                            put(getUnlockWalletMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));
                        }}
                )));
    }
}
