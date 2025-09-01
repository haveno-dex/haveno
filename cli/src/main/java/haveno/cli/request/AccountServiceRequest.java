package haveno.cli.request;

import haveno.cli.GrpcStubs;
import haveno.proto.grpc.AccountExistsRequest;
import haveno.proto.grpc.BackupAccountRequest;
import haveno.proto.grpc.ChangePasswordRequest;
import haveno.proto.grpc.CloseAccountRequest;
import haveno.proto.grpc.CreateAccountRequest;
import haveno.proto.grpc.DeleteAccountRequest;
import haveno.proto.grpc.IsAccountOpenRequest;
import haveno.proto.grpc.IsAppInitializedRequest;
import haveno.proto.grpc.OpenAccountRequest;
import haveno.proto.grpc.RestoreAccountRequest;

import java.util.List;
import java.util.ArrayList;

public class AccountServiceRequest {

    private final GrpcStubs grpcStubs;

    public AccountServiceRequest(GrpcStubs grpcStubs) {
        this.grpcStubs = grpcStubs;
    }

    public boolean accountExists() {
        AccountExistsRequest request = AccountExistsRequest.newBuilder().build();
        return grpcStubs.accountService.accountExists(request).getAccountExists();
    }

    public boolean isAccountOpen() {
        IsAccountOpenRequest request = IsAccountOpenRequest.newBuilder().build();
        return grpcStubs.accountService.isAccountOpen(request).getIsAccountOpen();
    }

    public void createAccount(String password) {
        CreateAccountRequest request = CreateAccountRequest.newBuilder()
                .setPassword(password)
                .build();
        grpcStubs.accountService.createAccount(request);
    }

    public void openAccount(String password) {
        OpenAccountRequest request = OpenAccountRequest.newBuilder()
                .setPassword(password)
                .build();
        grpcStubs.accountService.openAccount(request);
    }

    public boolean isAppInitialized() {
        IsAppInitializedRequest request = IsAppInitializedRequest.newBuilder().build();
        return grpcStubs.accountService.isAppInitialized(request).getIsAppInitialized();
    }

    public void changePassword(String oldPassword, String newPassword) {
        ChangePasswordRequest request = ChangePasswordRequest.newBuilder()
                .setOldPassword(oldPassword)
                .setNewPassword(newPassword)
                .build();
        grpcStubs.accountService.changePassword(request);
    }

    public void closeAccount() {
        CloseAccountRequest request = CloseAccountRequest.newBuilder().build();
        grpcStubs.accountService.closeAccount(request);
    }

    public void deleteAccount() {
        DeleteAccountRequest request = DeleteAccountRequest.newBuilder().build();
        grpcStubs.accountService.deleteAccount(request);
    }

    public List<Byte> backupAccount() {
        BackupAccountRequest request = BackupAccountRequest.newBuilder().build();
        List<Byte> backupData = new ArrayList<>();
        grpcStubs.accountService.backupAccount(request).forEachRemaining(reply -> {
            byte[] bytes = reply.getZipBytes().toByteArray();
            for (byte b : bytes) {
                backupData.add(b);
            }
        });
        return backupData;
    }

    public void restoreAccount(byte[] zipBytes, long offset, long totalLength, boolean hasMore) {
        RestoreAccountRequest request = RestoreAccountRequest.newBuilder()
                .setZipBytes(com.google.protobuf.ByteString.copyFrom(zipBytes))
                .setOffset(offset)
                .setTotalLength(totalLength)
                .setHasMore(hasMore)
                .build();
        grpcStubs.accountService.restoreAccount(request);
    }
}