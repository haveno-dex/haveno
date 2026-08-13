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
import haveno.cli.table.column.Column;
import haveno.cli.table.column.PiconeroColumn;
import haveno.proto.grpc.XmrBalanceInfo;

import java.util.List;

import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_AVAILABLE_BALANCE;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_PENDING_BALANCE;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_RESERVED_OFFER_BALANCE;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_RESERVED_TRADE_BALANCE;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_TOTAL_BALANCE;
import static haveno.cli.table.builder.TableType.XMR_BALANCE_TBL;

/**
 * Builds a {@code haveno.cli.table.Table} from a
 * {@code haveno.proto.grpc.XmrBalanceInfo} object.
 */
class XmrBalanceTableBuilder extends AbstractTableBuilder {

    // Default columns not dynamically generated with xmr balance info.
    private final Column<Long> colTotalBalance;
    private final Column<Long> colAvailableBalance;
    private final Column<Long> colPendingBalance;
    private final Column<Long> colReservedOfferBalance;
    private final Column<Long> colReservedTradeBalance;

    XmrBalanceTableBuilder(List<?> protos) {
        super(XMR_BALANCE_TBL, protos);
        this.colTotalBalance = new PiconeroColumn(COL_HEADER_TOTAL_BALANCE);
        this.colAvailableBalance = new PiconeroColumn(COL_HEADER_AVAILABLE_BALANCE);
        this.colPendingBalance = new PiconeroColumn(COL_HEADER_PENDING_BALANCE);
        this.colReservedOfferBalance = new PiconeroColumn(COL_HEADER_RESERVED_OFFER_BALANCE);
        this.colReservedTradeBalance = new PiconeroColumn(COL_HEADER_RESERVED_TRADE_BALANCE);
    }

    @Override
    public Table build() {
        XmrBalanceInfo balance = (XmrBalanceInfo) protos.get(0);

        // Populate columns with xmr balance info.

        colTotalBalance.addRow(balance.getBalance());
        colAvailableBalance.addRow(balance.getAvailableBalance());
        colPendingBalance.addRow(balance.getPendingBalance());
        colReservedOfferBalance.addRow(balance.getReservedOfferBalance());
        colReservedTradeBalance.addRow(balance.getReservedTradeBalance());

        // Define and return the table instance with populated columns.

        return new Table(colTotalBalance.asStringColumn(),
                colAvailableBalance.asStringColumn(),
                colPendingBalance.asStringColumn(),
                colReservedOfferBalance.asStringColumn(),
                colReservedTradeBalance.asStringColumn());
    }
}
