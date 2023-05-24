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
import haveno.cli.table.column.SatoshiColumn;
import haveno.proto.grpc.BtcBalanceInfo;

import java.util.List;

import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_AVAILABLE_BALANCE;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_LOCKED_BALANCE;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_RESERVED_BALANCE;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_TOTAL_AVAILABLE_BALANCE;
import static haveno.cli.table.builder.TableType.BTC_BALANCE_TBL;

/**
 * Builds a {@code haveno.cli.table.Table} from a
 * {@code haveno.proto.grpc.BtcBalanceInfo} object.
 */
class BtcBalanceTableBuilder extends AbstractTableBuilder {

    // Default columns not dynamically generated with btc balance info.
    private final Column<Long> colAvailableBalance;
    private final Column<Long> colReservedBalance;
    private final Column<Long> colTotalAvailableBalance;
    private final Column<Long> colLockedBalance;

    BtcBalanceTableBuilder(List<?> protos) {
        super(BTC_BALANCE_TBL, protos);
        this.colAvailableBalance = new SatoshiColumn(COL_HEADER_AVAILABLE_BALANCE);
        this.colReservedBalance = new SatoshiColumn(COL_HEADER_RESERVED_BALANCE);
        this.colTotalAvailableBalance = new SatoshiColumn(COL_HEADER_TOTAL_AVAILABLE_BALANCE);
        this.colLockedBalance = new SatoshiColumn(COL_HEADER_LOCKED_BALANCE);
    }

    @Override
    public Table build() {
        BtcBalanceInfo balance = (BtcBalanceInfo) protos.get(0);

        // Populate columns with btc balance info.

        colAvailableBalance.addRow(balance.getAvailableBalance());
        colReservedBalance.addRow(balance.getReservedBalance());
        colTotalAvailableBalance.addRow(balance.getTotalAvailableBalance());
        colLockedBalance.addRow(balance.getLockedBalance());

        // Define and return the table instance with populated columns.

        return new Table(colAvailableBalance.asStringColumn(),
                colReservedBalance.asStringColumn(),
                colTotalAvailableBalance.asStringColumn(),
                colLockedBalance.asStringColumn());
    }
}
