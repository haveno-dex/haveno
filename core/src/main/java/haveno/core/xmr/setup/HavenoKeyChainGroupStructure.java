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

package haveno.core.xmr.setup;

import com.google.common.collect.ImmutableList;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.KeyChainGroupStructure;

public class HavenoKeyChainGroupStructure implements KeyChainGroupStructure {

    // See https://github.com/bitcoin/bips/blob/master/bip-0044.mediawiki
    // https://github.com/satoshilabs/slips/blob/master/slip-0044.md
    // We use 0 (0x80000000) as coin_type for BTC
    // m / purpose' / coin_type' / account' / change / address_index
    public static final ImmutableList<ChildNumber> BIP44_BTC_NON_SEGWIT_ACCOUNT_PATH = ImmutableList.of(
            new ChildNumber(44, true),
            new ChildNumber(0, true),
            ChildNumber.ZERO_HARDENED);

    public static final ImmutableList<ChildNumber> BIP44_BTC_SEGWIT_ACCOUNT_PATH = ImmutableList.of(
            new ChildNumber(44, true),
            new ChildNumber(0, true),
            ChildNumber.ONE_HARDENED);

    @Override
    public ImmutableList<ChildNumber> accountPathFor(Script.ScriptType outputScriptType) {
        if (outputScriptType == null || outputScriptType == Script.ScriptType.P2PKH)
            return BIP44_BTC_NON_SEGWIT_ACCOUNT_PATH;
        else if (outputScriptType == Script.ScriptType.P2WPKH)
            return BIP44_BTC_SEGWIT_ACCOUNT_PATH;
        else
            throw new IllegalArgumentException(outputScriptType.toString());
    }
}
