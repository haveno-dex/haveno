package haveno.apitest.scenario.bot;

import protobuf.PaymentAccount;

import static haveno.core.payment.payload.PaymentMethod.CLEAR_X_CHANGE_ID;
import static haveno.core.payment.payload.PaymentMethod.F2F_ID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import haveno.core.api.model.PaymentAccountForm;
import haveno.core.locale.Country;
import java.io.File;

import java.util.Map;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BotPaymentAccountGenerator {

    private final Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private final BotClient botClient;

    public BotPaymentAccountGenerator(BotClient botClient) {
        this.botClient = botClient;
    }

    public PaymentAccount createF2FPaymentAccount(Country country, String accountName) {
        try {
            return botClient.getPaymentAccountWithName(accountName);
        } catch (PaymentAccountNotFoundException ignored) {
            // Ignore not found exception, create a new account.
        }
        Map<String, Object> p = getPaymentAccountFormMap(F2F_ID);
        p.put("accountName", accountName);
        p.put("city", country.name + " City");
        p.put("country", country.code);
        p.put("contact", "By Semaphore");
        p.put("extraInfo", "");
        // Convert the map back to a json string and create the payment account over gRPC.
        return botClient.createNewPaymentAccount(gson.toJson(p));
    }

    public PaymentAccount createZellePaymentAccount(String accountName, String holderName) {
        try {
            return botClient.getPaymentAccountWithName(accountName);
        } catch (PaymentAccountNotFoundException ignored) {
            // Ignore not found exception, create a new account.
        }
        Map<String, Object> p = getPaymentAccountFormMap(CLEAR_X_CHANGE_ID);
        p.put("accountName", accountName);
        p.put("emailOrMobileNr", holderName + "@zelle.com");
        p.put("holderName", holderName);
        return botClient.createNewPaymentAccount(gson.toJson(p));
    }

    private Map<String, Object> getPaymentAccountFormMap(String paymentMethodId) {
        File jsonFormTemplate = PaymentAccountForm.getPaymentAccountForm(paymentMethodId);
        jsonFormTemplate.deleteOnExit();
        String jsonString = PaymentAccountForm.toJsonString(jsonFormTemplate);
        //noinspection unchecked
        return (Map<String, Object>) gson.fromJson(jsonString, Object.class);
    }
}
