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

package haveno.core.app.misc;

import haveno.common.config.Config;
import haveno.core.account.sign.SignedWitnessService;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.filter.FilterManager;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.peers.PeerManager;
import haveno.network.p2p.storage.P2PDataStorage;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;

@Slf4j
public class AppSetupWithP2PAndDAO extends AppSetupWithP2P {

    @Inject
    public AppSetupWithP2PAndDAO(P2PService p2PService,
                                 P2PDataStorage p2PDataStorage,
                                 PeerManager peerManager,
                                 TradeStatisticsManager tradeStatisticsManager,
                                 AccountAgeWitnessService accountAgeWitnessService,
                                 SignedWitnessService signedWitnessService,
                                 FilterManager filterManager,
                                 Config config) {
        super(p2PService,
                p2PDataStorage,
                peerManager,
                tradeStatisticsManager,
                accountAgeWitnessService,
                signedWitnessService,
                filterManager,
                config);


    }

    @Override
    protected void onBasicServicesInitialized() {
        super.onBasicServicesInitialized();
    }
}
