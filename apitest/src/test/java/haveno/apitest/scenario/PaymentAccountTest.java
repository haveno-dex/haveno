package haveno.apitest.scenario;

import haveno.apitest.method.payment.AbstractPaymentAccountTest;
import haveno.apitest.method.payment.CreatePaymentAccountTest;
import haveno.apitest.method.payment.GetPaymentMethodsTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;

import static haveno.apitest.Scaffold.BitcoinCoreApp.bitcoind;
import static haveno.apitest.config.HavenoAppConfig.alicedaemon;
import static haveno.apitest.config.HavenoAppConfig.seednode;
import static org.junit.jupiter.api.Assertions.fail;

@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PaymentAccountTest extends AbstractPaymentAccountTest {

    @BeforeAll
    public static void setUp() {
        try {
            setUpScaffold(bitcoind, seednode, alicedaemon);
        } catch (Exception ex) {
            fail(ex);
        }
    }

    @Test
    @Order(1)
    public void testGetPaymentMethods() {
        GetPaymentMethodsTest test = new GetPaymentMethodsTest();
        test.testGetPaymentMethods();
    }

    @Test
    @Order(2)
    public void testCreatePaymentAccount(TestInfo testInfo) {
        CreatePaymentAccountTest test = new CreatePaymentAccountTest();

        test.testCreateAdvancedCashAccount(testInfo);
        test.testCreateAliPayAccount(testInfo);
        test.testCreateAustraliaPayidAccount(testInfo);
        test.testCreateCapitualAccount(testInfo);
        test.testCreateCashDepositAccount(testInfo);
        test.testCreateBrazilNationalBankAccount(testInfo);
        test.testCreateZelleAccount(testInfo);
        test.testCreateF2FAccount(testInfo);
        test.testCreateFasterPaymentsAccount(testInfo);
        test.testCreateHalCashAccount(testInfo);
        test.testCreateInteracETransferAccount(testInfo);
        test.testCreateJapanBankAccount(testInfo);
        test.testCreateMoneyBeamAccount(testInfo);
        test.testCreateMoneyGramAccount(testInfo);
        test.testCreatePerfectMoneyAccount(testInfo);
        test.testCreatePaxumAccount(testInfo);
        test.testCreatePayseraAccount(testInfo);
        test.testCreatePopmoneyAccount(testInfo);
        test.testCreatePromptPayAccount(testInfo);
        test.testCreateRevolutAccount(testInfo);
        test.testCreateSameBankAccount(testInfo);
        test.testCreateSepaInstantAccount(testInfo);
        test.testCreateSepaAccount(testInfo);
        test.testCreateSpecificBanksAccount(testInfo);
        test.testCreateSwiftAccount(testInfo);
        test.testCreateSwishAccount(testInfo);

        test.testCreateTransferwiseAccountWith1TradeCurrency(testInfo);
        test.testCreateTransferwiseAccountWith10TradeCurrencies(testInfo);
        test.testCreateTransferwiseAccountWithSupportedTradeCurrencies(testInfo);
        test.testCreateTransferwiseAccountWithInvalidBrlTradeCurrencyShouldThrowException(testInfo);
        test.testCreateTransferwiseAccountWithoutTradeCurrenciesShouldThrowException(testInfo);

        test.testCreateUpholdAccount(testInfo);
        test.testCreateUSPostalMoneyOrderAccount(testInfo);
        test.testCreateWeChatPayAccount(testInfo);
        test.testCreateWesternUnionAccount(testInfo);
    }

    @AfterAll
    public static void tearDown() {
        tearDownScaffold();
    }
}
