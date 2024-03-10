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
import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferUtil;
import haveno.core.offer.takeoffer.TakeOfferModel;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.support.messages.ChatMessage;
import haveno.core.support.traderchat.TradeChatSession;
import haveno.core.support.traderchat.TraderChatManager;
import haveno.core.trade.ClosedTradableManager;
import haveno.core.trade.Tradable;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.trade.TradeUtil;
import haveno.core.trade.protocol.BuyerProtocol;
import haveno.core.trade.protocol.SellerProtocol;
import haveno.core.user.User;
import haveno.core.util.coin.CoinUtil;
import haveno.core.util.validation.BtcAddressValidator;
import haveno.core.xmr.model.AddressEntry;
import static haveno.core.xmr.model.AddressEntry.Context.TRADE_PAYOUT;
import haveno.core.xmr.wallet.BtcWalletService;
import static java.lang.String.format;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Coin;

@Singleton
@Slf4j
class CoreTradesService {

    private final CoreContext coreContext;
    // Dependencies on core api services in this package must be kept to an absolute
    // minimum, but some trading functions require an unlocked wallet's key, so an
    // exception is made in this case.
    private final CoreWalletsService coreWalletsService;
    private final BtcWalletService btcWalletService;
    private final ClosedTradableManager closedTradableManager;
    private final TakeOfferModel takeOfferModel;
    private final TradeManager tradeManager;
    private final TraderChatManager traderChatManager;
    private final TradeUtil tradeUtil;
    private final OfferUtil offerUtil;
    private final User user;

    @Inject
    public CoreTradesService(CoreContext coreContext,
                             CoreWalletsService coreWalletsService,
                             BtcWalletService btcWalletService,
                             ClosedTradableManager closedTradableManager,
                             TakeOfferModel takeOfferModel,
                             TradeManager tradeManager,
                             TraderChatManager traderChatManager,
                             TradeUtil tradeUtil,
                             OfferUtil offerUtil,
                             User user) {
        this.coreContext = coreContext;
        this.coreWalletsService = coreWalletsService;
        this.btcWalletService = btcWalletService;
        this.closedTradableManager = closedTradableManager;
        this.takeOfferModel = takeOfferModel;
        this.tradeManager = tradeManager;
        this.traderChatManager = traderChatManager;
        this.tradeUtil = tradeUtil;
        this.offerUtil = offerUtil;
        this.user = user;
    }

    void takeOffer(Offer offer,
                   String paymentAccountId,
                   long amountAsLong,
                   Consumer<Trade> resultHandler,
                   ErrorMessageHandler errorMessageHandler) {
        try {
            coreWalletsService.verifyWalletsAreAvailable();
            coreWalletsService.verifyEncryptedWalletIsUnlocked();

            var paymentAccount = user.getPaymentAccount(paymentAccountId);
            if (paymentAccount == null)
                throw new IllegalArgumentException(format("payment account with id '%s' not found", paymentAccountId));

            var useSavingsWallet = true;

            // default to offer amount
            BigInteger amount = amountAsLong == 0 ? offer.getAmount() : BigInteger.valueOf(amountAsLong);

            // adjust amount for fixed-price offer (based on TakeOfferViewModel)
            String currencyCode = offer.getCurrencyCode();
            OfferDirection direction = offer.getOfferPayload().getDirection();
            long maxTradeLimit = offerUtil.getMaxTradeLimit(paymentAccount, currencyCode, direction);
            if (offer.getPrice() != null) {
                if (PaymentMethod.isRoundedForAtmCash(paymentAccount.getPaymentMethod().getId())) {
                    amount = CoinUtil.getRoundedAtmCashAmount(amount, offer.getPrice(), maxTradeLimit);
                } else if (offer.isTraditionalOffer()
                        && !amount.equals(offer.getMinAmount()) && !amount.equals(amount)) {
                    // We only apply the rounding if the amount is variable (minAmount is lower as amount).
                    // Otherwise we could get an amount lower then the minAmount set by rounding
                    amount = CoinUtil.getRoundedAmount(amount, offer.getPrice(), maxTradeLimit, offer.getCurrencyCode(), offer.getPaymentMethodId());
                }
            }

            // synchronize access to take offer model // TODO (woodser): to avoid synchronizing, don't use stateful model
            BigInteger takerFee;
            BigInteger fundsNeededForTrade;
            synchronized (takeOfferModel) {
                takeOfferModel.initModel(offer, paymentAccount, amount, useSavingsWallet);
                takerFee = takeOfferModel.getTakerFee();
                fundsNeededForTrade = takeOfferModel.getFundsNeededForTrade();
                log.debug("Initiating take {} offer, {}", offer.isBuyOffer() ? "buy" : "sell", takeOfferModel);
            }

            // take offer
            tradeManager.onTakeOffer(amount,
                    takerFee,
                    fundsNeededForTrade,
                    offer,
                    paymentAccountId,
                    useSavingsWallet,
                    coreContext.isApiUser(),
                    resultHandler::accept,
                    errorMessageHandler
            );
        } catch (Exception e) {
            e.printStackTrace();
            errorMessageHandler.handleErrorMessage(e.getMessage());
        }
    }

    void confirmPaymentSent(String tradeId,
                               ResultHandler resultHandler,
                               ErrorMessageHandler errorMessageHandler) {
        var trade = getTrade(tradeId);
        if (isFollowingBuyerProtocol(trade)) {
            var tradeProtocol = tradeManager.getTradeProtocol(trade);
            ((BuyerProtocol) tradeProtocol).onPaymentSent(resultHandler, errorMessageHandler);
        } else {
            throw new IllegalStateException("you are the seller and not sending payment");
        }
    }

    void confirmPaymentReceived(String tradeId,
                                ResultHandler resultHandler,
                                ErrorMessageHandler errorMessageHandler) {
        var trade = getTrade(tradeId);
        if (isFollowingBuyerProtocol(trade)) {
            throw new IllegalStateException("you are the buyer, and not receiving payment");
        } else {
            var tradeProtocol = tradeManager.getTradeProtocol(trade);
            ((SellerProtocol) tradeProtocol).onPaymentReceived(resultHandler, errorMessageHandler);
        }
    }

    void closeTrade(String tradeId) {
        coreWalletsService.verifyWalletsAreAvailable();
        coreWalletsService.verifyEncryptedWalletIsUnlocked();

        verifyTradeIsNotClosed(tradeId);
        var trade = getOpenTrade(tradeId).orElseThrow(() ->
                new IllegalArgumentException(format("trade with id '%s' not found", tradeId)));
        log.info("Keeping funds received from trade {}", tradeId);
        tradeManager.onTradeCompleted(trade);
    }

    String getTradeRole(String tradeId) {
        coreWalletsService.verifyWalletsAreAvailable();
        coreWalletsService.verifyEncryptedWalletIsUnlocked();
        return tradeUtil.getRole(getTrade(tradeId));
    }

    Trade getTrade(String tradeId) {
        coreWalletsService.verifyWalletsAreAvailable();
        coreWalletsService.verifyEncryptedWalletIsUnlocked();
        return getOpenTrade(tradeId).orElseGet(() ->
                getClosedTrade(tradeId).orElseThrow(() ->
                        new IllegalArgumentException(format("trade with id '%s' not found", tradeId))
                ));
    }

    private Optional<Trade> getOpenTrade(String tradeId) {
        return tradeManager.getOpenTrade(tradeId);
    }

    private Optional<Trade> getClosedTrade(String tradeId) {
        Optional<Tradable> tradable = closedTradableManager.getTradeById(tradeId);
        return tradable.filter((t) -> t instanceof Trade).map(value -> (Trade) value);
    }

    List<Trade> getTrades() {
        coreWalletsService.verifyWalletsAreAvailable();
        coreWalletsService.verifyEncryptedWalletIsUnlocked();
        List<Trade> trades = new ArrayList<Trade>(tradeManager.getOpenTrades());
        trades.addAll(closedTradableManager.getClosedTrades());
        return trades;
    }

    List<ChatMessage> getChatMessages(String tradeId) {
        Trade trade;
        var tradeOptional = tradeManager.getOpenTrade(tradeId);
        if (tradeOptional.isPresent()) trade = tradeOptional.get();
        else throw new IllegalStateException(format("trade with id '%s' not found", tradeId));
        boolean isMaker = tradeManager.isMyOffer(trade.getOffer());
        TradeChatSession tradeChatSession = new TradeChatSession(trade, !isMaker);
        return tradeChatSession.getObservableChatMessageList();
    }

    void sendChatMessage(String tradeId, String message) {
        Trade trade;
        var tradeOptional = tradeManager.getOpenTrade(tradeId);
        if (tradeOptional.isPresent()) trade = tradeOptional.get();
        else throw new IllegalStateException(format("trade with id '%s' not found", tradeId));
        boolean isMaker = tradeManager.isMyOffer(trade.getOffer());
        TradeChatSession tradeChatSession = new TradeChatSession(trade, !isMaker);
        ChatMessage chatMessage = new ChatMessage(
                traderChatManager.getSupportType(),
                tradeChatSession.getTradeId(),
                tradeChatSession.getClientId(),
                tradeChatSession.isClient(),
                message,
                traderChatManager.getMyAddress());
        traderChatManager.addAndPersistChatMessage(chatMessage);
        traderChatManager.sendChatMessage(chatMessage);
    }

    private boolean isFollowingBuyerProtocol(Trade trade) {
        return tradeManager.getTradeProtocol(trade) instanceof BuyerProtocol;
    }

    private Coin getEstimatedTxFee(String fromAddress, String toAddress, Coin amount) {
        // TODO This and identical logic should be refactored into TradeUtil.
        try {
            return btcWalletService.getFeeEstimationTransaction(fromAddress,
                    toAddress,
                    amount,
                    TRADE_PAYOUT).getFee();
        } catch (Exception ex) {
            log.error("", ex);
            throw new IllegalStateException(format("could not estimate tx fee: %s", ex.getMessage()));
        }
    }

    // Throws a RuntimeException trade is already closed.
    private void verifyTradeIsNotClosed(String tradeId) {
        if (getClosedTrade(tradeId).isPresent())
            throw new IllegalArgumentException(format("trade '%s' is already closed", tradeId));
    }

    // Throws a RuntimeException if address is not valid.
    private void verifyIsValidBTCAddress(String address) {
        try {
            new BtcAddressValidator().validate(address);
        } catch (Throwable t) {
            log.error("", t);
            throw new IllegalArgumentException(format("'%s' is not a valid btc address", address));
        }
    }

    // Throws a RuntimeException if address has a zero balance.
    private void verifyFundsNotWithdrawn(AddressEntry fromAddressEntry) {
        Coin fromAddressBalance = btcWalletService.getBalanceForAddress(fromAddressEntry.getAddress());
        if (fromAddressBalance.isZero())
            throw new IllegalStateException(format("funds already withdrawn from address '%s'",
                    fromAddressEntry.getAddressString()));
    }
}
