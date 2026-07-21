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
package haveno.core.payment.payload;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.ArrayUtils;

import com.google.protobuf.Message;
import haveno.core.locale.Country;
import haveno.core.locale.Res;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@EqualsAndHashCode(callSuper = true)
@ToString
@Setter
@Getter
@Slf4j
public final class BlikAccountPayload extends CountryBasedPaymentAccountPayload {

    private String extraInfo = "";

    public BlikAccountPayload(String paymentMethod, String id, List<Country> acceptedCountries) {
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

    private BlikAccountPayload(String paymentMethodId,
                               String id,
                               String countryCode,
                               List<String> acceptedCountryCodes,
                               String extraInfo,
                               long maxTradePeriod,
                               Map<String, String> excludeFromJsonDataMap) 
    {
        super(paymentMethodId,id,countryCode,acceptedCountryCodes,maxTradePeriod,excludeFromJsonDataMap);

        this.countryCode = countryCode;
        this.extraInfo = extraInfo;
        this.acceptedCountryCodes = acceptedCountryCodes;
    }

    @Override
    public Message toProtoMessage() {
        protobuf.BlikAccountPayload.Builder builder = protobuf.BlikAccountPayload.newBuilder()
            .setExtraInfo(extraInfo);

        final protobuf.CountryBasedPaymentAccountPayload.Builder countryBasedPaymentAccountPayload = getPaymentAccountPayloadBuilder()
            .getCountryBasedPaymentAccountPayloadBuilder()
            .setBlikAccountPayload(builder);

        return getPaymentAccountPayloadBuilder()
            .setCountryBasedPaymentAccountPayload(countryBasedPaymentAccountPayload)
            .build();
    }

    public static PaymentAccountPayload fromProto(protobuf.PaymentAccountPayload proto) {
        protobuf.CountryBasedPaymentAccountPayload countryBasedPaymentAccountPayload = proto.getCountryBasedPaymentAccountPayload();
        protobuf.BlikAccountPayload blikAccountPayloadPB = countryBasedPaymentAccountPayload.getBlikAccountPayload();

        return new BlikAccountPayload(proto.getPaymentMethodId(),
            proto.getId(),
            countryBasedPaymentAccountPayload.getCountryCode(),
            new ArrayList<>(countryBasedPaymentAccountPayload.getAcceptedCountryCodesList()),
            blikAccountPayloadPB.getExtraInfo(),
            proto.getMaxTradePeriod(),
            new HashMap<>(proto.getExcludeFromJsonDataMap()));
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////    
    @Override
    public String getPaymentDetails() {
        return Res.get(paymentMethodId) + " - " +
            Res.getWithCol("payment.shared.extraInfo") + " " + this.extraInfo+ "\n";
    }

    @Override
    public String getPaymentDetailsForTradePopup() {
        return getPaymentDetails();
    }

    @Override
    public byte[] getAgeWitnessInputData() {
        return super.getAgeWitnessInputData(ArrayUtils.addAll(id.getBytes(StandardCharsets.UTF_8)));
    }    
}