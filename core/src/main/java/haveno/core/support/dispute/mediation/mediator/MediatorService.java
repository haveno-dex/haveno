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

package haveno.core.support.dispute.mediation.mediator;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.core.filter.FilterManager;
import haveno.core.support.dispute.agent.DisputeAgentService;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@Singleton
public class MediatorService extends DisputeAgentService<Mediator> {

    @Inject
    public MediatorService(P2PService p2PService, FilterManager filterManager) {
        super(p2PService, filterManager);
    }

    @Override
    protected Set<Mediator> getDisputeAgentSet(List<String> bannedDisputeAgents) {
        return p2PService.getDataMap().values().stream()
                .filter(data -> data.getProtectedStoragePayload() instanceof Mediator)
                .map(data -> (Mediator) data.getProtectedStoragePayload())
                .filter(a -> bannedDisputeAgents == null ||
                        !bannedDisputeAgents.contains(a.getNodeAddress().getFullAddress()))
                .collect(Collectors.toSet());
    }

    @Override
    protected List<String> getDisputeAgentsFromFilter() {
        return filterManager.getFilter() != null ? filterManager.getFilter().getMediators() : new ArrayList<>();
    }

    public Map<NodeAddress, Mediator> getMediators() {
        return super.getDisputeAgents();
    }
}
