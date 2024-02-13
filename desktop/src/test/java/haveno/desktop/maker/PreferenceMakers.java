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

package haveno.desktop.maker;

import com.natpryce.makeiteasy.Instantiator;
import com.natpryce.makeiteasy.Property;
import com.natpryce.makeiteasy.SameValueDonor;
import haveno.common.config.Config;
import haveno.common.persistence.PersistenceManager;
import haveno.core.api.XmrLocalNode;
import haveno.core.user.Preferences;

import static com.natpryce.makeiteasy.MakeItEasy.a;
import static com.natpryce.makeiteasy.MakeItEasy.make;

public class PreferenceMakers {

    public static final Property<Preferences, PersistenceManager> storage = new Property<>();
    public static final Property<Preferences, Config> config = new Property<>();
    public static final Property<Preferences, XmrLocalNode> xmrLocalNode = new Property<>();
    public static final Property<Preferences, String> useTorFlagFromOptions = new Property<>();
    public static final Property<Preferences, String> referralID = new Property<>();

    public static final Instantiator<Preferences> Preferences = lookup -> new Preferences(
            lookup.valueOf(storage, new SameValueDonor<PersistenceManager>(null)),
            lookup.valueOf(config, new SameValueDonor<Config>(null)),
            lookup.valueOf(useTorFlagFromOptions, new SameValueDonor<String>(null))
            );

    public static final Preferences empty = make(a(Preferences));

}
