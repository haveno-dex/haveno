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

package bisq.asset.coins;

import bisq.asset.AddressValidationResult;
import bisq.asset.Base58AddressValidator;
import bisq.asset.Coin;
import bisq.asset.NetworkParametersAdapter;

public class QMCoin extends Coin {

    public QMCoin() {
        super("QMCoin", "QMCoin", new QMCoinAddressValidator());
    }


    public static class QMCoinAddressValidator extends Base58AddressValidator {

        public QMCoinAddressValidator() {
            super(new QMCoinParams());
        }

        @Override
        public AddressValidationResult validate(String address) {
            if (!address.matches("^[Q][a-km-zA-HJ-NP-Z1-9]{24,33}$"))
                return AddressValidationResult.invalidStructure();

            return super.validate(address);
        }
    }


    public static class QMCoinParams extends NetworkParametersAdapter {

        public QMCoinParams() {
            addressHeader = 58;
            p2shHeader = 120;
        }
    }
}
