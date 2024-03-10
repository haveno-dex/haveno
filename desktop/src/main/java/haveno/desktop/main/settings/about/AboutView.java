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

package haveno.desktop.main.settings.about;

import com.google.inject.Inject;
import haveno.common.app.Version;
import haveno.core.locale.Res;
import haveno.desktop.common.view.ActivatableView;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.HyperlinkWithIcon;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addHyperlinkWithIcon;
import static haveno.desktop.util.FormBuilder.addLabel;
import static haveno.desktop.util.FormBuilder.addTitledGroupBg;
import haveno.desktop.util.Layout;
import javafx.geometry.HPos;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;

@FxmlView
public class AboutView extends ActivatableView<GridPane, Void> {

    private int gridRow = 0;

    @Inject
    public AboutView() {
        super();
    }

    @Override
    public void initialize() {
        addTitledGroupBg(root, gridRow, 4, Res.get("setting.about.aboutHaveno"));

        Label label = addLabel(root, gridRow, Res.get("setting.about.about"), Layout.TWICE_FIRST_ROW_DISTANCE);
        label.setWrapText(true);
        GridPane.setColumnSpan(label, 2);
        GridPane.setHalignment(label, HPos.LEFT);
        HyperlinkWithIcon hyperlinkWithIcon = addHyperlinkWithIcon(root, ++gridRow, Res.get("setting.about.web"), "https://haveno.exchange");
        GridPane.setColumnSpan(hyperlinkWithIcon, 2);
        hyperlinkWithIcon = addHyperlinkWithIcon(root, ++gridRow, Res.get("setting.about.code"), "https://github.com/haveno-dex/haveno");
        GridPane.setColumnSpan(hyperlinkWithIcon, 2);
        hyperlinkWithIcon = addHyperlinkWithIcon(root, ++gridRow, Res.get("setting.about.agpl"), "https://github.com/haveno-dex/haveno/blob/master/LICENSE");
        GridPane.setColumnSpan(hyperlinkWithIcon, 2);

        addTitledGroupBg(root, ++gridRow, 2, Res.get("setting.about.support"), Layout.GROUP_DISTANCE);

        label = addLabel(root, gridRow, Res.get("setting.about.def"), Layout.TWICE_FIRST_ROW_AND_GROUP_DISTANCE);
        label.setWrapText(true);
        GridPane.setColumnSpan(label, 2);
        GridPane.setHalignment(label, HPos.LEFT);
        hyperlinkWithIcon = addHyperlinkWithIcon(root, ++gridRow, Res.get("setting.about.contribute"), "https://github.com/haveno-dex/haveno#support");
        GridPane.setColumnSpan(hyperlinkWithIcon, 2);

        boolean isXmr = Res.getBaseCurrencyCode().equals("XMR");
        addTitledGroupBg(root, ++gridRow, isXmr ? 3 : 2, Res.get("setting.about.providers"), Layout.GROUP_DISTANCE);

        label = addLabel(root, gridRow, Res.get(isXmr ? "setting.about.apisWithFee" : "setting.about.apis"), Layout.TWICE_FIRST_ROW_AND_GROUP_DISTANCE);
        label.setWrapText(true);
        GridPane.setHalignment(label, HPos.LEFT);
        addCompactTopLabelTextField(root, ++gridRow, Res.get("setting.about.pricesProvided"),
                "Haveno's pricenode (https://price.haveno.network)");
        if (isXmr)
            addCompactTopLabelTextField(root, ++gridRow, Res.get("setting.about.feeEstimation.label"), "Monero node");

        addTitledGroupBg(root, ++gridRow, 2, Res.get("setting.about.versionDetails"), Layout.GROUP_DISTANCE);
        addCompactTopLabelTextField(root, gridRow, Res.get("setting.about.version"), Version.VERSION, Layout.TWICE_FIRST_ROW_AND_GROUP_DISTANCE);
        addCompactTopLabelTextField(root, ++gridRow,
                Res.get("setting.about.subsystems.label"),
                Res.get("setting.about.subsystems.val",
                        Version.P2P_NETWORK_VERSION,
                        Version.getP2PMessageVersion(),
                        Version.LOCAL_DB_VERSION,
                        Version.TRADE_PROTOCOL_VERSION));

        addTitledGroupBg(root, ++gridRow, 18, Res.get("setting.about.shortcuts"), Layout.GROUP_DISTANCE);

        // basics
        addCompactTopLabelTextField(root, gridRow, Res.get("setting.about.shortcuts.menuNav"),
                Res.get("setting.about.shortcuts.menuNav.value"),
                Layout.TWICE_FIRST_ROW_AND_GROUP_DISTANCE);

        addCompactTopLabelTextField(root, ++gridRow, Res.get("setting.about.shortcuts.close"),
                Res.get("setting.about.shortcuts.close.value", "q", "w"));

        addCompactTopLabelTextField(root, ++gridRow, Res.get("setting.about.shortcuts.closePopup"),
                Res.get("setting.about.shortcuts.closePopup.value"));

        addCompactTopLabelTextField(root, ++gridRow, Res.get("setting.about.shortcuts.chatSendMsg"),
                Res.get("setting.about.shortcuts.chatSendMsg.value"));

        addCompactTopLabelTextField(root, ++gridRow, Res.get("setting.about.shortcuts.openDispute"),
                Res.get("setting.about.shortcuts.openDispute.value",
                        Res.get("setting.about.shortcuts.ctrlOrAltOrCmd", "o")));

        addCompactTopLabelTextField(root, ++gridRow, Res.get("setting.about.shortcuts.walletDetails"),
                Res.get("setting.about.shortcuts.ctrlOrAltOrCmd", "j"));

        addCompactTopLabelTextField(root, ++gridRow, Res.get("setting.about.shortcuts.openEmergencyXmrWalletTool"),
                Res.get("setting.about.shortcuts.ctrlOrAltOrCmd", "e"));

        addCompactTopLabelTextField(root, ++gridRow, Res.get("setting.about.shortcuts.showTorLogs"),
                Res.get("setting.about.shortcuts.ctrlOrAltOrCmd", "t"));

        // special
        addCompactTopLabelTextField(root, ++gridRow, Res.get("setting.about.shortcuts.removeStuckTrade"),
                Res.get("setting.about.shortcuts.removeStuckTrade.value",
                        Res.get("setting.about.shortcuts.ctrlOrAltOrCmd", "y")));

        addCompactTopLabelTextField(root, ++gridRow, Res.get("setting.about.shortcuts.manualPayoutTxWindow"),
                Res.get("setting.about.shortcuts.ctrlOrAltOrCmd", "g"));

        // for arbitrators
        addCompactTopLabelTextField(root, ++gridRow, Res.get("setting.about.shortcuts.registerArbitrator"),
                Res.get("setting.about.shortcuts.registerArbitrator.value",
                        Res.get("setting.about.shortcuts.ctrlOrAltOrCmd", "n")));

        addCompactTopLabelTextField(root, ++gridRow, Res.get("setting.about.shortcuts.registerMediator"),
                Res.get("setting.about.shortcuts.registerMediator.value",
                        Res.get("setting.about.shortcuts.ctrlOrAltOrCmd", "d")));

        addCompactTopLabelTextField(root, ++gridRow, Res.get("setting.about.shortcuts.openSignPaymentAccountsWindow"),
                Res.get("setting.about.shortcuts.openSignPaymentAccountsWindow.value",
                        Res.get("setting.about.shortcuts.ctrlOrAltOrCmd", "s")));

        // only for maintainers
        addCompactTopLabelTextField(root, ++gridRow, Res.get("setting.about.shortcuts.sendAlertMsg"),
                Res.get("setting.about.shortcuts.ctrlOrAltOrCmd", "m"));

        addCompactTopLabelTextField(root, ++gridRow, Res.get("setting.about.shortcuts.sendFilter"),
                Res.get("setting.about.shortcuts.ctrlOrAltOrCmd", "f"));

        addCompactTopLabelTextField(root, ++gridRow, Res.get("setting.about.shortcuts.sendPrivateNotification"),
                Res.get("setting.about.shortcuts.sendPrivateNotification.value",
                        Res.get("setting.about.shortcuts.ctrlOrAltOrCmd", "r")));

        // Not added:
        // allTradesWithReferralId, allOffersWithReferralId -> ReferralId is not used yet
        // revert tx -> not tested well, high risk
        // debug window -> not maintained, only for devs working on trade protocol relevant
    }

    @Override
    public void activate() {
    }

    @Override
    public void deactivate() {
    }

}

