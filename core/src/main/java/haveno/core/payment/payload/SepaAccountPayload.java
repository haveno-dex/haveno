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

package haveno.core.payment.payload;

import com.google.protobuf.Message;
import haveno.core.locale.Country;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.Res;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@EqualsAndHashCode(callSuper = true)
@ToString
@Getter
@Slf4j
public final class SepaAccountPayload extends CountryBasedPaymentAccountPayload implements PayloadWithHolderName {
    @Setter
    private String holderName = "";
    @Setter
    private String iban = "";
    @Setter
    private String bic = "";
    private String email = ""; // not used anymore but need to keep it for backward compatibility, must not be null but empty string, otherwise hash check fails for contract

    // Don't use a set here as we need a deterministic ordering, otherwise the contract hash does not match
    private final List<String> persistedAcceptedCountryCodes = new ArrayList<>();

    public SepaAccountPayload(String paymentMethod, String id, List<Country> acceptedCountries) {
        super(paymentMethod, id);
        acceptedCountryCodes = acceptedCountries.stream()
                .map(e -> e.code)
                .sorted()
                .distinct()
                .collect(Collectors.toList());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private SepaAccountPayload(String paymentMethodName,
                               String id,
                               String countryCode,
                               List<String> acceptedCountryCodes,
                               String holderName,
                               String iban,
                               String bic,
                               String email,
                               long maxTradePeriod,
                               Map<String, String> excludeFromJsonDataMap) {
        super(paymentMethodName,
                id,
                countryCode,
                acceptedCountryCodes,
                maxTradePeriod,
                excludeFromJsonDataMap);

        this.holderName = holderName;
        this.iban = iban;
        this.bic = bic;
        this.email = email;
        this.acceptedCountryCodes = acceptedCountryCodes;
        persistedAcceptedCountryCodes.addAll(acceptedCountryCodes);
    }

    @Override
    public Message toProtoMessage() {
        protobuf.SepaAccountPayload.Builder builder =
                protobuf.SepaAccountPayload.newBuilder()
                        .setHolderName(holderName)
                        .setIban(iban)
                        .setBic(bic)
                        .setEmail(email);
        final protobuf.CountryBasedPaymentAccountPayload.Builder countryBasedPaymentAccountPayload = getPaymentAccountPayloadBuilder()
                .getCountryBasedPaymentAccountPayloadBuilder()
                .setSepaAccountPayload(builder);
        return getPaymentAccountPayloadBuilder()
                .setCountryBasedPaymentAccountPayload(countryBasedPaymentAccountPayload)
                .build();
    }

    public static PaymentAccountPayload fromProto(protobuf.PaymentAccountPayload proto) {
        protobuf.CountryBasedPaymentAccountPayload countryBasedPaymentAccountPayload = proto.getCountryBasedPaymentAccountPayload();
        protobuf.SepaAccountPayload sepaAccountPayloadPB = countryBasedPaymentAccountPayload.getSepaAccountPayload();
        return new SepaAccountPayload(proto.getPaymentMethodId(),
                proto.getId(),
                countryBasedPaymentAccountPayload.getCountryCode(),
                new ArrayList<>(countryBasedPaymentAccountPayload.getAcceptedCountryCodesList()),
                sepaAccountPayloadPB.getHolderName(),
                sepaAccountPayloadPB.getIban(),
                sepaAccountPayloadPB.getBic(),
                sepaAccountPayloadPB.getEmail(),
                proto.getMaxTradePeriod(),
                new HashMap<>(proto.getExcludeFromJsonDataMap()));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void setAcceptedCountryCodes(List<String> acceptedCountryCodes) {
        this.acceptedCountryCodes.clear();
        for (String countryCode : acceptedCountryCodes) this.acceptedCountryCodes.add(countryCode);
    }

    public void addAcceptedCountry(String countryCode) {
        if (!acceptedCountryCodes.contains(countryCode))
            acceptedCountryCodes.add(countryCode);
    }

    public void removeAcceptedCountry(String countryCode) {
        acceptedCountryCodes.remove(countryCode);
    }

    public void onPersistChanges() {
        persistedAcceptedCountryCodes.clear();
        persistedAcceptedCountryCodes.addAll(acceptedCountryCodes);
    }

    public void revertChanges() {
        acceptedCountryCodes.clear();
        acceptedCountryCodes.addAll(persistedAcceptedCountryCodes);
    }

    @Override
    public String getPaymentDetails() {
        return Res.get(paymentMethodId) + " - " + Res.getWithCol("payment.account.owner") + " " + holderName + ", IBAN: " +
                iban + ", BIC: " + bic + ", " + Res.getWithCol("payment.bank.country") + " " + getCountryCode();
    }

    @Override
    public String getPaymentDetailsForTradePopup() {
        return Res.getWithCol("payment.account.owner") + " " + holderName + "\n" +
                "IBAN: " + iban + "\n" +
                "BIC: " + bic + "\n" +
                Res.getWithCol("payment.bank.country") + " " + CountryUtil.getNameByCode(countryCode);
    }

    @Override
    public byte[] getAgeWitnessInputData() {
        // We don't add holderName because we don't want to break age validation if the user recreates an account with
        // slight changes in holder name (e.g. add or remove middle name)
        return super.getAgeWitnessInputData(ArrayUtils.addAll(iban.getBytes(StandardCharsets.UTF_8), bic.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public String getOwnerId() {
        return holderName;
    }
}
