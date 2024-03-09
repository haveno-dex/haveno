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

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;
import com.google.protobuf.ByteString;
import haveno.common.crypto.IncorrectPasswordException;
import haveno.core.api.CoreApi;
import haveno.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import haveno.daemon.grpc.interceptor.GrpcCallRateMeter;
import static haveno.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import haveno.proto.grpc.AccountExistsReply;
import haveno.proto.grpc.AccountExistsRequest;
import haveno.proto.grpc.AccountGrpc.AccountImplBase;
import static haveno.proto.grpc.AccountGrpc.getAccountExistsMethod;
import static haveno.proto.grpc.AccountGrpc.getBackupAccountMethod;
import static haveno.proto.grpc.AccountGrpc.getChangePasswordMethod;
import static haveno.proto.grpc.AccountGrpc.getCloseAccountMethod;
import static haveno.proto.grpc.AccountGrpc.getCreateAccountMethod;
import static haveno.proto.grpc.AccountGrpc.getDeleteAccountMethod;
import static haveno.proto.grpc.AccountGrpc.getIsAccountOpenMethod;
import static haveno.proto.grpc.AccountGrpc.getOpenAccountMethod;
import static haveno.proto.grpc.AccountGrpc.getRestoreAccountMethod;
import haveno.proto.grpc.BackupAccountReply;
import haveno.proto.grpc.BackupAccountRequest;
import haveno.proto.grpc.ChangePasswordReply;
import haveno.proto.grpc.ChangePasswordRequest;
import haveno.proto.grpc.CloseAccountReply;
import haveno.proto.grpc.CloseAccountRequest;
import haveno.proto.grpc.CreateAccountReply;
import haveno.proto.grpc.CreateAccountRequest;
import haveno.proto.grpc.DeleteAccountReply;
import haveno.proto.grpc.DeleteAccountRequest;
import haveno.proto.grpc.IsAccountOpenReply;
import haveno.proto.grpc.IsAccountOpenRequest;
import haveno.proto.grpc.IsAppInitializedReply;
import haveno.proto.grpc.IsAppInitializedRequest;
import haveno.proto.grpc.OpenAccountReply;
import haveno.proto.grpc.OpenAccountRequest;
import haveno.proto.grpc.RestoreAccountReply;
import haveno.proto.grpc.RestoreAccountRequest;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Optional;
import static java.util.concurrent.TimeUnit.SECONDS;
import lombok.extern.slf4j.Slf4j;

@VisibleForTesting
@Slf4j
public class GrpcAccountService extends AccountImplBase {

    private final CoreApi coreApi;
    private final GrpcExceptionHandler exceptionHandler;

    private ByteArrayOutputStream restoreStream; // in memory stream for restoring account

    @Inject
    public GrpcAccountService(CoreApi coreApi, GrpcExceptionHandler exceptionHandler) {
        this.coreApi = coreApi;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void accountExists(AccountExistsRequest req, StreamObserver<AccountExistsReply> responseObserver) {
        try {
            var reply = AccountExistsReply.newBuilder()
                    .setAccountExists(coreApi.accountExists())
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void isAccountOpen(IsAccountOpenRequest req, StreamObserver<IsAccountOpenReply> responseObserver) {
        try {
            var reply = IsAccountOpenReply.newBuilder()
                    .setIsAccountOpen(coreApi.isAccountOpen())
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void createAccount(CreateAccountRequest req, StreamObserver<CreateAccountReply> responseObserver) {
        try {
            coreApi.createAccount(req.getPassword());
            var reply = CreateAccountReply.newBuilder()
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void openAccount(OpenAccountRequest req, StreamObserver<OpenAccountReply> responseObserver) {
        try {
            coreApi.openAccount(req.getPassword());
            var reply = OpenAccountReply.newBuilder().build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            if (cause instanceof IncorrectPasswordException) cause = new IllegalStateException(cause);
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void isAppInitialized(IsAppInitializedRequest req, StreamObserver<IsAppInitializedReply> responseObserver) {
        try {
            var reply = IsAppInitializedReply.newBuilder().setIsAppInitialized(coreApi.isAppInitialized()).build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void changePassword(ChangePasswordRequest req, StreamObserver<ChangePasswordReply> responseObserver) {
        try {
            coreApi.changePassword(req.getOldPassword(), req.getNewPassword());
            var reply = ChangePasswordReply.newBuilder().build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void closeAccount(CloseAccountRequest req, StreamObserver<CloseAccountReply> responseObserver) {
        try {
            coreApi.closeAccount();
            var reply = CloseAccountReply.newBuilder()
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void deleteAccount(DeleteAccountRequest req, StreamObserver<DeleteAccountReply> responseObserver) {
        try {
            coreApi.deleteAccount(() -> {
                var reply = DeleteAccountReply.newBuilder().build();
                responseObserver.onNext(reply);
                responseObserver.onCompleted(); // reply after shutdown
            });
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void backupAccount(BackupAccountRequest req, StreamObserver<BackupAccountReply> responseObserver) {

        // Send in large chunks to reduce unnecessary overhead. Typical backup will not be more than a few MB.
        // From current testing it appears that client gRPC-web is slow in processing the bytes on download.
        try {
            int bufferSize = 1024 * 1024 * 8;
            coreApi.backupAccount(bufferSize, (stream) -> {
                try {
                    log.info("Sending bytes in chunks of: " + bufferSize);
                    byte[] buffer = new byte[bufferSize];
                    int length;
                    int total = 0;
                    while ((length = stream.read(buffer, 0, bufferSize)) != -1) {
                        total += length;
                        var reply = BackupAccountReply.newBuilder()
                                .setZipBytes(ByteString.copyFrom(buffer, 0, length))
                                .build();
                        responseObserver.onNext(reply);
                    }
                    log.info("Completed backup account total sent: " + total);
                    stream.close();
                    responseObserver.onCompleted();
                } catch (Exception ex) {
                    exceptionHandler.handleException(log, ex, responseObserver);
                }
            }, (ex) -> exceptionHandler.handleException(log, ex, responseObserver));
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void restoreAccount(RestoreAccountRequest req, StreamObserver<RestoreAccountReply> responseObserver) {
        try {
            // Fail fast since uploading and processing bytes takes resources.
            if (coreApi.accountExists()) throw new IllegalStateException("Cannot restore account if there is an existing account");

            // If the entire zip is in memory, no need to write to disk.
            // Restore the account directly from the zip stream.
            if (!req.getHasMore() && req.getOffset() == 0) {
                var inputStream = req.getZipBytes().newInput();
                coreApi.restoreAccount(inputStream, 1024 * 64, () -> {
                    var reply = RestoreAccountReply.newBuilder().build();
                    responseObserver.onNext(reply);
                    responseObserver.onCompleted();  // reply after shutdown
                });
            } else {
                if (req.getOffset() == 0) {
                    log.info("RestoreAccount starting new chunked zip");
                    restoreStream = new ByteArrayOutputStream((int) req.getTotalLength());
                }
                if (restoreStream.size() != req.getOffset()) {
                    log.warn("Stream offset doesn't match current position");
                    IllegalStateException cause = new IllegalStateException("Stream offset doesn't match current position");
                    exceptionHandler.handleException(log, cause, responseObserver);
                } else {
                    log.info("RestoreAccount writing chunk size " + req.getZipBytes().size());
                    req.getZipBytes().writeTo(restoreStream);
                }

                if (!req.getHasMore()) {
                    var inputStream = new ByteArrayInputStream(restoreStream.toByteArray());
                    restoreStream.close();
                    restoreStream = null;
                    coreApi.restoreAccount(inputStream, 1024 * 64, () -> {
                        var reply = RestoreAccountReply.newBuilder().build();
                        responseObserver.onNext(reply);
                        responseObserver.onCompleted(); // reply after shutdown
                    });
                } else {
                    var reply = RestoreAccountReply.newBuilder().build();
                    responseObserver.onNext(reply);
                    responseObserver.onCompleted();
                }
            }
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
                            put(getAccountExistsMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getBackupAccountMethod().getFullMethodName(), new GrpcCallRateMeter(5, SECONDS));
                            put(getChangePasswordMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getCloseAccountMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getCreateAccountMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getDeleteAccountMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getIsAccountOpenMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getOpenAccountMethod().getFullMethodName(), new GrpcCallRateMeter(10, SECONDS));
                            put(getRestoreAccountMethod().getFullMethodName(), new GrpcCallRateMeter(5, SECONDS));
                        }}
                )));
    }
}
