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

package haveno.desktop.main.support.dispute.agent.arbitration;

import haveno.desktop.common.view.FxmlView;
import haveno.desktop.main.overlays.windows.ContractWindow;
import haveno.desktop.main.overlays.windows.DisputeSummaryWindow;
import haveno.desktop.main.overlays.windows.TradeDetailsWindow;
import haveno.desktop.main.support.dispute.agent.DisputeAgentView;

import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.alert.PrivateNotificationManager;
import haveno.core.support.SupportType;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeSession;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.support.dispute.arbitration.ArbitrationSession;
import haveno.core.support.dispute.mediation.mediator.MediatorManager;
import haveno.core.support.dispute.refund.refundagent.RefundAgentManager;
import haveno.core.trade.TradeManager;
import haveno.core.user.Preferences;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;

import haveno.common.config.Config;
import haveno.common.crypto.KeyRing;

import javax.inject.Inject;
import javax.inject.Named;

@FxmlView
public class ArbitratorView extends DisputeAgentView {

    @Inject
    public ArbitratorView(ArbitrationManager arbitrationManager,
                          KeyRing keyRing,
                          TradeManager tradeManager,
                          @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter,
                          Preferences preferences,
                          DisputeSummaryWindow disputeSummaryWindow,
                          PrivateNotificationManager privateNotificationManager,
                          ContractWindow contractWindow,
                          TradeDetailsWindow tradeDetailsWindow,
                          AccountAgeWitnessService accountAgeWitnessService,
                          MediatorManager mediatorManager,
                          RefundAgentManager refundAgentManager,
                          @Named(Config.USE_DEV_PRIVILEGE_KEYS) boolean useDevPrivilegeKeys) {
        super(arbitrationManager,
                keyRing,
                tradeManager,
                formatter,
                preferences,
                disputeSummaryWindow,
                privateNotificationManager,
                contractWindow,
                tradeDetailsWindow,
                accountAgeWitnessService,
                mediatorManager,
                refundAgentManager,
                useDevPrivilegeKeys);
    }

    @Override
    protected SupportType getType() {
        return SupportType.ARBITRATION;
    }

    @Override
    protected DisputeSession getConcreteDisputeChatSession(Dispute dispute) {
        return new ArbitrationSession(dispute, disputeManager.isTrader(dispute));
    }

    @Override
    protected void onCloseDispute(Dispute dispute) {
        long protocolVersion = dispute.getContract().getOfferPayload().getProtocolVersion();
        // Only cases with protocolVersion 1 are candidates for legacy arbitration.
        // This code path is not tested and it is not assumed that it is still be used as old arbitrators would use
        // their old Haveno version if still cases are pending.
//        if (protocolVersion == 1) {
            chatPopup.closeChat();
            disputeSummaryWindow.show(dispute);
//        } else {
//            new Popup().warning(Res.get("support.wrongVersion", protocolVersion)).show();
//        }
    }
}
