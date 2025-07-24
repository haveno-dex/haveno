package haveno.cli.table.builder;

import haveno.cli.table.Table;
import haveno.cli.table.column.Column;
import haveno.cli.table.column.PiconeroColumn;
import haveno.proto.grpc.XmrBalanceInfo;

import java.util.List;

import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_AVAILABLE_BALANCE;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_LOCKED_BALANCE;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_RESERVED_BALANCE;
import static haveno.cli.table.builder.TableBuilderConstants.COL_HEADER_TOTAL_AVAILABLE_BALANCE;
import static haveno.cli.table.builder.TableType.XMR_BALANCE_TBL;

public class XmrBalanceTableBuilder extends AbstractTableBuilder {

    private final Column<Long> colAvailableBalance;
    private final Column<Long> colReservedBalance;
    private final Column<Long> colTotalAvailableBalance;
    private final Column<Long> colLockedBalance;

    public XmrBalanceTableBuilder(List<?> protos) {
        super(XMR_BALANCE_TBL, protos);
        this.colAvailableBalance = new PiconeroColumn(COL_HEADER_AVAILABLE_BALANCE);
        this.colReservedBalance = new PiconeroColumn(COL_HEADER_RESERVED_BALANCE);
        this.colTotalAvailableBalance = new PiconeroColumn(COL_HEADER_TOTAL_AVAILABLE_BALANCE);
        this.colLockedBalance = new PiconeroColumn(COL_HEADER_LOCKED_BALANCE);
    }

    @Override
    public Table build() {
        XmrBalanceInfo balance = (XmrBalanceInfo) protos.get(0);

        // Populate columns with xmr balance info.
        colAvailableBalance.addRow(balance.getAvailableBalance());
        colReservedBalance.addRow(balance.getReservedOfferBalance() + balance.getReservedTradeBalance());
        colTotalAvailableBalance.addRow(balance.getAvailableBalance() + balance.getReservedOfferBalance() + balance.getReservedTradeBalance());
        colLockedBalance.addRow(balance.getBalance() - balance.getAvailableBalance() - balance.getPendingBalance());

        // Define and return the table instance with populated columns.
        return new Table(colAvailableBalance.asStringColumn(),
                colReservedBalance.asStringColumn(),
                colTotalAvailableBalance.asStringColumn(),
                colLockedBalance.asStringColumn());
    }
}
