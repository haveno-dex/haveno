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

package haveno.desktop.main.overlays.windows;

import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.core.locale.Res;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.Preferences;
import haveno.core.xmr.wallet.XmrWalletService;
import javafx.scene.Node;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Success dialog after a withdrawal, showing the sent amount, destination, fee and tx id. */
public class TxWithdrawWindow extends TxHeroWindow<TxWithdrawWindow> {

    private final String txId;
    private final String address;
    private final BigInteger amount;
    private final BigInteger fee;
    private final String memo;
    private final Function<BigInteger, String> fiatText; // approximate fiat for an amount, or null while no price
    private final XmrWalletService xmrWalletService;
    private final Preferences preferences;

    public TxWithdrawWindow(String txId, String address, BigInteger amount, BigInteger fee, String memo,
                            Function<BigInteger, String> fiatText, XmrWalletService xmrWalletService, Preferences preferences) {
        this.txId = txId;
        this.address = address;
        this.amount = amount;
        this.fee = fee;
        this.memo = memo;
        this.fiatText = fiatText;
        this.xmrWalletService = xmrWalletService;
        this.preferences = preferences;
    }

    @Override
    public void show() {
        showHeroWindow();
    }

    @Override
    protected void addContent() {
        List<Node> groups = new ArrayList<>();
        groups.add(sheetGroup(Res.get("funds.withdrawal.confirm.to"), wrappedLabel(address, "confirm-send-address"), copyIcon(address)));
        groups.add(detailRow(Res.get("funds.withdrawal.confirm.networkFee"), valueLabel(HavenoUtils.formatXmr(fee, true))));
        if (memo != null && !memo.isEmpty()) groups.add(sheetGroup(Res.get("funds.withdrawal.sent.note"), wrappedLabel(memo, "confirm-send-row-value")));
        groups.add(txIdGroup(txId, xmrWalletService, preferences));

        addHeroContent(
                hero(MaterialDesignIcon.CHECK, "tx-sent-icon", Res.get("funds.withdrawal.sent.headline"), amount, fiatText.apply(amount)),
                sheet(groups.toArray(new Node[0])));
    }
}
