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

package bisq.core.support.dispute.arbitration.arbitrator;

import bisq.core.filter.FilterManager;
import bisq.core.support.dispute.agent.DisputeAgentManager;
import bisq.core.user.User;

import bisq.network.p2p.storage.payload.ProtectedStorageEntry;

import bisq.common.config.Config;
import bisq.common.crypto.KeyRing;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.inject.Named;

import java.util.List;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class ArbitratorManager extends DisputeAgentManager<Arbitrator> {

    @Inject
    public ArbitratorManager(KeyRing keyRing,
                             ArbitratorService arbitratorService,
                             User user,
                             FilterManager filterManager,
                             @Named(Config.USE_DEV_PRIVILEGE_KEYS) boolean useDevPrivilegeKeys) {
        super(keyRing, arbitratorService, user, filterManager, useDevPrivilegeKeys);
    }

    @Override
    protected List<String> getPubKeyList() {
        return List.of("03bb559ce207a4deb51d4c705076c95b85ad8581d35936b2a422dcb504eaf7cdb0",
                "026c581ad773d987e6bd10785ac7f7e0e64864aedeb8bce5af37046de812a37854",
                "025b058c9f2c60d839669dbfa5578cf5a8117d60e6b70e2f0946f8a691273c6a36",
                "036c7d3f4bf05ef39b9d1b0a5d453a18210de36220c3d83cd16e59bd6132b037ad",
                "030f7122a10ff73cd73808bddace95be77a94189c8a0eb24586265e125ce5ce6b9",
                "03aa23e062afa0dda465f46986f8aa8d0374ad3e3f256141b05681dcb1e39c3859",
                "02d3beb1293ca2ca14e6d42ca8bd18089a62aac62fd6bb23923ee6ead46ac60fba",
                "03fa0f38f27bdd324db6f933f7e57851dadf3b911e4db6b19dd0950492c4525a31",
                "02a1a458df5acf4ab08fdca748e28f33a955a30854c8c1a831ee733dca7f0d2fcd",
                "0374dd70f3fa6e47ec5ab97932e1cec6233e98e6ae3129036b17118650c44fd3de");
    }

    @Override
    protected boolean isExpectedInstance(ProtectedStorageEntry data) {
        return data.getProtectedStoragePayload() instanceof Arbitrator;
    }

    @Override
    protected void addAcceptedDisputeAgentToUser(Arbitrator disputeAgent) {
        user.addAcceptedArbitrator(disputeAgent);
    }

    @Override
    protected void removeAcceptedDisputeAgentFromUser(ProtectedStorageEntry data) {
        user.removeAcceptedArbitrator((Arbitrator) data.getProtectedStoragePayload());
    }

    @Override
    protected List<Arbitrator> getAcceptedDisputeAgentsFromUser() {
        return user.getAcceptedArbitrators();
    }

    @Override
    protected void clearAcceptedDisputeAgentsAtUser() {
        user.clearAcceptedArbitrators();
    }

    @Override
    protected Arbitrator getRegisteredDisputeAgentFromUser() {
        return user.getRegisteredArbitrator();
    }

    @Override
    protected void setRegisteredDisputeAgentAtUser(Arbitrator disputeAgent) {
        user.setRegisteredArbitrator(disputeAgent);
    }
}
