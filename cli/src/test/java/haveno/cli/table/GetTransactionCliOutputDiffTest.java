package haveno.cli.table;

import haveno.cli.AbstractCliTest;
import lombok.extern.slf4j.Slf4j;

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
