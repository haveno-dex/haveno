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

package haveno.desktop.main.support.dispute.client.mediation;

import haveno.common.config.Config;
import haveno.common.crypto.KeyRing;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.alert.PrivateNotificationManager;
import haveno.core.locale.Res;
import haveno.core.support.SupportType;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeSession;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.support.dispute.mediation.MediationManager;
import haveno.core.support.dispute.mediation.MediationSession;
import haveno.core.trade.Contract;
import haveno.core.trade.TradeManager;
import haveno.core.user.Preferences;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.ContractWindow;
import haveno.desktop.main.overlays.windows.DisputeSummaryWindow;
import haveno.desktop.main.overlays.windows.TradeDetailsWindow;
import haveno.desktop.main.support.dispute.client.DisputeClientView;
import haveno.network.p2p.NodeAddress;

import javax.inject.Inject;
import javax.inject.Named;

@FxmlView
public class MediationClientView extends DisputeClientView {
    @Inject
    public MediationClientView(MediationManager mediationManager,
                               KeyRing keyRing,
                               TradeManager tradeManager,
                               @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter,
                               Preferences preferences,
                               DisputeSummaryWindow disputeSummaryWindow,
                               PrivateNotificationManager privateNotificationManager,
                               ContractWindow contractWindow,
                               TradeDetailsWindow tradeDetailsWindow,
                               AccountAgeWitnessService accountAgeWitnessService,
                               ArbitratorManager arbitratorManager,
                               @Named(Config.USE_DEV_PRIVILEGE_KEYS) boolean useDevPrivilegeKeys) {
        super(mediationManager, keyRing, tradeManager, formatter, preferences, disputeSummaryWindow,
                privateNotificationManager, contractWindow, tradeDetailsWindow, accountAgeWitnessService,
                arbitratorManager, useDevPrivilegeKeys);
    }

    @Override
    public void initialize() {
        super.initialize();
        reOpenButton.setVisible(true);
        reOpenButton.setManaged(true);
        closeButton.setVisible(true);
        closeButton.setManaged(true);
        setupReOpenDisputeListener();
    }

    @Override
    protected void activate() {
        super.activate();
        activateReOpenDisputeListener();
    }

    @Override
    protected void deactivate() {
        super.deactivate();
        deactivateReOpenDisputeListener();
    }

    @Override
    protected SupportType getType() {
        return SupportType.MEDIATION;
    }

    @Override
    protected DisputeSession getConcreteDisputeChatSession(Dispute dispute) {
        return new MediationSession(dispute, disputeManager.isTrader(dispute));
    }

    @Override
    protected void reOpenDisputeFromButton() {
        new Popup().attention(Res.get("support.reOpenByTrader.prompt"))
                .actionButtonText(Res.get("shared.yes"))
                .onAction(() -> reOpenDispute())
                .show();
    }

    @Override
    protected NodeAddress getAgentNodeAddress(Contract contract) {
        throw new RuntimeException("MediationClientView.getAgentNodeAddress() not implementd for XMR");
        //return contract.getMediatorNodeAddress();
    }

    @Override
    protected void maybeAddAgentColumn() {
        tableView.getColumns().add(getAgentColumn());
    }
}
