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

package haveno.core.alert;

import com.google.inject.Singleton;
import haveno.common.app.AppModule;
import haveno.common.config.Config;

import static com.google.inject.name.Names.named;
import static haveno.common.config.Config.IGNORE_DEV_MSG;

public class AlertModule extends AppModule {

    public AlertModule(Config config) {
        super(config);
    }

    @Override
    protected final void configure() {
        bind(AlertManager.class).in(Singleton.class);
        bind(PrivateNotificationManager.class).in(Singleton.class);
        bindConstant().annotatedWith(named(IGNORE_DEV_MSG)).to(config.ignoreDevMsg);
    }
}
