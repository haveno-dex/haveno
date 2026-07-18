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

package haveno.desktop.main.overlays.windows;

import com.google.inject.Inject;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.core.locale.Res;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.Preferences;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.main.funds.transactions.TransactionsListItem;
import haveno.desktop.util.GUIUtil;
import javafx.scene.Node;
import monero.wallet.model.MoneroTxWallet;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Details of a wallet transaction, shown as a sent or received hero amount. */
public class TxDetailsWindow extends TxHeroWindow<TxDetailsWindow> {

    private final XmrWalletService xmrWalletService;
    private final Preferences preferences;
    private final PriceFeedService priceFeedService;
    private TransactionsListItem item;

    @Inject
    public TxDetailsWindow(XmrWalletService xmrWalletService, Preferences preferences, PriceFeedService priceFeedService) {
        this.xmrWalletService = xmrWalletService;
        this.preferences = preferences;
        this.priceFeedService = priceFeedService;
    }

    public void show(TransactionsListItem item) {
        this.item = item;
        showHeroWindow();
    }

    @Override
    protected void addContent() {
        MoneroTxWallet tx = item.getTx();
        boolean isOutgoing = tx.getOutgoingTransfer() != null;
        BigInteger amount = isOutgoing ? tx.getOutgoingAmount() : tx.getIncomingAmount();
        String memo = tx.getNote();
        String txKey = null;
        if (isOutgoing) {
            try {
                synchronized (xmrWalletService.getWalletLock()) {
                    txKey = xmrWalletService.getWallet().getTxKey(tx.getHash());
                }
            } catch (Exception e) {
                // TODO (monero-java): wallet.getTxKey() should return null if key does not exist instead of throwing exception
            }
        }

        List<Node> groups = new ArrayList<>();
        groups.add(detailRow(Res.get("shared.dateTime"), valueLabel(item.getDateString())));
        groups.add(sheetGroup(Res.get(isOutgoing ? "txDetailsWindow.sentTo" : "txDetailsWindow.receivedWith"),
                wrappedLabel(item.getAddressString(), "confirm-send-address"), copyIcon(item.getAddressString())));
        if (isOutgoing) groups.add(detailRow(Res.get("funds.withdrawal.confirm.networkFee"), valueLabel(HavenoUtils.formatXmr(tx.getFee(), true))));
        if (memo != null && !memo.isEmpty()) groups.add(sheetGroup(Res.get("funds.withdrawal.sent.note"), wrappedLabel(memo, "confirm-send-row-value")));
        if (txKey != null && !txKey.isEmpty()) groups.add(sheetGroup(Res.get("txDetailsWindow.txKey"), wrappedLabel(txKey, "confirm-send-address"), copyIcon(txKey)));
        groups.add(txIdGroup(tx.getHash(), xmrWalletService, preferences));

        String fiat = GUIUtil.getFiatText(amount, priceFeedService, preferences);
        addHeroContent(
                isOutgoing
                        ? hero(MaterialDesignIcon.SEND, "confirm-send-icon", Res.get("funds.withdrawal.sent.headline"), amount, fiat)
                        : hero(MaterialDesignIcon.ARROW_DOWN, "tx-sent-icon", Res.get("txDetailsWindow.received"), amount, fiat),
                sheet(groups.toArray(new Node[0])));
    }
}
