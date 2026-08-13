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

package haveno.cli;

import static java.lang.System.out;
import static java.util.Arrays.stream;

/**
 Smoke tests for createoffer method.  Useful for testing CLI command and examining the
 format of its console output.

 Prerequisites:

 - Run `./haveno-apitest --apiPassword=xyz --supportingApps=seednode,arbdaemon,alicedaemon,bobdaemon --shutdownAfterTests=false --enableHavenoDebugging=false`

 - Create a BCH payment account with Alice's CLI:
   createcryptopaymentacct --account-name="BCH Account" --currency-code=bch --address=<bch-address>

 Never run on mainnet!
 */
@SuppressWarnings({"CommentedOutCode", "unused"})
public class CreateOfferSmokeTest extends AbstractCliTest {

    public static void main(String[] args) {
        CreateOfferSmokeTest test = new CreateOfferSmokeTest();
        test.createBchOffer("buy");
        test.createBchOffer("sell");
    }

    private void createBchOffer(String direction) {
        var paymentAccountId = getBchPaymentAccountId();
        String[] args = createBchOfferCommand(paymentAccountId, direction, "0.5", "0.25", "480.50");
        out.print(">>>>> haveno-cli ");
        stream(args).forEach(a -> out.print(a + " "));
        out.println();
        CliMain.main(args);
        out.println("<<<<<");

        args = getMyOffersCommand(direction, "bch");
        out.print(">>>>> haveno-cli ");
        stream(args).forEach(a -> out.print(a + " "));
        out.println();
        CliMain.main(args);
        out.println("<<<<<");

        args = getAvailableOffersCommand(direction, "bch");
        out.print(">>>>> haveno-cli ");
        stream(args).forEach(a -> out.print(a + " "));
        out.println();
        CliMain.main(args);
        out.println("<<<<<");
    }

    private String getBchPaymentAccountId() {
        return aliceClient.getPaymentAccounts().stream()
                .filter(a -> a.getSelectedTradeCurrency().getCode().equals("BCH"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no BCH payment account found, create one first"))
                .getId();
    }

    private String[] createBchOfferCommand(String paymentAccountId,
                                           String direction,
                                           String amount,
                                           String minAmount,
                                           String fixedPrice) {
        return new String[]{
                PASSWORD_OPT,
                ALICE_PORT_OPT,
                "createoffer",
                "--payment-account-id=" + paymentAccountId,
                "--direction=" + direction,
                "--currency-code=bch",
                "--amount=" + amount,
                "--min-amount=" + minAmount,
                "--fixed-price=" + fixedPrice,
                "--security-deposit=15.0"
        };
    }
}
