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

package bisq.core.api;

import bisq.core.api.model.AddressBalanceInfo;
import bisq.core.api.model.BalancesInfo;
import bisq.core.api.model.MarketDepthInfo;
import bisq.core.api.model.MarketPriceInfo;
import bisq.core.api.model.PaymentAccountForm;
import bisq.core.api.model.PaymentAccountFormField;
import bisq.core.app.AppStartupState;
import bisq.core.monetary.Price;
import bisq.core.offer.Offer;
import bisq.core.offer.OfferDirection;
import bisq.core.offer.OpenOffer;
import bisq.core.payment.PaymentAccount;
import bisq.core.payment.payload.PaymentMethod;
import bisq.core.support.dispute.Attachment;
import bisq.core.support.dispute.Dispute;
import bisq.core.support.dispute.DisputeResult;
import bisq.core.support.messages.ChatMessage;
import bisq.core.trade.Trade;
import bisq.core.trade.statistics.TradeStatistics3;
import bisq.core.trade.statistics.TradeStatisticsManager;
import bisq.core.xmr.MoneroNodeSettings;

import bisq.common.app.Version;
import bisq.common.config.Config;
import bisq.common.crypto.IncorrectPasswordException;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.FaultHandler;
import bisq.common.handlers.ResultHandler;

import bisq.proto.grpc.NotificationMessage;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.common.util.concurrent.FutureCallback;

import java.io.IOException;
import java.io.InputStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;


import monero.common.MoneroRpcConnection;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroTxWallet;

/**
 * Provides high level interface to functionality of core Bisq features.
 * E.g. useful for different APIs to access data of different domains of Bisq.
 */
@Singleton
@Slf4j
public class CoreApi {

    @Getter
    private final Config config;
    private final AppStartupState appStartupState;
    private final CoreAccountService coreAccountService;
    private final CoreDisputeAgentsService coreDisputeAgentsService;
    private final CoreDisputesService coreDisputeService;
    private final CoreHelpService coreHelpService;
    private final CoreOffersService coreOffersService;
    private final CorePaymentAccountsService paymentAccountsService;
    private final CorePriceService corePriceService;
    private final CoreTradesService coreTradesService;
    private final CoreWalletsService walletsService;
    private final TradeStatisticsManager tradeStatisticsManager;
    private final CoreNotificationService notificationService;
    private final CoreMoneroConnectionsService coreMoneroConnectionsService;
    private final CoreMoneroNodeService coreMoneroNodeService;

    @Inject
    public CoreApi(Config config,
                   AppStartupState appStartupState,
                   CoreAccountService coreAccountService,
                   CoreDisputeAgentsService coreDisputeAgentsService,
                   CoreDisputesService coreDisputeService,
                   CoreHelpService coreHelpService,
                   CoreOffersService coreOffersService,
                   CorePaymentAccountsService paymentAccountsService,
                   CorePriceService corePriceService,
                   CoreTradesService coreTradesService,
                   CoreWalletsService walletsService,
                   TradeStatisticsManager tradeStatisticsManager,
                   CoreNotificationService notificationService,
                   CoreMoneroConnectionsService coreMoneroConnectionsService,
                   CoreMoneroNodeService coreMoneroNodeService) {
        this.config = config;
        this.appStartupState = appStartupState;
        this.coreAccountService = coreAccountService;
        this.coreDisputeAgentsService = coreDisputeAgentsService;
        this.coreDisputeService = coreDisputeService;
        this.coreHelpService = coreHelpService;
        this.coreOffersService = coreOffersService;
        this.paymentAccountsService = paymentAccountsService;
        this.coreTradesService = coreTradesService;
        this.corePriceService = corePriceService;
        this.walletsService = walletsService;
        this.tradeStatisticsManager = tradeStatisticsManager;
        this.notificationService = notificationService;
        this.coreMoneroConnectionsService = coreMoneroConnectionsService;
        this.coreMoneroNodeService = coreMoneroNodeService;
    }

    @SuppressWarnings("SameReturnValue")
    public String getVersion() {
        return Version.VERSION;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Help
    ///////////////////////////////////////////////////////////////////////////////////////////

    public String getMethodHelp(String methodName) {
        return coreHelpService.getMethodHelp(methodName);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Account Service
    ///////////////////////////////////////////////////////////////////////////////////////////

    public boolean accountExists() {
        return coreAccountService.accountExists();
    }

    public boolean isAccountOpen() {
        return coreAccountService.isAccountOpen();
    }

    public void createAccount(String password) {
        coreAccountService.createAccount(password);
    }

    public void openAccount(String password) throws IncorrectPasswordException {
        coreAccountService.openAccount(password);
    }

    public boolean isAppInitialized() {
        return appStartupState.isApplicationFullyInitialized();
    }

    public void changePassword(String oldPassword, String newPassword) {
        coreAccountService.changePassword(oldPassword, newPassword);
    }

    public void closeAccount() {
        coreAccountService.closeAccount();
    }

    public void deleteAccount(Runnable onShutdown) {
        coreAccountService.deleteAccount(onShutdown);
    }

    public void backupAccount(int bufferSize, Consumer<InputStream> consume, Consumer<Exception> error) {
        coreAccountService.backupAccount(bufferSize, consume, error);
    }

    public void restoreAccount(InputStream zipStream, int bufferSize, Runnable onShutdown) throws Exception {
        coreAccountService.restoreAccount(zipStream, bufferSize, onShutdown);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Monero Connections
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void addMoneroConnection(MoneroRpcConnection connection) {
        coreMoneroConnectionsService.addConnection(connection);
    }

    public void removeMoneroConnection(String connectionUri) {
        coreMoneroConnectionsService.removeConnection(connectionUri);
    }

    public MoneroRpcConnection getMoneroConnection() {
        return coreMoneroConnectionsService.getConnection();
    }

    public List<MoneroRpcConnection> getMoneroConnections() {
        return coreMoneroConnectionsService.getConnections();
    }

    public void setMoneroConnection(String connectionUri) {
        coreMoneroConnectionsService.setConnection(connectionUri);
    }

    public void setMoneroConnection(MoneroRpcConnection connection) {
        coreMoneroConnectionsService.setConnection(connection);
    }

    public MoneroRpcConnection checkMoneroConnection() {
        return coreMoneroConnectionsService.checkConnection();
    }

    public List<MoneroRpcConnection> checkMoneroConnections() {
        return coreMoneroConnectionsService.checkConnections();
    }

    public void startCheckingMoneroConnection(Long refreshPeriod) {
        coreMoneroConnectionsService.startCheckingConnection(refreshPeriod);
    }

    public void stopCheckingMoneroConnection() {
        coreMoneroConnectionsService.stopCheckingConnection();
    }

    public MoneroRpcConnection getBestAvailableMoneroConnection() {
        return coreMoneroConnectionsService.getBestAvailableConnection();
    }

    public void setMoneroConnectionAutoSwitch(boolean autoSwitch) {
        coreMoneroConnectionsService.setAutoSwitch(autoSwitch);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Monero node
    ///////////////////////////////////////////////////////////////////////////////////////////

    public boolean isMoneroNodeOnline() {
        return coreMoneroNodeService.isOnline();
    }

    public MoneroNodeSettings getMoneroNodeSettings() {
        return coreMoneroNodeService.getMoneroNodeSettings();
    }

    public void startMoneroNode(MoneroNodeSettings settings) throws IOException {
        coreMoneroNodeService.startMoneroNode(settings);
    }

    public void stopMoneroNode() {
        coreMoneroNodeService.stopMoneroNode();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Wallets
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BalancesInfo getBalances(String currencyCode) {
        return walletsService.getBalances(currencyCode);
    }

    public String getXmrSeed() {
        return walletsService.getXmrSeed();
    }

    public String getXmrPrimaryAddress() {
        return walletsService.getXmrPrimaryAddress();
    }

    public String getXmrNewSubaddress() {
        return walletsService.getXmrNewSubaddress();
    }

    public List<MoneroTxWallet> getXmrTxs() {
        return walletsService.getXmrTxs();
    }

    public MoneroTxWallet createXmrTx(List<MoneroDestination> destinations) {
        return walletsService.createXmrTx(destinations);
    }

    public String relayXmrTx(String metadata) {
        return walletsService.relayXmrTx(metadata);
    }

    public long getAddressBalance(String addressString) {
        return walletsService.getAddressBalance(addressString);
    }

    public AddressBalanceInfo getAddressBalanceInfo(String addressString) {
        return walletsService.getAddressBalanceInfo(addressString);
    }

    public List<AddressBalanceInfo> getFundingAddresses() {
        return walletsService.getFundingAddresses();
    }

    public void sendBtc(String address,
                        String amount,
                        String txFeeRate,
                        String memo,
                        FutureCallback<Transaction> callback) {
        walletsService.sendBtc(address, amount, txFeeRate, memo, callback);
    }

    public Transaction getTransaction(String txId) {
        return walletsService.getTransaction(txId);
    }

    public void setWalletPassword(String password, String newPassword) {
        walletsService.setWalletPassword(password, newPassword);
    }

    public void lockWallet() {
        walletsService.lockWallet();
    }

    public void unlockWallet(String password, long timeout) {
        walletsService.unlockWallet(password, timeout);
    }

    public void removeWalletPassword(String password) {
        walletsService.removeWalletPassword(password);
    }

    public List<TradeStatistics3> getTradeStatistics() {
        return new ArrayList<>(tradeStatisticsManager.getObservableTradeStatisticsSet());
    }

    public int getNumConfirmationsForMostRecentTransaction(String addressString) {
        return walletsService.getNumConfirmationsForMostRecentTransaction(addressString);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Notifications
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void addNotificationListener(NotificationListener listener) {
        notificationService.addListener(listener);
    }

    public void sendNotification(NotificationMessage notification) {
        notificationService.sendNotification(notification);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Disputes
    ///////////////////////////////////////////////////////////////////////////////////////////

    public List<Dispute> getDisputes() {
        return coreDisputeService.getDisputes();
    }

    public Dispute getDispute(String tradeId) {
        return coreDisputeService.getDispute(tradeId);
    }

    public void openDispute(String tradeId, ResultHandler resultHandler, FaultHandler faultHandler) {
        coreDisputeService.openDispute(tradeId, resultHandler, faultHandler);
    }

    public void resolveDispute(String tradeId, DisputeResult.Winner winner, DisputeResult.Reason reason,
                               String summaryNotes, long customPayoutAmount) {
        coreDisputeService.resolveDispute(tradeId, winner, reason, summaryNotes, customPayoutAmount);
    }

    public void sendDisputeChatMessage(String disputeId, String message, ArrayList<Attachment> attachments) {
        coreDisputeService.sendDisputeChatMessage(disputeId, message, attachments);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Dispute Agents
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void registerDisputeAgent(String disputeAgentType, String registrationKey, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        coreDisputeAgentsService.registerDisputeAgent(disputeAgentType, registrationKey, resultHandler, errorMessageHandler);
    }

    public void unregisterDisputeAgent(String disputeAgentType, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        coreDisputeAgentsService.unregisterDisputeAgent(disputeAgentType, resultHandler, errorMessageHandler);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Offers
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Offer getOffer(String id) {
        return coreOffersService.getOffer(id);
    }

    public Offer getMyOffer(String id) {
        return coreOffersService.getMyOffer(id);
    }

    public List<Offer> getOffers(String direction, String currencyCode) {
        return coreOffersService.getOffers(direction, currencyCode);
    }

    public List<Offer> getMyOffers(String direction, String currencyCode) {
        return coreOffersService.getMyOffers(direction, currencyCode);
    }

    public OpenOffer getMyOpenOffer(String id) {
        return coreOffersService.getMyOpenOffer(id);
    }

    public void postOffer(String currencyCode,
                                   String directionAsString,
                                   String priceAsString,
                                   boolean useMarketBasedPrice,
                                   double marketPriceMargin,
                                   long amountAsLong,
                                   long minAmountAsLong,
                                   double buyerSecurityDeposit,
                                   String triggerPriceAsString,
                                   String paymentAccountId,
                                   Consumer<Offer> resultHandler,
                                   ErrorMessageHandler errorMessageHandler) {
        coreOffersService.postOffer(currencyCode,
                directionAsString,
                priceAsString,
                useMarketBasedPrice,
                marketPriceMargin,
                amountAsLong,
                minAmountAsLong,
                buyerSecurityDeposit,
                triggerPriceAsString,
                paymentAccountId,
                resultHandler,
                errorMessageHandler);
    }

    public Offer editOffer(String offerId,
                           String currencyCode,
                           OfferDirection direction,
                           Price price,
                           boolean useMarketBasedPrice,
                           double marketPriceMargin,
                           Coin amount,
                           Coin minAmount,
                           double buyerSecurityDeposit,
                           PaymentAccount paymentAccount) {
        return coreOffersService.editOffer(offerId,
                currencyCode,
                direction,
                price,
                useMarketBasedPrice,
                marketPriceMargin,
                amount,
                minAmount,
                buyerSecurityDeposit,
                paymentAccount);
    }

    public void cancelOffer(String id) {
        coreOffersService.cancelOffer(id);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PaymentAccounts
    ///////////////////////////////////////////////////////////////////////////////////////////

    public PaymentAccount createPaymentAccount(PaymentAccountForm form) {
        return paymentAccountsService.createPaymentAccount(form);
    }

    public Set<PaymentAccount> getPaymentAccounts() {
        return paymentAccountsService.getPaymentAccounts();
    }

    public List<PaymentMethod> getPaymentMethods() {
        return paymentAccountsService.getPaymentMethods();
    }

    public PaymentAccountForm getPaymentAccountForm(String paymentMethodId) {
        return paymentAccountsService.getPaymentAccountForm(paymentMethodId);
    }

    public PaymentAccountForm getPaymentAccountForm(PaymentAccount paymentAccount) {
        return paymentAccountsService.getPaymentAccountForm(paymentAccount);
    }

    public PaymentAccount createCryptoCurrencyPaymentAccount(String accountName,
                                                             String currencyCode,
                                                             String address,
                                                             boolean tradeInstant) {
        return paymentAccountsService.createCryptoCurrencyPaymentAccount(accountName,
                currencyCode,
                address,
                tradeInstant);
    }

    public List<PaymentMethod> getCryptoCurrencyPaymentMethods() {
        return paymentAccountsService.getCryptoCurrencyPaymentMethods();
    }
    
    public void validateFormField(PaymentAccountForm form, PaymentAccountFormField.FieldId fieldId, String value) {
        paymentAccountsService.validateFormField(form, fieldId, value);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Prices
    ///////////////////////////////////////////////////////////////////////////////////////////

    public double getMarketPrice(String currencyCode) throws ExecutionException, InterruptedException, TimeoutException {
        return corePriceService.getMarketPrice(currencyCode);
    }

    public List<MarketPriceInfo> getMarketPrices() throws ExecutionException, InterruptedException, TimeoutException {
        return corePriceService.getMarketPrices();
    }

    public MarketDepthInfo getMarketDepth(String currencyCode) throws ExecutionException, InterruptedException, TimeoutException {
        return corePriceService.getMarketDepth(currencyCode);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Trades
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void takeOffer(String offerId,
                          String paymentAccountId,
                          Consumer<Trade> resultHandler,
                          ErrorMessageHandler errorMessageHandler) {
        Offer offer = coreOffersService.getOffer(offerId);
        coreTradesService.takeOffer(offer, paymentAccountId, resultHandler, errorMessageHandler);
    }

    public void confirmPaymentSent(String tradeId,
                                      ResultHandler resultHandler,
                                      ErrorMessageHandler errorMessageHandler) {
        coreTradesService.confirmPaymentSent(tradeId, resultHandler, errorMessageHandler);
    }

    public void confirmPaymentReceived(String tradeId,
                                       ResultHandler resultHandler,
                                       ErrorMessageHandler errorMessageHandler) {
        coreTradesService.confirmPaymentReceived(tradeId, resultHandler, errorMessageHandler);
    }

    public void closeTrade(String tradeId) {
        coreTradesService.closeTrade(tradeId);
    }

    public void withdrawFunds(String tradeId, String address, String memo) {
        coreTradesService.withdrawFunds(tradeId, address, memo);
    }

    public Trade getTrade(String tradeId) {
        return coreTradesService.getTrade(tradeId);
    }

    public List<Trade> getTrades() {
        return coreTradesService.getTrades();
    }

    public String getTradeRole(String tradeId) {
        return coreTradesService.getTradeRole(tradeId);
    }

    public List<ChatMessage> getChatMessages(String tradeId) {
        return coreTradesService.getChatMessages(tradeId);
    }

    public void sendChatMessage(String tradeId, String message) {
        coreTradesService.sendChatMessage(tradeId, message);
    }
}
