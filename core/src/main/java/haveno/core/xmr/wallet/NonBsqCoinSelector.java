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

import haveno.core.user.Preferences;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionOutput;

import javax.inject.Inject;

/**
 * We use a specialized version of the CoinSelector based on the DefaultCoinSelector implementation.
 * We lookup for spendable outputs which matches our address of our address.
 */
@Slf4j
public class NonBsqCoinSelector extends HavenoDefaultCoinSelector {
    @Setter
    private Preferences preferences;

    @Inject
    public NonBsqCoinSelector() {
        super(false);
    }

    @Override
    protected boolean isTxOutputSpendable(TransactionOutput output) {
        // output.getParentTransaction() cannot be null as it is checked in calling method
        Transaction parentTransaction = output.getParentTransaction();
        if (parentTransaction == null)
            return false;

        // It is important to not allow pending txs as otherwise unconfirmed BSQ txs would be considered nonBSQ as
        // below outputIsNotInBsqState would be true.
        if (parentTransaction.getConfidence().getConfidenceType() != TransactionConfidence.ConfidenceType.BUILDING)
            return false;

        return true;
    }

    // Prevent usage of dust attack utxos
    @Override
    protected boolean isDustAttackUtxo(TransactionOutput output) {
        return output.getValue().value < preferences.getIgnoreDustThreshold();
    }
}
