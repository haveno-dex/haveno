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

package haveno.desktop.main.support.dispute.client;

import haveno.common.crypto.KeyRing;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.alert.PrivateNotificationManager;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeList;
import haveno.core.support.dispute.DisputeManager;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.trade.TradeManager;
import haveno.core.user.Preferences;
import haveno.core.util.coin.CoinFormatter;
import haveno.desktop.main.overlays.windows.ContractWindow;
import haveno.desktop.main.overlays.windows.DisputeSummaryWindow;
import haveno.desktop.main.overlays.windows.TradeDetailsWindow;
import haveno.desktop.main.support.dispute.DisputeView;

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
                             ArbitratorManager arbitratorManager,
                             boolean useDevPrivilegeKeys) {
        super(DisputeManager, keyRing, tradeManager, formatter, preferences, disputeSummaryWindow, privateNotificationManager,
                contractWindow, tradeDetailsWindow, accountAgeWitnessService, arbitratorManager, useDevPrivilegeKeys);
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
