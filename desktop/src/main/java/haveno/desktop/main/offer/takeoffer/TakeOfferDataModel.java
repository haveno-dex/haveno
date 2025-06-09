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

package haveno.desktop.main.offer.takeoffer;

import com.google.inject.Inject;

import haveno.common.ThreadUtils;
import haveno.common.UserThread;
import haveno.common.handlers.ErrorMessageHandler;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.filter.FilterManager;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;
import haveno.core.monetary.Price;
import haveno.core.monetary.Volume;
import haveno.core.offer.Offer;
import haveno.core.offer.OfferDirection;
import haveno.core.offer.OfferUtil;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PaymentAccountUtil;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.TradeManager;
import haveno.core.trade.handlers.TradeResultHandler;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.util.VolumeUtil;
import haveno.core.xmr.listeners.XmrBalanceListener;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.Navigation;
import haveno.desktop.main.offer.OfferDataModel;
import haveno.desktop.main.offer.offerbook.OfferBook;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.util.GUIUtil;
import haveno.network.p2p.P2PService;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ObservableList;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.Set;

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
    private final FilterManager filterManager;
    final Preferences preferences;
    private final PriceFeedService priceFeedService;
    private final AccountAgeWitnessService accountAgeWitnessService;
    private final Navigation navigation;
    private final P2PService p2PService;

    private BigInteger securityDeposit;

    private Offer offer;

    // final BooleanProperty isFeeFromFundingTxSufficient = new SimpleBooleanProperty();
    // final BooleanProperty isMainNet = new SimpleBooleanProperty();
    private final ObjectProperty<BigInteger> amount = new SimpleObjectProperty<>();
    final ObjectProperty<Volume> volume = new SimpleObjectProperty<>();

    private XmrBalanceListener balanceListener;
    private PaymentAccount paymentAccount;
    private boolean isTabSelected;
    Price tradePrice;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////


    @Inject
    TakeOfferDataModel(TradeManager tradeManager,
                       OfferBook offerBook,
                       OfferUtil offerUtil,
                       XmrWalletService xmrWalletService,
                       User user,
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

        updateBalances();

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
                    this.amount.get(),
                    () -> {
                    },
                    errorMessage -> {
                        log.warn(errorMessage);
                        if (offer.getState() != Offer.State.NOT_AVAILABLE && offer.getState() != Offer.State.INVALID) { // handled elsewhere in UI
                            new Popup().warning(errorMessage).show();
                        }
                    });
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

        this.amount.set(BigInteger.valueOf(getMaxTradeLimit()));

        updateSecurityDeposit();

        calculateVolume();
        calculateTotalToPay();

        balanceListener = new XmrBalanceListener(addressEntry.getSubaddressIndex()) {
            @Override
            public void onBalanceChanged(BigInteger balance) {
                updateBalances();
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

        // reset address entries off thread
        ThreadUtils.submitToPool(() -> xmrWalletService.resetAddressEntriesForOpenOffer(offer.getId()));
    }

    protected void updateBalances() {
        super.updateBalances();

        // update remaining balance
        UserThread.await(() -> {
            missingCoin.set(offerUtil.getBalanceShortage(totalToPay.get(), balance.get()));
            isXmrWalletFunded.set(offerUtil.isBalanceSufficient(totalToPay.get(), availableBalance.get()));
            if (totalToPay.get() != null && isXmrWalletFunded.get() && !showWalletFundedNotification.get()) {
                showWalletFundedNotification.set(true);
            }
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI actions
    ///////////////////////////////////////////////////////////////////////////////////////////

    void onTakeOffer(TradeResultHandler tradeResultHandler, ErrorMessageHandler errorMessageHandler) {
        checkNotNull(getTakerFee(), "takerFee must not be null");

        BigInteger fundsNeededForTrade = getFundsNeededForTrade();
        if (isBuyOffer())
            fundsNeededForTrade = fundsNeededForTrade.add(amount.get());

        String errorMsg = null;
        if (filterManager.isCurrencyBanned(offer.getCurrencyCode())) {
            errorMsg = Res.get("offerbook.warning.currencyBanned");
        } else if (filterManager.isPaymentMethodBanned(offer.getPaymentMethod())) {
            errorMsg = Res.get("offerbook.warning.paymentMethodBanned");
        } else if (filterManager.isOfferIdBanned(offer.getId())) {
            errorMsg = Res.get("offerbook.warning.offerBlocked");
        } else if (filterManager.isNodeAddressBanned(offer.getMakerNodeAddress())) {
            errorMsg = Res.get("offerbook.warning.nodeBlocked");
        } else if (filterManager.requireUpdateToNewVersionForTrading()) {
            errorMsg = Res.get("offerbook.warning.requireUpdateToNewVersion");
        } else if (tradeManager.wasOfferAlreadyUsedInTrade(offer.getId())) {
            errorMsg = Res.get("offerbook.warning.offerWasAlreadyUsedInTrade");
        } else {
            tradeManager.onTakeOffer(amount.get(),
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

        // handle error
        if (errorMsg != null) {
            new Popup().warning(errorMsg).show();
            log.warn("Error taking offer " + offer.getId() + ": " + errorMsg);
            errorMessageHandler.handleErrorMessage(errorMsg);
        }
    }

    public void onPaymentAccountSelected(PaymentAccount paymentAccount) {
        if (paymentAccount != null) {
            this.paymentAccount = paymentAccount;

            this.amount.set(BigInteger.valueOf(getMaxTradeLimit()));

            preferences.setTakeOfferSelectedPaymentAccountId(paymentAccount.getId());
        }
    }

    void fundFromSavingsWallet() {
        useSavingsWallet = true;
        updateBalances();
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

    long getMyMaxTradeLimit() {
        if (paymentAccount != null) {
            return accountAgeWitnessService.getMyTradeLimit(paymentAccount, getCurrencyCode(),
                    offer.getMirroredDirection(), offer.hasBuyerAsTakerWithoutDeposit());
        } else {
            return 0;
        }
    }

    long getMaxTradeLimit() {
        return Math.min(offer.getAmount().longValueExact(), getMyMaxTradeLimit());
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
                amount.get().compareTo(BigInteger.ZERO) != 0) {
            Volume volumeByAmount = tradePrice.getVolumeByAmount(amount.get());
            volumeByAmount = VolumeUtil.getAdjustedVolume(volumeByAmount, offer.getPaymentMethod().getId());

            volume.set(volumeByAmount);

            updateBalances();
        }
    }

    void maybeApplyAmount(BigInteger amount) {
        if (amount.compareTo(offer.getMinAmount()) >= 0 && amount.compareTo(BigInteger.valueOf(getMaxTradeLimit())) <= 0) {
            this.amount.set(amount);
        }
        calculateTotalToPay();
    }

    void calculateTotalToPay() {
        updateSecurityDeposit();

        // Taker pays 2 times the tx fee because the mining fee might be different when maker created the offer
        // and reserved his funds, so that would not work well with dynamic fees.
        // The mining fee for the takeOfferFee tx is deducted from the createOfferFee and not visible to the trader
        final BigInteger takerFee = getTakerFee();
        if (offer != null && amount.get() != null && takerFee != null) {
            BigInteger feeAndSecDeposit = securityDeposit.add(takerFee);
            if (isBuyOffer())
                totalToPay.set(feeAndSecDeposit.add(amount.get()));
            else
                totalToPay.set(feeAndSecDeposit);
            updateBalances();
            log.debug("totalToPay {}", totalToPay.get());
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
    BigInteger getTakerFee() {
        return HavenoUtils.multiply(this.amount.get(), offer.getTakerFeePct());
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
            return offer.getMinAmount().compareTo(amount.get()) <= 0;
        return true;
    }

    boolean isAmountLargerThanOfferAmount() {
        //noinspection SimplifiableIfStatement
        if (amount.get() != null && offer != null)
            return amount.get().compareTo(offer.getAmount()) > 0;
        return true;
    }

    boolean wouldCreateDustForMaker() {
        return false; // TODO: update for XMR?
    }

    ReadOnlyObjectProperty<BigInteger> getAmount() {
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
    private BigInteger getFundsNeededForTrade() {
        return getSecurityDeposit();
    }

    public XmrAddressEntry getAddressEntry() {
        return addressEntry;
    }

    public BigInteger getSecurityDeposit() {
        return securityDeposit;
    }

    private void updateSecurityDeposit() {
        securityDeposit = offer.getDirection() == OfferDirection.SELL ?
                getBuyerSecurityDeposit() :
                getSellerSecurityDeposit();
    }

    private BigInteger getBuyerSecurityDeposit() {
        return offer.getOfferPayload().getBuyerSecurityDepositForTradeAmount(amount.get());
    }

    private BigInteger getSellerSecurityDeposit() {
        return offer.getOfferPayload().getSellerSecurityDepositForTradeAmount(amount.get());
    }

    public boolean isRoundedForAtmCash() {
        return PaymentMethod.isRoundedForAtmCash(paymentAccount.getPaymentMethod().getId());
    }
}
