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
import haveno.proto.grpc.AddressBalanceInfo;
import haveno.proto.grpc.BalancesInfo;
import haveno.proto.grpc.CreateXmrSweepTxsRequest;
import haveno.proto.grpc.CreateXmrTxRequest;
import haveno.proto.grpc.GetAddressBalanceRequest;
import haveno.proto.grpc.GetBalancesRequest;
import haveno.proto.grpc.GetFundingAddressesRequest;
import haveno.proto.grpc.GetWalletHeightReply;
import haveno.proto.grpc.GetWalletHeightRequest;
import haveno.proto.grpc.GetXmrNewSubaddressRequest;
import haveno.proto.grpc.GetXmrPrimaryAddressRequest;
import haveno.proto.grpc.GetXmrSeedRequest;
import haveno.proto.grpc.GetXmrTxsRequest;
import haveno.proto.grpc.LockWalletRequest;
import haveno.proto.grpc.RelayXmrTxsRequest;
import haveno.proto.grpc.RemoveWalletPasswordRequest;
import haveno.proto.grpc.SetWalletPasswordRequest;
import haveno.proto.grpc.UnlockWalletRequest;
import haveno.proto.grpc.XmrBalanceInfo;
import haveno.proto.grpc.XmrDestination;
import haveno.proto.grpc.XmrTx;

import java.util.List;

public class WalletsServiceRequest {

    private final GrpcStubs grpcStubs;

    public WalletsServiceRequest(GrpcStubs grpcStubs) {
        this.grpcStubs = grpcStubs;
    }

    public BalancesInfo getBalances() {
        return getBalances("");
    }

    public XmrBalanceInfo getXmrBalances() {
        return getBalances("XMR").getXmr();
    }

    public BalancesInfo getBalances(String currencyCode) {
        var request = GetBalancesRequest.newBuilder()
                .setCurrencyCode(currencyCode)
                .build();
        return grpcStubs.walletsService.getBalances(request).getBalances();
    }

    public AddressBalanceInfo getAddressBalance(String address) {
        var request = GetAddressBalanceRequest.newBuilder()
                .setAddress(address).build();
        return grpcStubs.walletsService.getAddressBalance(request).getAddressBalanceInfo();
    }

    public List<AddressBalanceInfo> getFundingAddresses() {
        var request = GetFundingAddressesRequest.newBuilder().build();
        return grpcStubs.walletsService.getFundingAddresses(request).getAddressBalanceInfoList();
    }

    public String getXmrSeed() {
        var request = GetXmrSeedRequest.newBuilder().build();
        return grpcStubs.walletsService.getXmrSeed(request).getSeed();
    }

    public String getXmrPrimaryAddress() {
        var request = GetXmrPrimaryAddressRequest.newBuilder().build();
        return grpcStubs.walletsService.getXmrPrimaryAddress(request).getPrimaryAddress();
    }

    public String getXmrNewSubaddress() {
        var request = GetXmrNewSubaddressRequest.newBuilder().build();
        return grpcStubs.walletsService.getXmrNewSubaddress(request).getSubaddress();
    }

    public List<XmrTx> getXmrTxs() {
        var request = GetXmrTxsRequest.newBuilder().build();
        return grpcStubs.walletsService.getXmrTxs(request).getTxsList();
    }

    public XmrTx createXmrTx(List<XmrDestination> destinations) {
        var request = CreateXmrTxRequest.newBuilder()
                .addAllDestinations(destinations)
                .build();
        return grpcStubs.walletsService.createXmrTx(request).getTx();
    }

    public List<XmrTx> createXmrSweepTxs(String address) {
        var request = CreateXmrSweepTxsRequest.newBuilder()
                .setAddress(address)
                .build();
        return grpcStubs.walletsService.createXmrSweepTxs(request).getTxsList();
    }

    public List<String> relayXmrTxs(List<String> metadatas) {
        var request = RelayXmrTxsRequest.newBuilder()
                .addAllMetadatas(metadatas)
                .build();
        return grpcStubs.walletsService.relayXmrTxs(request).getHashesList();
    }

    public GetWalletHeightReply getWalletHeight() {
        var request = GetWalletHeightRequest.newBuilder().build();
        return grpcStubs.walletsService.getHeight(request);
    }

    public void lockWallet() {
        var request = LockWalletRequest.newBuilder().build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.walletsService.lockWallet(request);
    }

    public void unlockWallet(String walletPassword, long timeout) {
        var request = UnlockWalletRequest.newBuilder()
                .setPassword(walletPassword)
                .setTimeout(timeout).build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.walletsService.unlockWallet(request);
    }

    public void removeWalletPassword(String walletPassword) {
        var request = RemoveWalletPasswordRequest.newBuilder()
                .setPassword(walletPassword).build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.walletsService.removeWalletPassword(request);
    }

    public void setWalletPassword(String walletPassword) {
        var request = SetWalletPasswordRequest.newBuilder()
                .setPassword(walletPassword).build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.walletsService.setWalletPassword(request);
    }

    public void setWalletPassword(String oldWalletPassword, String newWalletPassword) {
        var request = SetWalletPasswordRequest.newBuilder()
                .setPassword(oldWalletPassword)
                .setNewPassword(newWalletPassword).build();
        //noinspection ResultOfMethodCallIgnored
        grpcStubs.walletsService.setWalletPassword(request);
    }
}
