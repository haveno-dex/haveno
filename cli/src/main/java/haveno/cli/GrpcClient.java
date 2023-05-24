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

package haveno.cli;

import haveno.cli.request.OffersServiceRequest;
import haveno.cli.request.PaymentAccountsServiceRequest;
import haveno.cli.request.TradesServiceRequest;
import haveno.cli.request.WalletsServiceRequest;
import haveno.proto.grpc.AddressBalanceInfo;
import haveno.proto.grpc.BalancesInfo;
import haveno.proto.grpc.BtcBalanceInfo;
import haveno.proto.grpc.GetMethodHelpRequest;
import haveno.proto.grpc.GetTradesRequest;
import haveno.proto.grpc.GetVersionRequest;
import haveno.proto.grpc.OfferInfo;
import haveno.proto.grpc.RegisterDisputeAgentRequest;
import haveno.proto.grpc.StopRequest;
import haveno.proto.grpc.TradeInfo;
import lombok.extern.slf4j.Slf4j;
import protobuf.PaymentAccount;
import protobuf.PaymentMethod;

import java.util.List;


@SuppressWarnings("ResultOfMethodCallIgnored")
@Slf4j
public final class GrpcClient {

    private final GrpcStubs grpcStubs;
    private final OffersServiceRequest offersServiceRequest;
    private final TradesServiceRequest tradesServiceRequest;
    private final WalletsServiceRequest walletsServiceRequest;
    private final PaymentAccountsServiceRequest paymentAccountsServiceRequest;

    public GrpcClient(String apiHost,
                      int apiPort,
                      String apiPassword) {
        this.grpcStubs = new GrpcStubs(apiHost, apiPort, apiPassword);
        this.offersServiceRequest = new OffersServiceRequest(grpcStubs);
        this.tradesServiceRequest = new TradesServiceRequest(grpcStubs);
        this.walletsServiceRequest = new WalletsServiceRequest(grpcStubs);
        this.paymentAccountsServiceRequest = new PaymentAccountsServiceRequest(grpcStubs);
    }

    public String getVersion() {
        var request = GetVersionRequest.newBuilder().build();
        return grpcStubs.versionService.getVersion(request).getVersion();
    }

    public BalancesInfo getBalances() {
        return walletsServiceRequest.getBalances();
    }

    public BtcBalanceInfo getBtcBalances() {
        return walletsServiceRequest.getBtcBalances();
    }

    public BalancesInfo getBalances(String currencyCode) {
        return walletsServiceRequest.getBalances(currencyCode);
    }

    public AddressBalanceInfo getAddressBalance(String address) {
        return walletsServiceRequest.getAddressBalance(address);
    }

    public double getBtcPrice(String currencyCode) {
        return walletsServiceRequest.getBtcPrice(currencyCode);
    }

    public List<AddressBalanceInfo> getFundingAddresses() {
        return walletsServiceRequest.getFundingAddresses();
    }

    public String getUnusedBtcAddress() {
        return walletsServiceRequest.getUnusedBtcAddress();
    }

    public OfferInfo createFixedPricedOffer(String direction,
                                            String currencyCode,
                                            long amount,
                                            long minAmount,
                                            String fixedPrice,
                                            double securityDepositPct,
                                            String paymentAcctId) {
        return offersServiceRequest.createOffer(direction,
                currencyCode,
                amount,
                minAmount,
                false,
                fixedPrice,
                0.00,
                securityDepositPct,
                paymentAcctId,
                "0" /* no trigger price */);
    }

    public OfferInfo createMarketBasedPricedOffer(String direction,
                                                  String currencyCode,
                                                  long amount,
                                                  long minAmount,
                                                  double marketPriceMarginPct,
                                                  double securityDepositPct,
                                                  String paymentAcctId,
                                                  String triggerPrice) {
        return offersServiceRequest.createOffer(direction,
                currencyCode,
                amount,
                minAmount,
                true,
                "0",
                marketPriceMarginPct,
                securityDepositPct,
                paymentAcctId,
                triggerPrice);
    }

    public OfferInfo createOffer(String direction,
                                 String currencyCode,
                                 long amount,
                                 long minAmount,
                                 boolean useMarketBasedPrice,
                                 String fixedPrice,
                                 double marketPriceMarginPct,
                                 double securityDepositPct,
                                 String paymentAcctId,
                                 String triggerPrice) {
        return offersServiceRequest.createOffer(direction,
                currencyCode,
                amount,
                minAmount,
                useMarketBasedPrice,
                fixedPrice,
                marketPriceMarginPct,
                securityDepositPct,
                paymentAcctId,
                triggerPrice);
    }

    public void cancelOffer(String offerId) {
        offersServiceRequest.cancelOffer(offerId);
    }

    public OfferInfo getOffer(String offerId) {
        return offersServiceRequest.getOffer(offerId);
    }

    @Deprecated // Since 5-Dec-2021.
    // Endpoint to be removed from future version.  Use getOffer service method instead.
    public OfferInfo getMyOffer(String offerId) {
        return offersServiceRequest.getMyOffer(offerId);
    }

    public List<OfferInfo> getOffers(String direction, String currencyCode) {
        return offersServiceRequest.getOffers(direction, currencyCode);
    }

    public List<OfferInfo> getOffersSortedByDate(String currencyCode) {
        return offersServiceRequest.getOffersSortedByDate(currencyCode);
    }

    public List<OfferInfo> getOffersSortedByDate(String direction, String currencyCode) {
        return offersServiceRequest.getOffersSortedByDate(direction, currencyCode);
    }

    public List<OfferInfo> getMyOffers(String direction, String currencyCode) {
        return offersServiceRequest.getMyOffers(direction, currencyCode);
    }

    public List<OfferInfo> getMyOffersSortedByDate(String currencyCode) {
        return offersServiceRequest.getMyOffersSortedByDate(currencyCode);
    }

    public List<OfferInfo> getMyOffersSortedByDate(String direction, String currencyCode) {
        return offersServiceRequest.getMyOffersSortedByDate(direction, currencyCode);
    }

    public TradeInfo takeOffer(String offerId, String paymentAccountId) {
        return tradesServiceRequest.takeOffer(offerId, paymentAccountId);
    }

    public TradeInfo getTrade(String tradeId) {
        return tradesServiceRequest.getTrade(tradeId);
    }

    public List<TradeInfo> getOpenTrades() {
        return tradesServiceRequest.getOpenTrades();
    }

    public List<TradeInfo> getTradeHistory(GetTradesRequest.Category category) {
        return tradesServiceRequest.getTradeHistory(category);
    }

    public void confirmPaymentSent(String tradeId) {
        tradesServiceRequest.confirmPaymentSent(tradeId);
    }

    public void confirmPaymentReceived(String tradeId) {
        tradesServiceRequest.confirmPaymentReceived(tradeId);
    }

    public void withdrawFunds(String tradeId, String address, String memo) {
        tradesServiceRequest.withdrawFunds(tradeId, address, memo);
    }

    public List<PaymentMethod> getPaymentMethods() {
        return paymentAccountsServiceRequest.getPaymentMethods();
    }

    public String getPaymentAcctFormAsJson(String paymentMethodId) {
        return paymentAccountsServiceRequest.getPaymentAcctFormAsJson(paymentMethodId);
    }

    public PaymentAccount createPaymentAccount(String json) {
        return paymentAccountsServiceRequest.createPaymentAccount(json);
    }

    public List<PaymentAccount> getPaymentAccounts() {
        return paymentAccountsServiceRequest.getPaymentAccounts();
    }

    public PaymentAccount getPaymentAccount(String accountName) {
        return paymentAccountsServiceRequest.getPaymentAccount(accountName);
    }

    public PaymentAccount createCryptoCurrencyPaymentAccount(String accountName,
                                                             String currencyCode,
                                                             String address,
                                                             boolean tradeInstant) {
        return paymentAccountsServiceRequest.createCryptoCurrencyPaymentAccount(accountName,
                currencyCode,
                address,
                tradeInstant);
    }

    public List<PaymentMethod> getCryptoPaymentMethods() {
        return paymentAccountsServiceRequest.getCryptoPaymentMethods();
    }

    public void lockWallet() {
        walletsServiceRequest.lockWallet();
    }

    public void unlockWallet(String walletPassword, long timeout) {
        walletsServiceRequest.unlockWallet(walletPassword, timeout);
    }

    public void removeWalletPassword(String walletPassword) {
        walletsServiceRequest.removeWalletPassword(walletPassword);
    }

    public void setWalletPassword(String walletPassword) {
        walletsServiceRequest.setWalletPassword(walletPassword);
    }

    public void setWalletPassword(String oldWalletPassword, String newWalletPassword) {
        walletsServiceRequest.setWalletPassword(oldWalletPassword, newWalletPassword);
    }

    public void registerDisputeAgent(String disputeAgentType, String registrationKey) {
        var request = RegisterDisputeAgentRequest.newBuilder()
                .setDisputeAgentType(disputeAgentType).setRegistrationKey(registrationKey).build();
        grpcStubs.disputeAgentsService.registerDisputeAgent(request);
    }

    public void stopServer() {
        var request = StopRequest.newBuilder().build();
        grpcStubs.shutdownService.stop(request);
    }

    public String getMethodHelp(Method method) {
        var request = GetMethodHelpRequest.newBuilder().setMethodName(method.name()).build();
        return grpcStubs.helpService.getMethodHelp(request).getMethodHelp();
    }
}
