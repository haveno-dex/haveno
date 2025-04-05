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

package haveno.desktop.main.portfolio.cloneoffer;


import haveno.desktop.Navigation;
import haveno.desktop.main.offer.MutableOfferDataModel;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.TradeCurrency;
import haveno.core.offer.CreateOfferService;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferUtil;
import haveno.core.offer.OpenOffer;
import haveno.core.offer.OpenOfferManager;
import haveno.core.payment.PaymentAccount;
import haveno.core.proto.persistable.CorePersistenceProtoResolver;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.p2p.P2PService;

import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;

import com.google.inject.Inject;
import com.google.inject.name.Named;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

class CloneOfferDataModel extends MutableOfferDataModel {

    private final CorePersistenceProtoResolver corePersistenceProtoResolver;
    private OpenOffer sourceOpenOffer;

    @Inject
    CloneOfferDataModel(CreateOfferService createOfferService,
                        OpenOfferManager openOfferManager,
                        OfferUtil offerUtil,
                        XmrWalletService xmrWalletService,
                        Preferences preferences,
                        User user,
                        P2PService p2PService,
                        PriceFeedService priceFeedService,
                        AccountAgeWitnessService accountAgeWitnessService,
                        @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter xmrFormatter,
                        CorePersistenceProtoResolver corePersistenceProtoResolver,
                        TradeStatisticsManager tradeStatisticsManager,
                        Navigation navigation) {

        super(createOfferService,
                openOfferManager,
                offerUtil,
                xmrWalletService,
                preferences,
                user,
                p2PService,
                priceFeedService,
                accountAgeWitnessService,
                xmrFormatter,
                tradeStatisticsManager,
                navigation);
        this.corePersistenceProtoResolver = corePersistenceProtoResolver;
    }

    public void reset() {
        direction = null;
        tradeCurrency = null;
        tradeCurrencyCode.set(null);
        useMarketBasedPrice.set(false);
        amount.set(null);
        minAmount.set(null);
        price.set(null);
        volume.set(null);
        minVolume.set(null);
        securityDepositPct.set(0);
        paymentAccounts.clear();
        paymentAccount = null;
        marketPriceMarginPct = 0;
        sourceOpenOffer = null;
    }

    public void applyOpenOffer(OpenOffer openOffer) {
        this.sourceOpenOffer = openOffer;

        Offer offer = openOffer.getOffer();
        direction = offer.getDirection();
        CurrencyUtil.getTradeCurrency(offer.getCurrencyCode())
                .ifPresent(c -> this.tradeCurrency = c);
        tradeCurrencyCode.set(offer.getCurrencyCode());

        PaymentAccount tmpPaymentAccount = user.getPaymentAccount(openOffer.getOffer().getMakerPaymentAccountId());
        Optional<TradeCurrency> optionalTradeCurrency = CurrencyUtil.getTradeCurrency(openOffer.getOffer().getCurrencyCode());
        if (optionalTradeCurrency.isPresent() && tmpPaymentAccount != null) {
            TradeCurrency selectedTradeCurrency = optionalTradeCurrency.get();
            this.paymentAccount = PaymentAccount.fromProto(tmpPaymentAccount.toProtoMessage(), corePersistenceProtoResolver);
            if (paymentAccount.getSingleTradeCurrency() != null)
                paymentAccount.setSingleTradeCurrency(selectedTradeCurrency);
            else
                paymentAccount.setSelectedTradeCurrency(selectedTradeCurrency);
        }

        allowAmountUpdate = false;
    }

    public boolean initWithData(OfferDirection direction, TradeCurrency tradeCurrency) {
        try {
            return super.initWithData(direction, tradeCurrency, false);
        } catch (NullPointerException e) {
            if (e.getMessage().contains("tradeCurrency")) {
                throw new IllegalArgumentException("Offers of removed assets cannot be edited. You can only cancel it.", e);
            }
            return false;
        }
    }

    @Override
    protected Set<PaymentAccount> getUserPaymentAccounts() {
        return Objects.requireNonNull(user.getPaymentAccounts()).stream()
                .filter(account -> !account.getPaymentMethod().isBsqSwap())
                .collect(Collectors.toSet());
    }

    @Override
    protected PaymentAccount getPreselectedPaymentAccount() {
        return paymentAccount;
    }

    public void populateData() {
        Offer offer = sourceOpenOffer.getOffer();
        // Min amount need to be set before amount as if minAmount is null it would be set by amount
        setMinAmount(offer.getMinAmount());
        setAmount(offer.getAmount());
        setPrice(offer.getPrice());
        setVolume(offer.getVolume());
        setUseMarketBasedPrice(offer.isUseMarketBasedPrice());
        setTriggerPrice(sourceOpenOffer.getTriggerPrice());
        if (offer.isUseMarketBasedPrice()) {
            setMarketPriceMarginPct(offer.getMarketPriceMarginPct());
        }
        setBuyerAsTakerWithoutDeposit(offer.hasBuyerAsTakerWithoutDeposit());
        setSecurityDepositPct(getSecurityAsPercent(offer));
        setExtraInfo(offer.getOfferExtraInfo());
    }

    public void onCloneOffer(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        Offer clonedOffer = createClonedOffer();
        openOfferManager.placeOffer(clonedOffer,
                false,
                triggerPrice,
                false,
                true,
                sourceOpenOffer.getId(),
                transaction -> resultHandler.handleResult(),
                errorMessageHandler);
    }

    private Offer createClonedOffer() {
        return createOfferService.createClonedOffer(sourceOpenOffer.getOffer(),
                tradeCurrencyCode.get(),
                useMarketBasedPrice.get() ? null : price.get(),
                useMarketBasedPrice.get(),
                useMarketBasedPrice.get() ? marketPriceMarginPct : 0,
                paymentAccount,
                extraInfo.get());
    }

    public boolean hasConflictingClone() {
        Offer clonedOffer = createClonedOffer();
        return openOfferManager.hasConflictingClone(clonedOffer, sourceOpenOffer);
    }
}