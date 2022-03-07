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
import bisq.core.api.model.TxFeeRateInfo;
import bisq.core.app.AppStartupState;
import bisq.core.monetary.Price;
import bisq.core.offer.Offer;
import bisq.core.offer.OfferPayload;
import bisq.core.offer.OpenOffer;
import bisq.core.payment.PaymentAccount;
import bisq.core.payment.payload.PaymentMethod;
import bisq.core.support.dispute.Attachment;
import bisq.core.support.dispute.Dispute;
import bisq.core.support.dispute.DisputeResult;
import bisq.core.trade.Trade;
import bisq.core.trade.statistics.TradeStatistics3;
import bisq.core.trade.statistics.TradeStatisticsManager;
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

import java.io.InputStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import lombok.Getter;
import lombok.NonNull;
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
                   CoreMoneroConnectionsService coreMoneroConnectionsService) {
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

    public void changePassword(String password) {
        coreAccountService.changePassword(password);
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
    // Wallets
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BalancesInfo getBalances(String currencyCode) {
        return walletsService.getBalances(currencyCode);
    }

    public String getNewDepositSubaddress() {
        return walletsService.getNewDepositSubaddress();
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


    public void getTxFeeRate(ResultHandler resultHandler) {
        walletsService.getTxFeeRate(resultHandler);
    }

    public void setTxFeeRatePreference(long txFeeRate,
                                       ResultHandler resultHandler) {
        walletsService.setTxFeeRatePreference(txFeeRate, resultHandler);
    }

    public void unsetTxFeeRatePreference(ResultHandler resultHandler) {
        walletsService.unsetTxFeeRatePreference(resultHandler);
    }

    public TxFeeRateInfo getMostRecentTxFeeRateInfo() {
        return walletsService.getMostRecentTxFeeRateInfo();
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

    public interface NotificationListener {
        void onMessage(@NonNull NotificationMessage message);
    }

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

    public void registerDisputeAgent(String disputeAgentType, String registrationKey) {
        coreDisputeAgentsService.registerDisputeAgent(disputeAgentType, registrationKey);
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

    public void createAnPlaceOffer(String currencyCode,
                                   String directionAsString,
                                   String priceAsString,
                                   boolean useMarketBasedPrice,
                                   double marketPriceMargin,
                                   long amountAsLong,
                                   long minAmountAsLong,
                                   double buyerSecurityDeposit,
                                   long triggerPrice,
                                   String paymentAccountId,
                                   Consumer<Offer> resultHandler) {
        coreOffersService.createAndPlaceOffer(currencyCode,
                directionAsString,
                priceAsString,
                useMarketBasedPrice,
                marketPriceMargin,
                amountAsLong,
                minAmountAsLong,
                buyerSecurityDeposit,
                triggerPrice,
                paymentAccountId,
                resultHandler);
    }

    public Offer editOffer(String offerId,
                           String currencyCode,
                           OfferPayload.Direction direction,
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

    public PaymentAccount createPaymentAccount(String jsonString) {
        return paymentAccountsService.createPaymentAccount(jsonString);
    }

    public Set<PaymentAccount> getPaymentAccounts() {
        return paymentAccountsService.getPaymentAccounts();
    }

    public List<PaymentMethod> getFiatPaymentMethods() {
        return paymentAccountsService.getFiatPaymentMethods();
    }

    public String getPaymentAccountForm(String paymentMethodId) {
        return paymentAccountsService.getPaymentAccountFormAsString(paymentMethodId);
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
        try {
            Offer offer = coreOffersService.getOffer(offerId);
            coreTradesService.takeOffer(offer,
                    paymentAccountId,
                    resultHandler,
                    errorMessageHandler);
        } catch (Exception e) {
            errorMessageHandler.handleErrorMessage(e.getMessage());
        }
    }

    public void confirmPaymentStarted(String tradeId) {
        coreTradesService.confirmPaymentStarted(tradeId);
    }

    public void confirmPaymentReceived(String tradeId) {
        coreTradesService.confirmPaymentReceived(tradeId);
    }

    public void keepFunds(String tradeId) {
        coreTradesService.keepFunds(tradeId);
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
}
