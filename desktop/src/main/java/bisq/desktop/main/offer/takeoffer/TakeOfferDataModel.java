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

package bisq.desktop.main.offer.takeoffer;

import bisq.desktop.Navigation;
import bisq.desktop.main.offer.OfferDataModel;
import bisq.desktop.main.offer.offerbook.OfferBook;
import bisq.desktop.main.overlays.popups.Popup;
import bisq.desktop.util.GUIUtil;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.core.account.witness.AccountAgeWitnessService;
import bisq.core.btc.listeners.XmrBalanceListener;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.wallet.Restrictions;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.filter.FilterManager;
import bisq.core.locale.CurrencyUtil;
import bisq.core.locale.Res;
import bisq.core.monetary.Price;
import bisq.core.monetary.Volume;
import bisq.core.offer.Offer;
import bisq.core.offer.OfferDirection;
import bisq.core.offer.OfferUtil;
import bisq.core.payment.PaymentAccount;
import bisq.core.payment.PaymentAccountUtil;
import bisq.core.payment.payload.PaymentMethod;
import bisq.core.provider.mempool.MempoolService;
import bisq.core.provider.price.PriceFeedService;
import bisq.core.trade.HavenoUtils;
import bisq.core.trade.TradeManager;
import bisq.core.trade.handlers.TradeResultHandler;
import bisq.core.user.Preferences;
import bisq.core.user.User;
import bisq.core.util.VolumeUtil;
import bisq.core.util.coin.CoinUtil;

import bisq.network.p2p.P2PService;

import org.bitcoinj.core.Coin;

import com.google.inject.Inject;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;

import javafx.collections.ObservableList;

import java.math.BigInteger;

import java.util.Set;

import lombok.Getter;

import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

import static bisq.core.payment.payload.PaymentMethod.HAL_CASH_ID;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Domain for that UI element.
 * Note that the create offer domain has a deeper scope in the application domain (TradeManager).
 * That model is just responsible for the domain specific parts displayed needed in that UI element.
 */
class TakeOfferDataModel extends OfferDataModel {
    private final TradeManager tradeManager;
    private final OfferBook offerBook;
    private final User user;
    private final MempoolService mempoolService;
    private final FilterManager filterManager;
    final Preferences preferences;
    private final PriceFeedService priceFeedService;
    private final AccountAgeWitnessService accountAgeWitnessService;
    private final Navigation navigation;
    private final P2PService p2PService;

    private Coin securityDeposit;

    private Offer offer;

    // final BooleanProperty isFeeFromFundingTxSufficient = new SimpleBooleanProperty();
    // final BooleanProperty isMainNet = new SimpleBooleanProperty();
    private final ObjectProperty<Coin> amount = new SimpleObjectProperty<>();
    final ObjectProperty<Volume> volume = new SimpleObjectProperty<>();

    private XmrBalanceListener balanceListener;
    private PaymentAccount paymentAccount;
    private boolean isTabSelected;
    Price tradePrice;
    @Getter
    protected final IntegerProperty mempoolStatus = new SimpleIntegerProperty();
    @Getter
    protected String mempoolStatusText;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////


    @Inject
    TakeOfferDataModel(TradeManager tradeManager,
                       OfferBook offerBook,
                       OfferUtil offerUtil,
                       XmrWalletService xmrWalletService,
                       User user,
                       MempoolService mempoolService,
                       FilterManager filterManager,
                       Preferences preferences,
                       PriceFeedService priceFeedService,
                       AccountAgeWitnessService accountAgeWitnessService,
                       Navigation navigation,
                       P2PService p2PService
    ) {
        super(xmrWalletService, offerUtil);

        this.tradeManager = tradeManager;
        this.offerBook = offerBook;
        this.user = user;
        this.mempoolService = mempoolService;
        this.filterManager = filterManager;
        this.preferences = preferences;
        this.priceFeedService = priceFeedService;
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.navigation = navigation;
        this.p2PService = p2PService;
    }

    @Override
    protected void activate() {
        // when leaving screen we reset state
        offer.setState(Offer.State.UNKNOWN);

        addListeners();

        updateBalance();

        // TODO In case that we have funded but restarted, or canceled but took again the offer we would need to
        // store locally the result when we received the funding tx(s).
        // For now we just ignore that rare case and bypass the check by setting a sufficient value
        // if (isWalletFunded.get())
        //     feeFromFundingTxProperty.set(FeePolicy.getMinRequiredFeeForFundingTx());

        if (isTabSelected)
            priceFeedService.setCurrencyCode(offer.getCurrencyCode());

        if (canTakeOffer()) {
            tradeManager.checkOfferAvailability(offer,
                    false,
                    paymentAccount.getId(),
                    () -> {
                    },
                    errorMessage -> new Popup().warning(errorMessage).show());
        }
    }

    @Override
    protected void deactivate() {
        removeListeners();
        if (offer != null) {
            offer.cancelAvailabilityRequest();
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    // called before activate
    void initWithData(Offer offer) {
        this.offer = offer;
        tradePrice = offer.getPrice();
        addressEntry = xmrWalletService.getOrCreateAddressEntry(offer.getId(), XmrAddressEntry.Context.OFFER_FUNDING);
        checkNotNull(addressEntry, "addressEntry must not be null");

        ObservableList<PaymentAccount> possiblePaymentAccounts = getPossiblePaymentAccounts();
        checkArgument(!possiblePaymentAccounts.isEmpty(), "possiblePaymentAccounts.isEmpty()");
        paymentAccount = getLastSelectedPaymentAccount();

        this.amount.set(Coin.valueOf(Math.min(offer.getAmount().value, getMaxTradeLimit())));

        securityDeposit = offer.getDirection() == OfferDirection.SELL ?
                getBuyerSecurityDeposit() :
                getSellerSecurityDeposit();

        mempoolStatus.setValue(-1);
        mempoolService.validateOfferMakerTx(offer.getOfferPayload(), (txValidator -> {
            mempoolStatus.setValue(txValidator.isFail() ? 0 : 1);
            if (txValidator.isFail()) {
                mempoolStatusText = txValidator.toString();
                log.info("Mempool check of OfferFeePaymentTxId returned errors: [{}]", mempoolStatusText);
            }
        }));

        calculateVolume();
        calculateTotalToPay();

        balanceListener = new XmrBalanceListener(addressEntry.getSubaddressIndex()) {
            @Override
            public void onBalanceChanged(BigInteger balance) {
                updateBalance();
            }
        };

        offer.resetState();

        priceFeedService.setCurrencyCode(offer.getCurrencyCode());
    }

    // We don't want that the fee gets updated anymore after we show the funding screen.
    void onShowPayFundsScreen() {
        calculateTotalToPay();
    }

    void onTabSelected(boolean isSelected) {
        this.isTabSelected = isSelected;
        if (isTabSelected)
            priceFeedService.setCurrencyCode(offer.getCurrencyCode());
    }

    public void onClose(boolean removeOffer) {
        // We do not wait until the offer got removed by a network remove message but remove it
        // directly from the offer book. The broadcast gets now bundled and has 2 sec. delay so the
        // removal from the network is a bit slower as it has been before. To avoid that the taker gets
        // confused to see the same offer still in the offerbook we remove it manually. This removal has
        // only local effect. Other trader might see the offer for a few seconds
        // still (but cannot take it).
        if (removeOffer) {
            offerBook.removeOffer(checkNotNull(offer));
        }

        xmrWalletService.resetAddressEntriesForOpenOffer(offer.getId());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI actions
    ///////////////////////////////////////////////////////////////////////////////////////////

    // errorMessageHandler is used only in the check availability phase. As soon we have a trade we write the error msg in the trade object as we want to
    // have it persisted as well.
    void onTakeOffer(TradeResultHandler tradeResultHandler, ErrorMessageHandler errorMessageHandler) {
        checkNotNull(getTakerFee(), "takerFee must not be null");

        Coin fundsNeededForTrade = getFundsNeededForTrade();
        if (isBuyOffer())
            fundsNeededForTrade = fundsNeededForTrade.add(amount.get());

        if (filterManager.isCurrencyBanned(offer.getCurrencyCode())) {
            new Popup().warning(Res.get("offerbook.warning.currencyBanned")).show();
        } else if (filterManager.isPaymentMethodBanned(offer.getPaymentMethod())) {
            new Popup().warning(Res.get("offerbook.warning.paymentMethodBanned")).show();
        } else if (filterManager.isOfferIdBanned(offer.getId())) {
            new Popup().warning(Res.get("offerbook.warning.offerBlocked")).show();
        } else if (filterManager.isNodeAddressBanned(offer.getMakerNodeAddress())) {
            new Popup().warning(Res.get("offerbook.warning.nodeBlocked")).show();
        } else if (filterManager.requireUpdateToNewVersionForTrading()) {
            new Popup().warning(Res.get("offerbook.warning.requireUpdateToNewVersion")).show();
        } else if (tradeManager.wasOfferAlreadyUsedInTrade(offer.getId())) {
            new Popup().warning(Res.get("offerbook.warning.offerWasAlreadyUsedInTrade")).show();
        } else {
            tradeManager.onTakeOffer(amount.get(),
                    getTakerFee(),
                    fundsNeededForTrade,
                    offer,
                    paymentAccount.getId(),
                    useSavingsWallet,
                    false,
                    tradeResultHandler,
                    errorMessage -> {
                        log.warn(errorMessage);
                        errorMessageHandler.handleErrorMessage(errorMessage);
                    }
            );
        }
    }

    public void onPaymentAccountSelected(PaymentAccount paymentAccount) {
        if (paymentAccount != null) {
            this.paymentAccount = paymentAccount;

            long myLimit = getMaxTradeLimit();
            this.amount.set(Coin.valueOf(Math.max(offer.getMinAmount().value, Math.min(amount.get().value, myLimit))));

            preferences.setTakeOfferSelectedPaymentAccountId(paymentAccount.getId());
        }
    }

    void fundFromSavingsWallet() {
        useSavingsWallet = true;
        updateBalance();
        if (!isBtcWalletFunded.get()) {
            this.useSavingsWallet = false;
            updateBalance();
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    OfferDirection getDirection() {
        return offer.getDirection();
    }

    public Offer getOffer() {
        return offer;
    }

    ObservableList<PaymentAccount> getPossiblePaymentAccounts() {
        Set<PaymentAccount> paymentAccounts = user.getPaymentAccounts();
        checkNotNull(paymentAccounts, "paymentAccounts must not be null");
        return PaymentAccountUtil.getPossiblePaymentAccounts(offer, paymentAccounts, accountAgeWitnessService);
    }

    public PaymentAccount getLastSelectedPaymentAccount() {
        ObservableList<PaymentAccount> possiblePaymentAccounts = getPossiblePaymentAccounts();
        checkArgument(!possiblePaymentAccounts.isEmpty(), "possiblePaymentAccounts must not be empty");
        PaymentAccount firstItem = possiblePaymentAccounts.get(0);

        String id = preferences.getTakeOfferSelectedPaymentAccountId();
        if (id == null)
            return firstItem;

        return possiblePaymentAccounts.stream()
                .filter(e -> e.getId().equals(id))
                .findAny()
                .orElse(firstItem);
    }

    long getMaxTradeLimit() {
        if (paymentAccount != null) {
            return accountAgeWitnessService.getMyTradeLimit(paymentAccount, getCurrencyCode(),
                    offer.getMirroredDirection());
        } else {
            return 0;
        }
    }

    boolean canTakeOffer() {
        return GUIUtil.canCreateOrTakeOfferOrShowPopup(user, navigation) &&
                GUIUtil.isBootstrappedOrShowPopup(p2PService);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Bindings, listeners
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void addListeners() {
        xmrWalletService.addBalanceListener(balanceListener);
    }

    private void removeListeners() {
        xmrWalletService.removeBalanceListener(balanceListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    void calculateVolume() {
        if (tradePrice != null && offer != null &&
                amount.get() != null &&
                !amount.get().isZero()) {
            Volume volumeByAmount = tradePrice.getVolumeByAmount(amount.get());
            if (offer.getPaymentMethod().getId().equals(PaymentMethod.HAL_CASH_ID))
                volumeByAmount = VolumeUtil.getAdjustedVolumeForHalCash(volumeByAmount);
            else if (offer.isFiatOffer())
                volumeByAmount = VolumeUtil.getRoundedFiatVolume(volumeByAmount);

            volume.set(volumeByAmount);

            updateBalance();
        }
    }

    void applyAmount(Coin amount) {
        this.amount.set(Coin.valueOf(Math.min(amount.value, getMaxTradeLimit())));

        calculateTotalToPay();
    }

    void calculateTotalToPay() {
        // Taker pays 2 times the tx fee because the mining fee might be different when maker created the offer
        // and reserved his funds, so that would not work well with dynamic fees.
        // The mining fee for the takeOfferFee tx is deducted from the createOfferFee and not visible to the trader
        final Coin takerFee = getTakerFee();
        if (offer != null && amount.get() != null && takerFee != null) {
            Coin feeAndSecDeposit = securityDeposit.add(takerFee);
            if (isBuyOffer())
                totalToPayAsCoin.set(feeAndSecDeposit.add(amount.get()));
            else
                totalToPayAsCoin.set(feeAndSecDeposit);
            updateBalance();
            log.debug("totalToPayAsCoin {}", totalToPayAsCoin.get().toFriendlyString());
        }
    }

    boolean isBuyOffer() {
        return getDirection() == OfferDirection.BUY;
    }

    boolean isSellOffer() {
        return getDirection() == OfferDirection.SELL;
    }

    boolean isCryptoCurrency() {
        return CurrencyUtil.isCryptoCurrency(getCurrencyCode());
    }

    @Nullable
    Coin getTakerFee() {
        Coin amount = this.amount.get();
        if (amount != null) {
            // TODO write unit test for that
            Coin feePerBtc = CoinUtil.getFeePerBtc(HavenoUtils.getTakerFeePerBtc(), amount);
            return CoinUtil.maxCoin(feePerBtc, HavenoUtils.getMinTakerFee());
        } else {
            return null;
        }
    }

    public void swapTradeToSavings() {
        log.debug("swapTradeToSavings, offerId={}", offer.getId());
        xmrWalletService.resetAddressEntriesForOpenOffer(offer.getId());
    }

  /*  private void setFeeFromFundingTx(Coin fee) {
        feeFromFundingTx = fee;
        isFeeFromFundingTxSufficient.set(feeFromFundingTx.compareTo(FeePolicy.getMinRequiredFeeForFundingTx()) >= 0);
    }*/

    boolean isMinAmountLessOrEqualAmount() {
        //noinspection SimplifiableIfStatement
        if (offer != null && amount.get() != null)
            return !offer.getMinAmount().isGreaterThan(amount.get());
        return true;
    }

    boolean isAmountLargerThanOfferAmount() {
        //noinspection SimplifiableIfStatement
        if (amount.get() != null && offer != null)
            return amount.get().isGreaterThan(offer.getAmount());
        return true;
    }

    boolean wouldCreateDustForMaker() {
        //noinspection SimplifiableIfStatement
        boolean result;
        if (amount.get() != null && offer != null) {
            Coin customAmount = offer.getAmount().subtract(amount.get());
            result = customAmount.isPositive() && customAmount.isLessThan(Restrictions.getMinNonDustOutput());

            if (result)
                log.info("would create dust for maker, customAmount={},  Restrictions.getMinNonDustOutput()={}", customAmount, Restrictions.getMinNonDustOutput());
        } else {
            result = true;
        }
        return result;
    }

    ReadOnlyObjectProperty<Coin> getAmount() {
        return amount;
    }

    public PaymentMethod getPaymentMethod() {
        return offer.getPaymentMethod();
    }

    public String getCurrencyCode() {
        return offer.getCurrencyCode();
    }

    public String getCurrencyNameAndCode() {
        return CurrencyUtil.getNameByCode(offer.getCurrencyCode());
    }

    @NotNull
    private Coin getFundsNeededForTrade() {
        return getSecurityDeposit();
    }

    public XmrAddressEntry getAddressEntry() {
        return addressEntry;
    }

    public Coin getSecurityDeposit() {
        return securityDeposit;
    }

    public Coin getBuyerSecurityDeposit() {
        return offer.getBuyerSecurityDeposit();
    }

    public Coin getSellerSecurityDeposit() {
        return offer.getSellerSecurityDeposit();
    }

    public boolean isUsingHalCashAccount() {
        return paymentAccount.hasPaymentMethodWithId(HAL_CASH_ID);
    }

    public Coin getTakerFeeInBtc() {
        return offerUtil.getTakerFee(amount.get());
    }
}
