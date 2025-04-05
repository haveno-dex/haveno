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

package haveno.desktop.main.portfolio.editoffer;


import com.google.inject.Inject;
import com.google.inject.name.Named;

import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.TradeCurrency;
import haveno.core.offer.CreateOfferService;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferPayload;
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
import haveno.core.util.coin.CoinUtil;
import haveno.core.xmr.wallet.Restrictions;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.Navigation;
import haveno.desktop.main.offer.MutableOfferDataModel;
import haveno.network.p2p.P2PService;
import java.util.Optional;
import java.util.Set;

class EditOfferDataModel extends MutableOfferDataModel {

    private final CorePersistenceProtoResolver corePersistenceProtoResolver;
    private OpenOffer openOffer;
    private OpenOffer.State initialState;
    private Offer editedOffer;

    @Inject
    EditOfferDataModel(CreateOfferService createOfferService,
                       OpenOfferManager openOfferManager,
                       OfferUtil offerUtil,
                       XmrWalletService xmrWalletService,
                       Preferences preferences,
                       User user,
                       P2PService p2PService,
                       PriceFeedService priceFeedService,
                       AccountAgeWitnessService accountAgeWitnessService,
                       @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter btcFormatter,
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
                btcFormatter,
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
    }

    public void applyOpenOffer(OpenOffer openOffer) {
        this.openOffer = openOffer;

        Offer offer = openOffer.getOffer();
        direction = offer.getDirection();
        CurrencyUtil.getTradeCurrency(offer.getCurrencyCode())
                .ifPresent(c -> this.tradeCurrency = c);
        tradeCurrencyCode.set(offer.getCurrencyCode());

        this.initialState = openOffer.getState();
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
        
        // TODO: update for XMR to use percent as double?

        // If the security deposit got bounded because it was below the coin amount limit, it can be bigger
        // by percentage than the restriction. We can't determine the percentage originally entered at offer
        // creation, so just use the default value as it doesn't matter anyway.
        double securityDepositPercent = CoinUtil.getAsPercentPerXmr(offer.getMaxSellerSecurityDeposit(), offer.getAmount());
        if (securityDepositPercent > Restrictions.getMaxSecurityDepositAsPercent()
                && offer.getMaxSellerSecurityDeposit().equals(Restrictions.getMinSecurityDeposit()))
            securityDepositPct.set(Restrictions.getDefaultSecurityDepositAsPercent());
        else
            securityDepositPct.set(securityDepositPercent);

        allowAmountUpdate = false;

        triggerPrice = openOffer.getTriggerPrice();
        extraInfo.set(offer.getOfferExtraInfo());
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
    protected PaymentAccount getPreselectedPaymentAccount() {
        return paymentAccount;
    }

    public void populateData() {
        Offer offer = openOffer.getOffer();
        // Min amount need to be set before amount as if minAmount is null it would be set by amount
        setMinAmount(offer.getMinAmount());
        setAmount(offer.getAmount());
        setPrice(offer.getPrice());
        setVolume(offer.getVolume());
        setUseMarketBasedPrice(offer.isUseMarketBasedPrice());
        if (offer.isUseMarketBasedPrice()) {
            setMarketPriceMarginPct(offer.getMarketPriceMarginPct());
        }
        setTriggerPrice(openOffer.getTriggerPrice());
        setExtraInfo(offer.getOfferExtraInfo());
    }

    public void onStartEditOffer(ErrorMessageHandler errorMessageHandler) {
        openOfferManager.editOpenOfferStart(openOffer, () -> {
        }, errorMessageHandler);
    }

    public void onPublishOffer(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        // editedPayload is a merge of the original offerPayload and newOfferPayload
        // fields which are editable are merged in from newOfferPayload (such as payment account details)
        // fields which cannot change (most importantly BTC amount) are sourced from the original offerPayload
        final OfferPayload offerPayload = openOffer.getOffer().getOfferPayload();
        final OfferPayload newOfferPayload = createAndGetOffer().getOfferPayload();
        final OfferPayload editedPayload = new OfferPayload(offerPayload.getId(),
                offerPayload.getDate(),
                offerPayload.getOwnerNodeAddress(),
                offerPayload.getPubKeyRing(),
                offerPayload.getDirection(),
                newOfferPayload.getPrice(),
                newOfferPayload.getMarketPriceMarginPct(),
                newOfferPayload.isUseMarketBasedPrice(),
                offerPayload.getAmount(),
                offerPayload.getMinAmount(),
                offerPayload.getMakerFeePct(),
                offerPayload.getTakerFeePct(),
                offerPayload.getPenaltyFeePct(),
                offerPayload.getBuyerSecurityDepositPct(),
                offerPayload.getSellerSecurityDepositPct(),
                newOfferPayload.getBaseCurrencyCode(),
                newOfferPayload.getCounterCurrencyCode(),
                newOfferPayload.getPaymentMethodId(),
                newOfferPayload.getMakerPaymentAccountId(),
                newOfferPayload.getCountryCode(),
                newOfferPayload.getAcceptedCountryCodes(),
                newOfferPayload.getBankId(),
                newOfferPayload.getAcceptedBankIds(),
                offerPayload.getVersionNr(),
                offerPayload.getBlockHeightAtOfferCreation(),
                offerPayload.getMaxTradeLimit(),
                offerPayload.getMaxTradePeriod(),
                offerPayload.isUseAutoClose(),
                offerPayload.isUseReOpenAfterAutoClose(),
                offerPayload.getLowerClosePrice(),
                offerPayload.getUpperClosePrice(),
                offerPayload.isPrivateOffer(),
                offerPayload.getChallengeHash(),
                offerPayload.getExtraDataMap(),
                offerPayload.getProtocolVersion(),
                offerPayload.getArbitratorSigner(),
                offerPayload.getArbitratorSignature(),
                offerPayload.getReserveTxKeyImages(),
                newOfferPayload.getExtraInfo());

        editedOffer = new Offer(editedPayload);
        editedOffer.setPriceFeedService(priceFeedService);
        editedOffer.setState(Offer.State.AVAILABLE);

        openOfferManager.editOpenOfferPublish(editedOffer, triggerPrice, initialState, () -> {
            resultHandler.handleResult(); // process result before nullifying state
            openOffer = null;
            editedOffer = null;
        }, (errorMsg) -> {
            errorMessageHandler.handleErrorMessage(errorMsg);
        });
    }

    public void onCancelEditOffer(ErrorMessageHandler errorMessageHandler) {
        if (openOffer != null)
            openOfferManager.editOpenOfferCancel(openOffer, initialState, () -> {
            }, errorMessageHandler);
    }

    public boolean hasConflictingClone() {
        Optional<OpenOffer> editedOpenOffer = openOfferManager.getOpenOffer(openOffer.getId());
        if (!editedOpenOffer.isPresent()) {
            log.warn("Edited open offer is no longer present");
            return false;
        }
        return openOfferManager.hasConflictingClone(editedOpenOffer.get());
    }

    @Override
    protected Set<PaymentAccount> getUserPaymentAccounts() {
        throw new RuntimeException("Edit offer not supported with XMR");
    }
}
