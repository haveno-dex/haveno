/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.core.api.model;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.Gson;
import haveno.common.proto.ProtoUtil;
import haveno.common.proto.persistable.PersistablePayload;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.PaymentAccountFactory;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.trade.HavenoUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.concurrent.Immutable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static java.lang.System.getProperty;
import static java.nio.charset.StandardCharsets.UTF_8;

@Getter
@Immutable
@EqualsAndHashCode
@ToString
@Slf4j
public final class PaymentAccountForm implements PersistablePayload {

    public enum FormId {
        BLOCK_CHAINS,
        CASH_AT_ATM,
        FASTER_PAYMENTS,
        F2F,
        MONEY_GRAM,
        PAXUM,
        PAY_BY_MAIL,
        REVOLUT,
        SEPA,
        SEPA_INSTANT,
        STRIKE,
        SWIFT,
        TRANSFERWISE,
        UPHOLD,
        ZELLE,
        AUSTRALIA_PAYID;

        public static PaymentAccountForm.FormId fromProto(protobuf.PaymentAccountForm.FormId formId) {
            return ProtoUtil.enumFromProto(PaymentAccountForm.FormId.class, formId.name());
        }

        public static protobuf.PaymentAccountForm.FormId toProtoMessage(PaymentAccountForm.FormId formId) {
            return protobuf.PaymentAccountForm.FormId.valueOf(formId.name());
        }
    }

    private final FormId id;
    private final List<PaymentAccountFormField> fields;

    public PaymentAccountForm(FormId id) {
        this.id = id;
        this.fields = new ArrayList<PaymentAccountFormField>();
    }

    public PaymentAccountForm(FormId id, List<PaymentAccountFormField> fields) {
        this.id = id;
        this.fields = fields;
    }

    @Override
    public protobuf.PaymentAccountForm toProtoMessage() {
        return protobuf.PaymentAccountForm.newBuilder()
                .setId(PaymentAccountForm.FormId.toProtoMessage(id))
                .addAllFields(fields.stream().map(field -> field.toProtoMessage()).collect(Collectors.toList()))
                .build();
    }

    public static PaymentAccountForm fromProto(protobuf.PaymentAccountForm proto) {
        List<PaymentAccountFormField> fields = proto.getFieldsList().isEmpty() ? null : proto.getFieldsList().stream().map(PaymentAccountFormField::fromProto).collect(Collectors.toList());
        return new PaymentAccountForm(FormId.fromProto(proto.getId()), fields);
    }

    public void addField(PaymentAccountFormField field) {
        fields.add(field);
    }

    public String getValue(PaymentAccountFormField.FieldId fieldId) {
        for (PaymentAccountFormField field : fields) {
            if (field.getId() == fieldId) {
                return field.getValue();
            }
        }
        throw new IllegalArgumentException("Form does not contain field " + fieldId);
    }

    /**
     * Convert this form to a PaymentAccount json string.
     */
    public String toPaymentAccountJsonString() {
        Map<String, Object> formMap = new HashMap<String, Object>();
        formMap.put("paymentMethodId", getId().toString());
        for (PaymentAccountFormField field : getFields()) {
            formMap.put(HavenoUtils.toCamelCase(field.getId().toString()), field.getValue());
        }
        return new Gson().toJson(formMap);
    }

    /**
     * Convert this form to a PaymentAccount.
     */
    public PaymentAccount toPaymentAccount() {
        return PaymentAccount.fromJson(toPaymentAccountJsonString());
    }

    /**
     * Get a structured form for the given payment method.
     */
    public static PaymentAccountForm getForm(String paymentMethodId) {
        PaymentAccount paymentAccount = PaymentAccountFactory.getPaymentAccount(PaymentMethod.getPaymentMethod(paymentMethodId));
        return paymentAccount.toForm();
    }

    // ----------------------------- OLD FORM API -----------------------------

    /**
     * Returns a blank payment account form (json) for the given paymentMethodId.
     *
     * @param paymentMethodId Determines what kind of json form to return.
     * @return A uniquely named tmp file used to define new payment account details.
     */
    public static File getPaymentAccountForm(String paymentMethodId) {
        PaymentMethod paymentMethod = PaymentMethod.getPaymentMethod(paymentMethodId);
        File file = getTmpJsonFile(paymentMethodId);
        try (OutputStreamWriter outputStreamWriter = new OutputStreamWriter(new FileOutputStream(checkNotNull(file), false), UTF_8)) {
            PaymentAccount paymentAccount = PaymentAccountFactory.getPaymentAccount(paymentMethod);
            String json = paymentAccount.toForm().toPaymentAccountJsonString();
            outputStreamWriter.write(json);
        } catch (Exception ex) {
            String errMsg = format("cannot create a payment account form for a %s payment method", paymentMethodId);
            log.error(StringUtils.capitalize(errMsg) + ".", ex);
            throw new IllegalStateException(errMsg);
        }
        return file;
    }

    /**
     * De-serialize a PaymentAccount json form into a new PaymentAccount instance.
     *
     * @param jsonForm The file representing a new payment account form.
     * @return A populated PaymentAccount subclass instance.
     */
    @SuppressWarnings("unused")
    @VisibleForTesting
    public static PaymentAccount toPaymentAccount(File jsonForm) {
        return PaymentAccount.fromJson(toJsonString(jsonForm));
    }

    public static String toJsonString(File jsonFile) {
        try {
            checkNotNull(jsonFile, "json file cannot be null");
            return new String(Files.readAllBytes(Paths.get(jsonFile.getAbsolutePath())));
        } catch (IOException ex) {
            String errMsg = format("cannot read json string from file '%s'",
                    jsonFile.getAbsolutePath());
            log.error(StringUtils.capitalize(errMsg) + ".", ex);
            throw new IllegalStateException(errMsg);
        }
    }

    @VisibleForTesting
    public static URI getClickableURI(File jsonFile) {
        try {
            return new URI("file",
                    "",
                    jsonFile.toURI().getPath(),
                    null,
                    null);
        } catch (URISyntaxException ex) {
            String errMsg = format("cannot create clickable url to file '%s'",
                    jsonFile.getAbsolutePath());
            log.error(StringUtils.capitalize(errMsg) + ".", ex);
            throw new IllegalStateException(errMsg);
        }
    }

    @VisibleForTesting
    public static File getTmpJsonFile(String paymentMethodId) {
        File file;
        try {
            // Creates a tmp file that includes a random number string between the
            // prefix and suffix, i.e., sepa_form_13243546575879.json, so there is
            // little chance this will fail because the tmp file already exists.
            file = File.createTempFile(paymentMethodId.toLowerCase() + "_form_",
                    ".json",
                    Paths.get(getProperty("java.io.tmpdir")).toFile());
        } catch (IOException ex) {
            String errMsg = format("cannot create json file for a %s payment method",
                    paymentMethodId);
            log.error(StringUtils.capitalize(errMsg) + ".", ex);
            throw new IllegalStateException(errMsg);
        }
        return file;
    }
}
