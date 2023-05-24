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
        assertTrue(paymentMethodIds.size() >= 20);
    }

    @AfterAll
    public static void tearDown() {
        tearDownScaffold();
    }
}
