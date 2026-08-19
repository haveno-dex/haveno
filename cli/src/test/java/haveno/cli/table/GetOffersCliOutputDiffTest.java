package haveno.cli.table;

import haveno.cli.AbstractCliTest;
import haveno.cli.table.builder.TableBuilder;
import haveno.proto.grpc.OfferInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static haveno.cli.table.builder.TableType.OFFER_TBL;
import static protobuf.OfferDirection.BUY;
import static protobuf.OfferDirection.SELL;

@SuppressWarnings("unused")
@Slf4j
public class GetOffersCliOutputDiffTest extends AbstractCliTest {

    // "My" offers are always Alice's offers.
    // "Available" offers are always Alice's offers available to Bob.

    public static void main(String[] args) {
        GetOffersCliOutputDiffTest test = new GetOffersCliOutputDiffTest();

        test.getMyBuyUsdOffers();
        test.getMySellUsdOffers();
        test.getAvailableBuyUsdOffers();
        test.getAvailableSellUsdOffers();

        /*
        // TODO Uncomment when XMR support is added.
        test.getMyBuyXmrOffers();
        test.getMySellXmrOffers();
        test.getAvailableBuyXmrOffers();
        test.getAvailableSellXmrOffers();
         */

        test.getMyBuyBchOffers();
        test.getMySellBchOffers();
        test.getAvailableBuyBchOffers();
        test.getAvailableSellBchOffers();
    }

    public GetOffersCliOutputDiffTest() {
        super();
    }

    private void getMyBuyUsdOffers() {
        var myOffers = aliceClient.getMyOffers(BUY.name(), "USD");
        printAndCheckDiffs(myOffers, BUY.name(), "USD");
    }

    private void getMySellUsdOffers() {
        var myOffers = aliceClient.getMyOffers(SELL.name(), "USD");
        printAndCheckDiffs(myOffers, SELL.name(), "USD");
    }

    private void getAvailableBuyUsdOffers() {
        var offers = bobClient.getOffers(BUY.name(), "USD");
        printAndCheckDiffs(offers, BUY.name(), "USD");
    }

    private void getAvailableSellUsdOffers() {
        var offers = bobClient.getOffers(SELL.name(), "USD");
        printAndCheckDiffs(offers, SELL.name(), "USD");
    }

    private void getMyBuyXmrOffers() {
        var myOffers = aliceClient.getMyOffers(BUY.name(), "XMR");
        printAndCheckDiffs(myOffers, BUY.name(), "XMR");
    }

    private void getMySellXmrOffers() {
        var myOffers = aliceClient.getMyOffers(SELL.name(), "XMR");
        printAndCheckDiffs(myOffers, SELL.name(), "XMR");
    }

    private void getAvailableBuyXmrOffers() {
        var offers = bobClient.getOffers(BUY.name(), "XMR");
        printAndCheckDiffs(offers, BUY.name(), "XMR");
    }

    private void getAvailableSellXmrOffers() {
        var offers = bobClient.getOffers(SELL.name(), "XMR");
        printAndCheckDiffs(offers, SELL.name(), "XMR");
    }

    private void getMyBuyBchOffers() {
        var myOffers = aliceClient.getMyOffers(BUY.name(), "BCH");
        printAndCheckDiffs(myOffers, BUY.name(), "BCH");
    }

    private void getMySellBchOffers() {
        var myOffers = aliceClient.getMyOffers(SELL.name(), "BCH");
        printAndCheckDiffs(myOffers, SELL.name(), "BCH");
    }

    private void getAvailableBuyBchOffers() {
        var offers = bobClient.getOffers(BUY.name(), "BCH");
        printAndCheckDiffs(offers, BUY.name(), "BCH");
    }

    private void getAvailableSellBchOffers() {
        var offers = bobClient.getOffers(SELL.name(), "BCH");
        printAndCheckDiffs(offers, SELL.name(), "BCH");
    }

    private void printAndCheckDiffs(List<OfferInfo> offers,
                                    String direction,
                                    String currencyCode) {
        if (offers.isEmpty()) {
            log.warn("No {} {} offers to print.", direction, currencyCode);
        } else {
            log.info("Checking for diffs in {} {} offers.", direction, currencyCode);
            // OfferFormat class had been deprecated, then deleted on 17-Feb-2022, but
            // these diff tests can be useful for testing changes to the current tbl formatting api.
            // var oldTbl = OfferFormat.formatOfferTable(offers, currencyCode);
            var newTbl = new TableBuilder(OFFER_TBL, offers).build().toString();
            // printOldTbl(oldTbl);
            printNewTbl(newTbl);
            // checkDiffsIgnoreWhitespace(oldTbl, newTbl);
        }
    }
}
