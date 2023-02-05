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

package bisq.cli.table.builder;

import bisq.proto.grpc.TradeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static bisq.cli.table.builder.TableType.TRADE_DETAIL_TBL;
import static java.lang.String.format;



import bisq.cli.table.Table;
import bisq.cli.table.column.Column;

/**
 * Builds a {@code bisq.cli.table.Table} from a {@code bisq.proto.grpc.TradeInfo} object.
 */
@SuppressWarnings("ConstantConditions")
class TradeDetailTableBuilder extends AbstractTradeListBuilder {

    TradeDetailTableBuilder(List<?> protos) {
        super(TRADE_DETAIL_TBL, protos);
    }

    /**
     * Build a single row trade detail table.
     * @return Table containing one row
     */
    @Override
    public Table build() {
        // A trade detail table only has one row.
        var trade = trades.get(0);
        populateColumns(trade);
        List<Column<?>> columns = defineColumnList(trade);
        return new Table(columns.toArray(new Column<?>[0]));
    }

    private void populateColumns(TradeInfo trade) {
        populateBisqV1TradeColumns(trade);
    }

    private void populateBisqV1TradeColumns(TradeInfo trade) {
        colTradeId.addRow(trade.getShortId());
        colRole.addRow(trade.getRole());
        colPrice.addRow(trade.getPrice());
        colAmount.addRow(toTradeAmount.apply(trade));
        colMinerTxFee.addRow(toMyMinerTxFee.apply(trade));
        colBisqTradeFee.addRow(toMyMakerOrTakerFee.apply(trade));
        colIsDepositPublished.addRow(trade.getIsDepositsPublished());
        colIsDepositConfirmed.addRow(trade.getIsDepositsUnlocked());
        colTradeCost.addRow(toTradeVolumeAsString.apply(trade));
        colIsPaymentSentMessageSent.addRow(trade.getIsPaymentSent());
        colIsPaymentReceivedMessageSent.addRow(trade.getIsPaymentReceived());
        colIsPayoutPublished.addRow(trade.getIsPayoutPublished());
        colIsCompleted.addRow(trade.getIsCompleted());
        if (colAltcoinReceiveAddressColumn != null)
            colAltcoinReceiveAddressColumn.addRow(toAltcoinReceiveAddress.apply(trade));
    }

    private List<Column<?>> defineColumnList(TradeInfo trade) {
        return getBisqV1TradeColumnList();
    }

    private List<Column<?>> getBisqV1TradeColumnList() {
        List<Column<?>> columns = new ArrayList<>() {{
            add(colTradeId);
            add(colRole);
            add(colPrice.justify());
            add(colAmount.asStringColumn());
            add(colMinerTxFee.asStringColumn());
            add(colBisqTradeFee.asStringColumn());
            add(colIsDepositPublished.asStringColumn());
            add(colIsDepositConfirmed.asStringColumn());
            add(colTradeCost.justify());
            add(colIsPaymentSentMessageSent.asStringColumn());
            add(colIsPaymentReceivedMessageSent.asStringColumn());
            add(colIsPayoutPublished.asStringColumn());
            add(colIsCompleted.asStringColumn());
        }};

        if (colAltcoinReceiveAddressColumn != null)
            columns.add(colAltcoinReceiveAddressColumn);

        return columns;
    }
}
