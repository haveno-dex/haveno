package haveno.cli.table;

import lombok.extern.slf4j.Slf4j;

import static haveno.cli.table.builder.TableType.TRANSACTION_TBL;

import haveno.cli.AbstractCliTest;
import haveno.cli.table.builder.TableBuilder;

@SuppressWarnings("unused")
@Slf4j
public class GetTransactionCliOutputDiffTest extends AbstractCliTest {

    public static void main(String[] args) {
        if (args.length == 0)
            throw new IllegalStateException("Need a single transaction-id program argument.");

        GetTransactionCliOutputDiffTest test = new GetTransactionCliOutputDiffTest(args[0]);
    }

    private final String transactionId;

    public GetTransactionCliOutputDiffTest(String transactionId) {
        super();
        this.transactionId = transactionId;
    }
}
