package haveno.cli;

import haveno.proto.grpc.TradeInfo;
import haveno.cli.GrpcClient;
import haveno.cli.table.builder.TableBuilder;
import java.util.List;

import static haveno.proto.grpc.GetTradesRequest.Category.CLOSED;
import static haveno.cli.table.builder.TableType.TRADE_DETAIL_TBL;
import static java.lang.System.out;

@SuppressWarnings("unused")
public class GetTradesSmokeTest extends AbstractCliTest {

    public static void main(String[] args) {
        GetTradesSmokeTest test = new GetTradesSmokeTest();
        test.printAlicesTrades();
        test.printBobsTrades();
    }

    private final List<TradeInfo> openTrades;
    private final List<TradeInfo> closedTrades;

    public GetTradesSmokeTest() {
        super();
        this.openTrades = aliceClient.getOpenTrades();
        this.closedTrades = aliceClient.getTradeHistory(CLOSED);
    }

    private void printAlicesTrades() {
        out.println("ALICE'S OPEN TRADES");
        openTrades.stream().forEachOrdered(t -> printTrade(aliceClient, t.getTradeId()));
        out.println("ALICE'S CLOSED TRADES");
        closedTrades.stream().forEachOrdered(t -> printTrade(aliceClient, t.getTradeId()));
    }

    private void printBobsTrades() {
        out.println("BOB'S OPEN TRADES");
        openTrades.stream().forEachOrdered(t -> printTrade(bobClient, t.getTradeId()));
        out.println("BOB'S CLOSED TRADES");
        closedTrades.stream().forEachOrdered(t -> printTrade(bobClient, t.getTradeId()));
    }

    private void printTrade(GrpcClient client, String tradeId) {
        var trade = client.getTrade(tradeId);
        var tbl = new TableBuilder(TRADE_DETAIL_TBL, trade).build().toString();
        out.println(tbl);
    }
}
