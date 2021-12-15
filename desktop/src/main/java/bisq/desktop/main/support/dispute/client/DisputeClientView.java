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

package bisq.desktop.main.support.dispute.client;

import bisq.desktop.main.overlays.windows.ContractWindow;
import bisq.desktop.main.overlays.windows.DisputeSummaryWindow;
import bisq.desktop.main.overlays.windows.TradeDetailsWindow;
import bisq.desktop.main.support.dispute.DisputeView;

import bisq.core.account.witness.AccountAgeWitnessService;
import bisq.core.alert.PrivateNotificationManager;
import bisq.core.support.dispute.Dispute;
import bisq.core.support.dispute.DisputeList;
import bisq.core.support.dispute.DisputeManager;
import bisq.core.support.dispute.mediation.mediator.MediatorManager;
import bisq.core.support.dispute.refund.refundagent.RefundAgentManager;
import bisq.core.trade.TradeManager;
import bisq.core.user.Preferences;
import bisq.core.util.coin.CoinFormatter;

import bisq.common.crypto.KeyRing;

public abstract class DisputeClientView extends DisputeView {
    public DisputeClientView(DisputeManager<? extends DisputeList<Dispute>> DisputeManager,
                             KeyRing keyRing,
                             TradeManager tradeManager,
                             CoinFormatter formatter,
                             Preferences preferences,
                             DisputeSummaryWindow disputeSummaryWindow,
                             PrivateNotificationManager privateNotificationManager,
                             ContractWindow contractWindow,
                             TradeDetailsWindow tradeDetailsWindow,
                             AccountAgeWitnessService accountAgeWitnessService,
                             MediatorManager mediatorManager,
                             RefundAgentManager refundAgentManager,
                             boolean useDevPrivilegeKeys) {
        super(DisputeManager, keyRing, tradeManager, formatter, preferences, disputeSummaryWindow, privateNotificationManager,
                contractWindow, tradeDetailsWindow, accountAgeWitnessService,
                mediatorManager, refundAgentManager, useDevPrivilegeKeys);
    }

    @Override
    protected DisputeView.FilterResult getFilterResult(Dispute dispute, String filterString) {
        // As we are in the client view we hide disputes where we are the agent
        if (dispute.getAgentPubKeyRing().equals(keyRing.getPubKeyRing())) {
            return FilterResult.NO_MATCH;
        }

        return super.getFilterResult(dispute, filterString);
    }

    @Override
    protected void maybeAddChatColumnForClient() {
        tableView.getColumns().add(getChatColumn());
    }

    @Override
    protected boolean senderFlag() {
        return false;
    }
}
