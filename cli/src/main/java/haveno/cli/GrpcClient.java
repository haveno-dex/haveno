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

package haveno.cli;

import haveno.cli.request.OffersServiceRequest;
import haveno.cli.request.PaymentAccountsServiceRequest;
import haveno.cli.request.TradesServiceRequest;
import haveno.cli.request.WalletsServiceRequest;
import haveno.cli.request.AccountServiceRequest;
import haveno.cli.request.DisputesServiceRequest;
import haveno.cli.request.XmrConnectionsServiceRequest;
import haveno.cli.request.XmrNodeServiceRequest;
import protobuf.Dispute;
import protobuf.DisputeResult;
import haveno.proto.grpc.AddressBalanceInfo;
import haveno.proto.grpc.BalancesInfo;
import haveno.proto.grpc.GetMethodHelpRequest;
import haveno.proto.grpc.GetTradesRequest;
import haveno.proto.grpc.GetVersionRequest;
import haveno.proto.grpc.OfferInfo;
import haveno.proto.grpc.RegisterDisputeAgentRequest;
import haveno.proto.grpc.StopRequest;
import haveno.proto.grpc.TradeInfo;
import haveno.proto.grpc.XmrBalanceInfo;
import haveno.proto.grpc.XmrTx;
import haveno.proto.grpc.XmrDestination;

import lombok.extern.slf4j.Slf4j;
import protobuf.ChatMessage;
import protobuf.PaymentAccount;
import protobuf.PaymentMethod;
import haveno.proto.grpc.UrlConnection;
import protobuf.XmrNodeSettings;

import java.util.List;

@SuppressWarnings("ResultOfMethodCallIgnored")
@Slf4j
public final class GrpcClient {

    private final GrpcStubs grpcStubs;
    private final OffersServiceRequest offersServiceRequest;
    private final TradesServiceRequest tradesServiceRequest;
    private final WalletsServiceRequest walletsServiceRequest;
    private final PaymentAccountsServiceRequest paymentAccountsServiceRequest;
    private final AccountServiceRequest accountServiceRequest;
    private final DisputesServiceRequest disputesServiceRequest;
    private final XmrConnectionsServiceRequest xmrConnectionsServiceRequest;
    private final XmrNodeServiceRequest xmrNodeServiceRequest;

    /**
     * Constructor for GrpcClient.
     *
     * @param grpcStubs The gRPC stubs to use for communication with the server.
     */

    public GrpcClient(String apiHost, int apiPort, String apiPassword) {
        this.grpcStubs = new GrpcStubs(apiHost, apiPort, apiPassword);
        this.offersServiceRequest = new OffersServiceRequest(grpcStubs);
        this.tradesServiceRequest = new TradesServiceRequest(grpcStubs);
        this.walletsServiceRequest = new WalletsServiceRequest(grpcStubs);
        this.paymentAccountsServiceRequest = new PaymentAccountsServiceRequest(grpcStubs);
        this.accountServiceRequest = new AccountServiceRequest(grpcStubs);
        this.disputesServiceRequest = new DisputesServiceRequest(grpcStubs);
        this.xmrConnectionsServiceRequest = new XmrConnectionsServiceRequest(grpcStubs);
        this.xmrNodeServiceRequest = new XmrNodeServiceRequest(grpcStubs);
    }

    /**
     * Retrieves the version of the gRPC server.
     *
     * @return The version of the gRPC server.
     */
    public String getVersion() {
        GetVersionRequest request = GetVersionRequest.newBuilder().build();
        return grpcStubs.versionService.getVersion(request).getVersion();
    }

    /**
     * Retrieves the balance information for all currencies.
     *
     * @return A BalancesInfo object containing the balance information.
     */
    public BalancesInfo getBalances() {
        return walletsServiceRequest.getBalances();
    }

    /**
     * Retrieves the balance information for Monero (XMR).
     *
     * @return An XmrBalanceInfo object containing the balance information for Monero.
     */
    public XmrBalanceInfo getXmrBalances() {
        return walletsServiceRequest.getXmrBalances();
    }

    /**
     * Retrieves the balance information for a specific currency.
     *
     * @param currencyCode The currency code for which to retrieve the balance.
     * @return A BalancesInfo object containing the balance information.
     * @throws IllegalArgumentException if the currencyCode is null or empty.
     */
    public BalancesInfo getBalances(String currencyCode) {
        if (currencyCode == null || currencyCode.isEmpty()) {
            throw new IllegalArgumentException("Currency code cannot be null or empty.");
        }
        return walletsServiceRequest.getBalances(currencyCode);
    }

    /**
     * Retrieves the balance information for a specific address.
     *
     * @param address The address for which to retrieve the balance.
     * @return An AddressBalanceInfo object containing the balance information.
     * @throws IllegalArgumentException if the address is null or empty.
     */
    public AddressBalanceInfo getAddressBalance(String address) {
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("Address cannot be null or empty.");
        }
        return walletsServiceRequest.getAddressBalance(address);
    }

    /**
     * Retrieves the current price of Monero (XMR) in a specific currency.
     *
     * @param currencyCode The currency code for which to retrieve the price.
     * @return The price of Monero in the specified currency.
     * @throws IllegalArgumentException if the currencyCode is null or empty.
     */
    public double getXmrPrice(String currencyCode) {
        if (currencyCode == null || currencyCode.isEmpty()) {
            throw new IllegalArgumentException("Currency code cannot be null or empty.");
        }
        return walletsServiceRequest.getXmrPrice(currencyCode);
    }

    /**
     * Retrieves the list of funding addresses.
     *
     * @return A list of AddressBalanceInfo objects.
     */
    public List<AddressBalanceInfo> getFundingAddresses() {
        return walletsServiceRequest.getFundingAddresses();
    }

    /**
     * Retrieves an unused Monero (XMR) address.
     *
     * @return An unused Monero address.
     */
    public String getUnusedXmrAddress() {
        return walletsServiceRequest.getUnusedXmrAddress();
    }

    /**
     * Retrieves the Monero (XMR) seed.
     *
     * @return The Monero seed.
     */
    public String getXmrSeed() {
        return walletsServiceRequest.getXmrSeed();
    }

    /**
     * Retrieves the primary Monero (XMR) address.
     *
     * @return The primary Monero address.
     */
    public String getXmrPrimaryAddress() {
        return walletsServiceRequest.getXmrPrimaryAddress();
    }

    /**
     * Retrieves a new Monero (XMR) subaddress.
     *
     * @return A new Monero subaddress.
     */
    public String getXmrNewSubaddress() {
        return walletsServiceRequest.getXmrNewSubaddress();
    }

    /**
     * Retrieves the list of Monero (XMR) transactions.
     *
     * @return A list of XmrTx objects.
     */
    public List<XmrTx> getXmrTxs() {
        return walletsServiceRequest.getXmrTxs();
    }

    /**
     * Creates a new Monero (XMR) transaction.
     *
     * @param destinations The list of destinations for the transaction.
     * @return An XmrTx object representing the created transaction.
     * @throws IllegalArgumentException if the destinations list is null or empty.
     */
    public XmrTx createXmrTx(List<XmrDestination> destinations) {
        if (destinations == null || destinations.isEmpty()) {
            throw new IllegalArgumentException("Destinations list cannot be null or empty.");
        }
        return walletsServiceRequest.createXmrTx(destinations);
    }

    /**
     * Relays Monero (XMR) transactions.
     *
     * @param metadatas The list of transaction metadatas to relay.
     * @return A list of transaction hashes.
     * @throws IllegalArgumentException if the metadatas list is null or empty.
     */
    public List<String> relayXmrTxs(List<String> metadatas) {
        if (metadatas == null || metadatas.isEmpty()) {
            throw new IllegalArgumentException("Metadatas list cannot be null or empty.");
        }
        return walletsServiceRequest.relayXmrTxs(metadatas);
    }

    /**
     * Creates sweep transactions for Monero (XMR).
     *
     * @param address The address to sweep funds to.
     * @return A list of XmrTx objects representing the sweep transactions.
     * @throws IllegalArgumentException if the address is null or empty.
     */
    public List<XmrTx> createXmrSweepTxs(String address) {
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("Address cannot be null or empty.");
        }
        return walletsServiceRequest.createXmrSweepTxs(address);
    }

    /**
     * Creates a fixed-priced offer.
     *
     * @param direction The direction of the offer (BUY or SELL).
     * @param currencyCode The currency code for the offer.
     * @param amount The amount for the offer.
     * @param minAmount The minimum amount for the offer.
     * @param fixedPrice The fixed price for the offer.
     * @param securityDepositPct The security deposit percentage for the offer.
     * @param paymentAcctId The payment account ID for the offer.
     * @return An OfferInfo object representing the created offer.
     * @throws IllegalArgumentException if any of the parameters are invalid.
     */
    public OfferInfo createFixedPricedOffer(String direction, String currencyCode, long amount, long minAmount, String fixedPrice, double securityDepositPct, String paymentAcctId) {
        if (direction == null || direction.isEmpty()) {
            throw new IllegalArgumentException("Direction cannot be null or empty.");
        }
        if (currencyCode == null || currencyCode.isEmpty()) {
            throw new IllegalArgumentException("Currency code cannot be null or empty.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero.");
        }
        if (minAmount <= 0) {
            throw new IllegalArgumentException("Minimum amount must be greater than zero.");
        }
        if (fixedPrice == null || fixedPrice.isEmpty()) {
            throw new IllegalArgumentException("Fixed price cannot be null or empty.");
        }
        if (paymentAcctId == null || paymentAcctId.isEmpty()) {
            throw new IllegalArgumentException("Payment account ID cannot be null or empty.");
        }
        return offersServiceRequest.createFixedPricedOffer(direction, currencyCode, amount, minAmount, fixedPrice, securityDepositPct, paymentAcctId, "0");
    }

    /**
     * Creates a market-based priced offer.
     *
     * @param direction The direction of the offer (BUY or SELL).
     * @param currencyCode The currency code for the offer.
     * @param amount The amount for the offer.
     * @param minAmount The minimum amount for the offer.
     * @param marketPriceMarginPct The market price margin percentage for the offer.
     * @param securityDepositPct The security deposit percentage for the offer.
     * @param paymentAcctId The payment account ID for the offer.
     * @param triggerPrice The trigger price for the offer.
     * @return An OfferInfo object representing the created offer.
     * @throws IllegalArgumentException if any of the parameters are invalid.
     */
    public OfferInfo createMarketBasedPricedOffer(String direction, String currencyCode, long amount, long minAmount, double marketPriceMarginPct, double securityDepositPct, String paymentAcctId, String triggerPrice) {
        if (direction == null || direction.isEmpty()) {
            throw new IllegalArgumentException("Direction cannot be null or empty.");
        }
        if (currencyCode == null || currencyCode.isEmpty()) {
            throw new IllegalArgumentException("Currency code cannot be null or empty.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero.");
        }
        if (minAmount <= 0) {
            throw new IllegalArgumentException("Minimum amount must be greater than zero.");
        }
        if (paymentAcctId == null || paymentAcctId.isEmpty()) {
            throw new IllegalArgumentException("Payment account ID cannot be null or empty.");
        }
        return offersServiceRequest.createOffer(direction, currencyCode, amount, minAmount, true, "0", marketPriceMarginPct, securityDepositPct, paymentAcctId, triggerPrice, false, false, false, "", "");
    }

    /**
     * Creates an offer.
     *
     * @param direction The direction of the offer (BUY or SELL).
     * @param currencyCode The currency code for the offer.
     * @param amount The amount for the offer.
     * @param minAmount The minimum amount for the offer.
     * @param useMarketBasedPrice Whether to use a market-based price for the offer.
     * @param fixedPrice The fixed price for the offer.
     * @param marketPriceMarginPct The market price margin percentage for the offer.
     * @param securityDepositPct The security deposit percentage for the offer.
     * @param paymentAcctId The payment account ID for the offer.
     * @param triggerPrice The trigger price for the offer.
     * @return An OfferInfo object representing the created offer.
     * @throws IllegalArgumentException if any of the parameters are invalid.
     */
    public OfferInfo createOffer(String direction, String currencyCode, long amount, long minAmount, boolean useMarketBasedPrice, String fixedPrice, double marketPriceMarginPct, double securityDepositPct, String paymentAcctId, String triggerPrice) {
        if (direction == null || direction.isEmpty()) {
            throw new IllegalArgumentException("Direction cannot be null or empty.");
        }
        if (currencyCode == null || currencyCode.isEmpty()) {
            throw new IllegalArgumentException("Currency code cannot be null or empty.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero.");
        }
        if (minAmount <= 0) {
            throw new IllegalArgumentException("Minimum amount must be greater than zero.");
        }
        if (paymentAcctId == null || paymentAcctId.isEmpty()) {
            throw new IllegalArgumentException("Payment account ID cannot be null or empty.");
        }
        return offersServiceRequest.createOffer(direction, currencyCode, amount, minAmount, useMarketBasedPrice, fixedPrice, marketPriceMarginPct, securityDepositPct, paymentAcctId, triggerPrice, false, false, false, "", "");
    }

    /**
     * Cancels an offer.
     *
     * @param offerId The ID of the offer to cancel.
     * @throws IllegalArgumentException if the offerId is null or empty.
     */
    public void cancelOffer(String offerId) {
        if (offerId == null || offerId.isEmpty()) {
            throw new IllegalArgumentException("Offer ID cannot be null or empty.");
        }
        offersServiceRequest.cancelOffer(offerId);
    }

    /**
     * Retrieves an offer by its ID.
     *
     * @param offerId The ID of the offer to retrieve.
     * @return An OfferInfo object representing the retrieved offer.
     * @throws IllegalArgumentException if the offerId is null or empty.
     */
    public OfferInfo getOffer(String offerId) {
        if (offerId == null || offerId.isEmpty()) {
            throw new IllegalArgumentException("Offer ID cannot be null or empty.");
        }
        return offersServiceRequest.getOffer(offerId);
    }

    /**
     * Retrieves an offer by its ID (deprecated).
     *
     * @param offerId The ID of the offer to retrieve.
     * @return An OfferInfo object representing the retrieved offer.
     * @throws IllegalArgumentException if the offerId is null or empty.
     * @deprecated Use getOffer instead.
     */
    @Deprecated
    public OfferInfo getMyOffer(String offerId) {
        if (offerId == null || offerId.isEmpty()) {
            throw new IllegalArgumentException("Offer ID cannot be null or empty.");
        }
        return offersServiceRequest.getMyOffer(offerId);
    }

    /**
     * Retrieves a list of offers based on direction and currency code.
     *
     * @param direction The direction of the offers (BUY or SELL).
     * @param currencyCode The currency code for the offers.
     * @return A list of OfferInfo objects.
     * @throws IllegalArgumentException if the direction or currencyCode is null or empty.
     */
    public List<OfferInfo> getOffers(String direction, String currencyCode) {
        if (direction == null || direction.isEmpty()) {
            throw new IllegalArgumentException("Direction cannot be null or empty.");
        }
        if (currencyCode == null || currencyCode.isEmpty()) {
            throw new IllegalArgumentException("Currency code cannot be null or empty.");
        }
        return offersServiceRequest.getOffers(direction, currencyCode);
    }

    /**
     * Retrieves a list of offers sorted by date for a specific currency.
     *
     * @param currencyCode The currency code for the offers.
     * @return A list of OfferInfo objects sorted by date.
     * @throws IllegalArgumentException if the currencyCode is null or empty.
     */
    public List<OfferInfo> getOffersSortedByDate(String currencyCode) {
        if (currencyCode == null || currencyCode.isEmpty()) {
            throw new IllegalArgumentException("Currency code cannot be null or empty.");
        }
        return offersServiceRequest.getOffersSortedByDate(currencyCode);
    }

    /**
     * Retrieves a list of offers sorted by date based on direction and currency code.
     *
     * @param direction The direction of the offers (BUY or SELL).
     * @param currencyCode The currency code for the offers.
     * @return A list of OfferInfo objects sorted by date.
     * @throws IllegalArgumentException if the direction or currencyCode is null or empty.
     */
    public List<OfferInfo> getOffersSortedByDate(String direction, String currencyCode) {
        if (direction == null || direction.isEmpty()) {
            throw new IllegalArgumentException("Direction cannot be null or empty.");
        }
        if (currencyCode == null || currencyCode.isEmpty()) {
            throw new IllegalArgumentException("Currency code cannot be null or empty.");
        }
        return offersServiceRequest.getOffersSortedByDate(direction, currencyCode);
    }

    /**
     * Retrieves a list of the user's offers based on direction and currency code.
     *
     * @param direction The direction of the offers (BUY or SELL).
     * @param currencyCode The currency code for the offers.
     * @return A list of OfferInfo objects.
     * @throws IllegalArgumentException if the direction or currencyCode is null or empty.
     */
    public List<OfferInfo> getMyOffers(String direction, String currencyCode) {
        if (direction == null || direction.isEmpty()) {
            throw new IllegalArgumentException("Direction cannot be null or empty.");
        }
        if (currencyCode == null || currencyCode.isEmpty()) {
            throw new IllegalArgumentException("Currency code cannot be null or empty.");
        }
        return offersServiceRequest.getMyOffers(direction, currencyCode);
    }

    /**
     * Retrieves a list of the user's offers sorted by date for a specific currency.
     *
     * @param currencyCode The currency code for the offers.
     * @return A list of OfferInfo objects sorted by date.
     * @throws IllegalArgumentException if the currencyCode is null or empty.
     */
    public List<OfferInfo> getMyOffersSortedByDate(String currencyCode) {
        if (currencyCode == null || currencyCode.isEmpty()) {
            throw new IllegalArgumentException("Currency code cannot be null or empty.");
        }
        return offersServiceRequest.getMyOffersSortedByDate(currencyCode);
    }

    /**
     * Retrieves a list of the user's offers sorted by date based on direction and currency code.
     *
     * @param direction The direction of the offers (BUY or SELL).
     * @param currencyCode The currency code for the offers.
     * @return A list of OfferInfo objects sorted by date.
     * @throws IllegalArgumentException if the direction or currencyCode is null or empty.
     */
    public List<OfferInfo> getMyOffersSortedByDate(String direction, String currencyCode) {
        if (direction == null || direction.isEmpty()) {
            throw new IllegalArgumentException("Direction cannot be null or empty.");
        }
        if (currencyCode == null || currencyCode.isEmpty()) {
            throw new IllegalArgumentException("Currency code cannot be null or empty.");
        }
        return offersServiceRequest.getMyOffersSortedByDate(direction, currencyCode);
    }

    /**
     * Takes an offer.
     *
     * @param offerId The ID of the offer to take.
     * @param paymentAccountId The payment account ID for the offer.
     * @return A TradeInfo object representing the trade created from the offer.
     * @throws IllegalArgumentException if the offerId or paymentAccountId is null or empty.
     */
    public TradeInfo takeOffer(String offerId, String paymentAccountId) {
        if (offerId == null || offerId.isEmpty()) {
            throw new IllegalArgumentException("Offer ID cannot be null or empty.");
        }
        if (paymentAccountId == null || paymentAccountId.isEmpty()) {
            throw new IllegalArgumentException("Payment account ID cannot be null or empty.");
        }
        return tradesServiceRequest.takeOffer(offerId, paymentAccountId, 0, "");
    }

    /**
     * Retrieves a trade by its ID.
     *
     * @param tradeId The ID of the trade to retrieve.
     * @return A TradeInfo object representing the retrieved trade.
     * @throws IllegalArgumentException if the tradeId is null or empty.
     */
    public TradeInfo getTrade(String tradeId) {
        if (tradeId == null || tradeId.isEmpty()) {
            throw new IllegalArgumentException("Trade ID cannot be null or empty.");
        }
        return tradesServiceRequest.getTrade(tradeId);
    }

    /**
     * Retrieves a list of open trades.
     *
     * @return A list of TradeInfo objects representing open trades.
     */
    public List<TradeInfo> getOpenTrades() {
        return tradesServiceRequest.getOpenTrades();
    }

    /**
     * Retrieves the trade history based on the category.
     *
     * @param category The category of trades to retrieve (OPEN, CLOSED, FAILED).
     * @return A list of TradeInfo objects representing the trade history.
     * @throws IllegalArgumentException if the category is null.
     */
    public List<TradeInfo> getTradeHistory(GetTradesRequest.Category category) {
        if (category == null) {
            throw new IllegalArgumentException("Category cannot be null.");
        }
        return tradesServiceRequest.getTradeHistory(category);
    }

    /**
     * Confirms that payment has been sent for a trade.
     *
     * @param tradeId The ID of the trade.
     * @throws IllegalArgumentException if the tradeId is null or empty.
     */
    public void confirmPaymentSent(String tradeId) {
        if (tradeId == null || tradeId.isEmpty()) {
            throw new IllegalArgumentException("Trade ID cannot be null or empty.");
        }
        tradesServiceRequest.confirmPaymentSent(tradeId);
    }

    /**
     * Confirms that payment has been received for a trade.
     *
     * @param tradeId The ID of the trade.
     * @throws IllegalArgumentException if the tradeId is null or empty.
     */
    public void confirmPaymentReceived(String tradeId) {
        if (tradeId == null || tradeId.isEmpty()) {
            throw new IllegalArgumentException("Trade ID cannot be null or empty.");
        }
        tradesServiceRequest.confirmPaymentReceived(tradeId);
    }

    /**
     * Withdraws funds from a trade.
     *
     * @param tradeId The ID of the trade.
     * @param address The address to withdraw funds to.
     * @param memo The memo for the withdrawal.
     * @throws IllegalArgumentException if the tradeId, address, or memo is null or empty.
     */
    public void withdrawFunds(String tradeId, String address, String memo) {
        if (tradeId == null || tradeId.isEmpty()) {
            throw new IllegalArgumentException("Trade ID cannot be null or empty.");
        }
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("Address cannot be null or empty.");
        }
        if (memo == null || memo.isEmpty()) {
            throw new IllegalArgumentException("Memo cannot be null or empty.");
        }
        tradesServiceRequest.withdrawFunds(tradeId, address, memo);
    }

    /**
     * Retrieves chat messages for a specific trade.
     *
     * @param tradeId The ID of the trade.
     * @return A list of ChatMessage objects.
     * @throws IllegalArgumentException if the tradeId is null or empty.
     */
    public List<ChatMessage> getChatMessages(String tradeId) {
        if (tradeId == null || tradeId.isEmpty()) {
            throw new IllegalArgumentException("Trade ID cannot be null or empty.");
        }
        return tradesServiceRequest.getChatMessages(tradeId);
    }

    /**
     * Sends a chat message for a specific trade.
     *
     * @param tradeId The ID of the trade.
     * @param message The message to send.
     * @throws IllegalArgumentException if the tradeId or message is null or empty.
     */
    public void sendChatMessage(String tradeId, String message) {
        if (tradeId == null || tradeId.isEmpty()) {
            throw new IllegalArgumentException("Trade ID cannot be null or empty.");
        }
        if (message == null || message.isEmpty()) {
            throw new IllegalArgumentException("Message cannot be null or empty.");
        }
        tradesServiceRequest.sendChatMessage(tradeId, message);
    }

    /**
     * Retrieves the list of payment methods.
     *
     * @return A list of PaymentMethod objects.
     */
    public List<PaymentMethod> getPaymentMethods() {
        return paymentAccountsServiceRequest.getPaymentMethods();
    }

    /**
     * Retrieves the payment account form as JSON for a specific payment method.
     *
     * @param paymentMethodId The ID of the payment method.
     * @return The payment account form as a JSON string.
     * @throws IllegalArgumentException if the paymentMethodId is null or empty.
     */
    public String getPaymentAcctFormAsJson(String paymentMethodId) {
        if (paymentMethodId == null || paymentMethodId.isEmpty()) {
            throw new IllegalArgumentException("Payment method ID cannot be null or empty.");
        }
        return paymentAccountsServiceRequest.getPaymentAcctFormAsJson(paymentMethodId);
    }

    /**
     * Creates a payment account from a JSON string.
     *
     * @param json The JSON string representing the payment account.
     * @return A PaymentAccount object representing the created payment account.
     * @throws IllegalArgumentException if the JSON string is null or empty.
     */
    public PaymentAccount createPaymentAccount(String json) {
        if (json == null || json.isEmpty()) {
            throw new IllegalArgumentException("JSON string cannot be null or empty.");
        }
        return paymentAccountsServiceRequest.createPaymentAccount(json);
    }

    /**
     * Retrieves the list of payment accounts.
     *
     * @return A list of PaymentAccount objects.
     */
    public List<PaymentAccount> getPaymentAccounts() {
        return paymentAccountsServiceRequest.getPaymentAccounts();
    }

    /**
     * Retrieves a payment account by its name.
     *
     * @param accountName The name of the payment account.
     * @return A PaymentAccount object representing the retrieved payment account.
     * @throws IllegalArgumentException if the accountName is null or empty.
     */
    public PaymentAccount getPaymentAccount(String accountName) {
        if (accountName == null || accountName.isEmpty()) {
            throw new IllegalArgumentException("Account name cannot be null or empty.");
        }
        return paymentAccountsServiceRequest.getPaymentAccount(accountName);
    }

    /**
     * Creates a cryptocurrency payment account.
     *
     * @param accountName The name of the payment account.
     * @param currencyCode The currency code for the payment account.
     * @param address The address for the payment account.
     * @param tradeInstant Whether the payment account supports instant trades.
     * @return A PaymentAccount object representing the created cryptocurrency payment account.
     * @throws IllegalArgumentException if any of the parameters are null or empty.
     */
    public PaymentAccount createCryptoCurrencyPaymentAccount(String accountName, String currencyCode, String address, boolean tradeInstant) {
        if (accountName == null || accountName.isEmpty()) {
            throw new IllegalArgumentException("Account name cannot be null or empty.");
        }
        if (currencyCode == null || currencyCode.isEmpty()) {
            throw new IllegalArgumentException("Currency code cannot be null or empty.");
        }
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("Address cannot be null or empty.");
        }
        return paymentAccountsServiceRequest.createCryptoCurrencyPaymentAccount(accountName, currencyCode, address, tradeInstant);
    }

    /**
     * Retrieves the list of cryptocurrency payment methods.
     *
     * @return A list of PaymentMethod objects.
     */
    public List<PaymentMethod> getCryptoPaymentMethods() {
        return paymentAccountsServiceRequest.getCryptoPaymentMethods();
    }

    /**
     * Locks the wallet.
     */
    public void lockWallet() {
        walletsServiceRequest.lockWallet();
    }

    /**
     * Unlocks the wallet.
     *
     * @param walletPassword The password for the wallet.
     * @param timeout The timeout for the unlock operation.
     * @throws IllegalArgumentException if the walletPassword is null or empty.
     */
    public void unlockWallet(String walletPassword, long timeout) {
        if (walletPassword == null || walletPassword.isEmpty()) {
            throw new IllegalArgumentException("Wallet password cannot be null or empty.");
        }
        walletsServiceRequest.unlockWallet(walletPassword, timeout);
    }

    /**
     * Removes the wallet password.
     *
     * @param walletPassword The current password for the wallet.
     * @throws IllegalArgumentException if the walletPassword is null or empty.
     */
    public void removeWalletPassword(String walletPassword) {
        if (walletPassword == null || walletPassword.isEmpty()) {
            throw new IllegalArgumentException("Wallet password cannot be null or empty.");
        }
        walletsServiceRequest.removeWalletPassword(walletPassword);
    }

    /**
     * Sets the wallet password.
     *
     * @param walletPassword The new password for the wallet.
     * @throws IllegalArgumentException if the walletPassword is null or empty.
     */
    public void setWalletPassword(String walletPassword) {
        if (walletPassword == null || walletPassword.isEmpty()) {
            throw new IllegalArgumentException("Wallet password cannot be null or empty.");
        }
        walletsServiceRequest.setWalletPassword(walletPassword);
    }

    /**
     * Sets the wallet password.
     *
     * @param oldWalletPassword The current password for the wallet.
     * @param newWalletPassword The new password for the wallet.
     * @throws IllegalArgumentException if the oldWalletPassword or newWalletPassword is null or empty.
     */
    public void setWalletPassword(String oldWalletPassword, String newWalletPassword) {
        if (oldWalletPassword == null || oldWalletPassword.isEmpty()) {
            throw new IllegalArgumentException("Old wallet password cannot be null or empty.");
        }
        if (newWalletPassword == null || newWalletPassword.isEmpty()) {
            throw new IllegalArgumentException("New wallet password cannot be null or empty.");
        }
        walletsServiceRequest.setWalletPassword(oldWalletPassword, newWalletPassword);
    }

    /**
     * Registers a dispute agent.
     *
     * @param disputeAgentType The type of the dispute agent.
     * @param registrationKey The registration key for the dispute agent.
     * @throws IllegalArgumentException if the disputeAgentType or registrationKey is null or empty.
     */
    public void registerDisputeAgent(String disputeAgentType, String registrationKey) {
        if (disputeAgentType == null || disputeAgentType.isEmpty()) {
            throw new IllegalArgumentException("Dispute agent type cannot be null or empty.");
        }
        if (registrationKey == null || registrationKey.isEmpty()) {
            throw new IllegalArgumentException("Registration key cannot be null or empty.");
        }
        RegisterDisputeAgentRequest request = RegisterDisputeAgentRequest.newBuilder()
                .setDisputeAgentType(disputeAgentType)
                .setRegistrationKey(registrationKey)
                .build();
        grpcStubs.disputeAgentsService.registerDisputeAgent(request);
    }

    /**
     * Stops the server.
     */
    public void stopServer() {
        StopRequest request = StopRequest.newBuilder().build();
        grpcStubs.shutdownService.stop(request);
    }

    /**
     * Retrieves the help information for a specific method.
     *
     * @param method The method for which to retrieve help information.
     * @return The help information for the specified method.
     * @throws IllegalArgumentException if the method is null.
     */
    public String getMethodHelp(Method method) {
        if (method == null) {
            throw new IllegalArgumentException("Method cannot be null.");
        }
        GetMethodHelpRequest request = GetMethodHelpRequest.newBuilder()
                .setMethodName(method.name())
                .build();
        return grpcStubs.helpService.getMethodHelp(request).getMethodHelp();
    }

    // Account methods
    public boolean accountExists() {
        return accountServiceRequest.accountExists();
    }

    public boolean isAccountOpen() {
        return accountServiceRequest.isAccountOpen();
    }

    public void createAccount(String password) {
        accountServiceRequest.createAccount(password);
    }

    public void openAccount(String password) {
        accountServiceRequest.openAccount(password);
    }

    public boolean isAppInitialized() {
        return accountServiceRequest.isAppInitialized();
    }

    public void changePassword(String oldPassword, String newPassword) {
        accountServiceRequest.changePassword(oldPassword,newPassword);
    }

    public void closeAccount() {
        accountServiceRequest.closeAccount();
    }

    public void deleteAccount() {
        accountServiceRequest.deleteAccount();
    }

    public void backupAccount() {
        accountServiceRequest.backupAccount();
    }

    public void restoreAccount(byte[] zipBytes, long offset, long totalLength, boolean hasMore) {
        accountServiceRequest.restoreAccount(zipBytes, offset, totalLength, hasMore);
    }

    // Dispute methods
    public Dispute getDispute(String tradeId) {
        if (tradeId == null || tradeId.isEmpty()) {
            throw new IllegalArgumentException("Trade ID cannot be null or empty.");
        }
        return disputesServiceRequest.getDispute(tradeId);
    }

    public List<Dispute> getDisputes() {
        return disputesServiceRequest.getDisputes();
    }

    public void openDispute(String tradeId) {
        if (tradeId == null || tradeId.isEmpty()) {
            throw new IllegalArgumentException("Trade ID cannot be null or empty.");
        }
        disputesServiceRequest.openDispute(tradeId);
    }

    public void resolveDispute(String tradeId, DisputeResult.Winner winner, DisputeResult.Reason reason, String summaryNotes, long customPayoutAmount) {
        if (tradeId == null || tradeId.isEmpty()) {
            throw new IllegalArgumentException("Trade ID cannot be null or empty.");
        }
        disputesServiceRequest.resolveDispute(tradeId, winner.name().toLowerCase(), reason.name().toLowerCase(), summaryNotes, customPayoutAmount);
    }

    public void sendDisputeChatMessage(String disputeId, String message) {
        if (disputeId == null || disputeId.isEmpty()) {
            throw new IllegalArgumentException("Dispute ID cannot be null or empty.");
        }
        if (message == null || message.isEmpty()) {
            throw new IllegalArgumentException("Message cannot be null or empty.");
        }
        disputesServiceRequest.sendDisputeChatMessage(disputeId, message);
    }

    // XMR Connections methods
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

    public void setConnection(String url, UrlConnection connection) {
        xmrConnectionsServiceRequest.setConnection(url, connection);
    }

    public UrlConnection checkConnection() {
        return xmrConnectionsServiceRequest.checkConnection();
    }

    public List<UrlConnection> checkConnections() {
        return xmrConnectionsServiceRequest.checkConnections();
    }

    public void startCheckingConnection(int refreshPeriod) {
        xmrConnectionsServiceRequest.startCheckingConnection(refreshPeriod);
    }

    public void stopCheckingConnection() {
        xmrConnectionsServiceRequest.stopCheckingConnection();
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

    // XMR Node methods
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

    /**
     * Sends XMR to a specified address.
     *
     * @param address The destination address.
     * @param amount The amount to send.
     * @param txFeeRate The transaction fee rate.
     * @param memo The memo for the transaction.
     */
    public void sendXmr(String address, long amount, String txFeeRate, String memo) {
        walletsServiceRequest.sendXmr(address, amount, txFeeRate, memo);
    }

    /**
     * Deletes a payment account.
     *
     * @param paymentAccountId The ID of the payment account to delete.
     */
    public void deletePaymentAccount(String paymentAccountId) {
        paymentAccountsServiceRequest.deletePaymentAccount(paymentAccountId);
    }

    /**
     * Completes a trade.
     *
     * @param tradeId The ID of the trade to complete.
     */
    public void completeTrade(String tradeId) {
        tradesServiceRequest.completeTrade(tradeId);
    }
}
