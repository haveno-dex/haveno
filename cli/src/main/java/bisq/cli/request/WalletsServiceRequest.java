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

package bisq.cli.request;

import bisq.proto.grpc.AddressBalanceInfo;
import bisq.proto.grpc.BalancesInfo;
import bisq.proto.grpc.BtcBalanceInfo;
import bisq.proto.grpc.GetAddressBalanceRequest;
import bisq.proto.grpc.GetBalancesRequest;
import bisq.proto.grpc.GetFundingAddressesRequest;
import bisq.proto.grpc.GetTransactionRequest;
import bisq.proto.grpc.GetTxFeeRateRequest;
import bisq.proto.grpc.LockWalletRequest;
import bisq.proto.grpc.MarketPriceRequest;
import bisq.proto.grpc.RemoveWalletPasswordRequest;
import bisq.proto.grpc.SendBtcRequest;
import bisq.proto.grpc.SetTxFeeRatePreferenceRequest;
import bisq.proto.grpc.SetWalletPasswordRequest;
import bisq.proto.grpc.TxFeeRateInfo;
import bisq.proto.grpc.TxInfo;
import bisq.proto.grpc.UnlockWalletRequest;
import bisq.proto.grpc.UnsetTxFeeRatePreferenceRequest;

import java.util.List;



import bisq.cli.GrpcStubs;

public class WalletsServiceRequest {

    private final GrpcStubs grpcStubs;

    public WalletsServiceRequest(GrpcStubs grpcStubs) {
        this.grpcStubs = grpcStubs;
    }

    public BalancesInfo getBalances() {
        return getBalances("");
    }

    public BtcBalanceInfo getBtcBalances() {
        return getBalances("BTC").getBtc();
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

    public double getBtcPrice(String currencyCode) {
        var request = MarketPriceRequest.newBuilder()
                .setCurrencyCode(currencyCode)
                .build();
        return grpcStubs.priceService.getMarketPrice(request).getPrice();
    }

    public List<AddressBalanceInfo> getFundingAddresses() {
        var request = GetFundingAddressesRequest.newBuilder().build();
        return grpcStubs.walletsService.getFundingAddresses(request).getAddressBalanceInfoList();
    }

    public String getUnusedBtcAddress() {
        var request = GetFundingAddressesRequest.newBuilder().build();
        var addressBalances = grpcStubs.walletsService.getFundingAddresses(request)
                .getAddressBalanceInfoList();
        //noinspection OptionalGetWithoutIsPresent
        return addressBalances.stream()
                .filter(AddressBalanceInfo::getIsAddressUnused)
                .findFirst()
                .get()
                .getAddress();
    }

    public TxInfo sendBtc(String address, String amount, String txFeeRate, String memo) {
        var request = SendBtcRequest.newBuilder()
                .setAddress(address)
                .setAmount(amount)
                .setTxFeeRate(txFeeRate)
                .setMemo(memo)
                .build();
        return grpcStubs.walletsService.sendBtc(request).getTxInfo();
    }

    public TxFeeRateInfo getTxFeeRate() {
        var request = GetTxFeeRateRequest.newBuilder().build();
        return grpcStubs.walletsService.getTxFeeRate(request).getTxFeeRateInfo();
    }

    public TxFeeRateInfo setTxFeeRate(long txFeeRate) {
        var request = SetTxFeeRatePreferenceRequest.newBuilder()
                .setTxFeeRatePreference(txFeeRate)
                .build();
        return grpcStubs.walletsService.setTxFeeRatePreference(request).getTxFeeRateInfo();
    }

    public TxFeeRateInfo unsetTxFeeRate() {
        var request = UnsetTxFeeRatePreferenceRequest.newBuilder().build();
        return grpcStubs.walletsService.unsetTxFeeRatePreference(request).getTxFeeRateInfo();
    }

    public TxInfo getTransaction(String txId) {
        var request = GetTransactionRequest.newBuilder()
                .setTxId(txId)
                .build();
        return grpcStubs.walletsService.getTransaction(request).getTxInfo();
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
