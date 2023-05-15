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

package haveno.desktop.maker;

import com.natpryce.makeiteasy.Instantiator;
import com.natpryce.makeiteasy.Maker;
import com.natpryce.makeiteasy.Property;
import haveno.core.monetary.CryptoMoney;
import haveno.core.monetary.TraditionalMoney;
import haveno.core.monetary.Volume;

import static com.natpryce.makeiteasy.MakeItEasy.a;

public class VolumeMaker {

    public static final Property<Volume, String> currencyCode = new Property<>();
    public static final Property<Volume, String> volumeString = new Property<>();

    public static final Instantiator<Volume> TraditionalMoneyVolume = lookup ->
            new Volume(TraditionalMoney.parseTraditionalMoney(lookup.valueOf(currencyCode, "USD"), lookup.valueOf(volumeString, "100")));

    public static final Instantiator<Volume> CryptoVolume = lookup ->
            new Volume(CryptoMoney.parseCrypto(lookup.valueOf(currencyCode, "LTC"), lookup.valueOf(volumeString, "100")));

    public static final Maker<Volume> usdVolume = a(TraditionalMoneyVolume);
    public static final Maker<Volume> ltcVolume = a(CryptoVolume);
}
