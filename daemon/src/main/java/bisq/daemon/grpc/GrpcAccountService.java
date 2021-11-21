package bisq.daemon.grpc;

import bisq.core.api.CoreApi;
import bisq.proto.grpc.ChangePasswordRequest;
import bisq.proto.grpc.CloseAccountReply;
import bisq.proto.grpc.AccountExistsReply;
import bisq.proto.grpc.AccountExistsRequest;
import bisq.proto.grpc.AccountGrpc.AccountImplBase;
import bisq.proto.grpc.BackupAccountReply;
import bisq.proto.grpc.BackupAccountRequest;
import bisq.proto.grpc.ChangePasswordReply;
import bisq.proto.grpc.CloseAccountRequest;
import bisq.proto.grpc.CreateAccountReply;
import bisq.proto.grpc.CreateAccountRequest;
import bisq.proto.grpc.DeleteAccountReply;
import bisq.proto.grpc.DeleteAccountRequest;
import bisq.proto.grpc.IsAccountOpenReply;
import bisq.proto.grpc.IsAccountOpenRequest;
import bisq.proto.grpc.OpenAccountReply;
import bisq.proto.grpc.OpenAccountRequest;
import bisq.proto.grpc.RestoreAccountReply;
import bisq.proto.grpc.RestoreAccountRequest;
import bisq.proto.grpc.Status;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;

import javax.inject.Inject;

import com.google.common.util.concurrent.FutureCallback;
import com.google.protobuf.ByteString;

import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import static bisq.daemon.grpc.interceptor.GrpcServiceRateMeteringConfig.getCustomRateMeteringInterceptor;
import static bisq.proto.grpc.AccountGrpc.*;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import bisq.daemon.grpc.interceptor.CallRateMeteringInterceptor;
import bisq.daemon.grpc.interceptor.GrpcCallRateMeter;

@Slf4j
public class GrpcAccountService extends AccountImplBase {

    private final CoreApi coreApi;
    private final GrpcExceptionHandler exceptionHandler;
    @Inject
    public GrpcAccountService(CoreApi coreApi, 
    						  GrpcExceptionHandler exceptionHandler) {
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
            var reply = OpenAccountReply.newBuilder()
                    .build();
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
        	coreApi.deleteAccount();
            var reply = DeleteAccountReply.newBuilder()
					 					  .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }

    @Override
    public void changePassword(ChangePasswordRequest req, StreamObserver<ChangePasswordReply> responseObserver) {
        try {
        	coreApi.changePassword(req.getPassword());
            var reply = ChangePasswordReply.newBuilder()
					  					   .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        }
    }
    
    private static final Path SERVER_BASE_PATH = Paths.get("src/test/resources/output");
       
    @Override
    public StreamObserver<RestoreAccountRequest> restoreAccount(StreamObserver<RestoreAccountReply> responseObserver) {
    	return new StreamObserver<RestoreAccountRequest>() {
    		OutputStream writer;
    		Status status = Status.IN_PROGRESS;
    		
    		@Override
    		public void onNext(RestoreAccountRequest restoreAccountRequest) {
    			try {
    				if (restoreAccountRequest.hasMetadata()) {
    					writer = getFilePath(restoreAccountRequest);
    				} else {
    					writeFile(writer, restoreAccountRequest.getZip().getContent());
    				}
    			}
    			catch (IOException e) {
    				this.onError(e);
    			}
    		}
    		
    		@Override
    		public void onError(Throwable throwable) {
    			status = Status.FAILED;
    			this.onCompleted();
    		}
    		
    		@Override
    		public void onCompleted() {
    			closeFile(writer);
    			status = Status.IN_PROGRESS.equals(status) ? Status.SUCCESS : status;
    			RestoreAccountReply response = RestoreAccountReply.newBuilder()
    															  .setStatus(status)
    															  .build();
    			responseObserver.onNext(response);
    			responseObserver.onCompleted();
    		}
    	};
    }
    
    @Override
    public void backupAccount(BackupAccountRequest req, StreamObserver<BackupAccountReply> responseObserver) {
    	File tmpFile = null;
    	try {
    	  BufferedInputStream bis = coreApi.backupAccount();          
		  int bufferSize = 64 * 1024;
		  byte[] buffer = new byte[bufferSize];
		  int length;
		  while ((length = bis.read(buffer, 0, bufferSize)) != -1) {
		    responseObserver.onNext(
		      BackupAccountReply.newBuilder().setData(ByteString.copyFrom(buffer, 0, length)).build()
		    );
		  }
		  responseObserver.onCompleted();
            
        } catch (Throwable cause) {
            exceptionHandler.handleException(log, cause, responseObserver);
        } finally {
        	if (tmpFile != null) {
        		tmpFile.delete();
        	}
        }
    }
    
    private OutputStream getFilePath(RestoreAccountRequest request) throws IOException {
        var fileName = request.getMetadata().getName() + "." + request.getMetadata().getType();
        return Files.newOutputStream(SERVER_BASE_PATH.resolve(fileName), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void writeFile(OutputStream writer, ByteString content) throws IOException {
        writer.write(content.toByteArray());
        writer.flush();
    }

    private void closeFile(OutputStream writer){
        try {
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
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
                        	put(getAccountExistsMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));
                            put(getIsAccountOpenMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));
                            put(getCreateAccountMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));
                            put(getOpenAccountMethod().getFullMethodName(), new GrpcCallRateMeter(1, MINUTES));
                            put(getCloseAccountMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));
                            put(getBackupAccountMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));
                            put(getDeleteAccountMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));
                            put(getChangePasswordMethod().getFullMethodName(), new GrpcCallRateMeter(1, SECONDS));
                        }}
                )));
    }
    
}
