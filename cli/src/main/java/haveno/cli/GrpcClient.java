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

import haveno.cli.request.AccountServiceRequest;
import haveno.cli.request.DisputesServiceRequest;
import haveno.cli.request.OffersServiceRequest;
import haveno.cli.request.PaymentAccountsServiceRequest;
import haveno.cli.request.PriceServiceRequest;
import haveno.cli.request.TradesServiceRequest;
import haveno.cli.request.WalletsServiceRequest;
import haveno.cli.request.XmrConnectionsServiceRequest;
import haveno.cli.request.XmrNodeServiceRequest;
import haveno.proto.grpc.AddressBalanceInfo;
import haveno.proto.grpc.BackupAccountReply;
import haveno.proto.grpc.BalancesInfo;
import haveno.proto.grpc.GetMethodHelpRequest;
import haveno.proto.grpc.GetTradeStatisticsRequest;
import haveno.proto.grpc.GetTradesRequest;
import haveno.proto.grpc.GetVersionRequest;
import haveno.proto.grpc.GetWalletHeightReply;
import haveno.proto.grpc.MarketDepthInfo;
import haveno.proto.grpc.MarketPriceInfo;
import haveno.proto.grpc.NotificationMessage;
import haveno.proto.grpc.OfferInfo;
import haveno.proto.grpc.RegisterDisputeAgentRequest;
import haveno.proto.grpc.RegisterNotificationListenerRequest;
import haveno.proto.grpc.StopRequest;
import haveno.proto.grpc.TradeInfo;
import haveno.proto.grpc.UnregisterDisputeAgentRequest;
import haveno.proto.grpc.UrlConnection;
import haveno.proto.grpc.XmrBalanceInfo;
import haveno.proto.grpc.XmrDestination;
import haveno.proto.grpc.XmrTx;
import lombok.extern.slf4j.Slf4j;
import protobuf.ChatMessage;
import protobuf.Dispute;
import protobuf.DisputeResult;
import protobuf.PaymentAccount;
import protobuf.PaymentMethod;
import protobuf.TradeStatistics3;
import protobuf.XmrNodeSettings;

import java.util.Iterator;
import java.util.List;


@SuppressWarnings("ResultOfMethodCallIgnored")
@Slf4j
public final class GrpcClient {

    private final GrpcStubs grpcStubs;
    private final AccountServiceRequest accountServiceRequest;
    private final DisputesServiceRequest disputesServiceRequest;
    private final OffersServiceRequest offersServiceRequest;
    private final PaymentAccountsServiceRequest paymentAccountsServiceRequest;
    private final PriceServiceRequest priceServiceRequest;
    private final TradesServiceRequest tradesServiceRequest;
    private final WalletsServiceRequest walletsServiceRequest;
    private final XmrConnectionsServiceRequest xmrConnectionsServiceRequest;
    private final XmrNodeServiceRequest xmrNodeServiceRequest;

    public GrpcClient(String apiHost,
                      int apiPort,
                      String apiPassword) {
        this.grpcStubs = new GrpcStubs(apiHost, apiPort, apiPassword);
        this.accountServiceRequest = new AccountServiceRequest(grpcStubs);
        this.disputesServiceRequest = new DisputesServiceRequest(grpcStubs);
        this.offersServiceRequest = new OffersServiceRequest(grpcStubs);
        this.paymentAccountsServiceRequest = new PaymentAccountsServiceRequest(grpcStubs);
        this.priceServiceRequest = new PriceServiceRequest(grpcStubs);
        this.tradesServiceRequest = new TradesServiceRequest(grpcStubs);
        this.walletsServiceRequest = new WalletsServiceRequest(grpcStubs);
        this.xmrConnectionsServiceRequest = new XmrConnectionsServiceRequest(grpcStubs);
        this.xmrNodeServiceRequest = new XmrNodeServiceRequest(grpcStubs);
    }

    public String getVersion() {
        var request = GetVersionRequest.newBuilder().build();
        return grpcStubs.versionService.getVersion(request).getVersion();
    }

    // Account

    public boolean accountExists() {
        return accountServiceRequest.accountExists();
    }

    public boolean isAccountOpen() {
        return accountServiceRequest.isAccountOpen();
    }

    public boolean isAppInitialized() {
        return accountServiceRequest.isAppInitialized();
    }

    public void createAccount(String password) {
        accountServiceRequest.createAccount(password);
    }

    public void openAccount(String password) {
        accountServiceRequest.openAccount(password);
    }

    public void changePassword(String oldPassword, String newPassword) {
        accountServiceRequest.changePassword(oldPassword, newPassword);
    }

    public void closeAccount() {
        accountServiceRequest.closeAccount();
    }

    public void deleteAccount() {
        accountServiceRequest.deleteAccount();
    }

    public Iterator<BackupAccountReply> backupAccount() {
        return accountServiceRequest.backupAccount();
    }

    public void restoreAccount(byte[] zipBytes, long offset, long totalLength, boolean hasMore) {
        accountServiceRequest.restoreAccount(zipBytes, offset, totalLength, hasMore);
    }

    // Wallets

    public BalancesInfo getBalances() {
        return walletsServiceRequest.getBalances();
    }

    public XmrBalanceInfo getXmrBalances() {
        return walletsServiceRequest.getXmrBalances();
    }

    public BalancesInfo getBalances(String currencyCode) {
        return walletsServiceRequest.getBalances(currencyCode);
    }

    public AddressBalanceInfo getAddressBalance(String address) {
        return walletsServiceRequest.getAddressBalance(address);
    }

    public List<AddressBalanceInfo> getFundingAddresses() {
        return walletsServiceRequest.getFundingAddresses();
    }

    public String getXmrSeed() {
        return walletsServiceRequest.getXmrSeed();
    }

    public String getXmrPrimaryAddress() {
        return walletsServiceRequest.getXmrPrimaryAddress();
    }

    public String getXmrNewSubaddress() {
        return walletsServiceRequest.getXmrNewSubaddress();
    }

    public List<XmrTx> getXmrTxs() {
        return walletsServiceRequest.getXmrTxs();
    }

    public XmrTx createXmrTx(List<XmrDestination> destinations) {
        return walletsServiceRequest.createXmrTx(destinations);
    }

    public List<XmrTx> createXmrSweepTxs(String address) {
        return walletsServiceRequest.createXmrSweepTxs(address);
    }

    public List<String> relayXmrTxs(List<String> metadatas) {
        return walletsServiceRequest.relayXmrTxs(metadatas);
    }

    public GetWalletHeightReply getWalletHeight() {
        return walletsServiceRequest.getWalletHeight();
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

    // Prices

    public double getXmrPrice(String currencyCode) {
        return priceServiceRequest.getXmrPrice(currencyCode);
    }

    public List<MarketPriceInfo> getXmrPrices() {
        return priceServiceRequest.getXmrPrices();
    }

    public MarketDepthInfo getMarketDepth(String currencyCode) {
        return priceServiceRequest.getMarketDepth(currencyCode);
    }

    // Offers

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

    public OfferInfo createOffer(String direction,
                                 String currencyCode,
                                 long amount,
                                 long minAmount,
                                 boolean useMarketBasedPrice,
                                 String fixedPrice,
                                 double marketPriceMarginPct,
                                 double securityDepositPct,
                                 String paymentAcctId,
                                 String triggerPrice,
                                 boolean reserveExactAmount,
                                 String extraInfo) {
        return offersServiceRequest.createOffer(direction,
                currencyCode,
                amount,
                minAmount,
                useMarketBasedPrice,
                fixedPrice,
                marketPriceMarginPct,
                securityDepositPct,
                paymentAcctId,
                triggerPrice,
                reserveExactAmount,
                extraInfo);
    }

    public OfferInfo editOffer(String offerId,
                               String currencyCode,
                               String price,
                               boolean useMarketBasedPrice,
                               double marketPriceMarginPct,
                               String triggerPrice,
                               String paymentAcctId,
                               String extraInfo) {
        return offersServiceRequest.editOffer(offerId,
                currencyCode,
                price,
                useMarketBasedPrice,
                marketPriceMarginPct,
                triggerPrice,
                paymentAcctId,
                extraInfo);
    }

    public void activateOffer(String offerId) {
        offersServiceRequest.activateOffer(offerId);
    }

    public void deactivateOffer(String offerId) {
        offersServiceRequest.deactivateOffer(offerId);
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

    // Trades

    public TradeInfo takeOffer(String offerId, String paymentAccountId) {
        return tradesServiceRequest.takeOffer(offerId, paymentAccountId);
    }

    public TradeInfo takeOffer(String offerId, String paymentAccountId, long amount, String challenge) {
        return tradesServiceRequest.takeOffer(offerId, paymentAccountId, amount, challenge);
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

    public void completeTrade(String tradeId) {
        tradesServiceRequest.completeTrade(tradeId);
    }

    public void withdrawFunds(String tradeId, String address, String memo) {
        tradesServiceRequest.withdrawFunds(tradeId, address, memo);
    }

    public List<ChatMessage> getChatMessages(String tradeId) {
        return tradesServiceRequest.getChatMessages(tradeId);
    }

    public void sendChatMessage(String tradeId, String message) {
        tradesServiceRequest.sendChatMessage(tradeId, message);
    }

    // Payment accounts

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

    public void deletePaymentAccount(String paymentAccountId) {
        paymentAccountsServiceRequest.deletePaymentAccount(paymentAccountId);
    }

    public List<PaymentMethod> getCryptoPaymentMethods() {
        return paymentAccountsServiceRequest.getCryptoPaymentMethods();
    }

    // Disputes

    public Dispute getDispute(String tradeId) {
        return disputesServiceRequest.getDispute(tradeId);
    }

    public List<Dispute> getDisputes() {
        return disputesServiceRequest.getDisputes();
    }

    public void openDispute(String tradeId) {
        disputesServiceRequest.openDispute(tradeId);
    }

    public void resolveDispute(String tradeId,
                               DisputeResult.Winner winner,
                               DisputeResult.Reason reason,
                               String summaryNotes,
                               long customPayoutAmount) {
        disputesServiceRequest.resolveDispute(tradeId, winner, reason, summaryNotes, customPayoutAmount);
    }

    public void sendDisputeChatMessage(String disputeId, String message) {
        disputesServiceRequest.sendDisputeChatMessage(disputeId, message);
    }

    // Dispute agents

    public void registerDisputeAgent(String disputeAgentType, String registrationKey) {
        var request = RegisterDisputeAgentRequest.newBuilder()
                .setDisputeAgentType(disputeAgentType).setRegistrationKey(registrationKey).build();
        grpcStubs.disputeAgentsService.registerDisputeAgent(request);
    }

    public void unregisterDisputeAgent(String disputeAgentType) {
        var request = UnregisterDisputeAgentRequest.newBuilder()
                .setDisputeAgentType(disputeAgentType).build();
        grpcStubs.disputeAgentsService.unregisterDisputeAgent(request);
    }

    // Trade statistics

    public List<TradeStatistics3> getTradeStatistics() {
        var request = GetTradeStatisticsRequest.newBuilder().build();
        return grpcStubs.tradeStatisticsService.getTradeStatistics(request).getTradeStatisticsList();
    }

    // XMR connections

    public void addConnection(UrlConnection connection) {
        xmrConnectionsServiceRequest.addConnection(connection);
    }

    public void removeConnection(String url) {
        xmrConnectionsServiceRequest.removeConnection(url);
    }

    public UrlConnection getConnection() {
        return xmrConnectionsServiceRequest.getConnection();
    }

    public List<UrlConnection> getConnections() {
        return xmrConnectionsServiceRequest.getConnections();
    }

    public void setConnection(String url) {
        xmrConnectionsServiceRequest.setConnection(url);
    }

    public UrlConnection checkConnection() {
        return xmrConnectionsServiceRequest.checkConnection();
    }

    public UrlConnection getBestConnection() {
        return xmrConnectionsServiceRequest.getBestConnection();
    }

    public void setAutoSwitch(boolean autoSwitch) {
        xmrConnectionsServiceRequest.setAutoSwitch(autoSwitch);
    }

    public boolean getAutoSwitch() {
        return xmrConnectionsServiceRequest.getAutoSwitch();
    }

    // XMR node

    public boolean isXmrNodeOnline() {
        return xmrNodeServiceRequest.isXmrNodeOnline();
    }

    public XmrNodeSettings getXmrNodeSettings() {
        return xmrNodeServiceRequest.getXmrNodeSettings();
    }

    public void startXmrNode(XmrNodeSettings settings) {
        xmrNodeServiceRequest.startXmrNode(settings);
    }

    public void stopXmrNode() {
        xmrNodeServiceRequest.stopXmrNode();
    }

    // Notifications

    public Iterator<NotificationMessage> registerNotificationListener() {
        var request = RegisterNotificationListenerRequest.newBuilder().build();
        return grpcStubs.notificationsService.registerNotificationListener(request);
    }

    // Server

    public void stopServer() {
        var request = StopRequest.newBuilder().build();
        grpcStubs.shutdownService.stop(request);
    }

    public String getMethodHelp(Method method) {
        var request = GetMethodHelpRequest.newBuilder().setMethodName(method.name()).build();
        return grpcStubs.helpService.getMethodHelp(request).getMethodHelp();
    }
}
