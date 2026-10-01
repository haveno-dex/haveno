package haveno.cli.table;

import haveno.cli.AbstractCliTest;
import haveno.cli.table.builder.TableBuilder;
import haveno.proto.grpc.XmrTx;
import org.junit.jupiter.api.Test;

import java.util.List;

import static haveno.cli.table.builder.TableType.XMR_BALANCE_TBL;
import static haveno.cli.table.builder.TableType.XMR_TX_TBL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SuppressWarnings("unused")
public class GetBalanceCliOutputDiffTest extends AbstractCliTest {

    public static void main(String[] args) {
        GetBalanceCliOutputDiffTest test = new GetBalanceCliOutputDiffTest();
        test.getXmrBalance();
    }

    public GetBalanceCliOutputDiffTest() {
        super();
    }

    @Test
    public void testTransactionsWithoutBlockDataHaveBlankHeightAndDate() {
        XmrTx pending = XmrTx.newBuilder().setHash("pending").build();
        XmrTx confirmed = XmrTx.newBuilder().setHash("confirmed").setHeight(1234567)
                .setTimestamp(1704067200).setIsConfirmed(true).build();
        Table table = new TableBuilder(XMR_TX_TBL, List.of(pending, confirmed)).build();
        assertEquals("", table.columns[1].getRow(0).toString().trim());
        assertEquals("", table.columns[2].getRow(0).toString().trim());
        assertEquals("1234567", table.columns[1].getRow(1).toString().trim());
        assertEquals("2024-01-01T00:00:00Z", table.columns[2].getRow(1).toString().trim());
        assertFalse(table.toString().contains("1970-01-01"));
    }

    private void getXmrBalance() {
        var balance = aliceClient.getXmrBalances();
        // TableFormat class had been deprecated, then deleted on 17-Feb-2022, but these
        // diff tests can be useful for testing changes to the current tbl formatting api.
        // var oldTbl = TableFormat.formatXmrBalanceInfoTbl(balance);
        var newTbl = new TableBuilder(XMR_BALANCE_TBL, balance).build().toString();
        // printOldTbl(oldTbl);
        printNewTbl(newTbl);
        // checkDiffsIgnoreWhitespace(oldTbl, newTbl);
    }
}
