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

package haveno.asset.coins;

import haveno.asset.AltCoinAccountDisclaimer;
import haveno.asset.Coin;
import haveno.asset.I18n;
import haveno.asset.RegexAddressValidator;

@AltCoinAccountDisclaimer("account.altcoin.popup.XZC.msg")
public class Zcoin extends Coin {

	public Zcoin() {
		super("Zcoin", "XZC", new XzcAddressValidator());
    }
    
    public static class XzcAddressValidator extends RegexAddressValidator {

        public XzcAddressValidator() {
            super("^a?[a-zA-Z0-9]{33}", I18n.DISPLAY_STRINGS.getString("account.altcoin.popup.validation.XZC"));
        }
    }
}
