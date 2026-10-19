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

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.core.api.model.AddressBalanceInfo;
import haveno.core.api.model.BalancesInfo;
import haveno.core.api.model.BtcBalanceInfo;
import haveno.core.api.model.XmrBalanceInfo;
import haveno.core.app.AppStartupState;
import haveno.core.offer.OpenOfferManager;
import haveno.core.user.Preferences;
import haveno.core.util.FormattingUtils;
import static haveno.core.util.ParsingUtils.parseToCoin;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.xmr.Balances;
import haveno.core.xmr.model.AddressEntry;
import haveno.core.xmr.setup.WalletsSetup;
import haveno.core.xmr.wallet.BtcWalletService;
import static haveno.core.xmr.wallet.Restrictions.getMinNonDustOutput;
import haveno.core.xmr.wallet.WalletsManager;
import haveno.core.xmr.wallet.XmrWalletService;
import static java.lang.String.format;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static java.util.concurrent.TimeUnit.SECONDS;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroTxWallet;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.crypto.KeyCrypterScrypt;
import org.bouncycastle.crypto.params.KeyParameter;

@Singleton
@Slf4j
class CoreWalletsService {

    private static final long MAX_RESTORE_HEIGHT = (1L << 53) - 1; // matches the TypeScript client's safe integer limit

    private record WalletImport(String seed, Long restoreHeight, LocalDate restoreDate) {}

    private final AppStartupState appStartupState;
    private final CoreAccountService accountService;
    private final CoreContext coreContext;
    private final Balances balances;
    private final WalletsManager walletsManager;
    private final WalletsSetup walletsSetup;
    private final BtcWalletService btcWalletService;
    private final XmrWalletService xmrWalletService;
    private final OpenOfferManager openOfferManager;
    private final CoinFormatter btcFormatter;

    @Nullable
    private Timer lockTimer;

    @Nullable
    private KeyParameter tempAesKey;

    @Inject
    public CoreWalletsService(AppStartupState appStartupState,
                              CoreContext coreContext,
                              CoreAccountService accountService,
                              Balances balances,
                              WalletsManager walletsManager,
                              WalletsSetup walletsSetup,
                              BtcWalletService btcWalletService,
                              XmrWalletService xmrWalletService,
                              OpenOfferManager openOfferManager,
                              @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter btcFormatter,
                              Preferences preferences) {
        this.appStartupState = appStartupState;
        this.coreContext = coreContext;
        this.accountService = accountService;
        this.balances = balances;
        this.walletsManager = walletsManager;
        this.walletsSetup = walletsSetup;
        this.btcWalletService = btcWalletService;
        this.xmrWalletService = xmrWalletService;
        this.openOfferManager = openOfferManager;
        this.btcFormatter = btcFormatter;
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

        switch (currencyCode.trim().toUpperCase()) {
            case "":
            case "XMR":
                return new BalancesInfo(BtcBalanceInfo.EMPTY, getXmrBalances());
            default:
                throw new IllegalStateException("Unsupported currency code: " + currencyCode.trim().toUpperCase());
        }
    }

    void createAccountFromSeed(String password, String seed, Long restoreHeight, String restoreDate) {
        if (accountService.accountExists()) throw new IllegalStateException("Cannot create account if account already exists");
        WalletImport walletImport = parseWalletImport(seed, restoreHeight, restoreDate);
        accountService.createAccount(password, () -> xmrWalletService.createMainWalletFromSeed(walletImport.seed(), walletImport.restoreHeight(), walletImport.restoreDate()));
    }

    /** Validate a required seed with its optional restore height or date. */
    private WalletImport parseWalletImport(String seed, Long restoreHeight, String restoreDate) {
        seed = seed == null ? "" : seed.trim().replaceAll("\\s+", " ");
        restoreDate = restoreDate == null ? "" : restoreDate.trim();
        if (seed.isEmpty()) throw new IllegalArgumentException("Wallet seed is required");
        if (restoreHeight != null && (restoreHeight < 0 || restoreHeight > MAX_RESTORE_HEIGHT)) throw new IllegalArgumentException("Restore height must be between 0 and " + MAX_RESTORE_HEIGHT);
        LocalDate date = null;
        if (!restoreDate.isEmpty()) {
            if (restoreHeight != null) throw new IllegalArgumentException("Specify either a restore height or a restore date");
            if (!restoreDate.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Restore date must be a valid YYYY-MM-DD date");
            try {
                date = LocalDate.parse(restoreDate);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Restore date must be a valid YYYY-MM-DD date");
            }
            if (date.isAfter(LocalDate.now(ZoneOffset.UTC))) throw new IllegalArgumentException("Restore date cannot be in the future (UTC)");
        }
        if (!xmrWalletService.isSeedValid(seed)) throw new IllegalArgumentException("Invalid wallet seed");
        return new WalletImport(seed, restoreHeight, date);
    }

    void restoreXmrWalletFromSeed(String seed, Long restoreHeight, String restoreDate) {
        accountService.checkAccountOpen();
        verifyWalletsAreAvailable();
        verifyEncryptedWalletIsUnlocked();
        WalletImport walletImport = parseWalletImport(seed, restoreHeight, restoreDate);
        if (!Boolean.TRUE.equals(xmrWalletService.getXmrConnectionService().isConnected())) throw new IllegalStateException("Cannot restore wallet because there is no connection to Monero daemon"); // before offers are removed, which cannot be undone

        // wait off the user thread until published offers have been removed before replacing the wallet,
        // allowing the manager's propagation delay of 200 ms per offer on top of the removals themselves
        long timeoutMs = TimeUnit.MINUTES.toMillis(2) + 200L * openOfferManager.getOpenOffers().size();
        CountDownLatch removed = new CountDownLatch(1);
        AtomicReference<String> removalError = new AtomicReference<>();
        openOfferManager.removeAllOpenOffers(removed::countDown, error -> {
            removalError.set(error);
            removed.countDown();
        });
        try {
            if (!removed.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new IllegalStateException("Timed out removing open offers before restoring wallet");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted removing open offers before restoring wallet", e);
        }
        if (removalError.get() != null) throw new IllegalStateException(removalError.get());
        xmrWalletService.restoreWalletFromSeed(walletImport.seed(), walletImport.restoreHeight(), walletImport.restoreDate());
    }

    String getXmrSeed() {
        accountService.checkAccountOpen();
        verifyWalletsAreAvailable();
        verifyEncryptedWalletIsUnlocked();
        return xmrWalletService.getSeed();
    }

    String getXmrPrimaryAddress() {
        accountService.checkAccountOpen();
        verifyWalletsAreAvailable();
        return xmrWalletService.getPrimaryAddress();
    }

    String getXmrNewSubaddress() {
        accountService.checkAccountOpen();
        return xmrWalletService.getNewAddressEntry().getAddressString();
    }

    List<MoneroTxWallet> getXmrTxs() {
        accountService.checkAccountOpen();
        return xmrWalletService.getTxs();
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

    List<MoneroTxWallet> createXmrSweepTxs(String address) {
        accountService.checkAccountOpen();
        verifyWalletsAreAvailable();
        verifyEncryptedWalletIsUnlocked();
        try {
            return xmrWalletService.createSweepTxs(address);
        } catch (Exception ex) {
            log.error("", ex);
            throw new IllegalStateException(ex);
        }
    }

    List<String> relayXmrTxs(List<String> metadatas) {
        accountService.checkAccountOpen();
        verifyWalletsAreAvailable();
        verifyEncryptedWalletIsUnlocked();
        try {
            return xmrWalletService.relayTxs(metadatas);
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
        if (walletsManager.areWalletsEncrypted() && !accountService.isAccountOpen())
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

    private XmrBalanceInfo getXmrBalances() {
        verifyWalletsAreAvailable();
        verifyEncryptedWalletIsUnlocked();
        if (balances.getAvailableBalance() == null) throw new IllegalStateException("Balances are not yet available");
        return balances.getBalances();
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

    public long getHeight() {
        return xmrWalletService.getHeight();
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
