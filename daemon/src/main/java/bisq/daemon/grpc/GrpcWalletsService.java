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

import haveno.core.api.CoreApi;
import haveno.core.api.model.AddressBalanceInfo;
import haveno.core.api.model.TxFeeRateInfo;
import haveno.core.btc.exceptions.TxBroadcastException;
import haveno.core.btc.wallet.TxBroadcaster;

import haveno.proto.grpc.GetAddressBalanceReply;
import haveno.proto.grpc.GetAddressBalanceRequest;
import haveno.proto.grpc.GetBalancesReply;
import haveno.proto.grpc.GetBalancesRequest;
import haveno.proto.grpc.GetFundingAddressesReply;
import haveno.proto.grpc.GetFundingAddressesRequest;
import haveno.proto.grpc.GetNewDepositSubaddressRequest;
import haveno.proto.grpc.GetNewDepositSubaddressReply;
import haveno.proto.grpc.GetTransactionReply;
import haveno.proto.grpc.GetTransactionRequest;
import haveno.proto.grpc.GetTxFeeRateReply;
import haveno.proto.grpc.GetTxFeeRateRequest;
import haveno.proto.grpc.LockWalletReply;
import haveno.proto.grpc.LockWalletRequest;
import haveno.proto.grpc.RemoveWalletPasswordReply;
import haveno.proto.grpc.RemoveWalletPasswordRequest;
import haveno.proto.grpc.SendBtcReply;
import haveno.proto.grpc.SendBtcRequest;
import haveno.proto.grpc.SetTxFeeRatePreferenceReply;
import haveno.proto.grpc.SetTxFeeRatePreferenceRequest;
import haveno.proto.grpc.SetWalletPasswordReply;
import haveno.proto.grpc.SetWalletPasswordRequest;
import haveno.proto.grpc.UnlockWalletReply;
import haveno.proto.grpc.UnlockWalletRequest;
import haveno.proto.grpc.UnsetTxFeeRatePreferenceReply;
import haveno.proto.grpc.UnsetTxFeeRatePreferenceRequest;

import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;

import org.bitcoinj.core.Transaction;

import javax.inject.Inject;

import com.google.common.util.concurrent.FutureCallback;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;

import static haveno.core.api.model.TxInfo.toTxInfo;
import static haveno.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import static haveno.proto.grpc.WalletsGrpc.*;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;



import haveno.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import haveno.daemon.grpc.interceptor.GrpcCallRateMeter;

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
    }
    
    @Override
    public void getNewDepositSubaddress(GetNewDepositSubaddressRequest req,
                                    StreamObserver<GetNewDepositSubaddressReply> responseObserver) {
        try {
            String subaddress = coreApi.getNewDepositSubaddress();
            var reply = GetNewDepositSubaddressReply.newBuilder()
                    .setSubaddress(subaddress)
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
    public void sendBtc(SendBtcRequest req,
                        StreamObserver<SendBtcReply> responseObserver) {
        try {
            coreApi.sendBtc(req.getAddress(),
                    req.getAmount(),
                    req.getTxFeeRate(),
                    req.getMemo(),
                    new FutureCallback<>() {
                        @Override
                        public void onSuccess(Transaction tx) {
                            if (tx != null) {
                                log.info("Successfully published BTC tx: id {}, output sum {} sats, fee {} sats, size {} bytes",
                                        tx.getTxId().toString(),
                                        tx.getOutputSum(),
                                        tx.getFee(),
                                        tx.getMessageSize());
                                var reply = SendBtcReply.newBuilder()
                                        .setTxInfo(toTxInfo(tx).toProtoMessage())
                                        .build();
                                responseObserver.onNext(reply);
                                responseObserver.onCompleted();
                            } else {
                                throw new IllegalStateException("btc transaction is null");
                            }
                        }

                        @Override
                        public void onFailure(@NotNull Throwable t) {
                            log.error("", t);
                            throw new IllegalStateException(t);
                        }
                    });
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void getTxFeeRate(GetTxFeeRateRequest req,
                             StreamObserver<GetTxFeeRateReply> responseObserver) {
        try {
            coreApi.getTxFeeRate(() -> {
                TxFeeRateInfo txFeeRateInfo = coreApi.getMostRecentTxFeeRateInfo();
                var reply = GetTxFeeRateReply.newBuilder()
                        .setTxFeeRateInfo(txFeeRateInfo.toProtoMessage())
                        .build();
                responseObserver.onNext(reply);
                responseObserver.onCompleted();
            });
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void setTxFeeRatePreference(SetTxFeeRatePreferenceRequest req,
                                       StreamObserver<SetTxFeeRatePreferenceReply> responseObserver) {
        try {
            coreApi.setTxFeeRatePreference(req.getTxFeeRatePreference(), () -> {
                TxFeeRateInfo txFeeRateInfo = coreApi.getMostRecentTxFeeRateInfo();
                var reply = SetTxFeeRatePreferenceReply.newBuilder()
                        .setTxFeeRateInfo(txFeeRateInfo.toProtoMessage())
                        .build();
                responseObserver.onNext(reply);
                responseObserver.onCompleted();
            });
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void unsetTxFeeRatePreference(UnsetTxFeeRatePreferenceRequest req,
                                         StreamObserver<UnsetTxFeeRatePreferenceReply> responseObserver) {
        try {
            coreApi.unsetTxFeeRatePreference(() -> {
                TxFeeRateInfo txFeeRateInfo = coreApi.getMostRecentTxFeeRateInfo();
                var reply = UnsetTxFeeRatePreferenceReply.newBuilder()
                        .setTxFeeRateInfo(txFeeRateInfo.toProtoMessage())
                        .build();
                responseObserver.onNext(reply);
                responseObserver.onCompleted();
            });
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void getTransaction(GetTransactionRequest req,
                               StreamObserver<GetTransactionReply> responseObserver) {
        try {
            Transaction tx = coreApi.getTransaction(req.getTxId());
            var reply = GetTransactionReply.newBuilder()
                    .setTxInfo(toTxInfo(tx).toProtoMessage())
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
                            put(getGetBalancesMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getGetAddressBalanceMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));
                            put(getGetFundingAddressesMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));
                            put(getSendBtcMethod().getFullMethodName(), new GrpcCallRateMeter(1, MINUTES));
                            put(getGetTxFeeRateMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));
                            put(getSetTxFeeRatePreferenceMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));
                            put(getUnsetTxFeeRatePreferenceMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));
                            put(getGetTransactionMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));

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
