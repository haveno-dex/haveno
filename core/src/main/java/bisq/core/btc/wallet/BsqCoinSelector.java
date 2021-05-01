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

import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;

import javax.inject.Inject;

/**
 * We use a specialized version of the CoinSelector based on the DefaultCoinSelector implementation.
 * We lookup for spendable outputs which matches our address of our address.
 */
@Slf4j
public class BsqCoinSelector extends BisqDefaultCoinSelector {

    @Inject
    public BsqCoinSelector() {
        // permitForeignPendingTx is not relevant here as we do not support pending foreign utxos anyway.
        super(false);
    }

    @Override
    protected boolean isTxOutputSpendable(TransactionOutput output) {
        // output.getParentTransaction() cannot be null as it is checked in calling method
        Transaction parentTransaction = output.getParentTransaction();
        if (parentTransaction == null)
            return false;

        // Only if it's not existing yet in the dao state (unconfirmed) we use our unconfirmedBsqChangeOutputList to
        // check if it is an own change output.
        return false;//TODO(niyi) Still retaining for code consistency
    }

    // For BSQ we do not check for dust attack utxos as they are 5.46 BSQ and a considerable value.
    // The default 546 sat dust limit is handled in the BitcoinJ side anyway.
    @Override
    protected boolean isDustAttackUtxo(TransactionOutput output) {
        return false;
    }
}
