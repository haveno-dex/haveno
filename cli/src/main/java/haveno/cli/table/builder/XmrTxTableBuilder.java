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

package haveno.cli.table.builder;

import haveno.cli.table.Table;
import haveno.cli.table.column.BooleanColumn;
import haveno.cli.table.column.Column;
import haveno.cli.table.column.Iso8601DateTimeColumn;
import haveno.cli.table.column.LongColumn;
import haveno.cli.table.column.PiconeroColumn;
import haveno.cli.table.column.StringColumn;
import haveno.proto.grpc.XmrIncomingTransfer;
import haveno.proto.grpc.XmrTx;

import java.util.List;
import java.util.stream.Collectors;

import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_DATE_TIME;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_HEIGHT;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_TX_FEE;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_TX_ID;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_TX_INCOMING;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_TX_IS_CONFIRMED;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_TX_IS_LOCKED;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_TX_OUTGOING;
import static haveno.cli.table.builder.TableType.XMR_TX_TBL;
import static haveno.cli.table.column.Column.JUSTIFICATION.LEFT;

/**
 * Builds a {@code haveno.cli.table.Table} from a list of
 * {@code haveno.proto.grpc.XmrTx} objects.
 */
class XmrTxTableBuilder extends AbstractTableBuilder {

    // Default columns not dynamically generated with xmr tx info.
    private final Column<String> colTxId;
    private final Column<Long> colHeight;
    private final Column<Long> colTimestamp;
    private final Column<Long> colIncoming;
    private final Column<Long> colOutgoing;
    private final Column<Long> colTxFee;
    private final Column<Boolean> colIsConfirmed;
    private final Column<Boolean> colIsLocked;

    XmrTxTableBuilder(List<?> protos) {
        super(XMR_TX_TBL, protos);
        this.colTxId = new StringColumn(COL_HEADER_TX_ID, LEFT);
        this.colHeight = new LongColumn(COL_HEADER_HEIGHT);
        this.colTimestamp = new Iso8601DateTimeColumn(COL_HEADER_DATE_TIME);
        this.colIncoming = new PiconeroColumn(COL_HEADER_TX_INCOMING);
        this.colOutgoing = new PiconeroColumn(COL_HEADER_TX_OUTGOING);
        this.colTxFee = new PiconeroColumn(COL_HEADER_TX_FEE);
        this.colIsConfirmed = new BooleanColumn(COL_HEADER_TX_IS_CONFIRMED);
        this.colIsLocked = new BooleanColumn(COL_HEADER_TX_IS_LOCKED);
    }

    @Override
    public Table build() {
        List<XmrTx> txs = protos.stream()
                .map(p -> (XmrTx) p)
                .collect(Collectors.toList());

        // Populate columns with xmr tx info.

        txs.forEach(tx -> {
            colTxId.addRow(tx.getHash());
            colHeight.addRow(tx.getHeight());
            colTimestamp.addRow(tx.getTimestamp() * 1000); // seconds -> ms
            colIncoming.addRow(toIncomingAmount(tx));
            colOutgoing.addRow(toOutgoingAmount(tx));
            colTxFee.addRow(toPiconeros(tx.getFee()));
            colIsConfirmed.addRow(tx.getIsConfirmed());
            colIsLocked.addRow(tx.getIsLocked());
        });

        // Define and return the table instance with populated columns.

        return new Table(colTxId,
                colHeight.asStringColumn(),
                colTimestamp.asStringColumn(),
                colIncoming.asStringColumn(),
                colOutgoing.asStringColumn(),
                colTxFee.asStringColumn(),
                colIsConfirmed.asStringColumn(),
                colIsLocked.asStringColumn());
    }

    private long toIncomingAmount(XmrTx tx) {
        return tx.getIncomingTransfersList().stream()
                .map(XmrIncomingTransfer::getAmount)
                .mapToLong(this::toPiconeros)
                .sum();
    }

    private long toOutgoingAmount(XmrTx tx) {
        return tx.hasOutgoingTransfer()
                ? toPiconeros(tx.getOutgoingTransfer().getAmount())
                : 0;
    }

    private long toPiconeros(String amount) {
        return amount.isEmpty() ? 0 : Long.parseLong(amount);
    }
}
