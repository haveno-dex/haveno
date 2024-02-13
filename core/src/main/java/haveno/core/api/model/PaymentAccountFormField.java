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

import haveno.common.proto.ProtoUtil;
import haveno.common.proto.persistable.PersistablePayload;
import haveno.core.locale.Country;
import haveno.core.locale.TradeCurrency;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Getter
@Setter
@Immutable
@EqualsAndHashCode
@ToString
public final class PaymentAccountFormField implements PersistablePayload {

    public enum FieldId {
        ADDRESS,
        ACCEPTED_COUNTRY_CODES,
        ACCOUNT_ID,
        ACCOUNT_NAME,
        ACCOUNT_NR,
        ACCOUNT_OWNER,
        ACCOUNT_TYPE,
        ANSWER,
        BANK_ACCOUNT_NAME,
        BANK_ACCOUNT_NUMBER,
        BANK_ACCOUNT_TYPE,
        BANK_ADDRESS,
        BANK_BRANCH,
        BANK_BRANCH_CODE,
        BANK_BRANCH_NAME,
        BANK_CODE,
        BANK_COUNTRY_CODE,
        BANK_ID,
        BANK_NAME,
        BANK_SWIFT_CODE,
        BENEFICIARY_ACCOUNT_NR,
        BENEFICIARY_ADDRESS,
        BENEFICIARY_CITY,
        BENEFICIARY_NAME,
        BENEFICIARY_PHONE,
        BIC,
        BRANCH_ID,
        CITY,
        CONTACT,
        COUNTRY,
        EMAIL,
        EMAIL_OR_MOBILE_NR,
        EXTRA_INFO,
        HOLDER_ADDRESS,
        HOLDER_EMAIL,
        HOLDER_NAME,
        HOLDER_TAX_ID,
        IBAN,
        IFSC,
        INTERMEDIARY_ADDRESS,
        INTERMEDIARY_BRANCH,
        INTERMEDIARY_COUNTRY_CODE,
        INTERMEDIARY_NAME,
        INTERMEDIARY_SWIFT_CODE,
        MOBILE_NR,
        NATIONAL_ACCOUNT_ID,
        PAYID,
        PIX_KEY,
        POSTAL_ADDRESS,
        PROMPT_PAY_ID,
        QUESTION,
        REQUIREMENTS,
        SALT,
        SORT_CODE,
        SPECIAL_INSTRUCTIONS,
        STATE,
        TRADE_CURRENCIES,
        USER_NAME;

        public static PaymentAccountFormField.FieldId fromProto(protobuf.PaymentAccountFormField.FieldId fieldId) {
            return ProtoUtil.enumFromProto(PaymentAccountFormField.FieldId.class, fieldId.name());
        }

        public static protobuf.PaymentAccountFormField.FieldId toProtoMessage(PaymentAccountFormField.FieldId fieldId) {
            return protobuf.PaymentAccountFormField.FieldId.valueOf(fieldId.name());
        }
    }

    public enum Component {
        TEXT,
        TEXTAREA,
        SELECT_ONE,
        SELECT_MULTIPLE;

        public static PaymentAccountFormField.Component fromProto(protobuf.PaymentAccountFormField.Component component) {
            return ProtoUtil.enumFromProto(PaymentAccountFormField.Component.class, component.name());
        }

        public static protobuf.PaymentAccountFormField.Component toProtoMessage(PaymentAccountFormField.Component component) {
            return protobuf.PaymentAccountFormField.Component.valueOf(component.name());
        }
    }

    private FieldId id;
    private Component component;
    @Nullable
    private String type;
    private String label;
    private String value;
    private int minLength;
    private int maxLength;
    private List<TradeCurrency> supportedCurrencies;
    private List<Country> supportedCountries;
    private List<Country> supportedSepaEuroCountries;
    private List<Country> supportedSepaNonEuroCountries;
    private List<String> requiredForCountries;

    public PaymentAccountFormField(FieldId id) {
        this.id = id;
    }

    @Override
    public protobuf.PaymentAccountFormField toProtoMessage() {
        protobuf.PaymentAccountFormField.Builder builder = protobuf.PaymentAccountFormField.newBuilder()
                .setId(PaymentAccountFormField.FieldId.toProtoMessage(id))
                .setComponent(PaymentAccountFormField.Component.toProtoMessage(component))
                .setMinLength(minLength)
                .setMaxLength(maxLength);
        Optional.ofNullable(type).ifPresent(builder::setType);
        Optional.ofNullable(label).ifPresent(builder::setLabel);
        Optional.ofNullable(value).ifPresent(builder::setValue);
        Optional.ofNullable(supportedCurrencies).ifPresent(e -> builder.addAllSupportedCurrencies(ProtoUtil.collectionToProto(supportedCurrencies, protobuf.TradeCurrency.class)));
        Optional.ofNullable(supportedCountries).ifPresent(e -> builder.addAllSupportedCountries(ProtoUtil.collectionToProto(supportedCountries, protobuf.Country.class)));
        Optional.ofNullable(supportedSepaEuroCountries).ifPresent(e -> builder.addAllSupportedSepaEuroCountries(ProtoUtil.collectionToProto(supportedSepaEuroCountries, protobuf.Country.class)));
        Optional.ofNullable(supportedSepaNonEuroCountries).ifPresent(e -> builder.addAllSupportedSepaNonEuroCountries(ProtoUtil.collectionToProto(supportedSepaNonEuroCountries, protobuf.Country.class)));
        Optional.ofNullable(requiredForCountries).ifPresent(builder::addAllRequiredForCountries);
        return builder.build();
    }

    public static PaymentAccountFormField fromProto(protobuf.PaymentAccountFormField proto) {
        PaymentAccountFormField formField = new PaymentAccountFormField(FieldId.fromProto(proto.getId()));
        formField.type = proto.getType();
        formField.label = proto.getLabel();
        formField.value = proto.getValue();
        formField.minLength = proto.getMinLength();
        formField.maxLength = proto.getMaxLength();
        formField.supportedCountries = proto.getSupportedCountriesList().isEmpty() ? null : proto.getSupportedCountriesList().stream().map(Country::fromProto).collect(Collectors.toList());
        formField.supportedSepaEuroCountries = proto.getSupportedSepaEuroCountriesList().isEmpty() ? null : proto.getSupportedSepaEuroCountriesList().stream().map(Country::fromProto).collect(Collectors.toList());
        formField.supportedSepaNonEuroCountries = proto.getSupportedSepaNonEuroCountriesList().isEmpty() ? null : proto.getSupportedSepaNonEuroCountriesList().stream().map(Country::fromProto).collect(Collectors.toList());
        formField.requiredForCountries = proto.getRequiredForCountriesList() == null ? null : new ArrayList<String>(proto.getRequiredForCountriesList());
        return formField;
    }
}
