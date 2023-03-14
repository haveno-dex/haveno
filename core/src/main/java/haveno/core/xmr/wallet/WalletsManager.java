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

package haveno.core.xmr.wallet;

import com.google.inject.Inject;
import haveno.common.crypto.ScryptUtil;
import haveno.common.handlers.ExceptionHandler;
import haveno.common.handlers.ResultHandler;
import haveno.core.locale.Res;
import haveno.core.xmr.setup.WalletsSetup;
import org.bitcoinj.crypto.KeyCrypter;
import org.bitcoinj.crypto.KeyCrypterScrypt;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;
import org.bouncycastle.crypto.params.KeyParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

// Convenience class to handle methods applied to several wallets
public class WalletsManager {
    private static final Logger log = LoggerFactory.getLogger(WalletsManager.class);

    private final BtcWalletService btcWalletService;
    private final XmrWalletService xmrWalletService;
    private final TradeWalletService tradeWalletService;
    private final WalletsSetup walletsSetup;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public WalletsManager(BtcWalletService btcWalletService,
                          XmrWalletService xmrWalletService,
                          TradeWalletService tradeWalletService,
                          WalletsSetup walletsSetup) {
        this.btcWalletService = btcWalletService;
        this.xmrWalletService = xmrWalletService;
        this.tradeWalletService = tradeWalletService;
        this.walletsSetup = walletsSetup;
    }

    public void decryptWallets(KeyParameter aesKey) {
        btcWalletService.decryptWallet(aesKey);
        tradeWalletService.setAesKey(null);
    }

    public void encryptWallets(KeyCrypterScrypt keyCrypterScrypt, KeyParameter aesKey) {
        try {
            btcWalletService.encryptWallet(keyCrypterScrypt, aesKey);

            // we save the key for the trade wallet as we don't require passwords here
            tradeWalletService.setAesKey(aesKey);
        } catch (Throwable t) {
            log.error(t.toString());
            throw t;
        }
    }

    public String getWalletsAsString(boolean includePrivKeys) {
        final String baseCurrencyWalletDetails = Res.getBaseCurrencyCode() + " Wallet:\n" +
                btcWalletService.getWalletAsString(includePrivKeys);
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
        return xmrWalletService.isWalletEncrypted();
    }

    public boolean areWalletsAvailable() {
        return xmrWalletService.isWalletReady();
    }

    public KeyCrypterScrypt getKeyCrypterScrypt() {
        if (areWalletsEncrypted() && btcWalletService.getKeyCrypter() != null)
            return (KeyCrypterScrypt) btcWalletService.getKeyCrypter();
        else
            return ScryptUtil.getKeyCrypterScrypt();
    }

    public boolean checkAESKey(KeyParameter aesKey) {
        return btcWalletService.checkAESKey(aesKey);
    }

    public long getChainSeedCreationTimeSeconds() {
        return btcWalletService.getKeyChainSeed().getCreationTimeSeconds();
    }

    public boolean hasPositiveBalance() {
        return btcWalletService.getBalance(Wallet.BalanceType.AVAILABLE)
                .isPositive();
    }

    public void setAesKey(KeyParameter aesKey) {
        btcWalletService.setAesKey(aesKey);
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
}
