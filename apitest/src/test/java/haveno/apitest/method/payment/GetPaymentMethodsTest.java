package haveno.apitest.method.payment;

import haveno.apitest.method.MethodTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import protobuf.PaymentMethod;

import java.util.List;
import java.util.stream.Collectors;

import static haveno.apitest.Scaffold.BitcoinCoreApp.bitcoind;
import static haveno.apitest.config.HavenoAppConfig.alicedaemon;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Disabled
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class GetPaymentMethodsTest extends MethodTest {

    @BeforeAll
    public static void setUp() {
        try {
            setUpScaffold(bitcoind, alicedaemon);
        } catch (Exception ex) {
            fail(ex);
        }
    }

    @Test
    @Order(1)
    public void testGetPaymentMethods() {
        List<String> paymentMethodIds = aliceClient.getPaymentMethods()
                .stream()
                .map(PaymentMethod::getId)
                .collect(Collectors.toList());
        assertTrue(paymentMethodIds.size() >= 55);

        // List of expected payment method IDs
        List<String> expectedIds = List.of(
            "HAL_CASH",
            "MONEY_BEAM",
            "SWISH",
            "POPMONEY",
            "US_POSTAL_MONEY_ORDER",
            "INTERAC_E_TRANSFER",
            "CASH_DEPOSIT",
            "CASH_AT_ATM",
            "WESTERN_UNION",
            "NATIONAL_BANK",
            "SAME_BANK",
            "SPECIFIC_BANKS",
            "AMAZON_GIFT_CARD",
            "PERFECT_MONEY",
            "ADVANCED_CASH",
            "PAYSERA",
            "NEFT",
            "RTGS",
            "IMPS",
            "UPI",
            "PAYTM",
            "NEQUI",
            "BIZUM",
            "PIX",
            "CAPITUAL",
            "CELPAY",
            "MONESE",
            "SATISPAY",
            "TIKKIE",
            "VERSE",
            "ACH_TRANSFER",
            "DOMESTIC_WIRE_TRANSFER",
            "JAPAN_BANK",
            "ALI_PAY",
            "WECHAT_PAY",
            "PROMPT_PAY",
            "PAY_BY_MAIL",
            "CARD_LESS_CASH",
            "MONEY_GRAM",
            "F2F",
            "AUSTRALIA_PAYID",
            "UPHOLD",
            "REVOLUT",
            "SEPA",
            "SEPA_INSTANT",
            "FASTER_PAYMENTS",
            "ZELLE",
            "BLOCK_CHAINS",
            "WISE",
            "PAXUM",
            "STRIKE",
            "SWIFT",
            "CASH_APP",
            "VENMO",
            "PAYPAL",
            "PAYSAFE"
        );

        for (String id : expectedIds) {
            assertTrue(paymentMethodIds.contains(id), "Missing payment method: " + id);
        }
    }

    @AfterAll
    public static void tearDown() {
        tearDownScaffold();
    }
}
