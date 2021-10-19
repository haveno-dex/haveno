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

package bisq.core.app.misc;

import bisq.core.account.sign.SignedWitnessService;
import bisq.core.account.witness.AccountAgeWitnessService;
import bisq.core.filter.FilterManager;
import bisq.core.trade.statistics.TradeStatisticsManager;

import bisq.network.p2p.P2PService;
import bisq.network.p2p.peers.PeerManager;
import bisq.network.p2p.storage.P2PDataStorage;

import bisq.common.config.Config;

import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;

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
