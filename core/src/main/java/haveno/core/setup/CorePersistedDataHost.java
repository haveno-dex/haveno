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

package haveno.core.setup;

import com.google.inject.Injector;
import haveno.common.proto.persistable.PersistedDataHost;
import haveno.core.offer.OpenOfferManager;
import haveno.core.support.dispute.arbitration.ArbitrationDisputeListService;
import haveno.core.support.dispute.mediation.MediationDisputeListService;
import haveno.core.support.dispute.refund.RefundDisputeListService;
import haveno.core.trade.ClosedTradableManager;
import haveno.core.trade.TradeManager;
import haveno.core.trade.failed.FailedTradesManager;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.xmr.model.AddressEntryList;
import haveno.core.xmr.model.EncryptedConnectionList;
import haveno.core.xmr.model.XmrAddressEntryList;
import haveno.network.p2p.mailbox.IgnoredMailboxService;
import haveno.network.p2p.mailbox.MailboxMessageService;
import haveno.network.p2p.peers.PeerManager;
import haveno.network.p2p.storage.P2PDataStorage;
import haveno.network.p2p.storage.persistence.RemovedPayloadsService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class CorePersistedDataHost {

    // All classes which are persisting objects need to be added here
    public static List<PersistedDataHost> getPersistedDataHosts(Injector injector) {
        List<PersistedDataHost> persistedDataHosts = new ArrayList<>();
        persistedDataHosts.add(injector.getInstance(Preferences.class));
        persistedDataHosts.add(injector.getInstance(User.class));
        persistedDataHosts.add(injector.getInstance(AddressEntryList.class));
        persistedDataHosts.add(injector.getInstance(XmrAddressEntryList.class));
        persistedDataHosts.add(injector.getInstance(EncryptedConnectionList.class));
        persistedDataHosts.add(injector.getInstance(OpenOfferManager.class));
        persistedDataHosts.add(injector.getInstance(TradeManager.class));
        persistedDataHosts.add(injector.getInstance(ClosedTradableManager.class));
        persistedDataHosts.add(injector.getInstance(FailedTradesManager.class));
        persistedDataHosts.add(injector.getInstance(ArbitrationDisputeListService.class));
        persistedDataHosts.add(injector.getInstance(MediationDisputeListService.class));
        persistedDataHosts.add(injector.getInstance(RefundDisputeListService.class));
        persistedDataHosts.add(injector.getInstance(P2PDataStorage.class));
        persistedDataHosts.add(injector.getInstance(PeerManager.class));
        persistedDataHosts.add(injector.getInstance(MailboxMessageService.class));
        persistedDataHosts.add(injector.getInstance(IgnoredMailboxService.class));
        persistedDataHosts.add(injector.getInstance(RemovedPayloadsService.class));

        return persistedDataHosts;
    }
}
