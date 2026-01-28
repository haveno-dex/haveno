/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

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

package haveno.core.api;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.common.app.Version;
import haveno.common.config.Config;
import haveno.common.crypto.IncorrectPasswordException;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.FaultHandler;
import haveno.common.handlers.ResultHandler;
import haveno.core.api.model.AddressBalanceInfo;
import haveno.core.api.model.BalancesInfo;
import haveno.core.api.model.MarketDepthInfo;
import haveno.core.api.model.MarketPriceInfo;
import haveno.core.api.model.PaymentAccountForm;
import haveno.core.api.model.PaymentAccountFormField;
import haveno.core.app.AppStartupState;
import haveno.core.offer.Offer;
import haveno.core.offer.OpenOffer;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.support.dispute.Attachment;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeResult;
import haveno.core.support.messages.ChatMessage;
import haveno.core.trade.Trade;
import haveno.core.trade.statistics.TradeStatistics3;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.xmr.XmrNodeSettings;
import haveno.proto.grpc.NotificationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroRpcConnection;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroTxWallet;
import org.bitcoinj.core.Transaction;

/**
 * Provides high level interface to functionality of core Haveno features.
 * E.g. useful for different APIs to access data of different domains of Haveno.
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
    private final XmrConnectionService xmrConnectionService;
    private final XmrLocalNode xmrLocalNode;

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
                   XmrConnectionService xmrConnectionService,
                   XmrLocalNode xmrLocalNode) {
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
        this.xmrConnectionService = xmrConnectionService;
        this.xmrLocalNode = xmrLocalNode;
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

    public void addXmrConnection(MoneroRpcConnection connection) {
        xmrConnectionService.addConnection(connection);
    }

    public void removeXmrConnection(String connectionUri) {
        xmrConnectionService.removeConnection(connectionUri);
    }

    public MoneroRpcConnection getXmrConnection() {
        return xmrConnectionService.getConnection();
    }

    public List<MoneroRpcConnection> getXmrConnections() {
        return xmrConnectionService.getConnections();
    }

    public void setXmrConnection(String connectionUri) {
        xmrConnectionService.setConnection(connectionUri);
    }

    public void setXmrConnection(MoneroRpcConnection connection) {
        xmrConnectionService.setConnection(connection);
    }

    public MoneroRpcConnection checkXmrConnection() {
        return xmrConnectionService.checkConnection();
    }

    public MoneroRpcConnection getBestXmrConnection() {
        return xmrConnectionService.getBestConnection();
    }

    public void setXmrConnectionAutoSwitch(boolean autoSwitch) {
        xmrConnectionService.setAutoSwitch(autoSwitch);
    }

    public boolean getXmrConnectionAutoSwitch() {
        return xmrConnectionService.getAutoSwitch();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Monero node
    ///////////////////////////////////////////////////////////////////////////////////////////

    public boolean isXmrNodeOnline() {
        return xmrLocalNode.isDetected();
    }

    public XmrNodeSettings getXmrNodeSettings() {
        return xmrLocalNode.getNodeSettings();
    }

    public void startXmrNode(XmrNodeSettings settings) throws IOException {
        xmrLocalNode.start(settings);
    }

    public void stopXmrNode() {
        xmrLocalNode.stop();
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

    public List<MoneroTxWallet> createXmrSweepTxs(String address) {
        return walletsService.createXmrSweepTxs(address);
    }

    public List<String> relayXmrTxs(List<String> metadatas) {
        return walletsService.relayXmrTxs(metadatas);
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
        return new ArrayList<>(tradeStatisticsManager.getObservableTradeStatisticsList());
    }

    public int getNumConfirmationsForMostRecentTransaction(String addressString) {
        return walletsService.getNumConfirmationsForMostRecentTransaction(addressString);
    }

    public long getHeight() {
        return walletsService.getHeight();
    }

    public long getTargetHeight() {
        return Objects.requireNonNullElse(xmrConnectionService.getTargetHeight(), 0L);
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

    public List<Offer> getOffers(String direction, String currencyCode) {
        return coreOffersService.getOffers(direction, currencyCode);
    }

    public List<OpenOffer> getMyOffers(String direction, String currencyCode) {
        return coreOffersService.getMyOffers(direction, currencyCode);
    }

    public OpenOffer getMyOffer(String id) {
        return coreOffersService.getMyOffer(id);
    }

    public void postOffer(String currencyCode,
                            String directionAsString,
                            String priceAsString,
                            boolean useMarketBasedPrice,
                            double marketPriceMarginPct,
                            long amountAsLong,
                            long minAmountAsLong,
                            double securityDepositPct,
                            String triggerPriceAsString,
                            boolean reserveExactAmount,
                            String paymentAccountId,
                            boolean isPrivateOffer,
                            boolean buyerAsTakerWithoutDeposit,
                            String extraInfo,
                            String sourceOfferId,
                            Consumer<Offer> resultHandler,
                            ErrorMessageHandler errorMessageHandler) {
        coreOffersService.postOffer(currencyCode,
                directionAsString,
                priceAsString,
                useMarketBasedPrice,
                marketPriceMarginPct,
                amountAsLong,
                minAmountAsLong,
                securityDepositPct,
                triggerPriceAsString,
                reserveExactAmount,
                paymentAccountId,
                isPrivateOffer,
                buyerAsTakerWithoutDeposit,
                extraInfo,
                sourceOfferId,
                resultHandler,
                errorMessageHandler);
    }

    public void editOffer(String offerId,
                          String currencyCode,
                          String priceAsString,
                          boolean useMarketBasedPrice,
                          double marketPriceMarginPct,
                          String triggerPriceAsString,
                          String paymentAccountId,
                          String extraInfo,
                          Consumer<Offer> resultHandler,
                          ErrorMessageHandler errorMessageHandler) {
        coreOffersService.editOffer(offerId,
                currencyCode,
                priceAsString,
                useMarketBasedPrice,
                marketPriceMarginPct,
                triggerPriceAsString,
                paymentAccountId,
                extraInfo,
                resultHandler,
                errorMessageHandler);
    }

    public void deactivateOffer(String offerId, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        coreOffersService.deactivateOffer(offerId, resultHandler, errorMessageHandler);
    }

    public void activateOffer(String offerId, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        coreOffersService.activateOffer(offerId, resultHandler, errorMessageHandler);
    }

    public void cancelOffer(String id, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        coreOffersService.cancelOffer(id, resultHandler, errorMessageHandler);
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

    public void deletePaymentAccount(String paymentAccountId) {
        paymentAccountsService.deletePaymentAccount(paymentAccountId);
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
                          long amountAsLong,
                          String challenge,
                          Consumer<Trade> resultHandler,
                          ErrorMessageHandler errorMessageHandler) {
        Offer offer = coreOffersService.getOffer(offerId);
        offer.setChallenge(challenge);
        coreTradesService.takeOffer(offer, paymentAccountId, amountAsLong, resultHandler, errorMessageHandler);
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

    public Trade getTrade(String tradeId) {
        return coreTradesService.getTrade(tradeId);
    }

    public List<Trade> getTrades() {
        return coreTradesService.getTrades();
    }

    public List<ChatMessage> getChatMessages(String tradeId) {
        return coreTradesService.getChatMessages(tradeId);
    }

    public void sendChatMessage(String tradeId, String message) {
        coreTradesService.sendChatMessage(tradeId, message);
    }
}
