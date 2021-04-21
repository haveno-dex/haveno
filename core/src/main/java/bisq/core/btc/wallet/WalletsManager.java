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

package bisq.core.btc.wallet;

import bisq.core.btc.setup.WalletsSetup;
import bisq.core.crypto.ScryptUtil;
import bisq.core.locale.Res;

import bisq.common.handlers.ExceptionHandler;
import bisq.common.handlers.ResultHandler;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.crypto.KeyCrypter;
import org.bitcoinj.crypto.KeyCrypterScrypt;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;

import com.google.inject.Inject;

import org.bouncycastle.crypto.params.KeyParameter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

// TODO(niyid) Handle missing methods for XmrWalletSerice where necessary
// Convenience class to handle methods applied to several wallets
public class WalletsManager {
    private static final Logger log = LoggerFactory.getLogger(WalletsManager.class);

    private final XmrWalletService xmrWalletService; //TODO(niyid) Change to XmrWalletService and add missing methods
    private final TradeWalletService tradeWalletService;
    private final WalletsSetup walletsSetup;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public WalletsManager(XmrWalletService xmrWalletService,
                          TradeWalletService tradeWalletService,
                          WalletsSetup walletsSetup) {
        this.xmrWalletService = xmrWalletService;
        this.tradeWalletService = tradeWalletService;
        this.walletsSetup = walletsSetup;
    }

    public void decryptWallets(KeyParameter aesKey) {
//        xmrWalletService.decryptWallet(aesKey); // TODO(niyid) <==
        tradeWalletService.setAesKey(null);
    }

    public void encryptWallets(KeyCrypterScrypt keyCrypterScrypt, KeyParameter aesKey) {
        try {
//            xmrWalletService.encryptWallet(keyCrypterScrypt, aesKey); // TODO(niyid) <==

            // we save the key for the trade wallet as we don't require passwords here
            tradeWalletService.setAesKey(aesKey);
        } catch (Throwable t) {
            log.error(t.toString());
            throw t;
        }
    }

    public String getWalletsAsString(boolean includePrivKeys) {
        final String baseCurrencyWalletDetails = Res.getBaseCurrencyCode() + " Wallet:\n"; // TODO(niyid) <==
        return baseCurrencyWalletDetails;
    }

    public void restoreSeedWords(@Nullable DeterministicSeed seed, ResultHandler resultHandler, ExceptionHandler exceptionHandler) {
        walletsSetup.restoreSeedWords(seed, resultHandler, exceptionHandler);
    }

    public void backupWallets() {
        walletsSetup.backupWallets();
    }

    public void clearBackup() {
        walletsSetup.clearBackups();
    }

    public boolean areWalletsEncrypted() {
        return areWalletsAvailable(); // TODO(niyid) <==
    }

    public boolean areWalletsAvailable() {
        return true;
    } // TODO(niyid) <==

    public KeyCrypterScrypt getKeyCrypterScrypt() {
        if (areWalletsEncrypted()) // TODO(niyid) <==
            return ScryptUtil.getKeyCrypterScrypt(); //TODO(niyid) <==
        else
            return ScryptUtil.getKeyCrypterScrypt(); //TODO(niyid) <==
    }

    public boolean checkAESKey(KeyParameter aesKey) {
        return false;
    }// TODO(niyid) <==

    public long getChainSeedCreationTimeSeconds() {
        return 0L;// TODO(niyid) <==
    }

    public boolean hasPositiveBalance() {
        return true;// TODO(niyid) <==
    }

    public void setAesKey(KeyParameter aesKey) {
//        xmrWalletService.setAesKey(aesKey);// TODO(niyid) <==
        tradeWalletService.setAesKey(aesKey);
    }

    public DeterministicSeed getDecryptedSeed(KeyParameter aesKey, DeterministicSeed keyChainSeed, KeyCrypter keyCrypter) {
        if (keyCrypter != null) {
            return keyChainSeed.decrypt(keyCrypter, "", aesKey);
        } else {
            log.warn("keyCrypter is null");
            return null;
        }
    }

    // A bsq tx has miner fees in btc included. Thus we need to handle it on both wallets.
    public void publishAndCommitBsqTx(Transaction tx, TxBroadcaster.Callback callback) {
        // We need to create another instance, otherwise the tx would trigger an invalid state exception
        // if it gets committed 2 times
        // We clone before commit to avoid unwanted side effects
//        Transaction clonedTx = xmrWalletService.getClonedTransaction(tx);// TODO(niyid) <==
//        xmrWalletService.commitTx(clonedTx);// TODO(niyid) <==
    }
}
