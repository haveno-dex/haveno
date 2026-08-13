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

import com.google.protobuf.ByteString;
import haveno.cli.GrpcStubs;
import haveno.proto.grpc.AccountExistsRequest;
import haveno.proto.grpc.BackupAccountReply;
import haveno.proto.grpc.BackupAccountRequest;
import haveno.proto.grpc.ChangePasswordRequest;
import haveno.proto.grpc.CloseAccountRequest;
import haveno.proto.grpc.CreateAccountRequest;
import haveno.proto.grpc.DeleteAccountRequest;
import haveno.proto.grpc.IsAccountOpenRequest;
import haveno.proto.grpc.IsAppInitializedRequest;
import haveno.proto.grpc.OpenAccountRequest;
import haveno.proto.grpc.RestoreAccountRequest;

import java.util.Iterator;

public class AccountServiceRequest {

    private final GrpcStubs grpcStubs;

    public AccountServiceRequest(GrpcStubs grpcStubs) {
        this.grpcStubs = grpcStubs;
    }

    public boolean accountExists() {
        var request = AccountExistsRequest.newBuilder().build();
        return grpcStubs.accountService.accountExists(request).getAccountExists();
    }

    public boolean isAccountOpen() {
        var request = IsAccountOpenRequest.newBuilder().build();
        return grpcStubs.accountService.isAccountOpen(request).getIsAccountOpen();
    }

    public boolean isAppInitialized() {
        var request = IsAppInitializedRequest.newBuilder().build();
        return grpcStubs.accountService.isAppInitialized(request).getIsAppInitialized();
    }

    public void createAccount(String password) {
        var request = CreateAccountRequest.newBuilder()
                .setPassword(password)
                .build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.accountService.createAccount(request);
    }

    public void openAccount(String password) {
        var request = OpenAccountRequest.newBuilder()
                .setPassword(password)
                .build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.accountService.openAccount(request);
    }

    public void changePassword(String oldPassword, String newPassword) {
        var request = ChangePasswordRequest.newBuilder()
                .setOldPassword(oldPassword)
                .setNewPassword(newPassword)
                .build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.accountService.changePassword(request);
    }

    public void closeAccount() {
        var request = CloseAccountRequest.newBuilder().build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.accountService.closeAccount(request);
    }

    public void deleteAccount() {
        var request = DeleteAccountRequest.newBuilder().build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.accountService.deleteAccount(request);
    }

    public Iterator<BackupAccountReply> backupAccount() {
        var request = BackupAccountRequest.newBuilder().build();
        return grpcStubs.accountService.backupAccount(request);
    }

    public void restoreAccount(byte[] zipBytes, long offset, long totalLength, boolean hasMore) {
        var request = RestoreAccountRequest.newBuilder()
                .setZipBytes(ByteString.copyFrom(zipBytes))
                .setOffset(offset)
                .setTotalLength(totalLength)
                .setHasMore(hasMore)
                .build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.accountService.restoreAccount(request);
    }
}
