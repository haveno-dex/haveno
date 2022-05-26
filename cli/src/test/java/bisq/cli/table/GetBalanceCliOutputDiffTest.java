package bisq.cli.table;

import static bisq.cli.table.builder.TableType.BTC_BALANCE_TBL;



import bisq.cli.AbstractCliTest;
import bisq.cli.table.builder.TableBuilder;

@SuppressWarnings("unused")
public class GetBalanceCliOutputDiffTest extends AbstractCliTest {

    public static void main(String[] args) {
        GetBalanceCliOutputDiffTest test = new GetBalanceCliOutputDiffTest();
        test.getBtcBalance();
    }

    public GetBalanceCliOutputDiffTest() {
        super();
    }

    private void getBtcBalance() {
        var balance = aliceClient.getBtcBalances();
        // TableFormat class had been deprecated, then deleted on 17-Feb-2022, but these
        // diff tests can be useful for testing changes to the current tbl formatting api.
        // var oldTbl = TableFormat.formatBtcBalanceInfoTbl(balance);
        var newTbl = new TableBuilder(BTC_BALANCE_TBL, balance).build().toString();
        // printOldTbl(oldTbl);
        printNewTbl(newTbl);
        // checkDiffsIgnoreWhitespace(oldTbl, newTbl);
    }
}
