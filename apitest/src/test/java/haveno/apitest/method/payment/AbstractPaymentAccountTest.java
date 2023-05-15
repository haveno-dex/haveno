package haveno.apitest.method.payment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonWriter;
import haveno.apitest.method.MethodTest;
import haveno.cli.GrpcClient;
import haveno.core.api.model.PaymentAccountForm;
import haveno.core.locale.TraditionalCurrency;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.PaymentAccount;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.lang.System.getProperty;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Slf4j
public class AbstractPaymentAccountTest extends MethodTest {

    static final String PROPERTY_NAME_JSON_COMMENTS = "_COMMENTS_";
    static final List<String> PROPERTY_VALUE_JSON_COMMENTS = new ArrayList<>() {{
        add("Do not manually edit the paymentMethodId field.");
        add("Edit the salt field only if you are recreating a payment"
                + " account on a new installation and wish to preserve the account age.");
    }};

    static final String PROPERTY_NAME_PAYMENT_METHOD_ID = "paymentMethodId";

    static final String PROPERTY_NAME_ACCOUNT_ID = "accountId";
    static final String PROPERTY_NAME_ACCOUNT_NAME = "accountName";
    static final String PROPERTY_NAME_ACCOUNT_NR = "accountNr";
    static final String PROPERTY_NAME_ACCOUNT_TYPE = "accountType";
    static final String PROPERTY_NAME_ANSWER = "answer";
    static final String PROPERTY_NAME_BANK_ACCOUNT_NAME = "bankAccountName";
    static final String PROPERTY_NAME_BANK_ACCOUNT_NUMBER = "bankAccountNumber";
    static final String PROPERTY_NAME_BANK_ACCOUNT_TYPE = "bankAccountType";
    static final String PROPERTY_NAME_BANK_ADDRESS = "bankAddress";
    static final String PROPERTY_NAME_BANK_BRANCH = "bankBranch";
    static final String PROPERTY_NAME_BANK_BRANCH_CODE = "bankBranchCode";
    static final String PROPERTY_NAME_BANK_BRANCH_NAME = "bankBranchName";
    static final String PROPERTY_NAME_BANK_CODE = "bankCode";
    static final String PROPERTY_NAME_BANK_COUNTRY_CODE = "bankCountryCode";
    @SuppressWarnings("unused")
    static final String PROPERTY_NAME_BANK_ID = "bankId";
    static final String PROPERTY_NAME_BANK_NAME = "bankName";
    static final String PROPERTY_NAME_BANK_SWIFT_CODE = "bankSwiftCode";
    static final String PROPERTY_NAME_BRANCH_ID = "branchId";
    static final String PROPERTY_NAME_BIC = "bic";
    static final String PROPERTY_NAME_BENEFICIARY_NAME = "beneficiaryName";
    static final String PROPERTY_NAME_BENEFICIARY_ACCOUNT_NR = "beneficiaryAccountNr";
    static final String PROPERTY_NAME_BENEFICIARY_ADDRESS = "beneficiaryAddress";
    static final String PROPERTY_NAME_BENEFICIARY_CITY = "beneficiaryCity";
    static final String PROPERTY_NAME_BENEFICIARY_PHONE = "beneficiaryPhone";
    static final String PROPERTY_NAME_COUNTRY = "country";
    static final String PROPERTY_NAME_CITY = "city";
    static final String PROPERTY_NAME_CONTACT = "contact";
    static final String PROPERTY_NAME_EMAIL = "email";
    static final String PROPERTY_NAME_EMAIL_OR_MOBILE_NR = "emailOrMobileNr";
    static final String PROPERTY_NAME_EXTRA_INFO = "extraInfo";
    static final String PROPERTY_NAME_HOLDER_EMAIL = "holderEmail";
    static final String PROPERTY_NAME_HOLDER_NAME = "holderName";
    static final String PROPERTY_NAME_HOLDER_TAX_ID = "holderTaxId";
    static final String PROPERTY_NAME_IBAN = "iban";
    static final String PROPERTY_NAME_INTERMEDIARY_ADDRESS = "intermediaryAddress";
    static final String PROPERTY_NAME_INTERMEDIARY_BRANCH = "intermediaryBranch";
    static final String PROPERTY_NAME_INTERMEDIARY_COUNTRY_CODE = "intermediaryCountryCode";
    static final String PROPERTY_NAME_INTERMEDIARY_NAME = "intermediaryName";
    static final String PROPERTY_NAME_INTERMEDIARY_SWIFT_CODE = "intermediarySwiftCode";
    static final String PROPERTY_NAME_MOBILE_NR = "mobileNr";
    static final String PROPERTY_NAME_NATIONAL_ACCOUNT_ID = "nationalAccountId";
    static final String PROPERTY_NAME_PAY_ID = "payid";
    static final String PROPERTY_NAME_POSTAL_ADDRESS = "postalAddress";
    static final String PROPERTY_NAME_PROMPT_PAY_ID = "promptPayId";
    static final String PROPERTY_NAME_QUESTION = "question";
    static final String PROPERTY_NAME_REQUIREMENTS = "requirements";
    static final String PROPERTY_NAME_SALT = "salt";
    static final String PROPERTY_NAME_SELECTED_TRADE_CURRENCY = "selectedTradeCurrency";
    static final String PROPERTY_NAME_SORT_CODE = "sortCode";
    static final String PROPERTY_NAME_SPECIAL_INSTRUCTIONS = "specialInstructions";
    static final String PROPERTY_NAME_STATE = "state";
    static final String PROPERTY_NAME_TRADE_CURRENCIES = "tradeCurrencies";
    static final String PROPERTY_NAME_USERNAME = "userName";

    static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    static final Map<String, Object> COMPLETED_FORM_MAP = new HashMap<>();

    @BeforeEach
    public void setup() {
        Res.setup();
    }

    protected final File getEmptyForm(TestInfo testInfo, String paymentMethodId) {
        // This would normally be done in @BeforeEach, but these test cases might be
        // called from a single 'scenario' test case, and the @BeforeEach -> clear()
        // would be skipped.
        COMPLETED_FORM_MAP.clear();

        File emptyForm = getPaymentAccountForm(aliceClient, paymentMethodId);
        // A shortcut over the API:
        // File emptyForm = PAYMENT_ACCOUNT_FORM.getPaymentAccountForm(paymentMethodId);
        log.debug("{} Empty form saved to {}",
                testName(testInfo),
                PaymentAccountForm.getClickableURI(emptyForm));
        emptyForm.deleteOnExit();
        return emptyForm;
    }

    protected final void verifyEmptyForm(File jsonForm, String paymentMethodId, String... fields) {
        @SuppressWarnings("unchecked")
        Map<String, Object> emptyForm = (Map<String, Object>) GSON.fromJson(
                PaymentAccountForm.toJsonString(jsonForm),
                Object.class);
        assertNotNull(emptyForm);

        if (paymentMethodId.equals("SWIFT_ID")) {
            assertEquals(getSwiftFormComments(), emptyForm.get(PROPERTY_NAME_JSON_COMMENTS));
        } else {
            assertEquals(PROPERTY_VALUE_JSON_COMMENTS, emptyForm.get(PROPERTY_NAME_JSON_COMMENTS));
        }

        assertEquals(paymentMethodId, emptyForm.get(PROPERTY_NAME_PAYMENT_METHOD_ID));
        assertEquals("your accountname", emptyForm.get(PROPERTY_NAME_ACCOUNT_NAME));
        for (String field : fields) {
            if (field.equals("country"))
                assertEquals("your two letter country code", emptyForm.get(field));
            else
                assertEquals("your " + field.toLowerCase(), emptyForm.get(field));
        }
    }

    protected final void verifyCommonFormEntries(PaymentAccount paymentAccount) {
        // All PaymentAccount subclasses have paymentMethodId and an accountName fields.
        assertNotNull(paymentAccount);
        assertEquals(COMPLETED_FORM_MAP.get(PROPERTY_NAME_PAYMENT_METHOD_ID), paymentAccount.getPaymentMethod().getId());
        assertTrue(paymentAccount.getCreationDate().getTime() > 0);
        assertEquals(COMPLETED_FORM_MAP.get(PROPERTY_NAME_ACCOUNT_NAME), paymentAccount.getAccountName());
    }

    protected final void verifyAccountSingleTradeCurrency(String expectedCurrencyCode, PaymentAccount paymentAccount) {
        assertNotNull(paymentAccount.getSingleTradeCurrency());
        assertEquals(expectedCurrencyCode, paymentAccount.getSingleTradeCurrency().getCode());
    }

    protected final void verifyAccountTradeCurrencies(Collection<TraditionalCurrency> expectedTraditionalCurrencies,
                                                      PaymentAccount paymentAccount) {
        assertNotNull(paymentAccount.getTradeCurrencies());
        List<TradeCurrency> expectedTradeCurrencies = new ArrayList<>() {{
            addAll(expectedTraditionalCurrencies);
        }};
        assertArrayEquals(expectedTradeCurrencies.toArray(), paymentAccount.getTradeCurrencies().toArray());
    }

    protected final void verifyAccountTradeCurrencies(List<TradeCurrency> expectedTradeCurrencies,
                                                      PaymentAccount paymentAccount) {
        assertNotNull(paymentAccount.getTradeCurrencies());
        assertArrayEquals(expectedTradeCurrencies.toArray(), paymentAccount.getTradeCurrencies().toArray());
    }

    protected final void verifyUserPayloadHasPaymentAccountWithId(GrpcClient grpcClient,
                                                                  String paymentAccountId) {
        Optional<protobuf.PaymentAccount> paymentAccount = grpcClient.getPaymentAccounts()
                .stream()
                .filter(a -> a.getId().equals(paymentAccountId))
                .findFirst();
        assertTrue(paymentAccount.isPresent());
    }

    protected final String getCompletedFormAsJsonString(List<String> comments) {
        File completedForm = fillPaymentAccountForm(comments);
        String jsonString = PaymentAccountForm.toJsonString(completedForm);
        log.debug("Completed form: {}", jsonString);
        return jsonString;
    }

    protected final String getCompletedFormAsJsonString() {
        File completedForm = fillPaymentAccountForm(PROPERTY_VALUE_JSON_COMMENTS);
        String jsonString = PaymentAccountForm.toJsonString(completedForm);
        log.debug("Completed form: {}", jsonString);
        return jsonString;
    }

    protected final String getCommaDelimitedTraditionalCurrencyCodes(Collection<TraditionalCurrency> traditionalCurrencies) {
        return traditionalCurrencies.stream()
                .sorted(Comparator.comparing(TradeCurrency::getCode))
                .map(c -> c.getCurrency().getCurrencyCode())
                .collect(Collectors.joining(","));
    }

    protected final List<String> getSwiftFormComments() {
        List<String> comments = new ArrayList<>();
        comments.addAll(PROPERTY_VALUE_JSON_COMMENTS);
        List<String> wrappedSwiftComments = Res.getWrappedAsList("payment.swift.info.account", 110);
        comments.addAll(wrappedSwiftComments);
        return comments;
    }

    private File fillPaymentAccountForm(List<String> comments) {
        File tmpJsonForm = null;
        try {
            tmpJsonForm = File.createTempFile("temp_acct_form_",
                    ".json",
                    Paths.get(getProperty("java.io.tmpdir")).toFile());
            JsonWriter writer = new JsonWriter(new OutputStreamWriter(new FileOutputStream(tmpJsonForm), UTF_8));
            writer.beginObject();

            writer.name(PROPERTY_NAME_JSON_COMMENTS);
            writer.beginArray();
            for (String s : comments) {
                writer.value(s);
            }
            writer.endArray();

            for (Map.Entry<String, Object> entry : COMPLETED_FORM_MAP.entrySet()) {
                String k = entry.getKey();
                Object v = entry.getValue();
                writer.name(k);
                writer.value(v.toString());
            }
            writer.endObject();
            writer.close();
        } catch (IOException ex) {
            log.error("", ex);
            fail(format("Could not write json file from form entries %s", COMPLETED_FORM_MAP));
        }
        tmpJsonForm.deleteOnExit();
        return tmpJsonForm;
    }
}
