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
import bisq.core.api.model.BtcBalanceInfo;
import bisq.core.api.model.TxFeeRateInfo;
import bisq.core.api.model.XmrBalanceInfo;
import bisq.core.app.AppStartupState;
import bisq.core.btc.Balances;
import bisq.core.btc.exceptions.AddressEntryException;
import bisq.core.btc.exceptions.InsufficientFundsException;
import bisq.core.btc.model.AddressEntry;
import bisq.core.btc.setup.WalletsSetup;
import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.btc.wallet.WalletsManager;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.provider.fee.FeeService;
import bisq.core.user.Preferences;
import bisq.core.util.FormattingUtils;
import bisq.core.util.coin.CoinFormatter;

import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.handlers.ResultHandler;
import bisq.common.util.Utilities;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.crypto.KeyCrypterScrypt;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import org.bouncycastle.crypto.params.KeyParameter;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

import static bisq.core.btc.wallet.Restrictions.getMinNonDustOutput;
import static bisq.core.util.ParsingUtils.parseToCoin;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;



import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroTxWallet;

@Singleton
@Slf4j
class CoreWalletsService {

    private final AppStartupState appStartupState;
    private final CoreAccountService accountService;
    private final CoreContext coreContext;
    private final Balances balances;
    private final WalletsManager walletsManager;
    private final WalletsSetup walletsSetup;
    private final BtcWalletService btcWalletService;
    private final XmrWalletService xmrWalletService;
    private final CoinFormatter btcFormatter;
    private final FeeService feeService;
    private final Preferences preferences;

    @Nullable
    private Timer lockTimer;

    @Nullable
    private KeyParameter tempAesKey;

    private final ListeningExecutorService executor = Utilities.getSingleThreadListeningExecutor("CoreWalletsService");

    @Inject
    public CoreWalletsService(AppStartupState appStartupState,
                              CoreContext coreContext,
                              CoreAccountService accountService,
                              Balances balances,
                              WalletsManager walletsManager,
                              WalletsSetup walletsSetup,
                              BtcWalletService btcWalletService,
                              XmrWalletService xmrWalletService,
                              @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter btcFormatter,
                              FeeService feeService,
                              Preferences preferences) {
        this.appStartupState = appStartupState;
        this.coreContext = coreContext;
        this.accountService = accountService;
        this.balances = balances;
        this.walletsManager = walletsManager;
        this.walletsSetup = walletsSetup;
        this.btcWalletService = btcWalletService;
        this.xmrWalletService = xmrWalletService;
        this.btcFormatter = btcFormatter;
        this.feeService = feeService;
        this.preferences = preferences;
    }

    @Nullable
    KeyParameter getKey() {
        verifyEncryptedWalletIsUnlocked();
        return tempAesKey;
    }

    NetworkParameters getNetworkParameters() {
        return btcWalletService.getWallet().getContext().getParams();
    }

    BalancesInfo getBalances(String currencyCode) {
        accountService.checkAccountOpen();
        verifyWalletCurrencyCodeIsValid(currencyCode);
        verifyWalletsAreAvailable();
        verifyEncryptedWalletIsUnlocked();
        if (balances.getAvailableBalance().get() == null)
            throw new IllegalStateException("balance is not yet available");

        switch (currencyCode.trim().toUpperCase()) {
            case "BTC":
                return new BalancesInfo(getBtcBalances(), XmrBalanceInfo.EMPTY);
            case "XMR":
                return new BalancesInfo(BtcBalanceInfo.EMPTY, getXmrBalances());
            default:
                return new BalancesInfo(getBtcBalances(), getXmrBalances());
        }
    }

    String getNewDepositSubaddress() {
        accountService.checkAccountOpen();
        return xmrWalletService.getWallet().createSubaddress(0).getAddress();
    }

    List<MoneroTxWallet> getXmrTxs() {
        accountService.checkAccountOpen();
        return xmrWalletService.getWallet().getTxs();
    }

    MoneroTxWallet createXmrTx(List<MoneroDestination> destinations) {
        accountService.checkAccountOpen();
        verifyWalletsAreAvailable();
        verifyEncryptedWalletIsUnlocked();
        try {
            return xmrWalletService.createTx(destinations);
        } catch (Exception ex) {
            log.error("", ex);
            throw new IllegalStateException(ex);
        }
    }

    String relayXmrTx(String metadata) {
        accountService.checkAccountOpen();
        verifyWalletsAreAvailable();
        verifyEncryptedWalletIsUnlocked();
        try {
            return xmrWalletService.getWallet().relayTx(metadata);
        } catch (Exception ex) {
            log.error("", ex);
            throw new IllegalStateException(ex);
        }
    }

    long getAddressBalance(String addressString) {
        Address address = getAddressEntry(addressString).getAddress();
        return btcWalletService.getBalanceForAddress(address).value;
    }

    AddressBalanceInfo getAddressBalanceInfo(String addressString) {
        var satoshiBalance = getAddressBalance(addressString);
        var numConfirmations = getNumConfirmationsForMostRecentTransaction(addressString);
        Address address = getAddressEntry(addressString).getAddress();
        return new AddressBalanceInfo(addressString,
                satoshiBalance,
                numConfirmations,
                btcWalletService.isAddressUnused(address));
    }

    List<AddressBalanceInfo> getFundingAddresses() {
        verifyWalletsAreAvailable();
        verifyEncryptedWalletIsUnlocked();

        // Create a new  unused funding address if none exists.
        boolean unusedAddressExists = btcWalletService.getAvailableAddressEntries()
                .stream()
                .anyMatch(a -> btcWalletService.isAddressUnused(a.getAddress()));
        if (!unusedAddressExists)
            btcWalletService.getFreshAddressEntry();

        List<String> addressStrings = btcWalletService
                .getAvailableAddressEntries()
                .stream()
                .map(AddressEntry::getAddressString)
                .collect(Collectors.toList());

        // getAddressBalance is memoized, because we'll map it over addresses twice.
        // To get the balances, we'll be using .getUnchecked, because we know that
        // this::getAddressBalance cannot return null.
        var balances = memoize(this::getAddressBalance);

        boolean noAddressHasZeroBalance = addressStrings.stream()
                .allMatch(addressString -> balances.getUnchecked(addressString) != 0);

        if (noAddressHasZeroBalance) {
            var newZeroBalanceAddress = btcWalletService.getFreshAddressEntry();
            addressStrings.add(newZeroBalanceAddress.getAddressString());
        }

        return addressStrings.stream().map(address ->
                new AddressBalanceInfo(address,
                        balances.getUnchecked(address),
                        getNumConfirmationsForMostRecentTransaction(address),
                        btcWalletService.isAddressUnused(getAddressEntry(address).getAddress())))
                .collect(Collectors.toList());
    }

    void sendBtc(String address,
                 String amount,
                 String txFeeRate,
                 String memo,
                 FutureCallback<Transaction> callback) {
        verifyWalletsAreAvailable();
        verifyEncryptedWalletIsUnlocked();

        try {
            Set<String> fromAddresses = btcWalletService.getAddressEntriesForAvailableBalanceStream()
                    .map(AddressEntry::getAddressString)
                    .collect(Collectors.toSet());
            Coin receiverAmount = getValidTransferAmount(amount, btcFormatter);
            Coin txFeePerVbyte = getTxFeeRateFromParamOrPreferenceOrFeeService(txFeeRate);

            // TODO Support feeExcluded (or included), default is fee included.
            //  See WithdrawalView # onWithdraw (and refactor).
            Transaction feeEstimationTransaction =
                    btcWalletService.getFeeEstimationTransactionForMultipleAddresses(fromAddresses,
                            receiverAmount,
                            txFeePerVbyte);
            if (feeEstimationTransaction == null)
                throw new IllegalStateException("could not estimate the transaction fee");

            Coin dust = btcWalletService.getDust(feeEstimationTransaction);
            Coin fee = feeEstimationTransaction.getFee().add(dust);
            if (dust.isPositive()) {
                fee = feeEstimationTransaction.getFee().add(dust);
                log.info("Dust txo ({} sats) was detected, the dust amount has been added to the fee (was {}, now {})",
                        dust.value,
                        feeEstimationTransaction.getFee(),
                        fee.value);
            }
            log.info("Sending {} BTC to {} with tx fee of {} sats (fee rate {} sats/byte).",
                    amount,
                    address,
                    fee.value,
                    txFeePerVbyte.value);
            btcWalletService.sendFundsForMultipleAddresses(fromAddresses,
                    address,
                    receiverAmount,
                    fee,
                    null,
                    tempAesKey,
                    memo.isEmpty() ? null : memo,
                    callback);
        } catch (AddressEntryException ex) {
            log.error("", ex);
            throw new IllegalStateException("cannot send btc from any addresses in wallet", ex);
        } catch (InsufficientFundsException | InsufficientMoneyException ex) {
            log.error("", ex);
            throw new IllegalStateException("cannot send btc due to insufficient funds", ex);
        }
    }


    void getTxFeeRate(ResultHandler resultHandler) {
        try {
            @SuppressWarnings({"unchecked", "Convert2MethodRef"})
            ListenableFuture<Void> future =
                    (ListenableFuture<Void>) executor.submit(() -> feeService.requestFees());
            Futures.addCallback(future, new FutureCallback<>() {
                @Override
                public void onSuccess(@Nullable Void ignored) {
                    resultHandler.handleResult();
                }

                @Override
                public void onFailure(Throwable t) {
                    log.error("", t);
                    throw new IllegalStateException("could not request fees from fee service", t);
                }
            }, MoreExecutors.directExecutor());

        } catch (Exception ex) {
            log.error("", ex);
            throw new IllegalStateException("could not request fees from fee service", ex);
        }
    }

    void setTxFeeRatePreference(long txFeeRate,
                                ResultHandler resultHandler) {
        long minFeePerVbyte = feeService.getMinFeePerVByte();
        if (txFeeRate < minFeePerVbyte)
            throw new IllegalStateException(
                    format("tx fee rate preference must be >= %d sats/byte", minFeePerVbyte));

        preferences.setUseCustomWithdrawalTxFee(true);
        Coin satsPerByte = Coin.valueOf(txFeeRate);
        preferences.setWithdrawalTxFeeInVbytes(satsPerByte.value);
        getTxFeeRate(resultHandler);
    }

    void unsetTxFeeRatePreference(ResultHandler resultHandler) {
        preferences.setUseCustomWithdrawalTxFee(false);
        getTxFeeRate(resultHandler);
    }

    TxFeeRateInfo getMostRecentTxFeeRateInfo() {
        return new TxFeeRateInfo(
                preferences.isUseCustomWithdrawalTxFee(),
                preferences.getWithdrawalTxFeeInVbytes(),
                feeService.getMinFeePerVByte(),
                feeService.getTxFeePerVbyte().value,
                feeService.getLastRequest());
    }

    Transaction getTransaction(String txId) {
        if (txId.length() != 64)
            throw new IllegalArgumentException(format("%s is not a transaction id", txId));

        try {
            Transaction tx = btcWalletService.getTransaction(txId);
            if (tx == null)
                throw new IllegalArgumentException(format("tx with id %s not found", txId));
            else
                return tx;

        } catch (IllegalArgumentException ex) {
            log.error("", ex);
            throw new IllegalArgumentException(
                    format("could not get transaction with id %s%ncause: %s",
                            txId,
                            ex.getMessage().toLowerCase()));
        }
    }

    int getNumConfirmationsForMostRecentTransaction(String addressString) {
        Address address = getAddressEntry(addressString).getAddress();
        TransactionConfidence confidence = btcWalletService.getConfidenceForAddress(address);
        return confidence == null ? 0 : confidence.getDepthInBlocks();
    }

    void setWalletPassword(String password, String newPassword) {
        verifyWalletsAreAvailable();

        KeyCrypterScrypt keyCrypterScrypt = getKeyCrypterScrypt();

        if (newPassword != null && !newPassword.isEmpty()) {
            // TODO Validate new password before replacing old password.
            if (!walletsManager.areWalletsEncrypted())
                throw new IllegalStateException("wallet is not encrypted with a password");

            KeyParameter aesKey = keyCrypterScrypt.deriveKey(password);
            if (!walletsManager.checkAESKey(aesKey))
                throw new IllegalStateException("incorrect old password");

            walletsManager.decryptWallets(aesKey);
            aesKey = keyCrypterScrypt.deriveKey(newPassword);
            walletsManager.encryptWallets(keyCrypterScrypt, aesKey);
            walletsManager.backupWallets();
            return;
        }

        if (walletsManager.areWalletsEncrypted())
            throw new IllegalStateException("wallet is encrypted with a password");

        // TODO Validate new password.
        KeyParameter aesKey = keyCrypterScrypt.deriveKey(password);
        walletsManager.encryptWallets(keyCrypterScrypt, aesKey);
        walletsManager.backupWallets();
    }

    void lockWallet() {
        if (!walletsManager.areWalletsEncrypted())
            throw new IllegalStateException("wallet is not encrypted with a password");

        if (tempAesKey == null)
            throw new IllegalStateException("wallet is already locked");

        tempAesKey = null;
    }

    void unlockWallet(String password, long timeout) {
        verifyWalletIsAvailableAndEncrypted();

        KeyCrypterScrypt keyCrypterScrypt = getKeyCrypterScrypt();
        // The aesKey is also cached for timeout (secs) after being used to decrypt the
        // wallet, in case the user wants to manually lock the wallet before the timeout.
        tempAesKey = keyCrypterScrypt.deriveKey(password);

        if (!walletsManager.checkAESKey(tempAesKey))
            throw new IllegalStateException("incorrect password");

        if (lockTimer != null) {
            // The user has called unlockwallet again, before the prior unlockwallet
            // timeout has expired.  He's overriding it with a new timeout value.
            // Remove the existing lock timer to prevent it from calling lockwallet
            // before or after the new one does.
            lockTimer.stop();
            lockTimer = null;
        }

        if (coreContext.isApiUser())
            maybeSetWalletsManagerKey();

        lockTimer = UserThread.runAfter(() -> {
            if (tempAesKey != null) {
                // The unlockwallet timeout has expired;  re-lock the wallet.
                log.info("Locking wallet after {} second timeout expired.", timeout);
                tempAesKey = null;
            }
        }, timeout, SECONDS);
    }

    // Provided for automated wallet protection method testing, despite the
    // security risks exposed by providing users the ability to decrypt their wallets.
    void removeWalletPassword(String password) {
        verifyWalletIsAvailableAndEncrypted();
        KeyCrypterScrypt keyCrypterScrypt = getKeyCrypterScrypt();

        KeyParameter aesKey = keyCrypterScrypt.deriveKey(password);
        if (!walletsManager.checkAESKey(aesKey))
            throw new IllegalStateException("incorrect password");

        walletsManager.decryptWallets(aesKey);
        walletsManager.backupWallets();
    }

    // Throws a RuntimeException if wallets are not available (encrypted or not).
    void verifyWalletsAreAvailable() {
        verifyWalletAndNetworkIsReady();

        // TODO This check may be redundant, but the AppStartupState is new and unused
        //  prior to commit 838595cb03886c3980c40df9cfe5f19e9f8a0e39.  I would prefer
        //  to leave this check in place until certain AppStartupState will always work
        //  as expected.
        if (!walletsManager.areWalletsAvailable())
            throw new IllegalStateException("wallet is not yet available");
    }

    // Throws a RuntimeException if wallets are not available or not encrypted.
    void verifyWalletIsAvailableAndEncrypted() {
        verifyWalletAndNetworkIsReady();

        if (!walletsManager.areWalletsAvailable())
            throw new IllegalStateException("wallet is not yet available");

        if (!walletsManager.areWalletsEncrypted())
            throw new IllegalStateException("wallet is not encrypted with a password");
    }

    // Throws a RuntimeException if wallets are encrypted and locked.
    void verifyEncryptedWalletIsUnlocked() {
        if (walletsManager.areWalletsEncrypted() && tempAesKey == null)
            throw new IllegalStateException("wallet is locked");
    }

    // Throws a RuntimeException if wallets and network are not ready.
    void verifyWalletAndNetworkIsReady() {
        if (!appStartupState.isWalletAndNetworkReady())
            throw new IllegalStateException("wallet and network is not yet initialized");
    }

    // Throws a RuntimeException if application is not fully initialized.
    void verifyApplicationIsFullyInitialized() {
        if (!appStartupState.isApplicationFullyInitialized())
            throw new IllegalStateException("server is not fully initialized");
    }


    // Throws a RuntimeException if wallet currency code is not BTC or XMR.
    private void verifyWalletCurrencyCodeIsValid(String currencyCode) {
        if (currencyCode == null || currencyCode.isEmpty())
            return;

        if (!currencyCode.equalsIgnoreCase("BTC") && !currencyCode.equalsIgnoreCase("XMR"))
            throw new IllegalStateException(format("wallet does not support %s", currencyCode));
    }

    private void maybeSetWalletsManagerKey() {
        // Unlike the UI, a daemon cannot capture the user's wallet encryption password
        // during startup.  This method will set the wallet service's aesKey if necessary.
        if (tempAesKey == null)
            throw new IllegalStateException("cannot use null key, unlockwallet timeout may have expired");

        if (btcWalletService.getAesKey() == null) {
            KeyParameter aesKey = new KeyParameter(tempAesKey.getKey());
            walletsManager.setAesKey(aesKey);
            walletsSetup.getWalletConfig().maybeAddSegwitKeychain(walletsSetup.getWalletConfig().btcWallet(), aesKey);
        }
    }


    // TODO (woodser): delete this since it's serving XMR balances
    private BtcBalanceInfo getBtcBalances() {
        verifyWalletsAreAvailable();
        verifyEncryptedWalletIsUnlocked();

        var availableBalance = balances.getAvailableBalance().get();
        if (availableBalance == null)
            throw new IllegalStateException("balance is not yet available");

        var reservedBalance = balances.getReservedBalance().get();
        if (reservedBalance == null)
            throw new IllegalStateException("reserved balance is not yet available");

        var lockedBalance = balances.getLockedBalance().get();
        if (lockedBalance == null)
            throw new IllegalStateException("locked balance is not yet available");

        return new BtcBalanceInfo(availableBalance.value,
                reservedBalance.value,
                availableBalance.add(reservedBalance).value,
                lockedBalance.value);
    }

    private XmrBalanceInfo getXmrBalances() {
        verifyWalletsAreAvailable();
        verifyEncryptedWalletIsUnlocked();

        var availableBalance = balances.getAvailableBalance().get();
        if (availableBalance == null)
            throw new IllegalStateException("available balance is not yet available");

        var lockedBalance = balances.getLockedBalance().get();
        if (lockedBalance == null)
            throw new IllegalStateException("locked balance is not yet available");

        var reservedOfferBalance = balances.getReservedOfferBalance().get();
        if (reservedOfferBalance == null)
            throw new IllegalStateException("reserved offer balance is not yet available");

        var reservedTradeBalance = balances.getReservedTradeBalance().get();
        if (reservedTradeBalance == null)
            throw new IllegalStateException("reserved trade balance is not yet available");

        return new XmrBalanceInfo(availableBalance.longValue() + lockedBalance.longValue(),
                availableBalance.longValue(),
                lockedBalance.longValue(),
                reservedOfferBalance.longValue(),
                reservedTradeBalance.longValue());
    }

    // Returns a Coin for the transfer amount string, or a RuntimeException if invalid.
    private Coin getValidTransferAmount(String amount, CoinFormatter coinFormatter) {
        Coin amountAsCoin = parseToCoin(amount, coinFormatter);
        if (amountAsCoin.isLessThan(getMinNonDustOutput()))
            throw new IllegalStateException(format("%s is an invalid transfer amount", amount));

        return amountAsCoin;
    }

    private Coin getTxFeeRateFromParamOrPreferenceOrFeeService(String txFeeRate) {
        // A non txFeeRate String value overrides the fee service and custom fee.
        return txFeeRate.isEmpty()
                ? btcWalletService.getTxFeeForWithdrawalPerVbyte()
                : Coin.valueOf(Long.parseLong(txFeeRate));
    }

    private KeyCrypterScrypt getKeyCrypterScrypt() {
        KeyCrypterScrypt keyCrypterScrypt = walletsManager.getKeyCrypterScrypt();
        if (keyCrypterScrypt == null)
            throw new IllegalStateException("wallet encrypter is not available");
        return keyCrypterScrypt;
    }

    private AddressEntry getAddressEntry(String addressString) {
        Optional<AddressEntry> addressEntry =
                btcWalletService.getAddressEntryListAsImmutableList().stream()
                        .filter(e -> addressString.equals(e.getAddressString()))
                        .findFirst();

        if (!addressEntry.isPresent())
            throw new IllegalStateException(format("address %s not found in wallet", addressString));

        return addressEntry.get();
    }

    /**
     * Memoization stores the results of expensive function calls and returns
     * the cached result when the same input occurs again.
     *
     * Resulting LoadingCache is used by calling `.get(input I)` or
     * `.getUnchecked(input I)`, depending on whether or not `f` can return null.
     * That's because CacheLoader throws an exception on null output from `f`.
     */
    private static <I, O> LoadingCache<I, O> memoize(Function<I, O> f) {
        // f::apply is used, because Guava 20.0 Function doesn't yet extend
        // Java Function.
        return CacheBuilder.newBuilder().build(CacheLoader.from(f::apply));
    }
}
