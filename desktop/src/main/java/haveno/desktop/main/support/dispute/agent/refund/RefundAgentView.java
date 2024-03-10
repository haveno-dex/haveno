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

package haveno.desktop.main.support.dispute.agent.refund;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import haveno.common.config.Config;
import haveno.common.crypto.KeyRing;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.alert.PrivateNotificationManager;
import haveno.core.locale.Res;
import haveno.core.support.SupportType;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.DisputeSession;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.support.dispute.refund.RefundManager;
import haveno.core.support.dispute.refund.RefundSession;
import haveno.core.trade.TradeManager;
import haveno.core.user.Preferences;
import haveno.core.util.FormattingUtils;
import haveno.core.util.coin.CoinFormatter;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.ContractWindow;
import haveno.desktop.main.overlays.windows.DisputeSummaryWindow;
import haveno.desktop.main.overlays.windows.TradeDetailsWindow;
import haveno.desktop.main.support.dispute.agent.DisputeAgentView;

@FxmlView
public class RefundAgentView extends DisputeAgentView {

    @Inject
    public RefundAgentView(RefundManager refundManager,
                           KeyRing keyRing,
                           TradeManager tradeManager,
                           @Named(FormattingUtils.BTC_FORMATTER_KEY) CoinFormatter formatter,
                           Preferences preferences,
                           DisputeSummaryWindow disputeSummaryWindow,
                           PrivateNotificationManager privateNotificationManager,
                           ContractWindow contractWindow,
                           TradeDetailsWindow tradeDetailsWindow,
                           AccountAgeWitnessService accountAgeWitnessService,
                           ArbitratorManager arbitratorService,
                           @Named(Config.USE_DEV_PRIVILEGE_KEYS) boolean useDevPrivilegeKeys) {
        super(refundManager,
                keyRing,
                tradeManager,
                formatter,
                preferences,
                disputeSummaryWindow,
                privateNotificationManager,
                contractWindow,
                tradeDetailsWindow,
                accountAgeWitnessService,
                arbitratorService,
                useDevPrivilegeKeys);
    }

    @Override
    protected SupportType getType() {
        return SupportType.REFUND;
    }

    @Override
    protected DisputeSession getConcreteDisputeChatSession(Dispute dispute) {
        return new RefundSession(dispute, disputeManager.isTrader(dispute));
    }

    @Override
    protected void onCloseDispute(Dispute dispute) {
        long protocolVersion = dispute.getContract().getOfferPayload().getProtocolVersion();
        // Refund agent was introduced with protocolVersion version 2. We do not support old trade protocol cases.
        if (protocolVersion >= 2) {
            chatPopup.closeChat();
            disputeSummaryWindow.show(dispute);
        } else {
            new Popup().warning(Res.get("support.wrongVersion", protocolVersion)).show();
        }
    }
}
