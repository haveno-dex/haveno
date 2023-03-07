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

package haveno.seednode;

import com.google.inject.Injector;
import haveno.core.app.misc.AppSetup;
import haveno.core.app.misc.AppSetupWithP2PAndDAO;
import haveno.core.network.p2p.inventory.GetInventoryRequestHandler;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SeedNode {
    @Setter
    private Injector injector;
    private AppSetup appSetup;
    private GetInventoryRequestHandler getInventoryRequestHandler;

    public SeedNode() {
    }

    public void startApplication() {
        appSetup = injector.getInstance(AppSetupWithP2PAndDAO.class);
        appSetup.start();

        getInventoryRequestHandler = injector.getInstance(GetInventoryRequestHandler.class);
    }

    public void shutDown() {
        getInventoryRequestHandler.shutDown();
    }
}
