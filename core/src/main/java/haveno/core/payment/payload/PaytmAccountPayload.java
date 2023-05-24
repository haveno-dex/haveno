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

import com.google.protobuf.Message;
import haveno.core.locale.Res;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@EqualsAndHashCode(callSuper = true)
@ToString
@Setter
@Getter
@Slf4j
public final class PaytmAccountPayload extends CountryBasedPaymentAccountPayload {
    private String emailOrMobileNr = "";

    public PaytmAccountPayload(String paymentMethod, String id) {
        super(paymentMethod, id);
    }

    private PaytmAccountPayload(String paymentMethod,
                                String id,
                                String countryCode,
                                List<String> acceptedCountryCodes,
                                String emailOrMobileNr,
                                long maxTradePeriod,
                                Map<String, String> excludeFromJsonDataMap) {
        super(paymentMethod,
                id,
                countryCode,
                acceptedCountryCodes,
                maxTradePeriod,
                excludeFromJsonDataMap);

        this.emailOrMobileNr = emailOrMobileNr;
    }

    @Override
    public Message toProtoMessage() {
        protobuf.PaytmAccountPayload.Builder builder = protobuf.PaytmAccountPayload.newBuilder()
                .setEmailOrMobileNr(emailOrMobileNr);
        final protobuf.CountryBasedPaymentAccountPayload.Builder countryBasedPaymentAccountPayload = getPaymentAccountPayloadBuilder()
                .getCountryBasedPaymentAccountPayloadBuilder()
                .setPaytmAccountPayload(builder);
        return getPaymentAccountPayloadBuilder()
                .setCountryBasedPaymentAccountPayload(countryBasedPaymentAccountPayload)
                .build();
    }

    public static PaytmAccountPayload fromProto(protobuf.PaymentAccountPayload proto) {
        protobuf.CountryBasedPaymentAccountPayload countryBasedPaymentAccountPayload = proto.getCountryBasedPaymentAccountPayload();
        protobuf.PaytmAccountPayload paytmAccountPayloadPB = countryBasedPaymentAccountPayload.getPaytmAccountPayload();
        return new PaytmAccountPayload(proto.getPaymentMethodId(),
                proto.getId(),
                countryBasedPaymentAccountPayload.getCountryCode(),
                new ArrayList<>(countryBasedPaymentAccountPayload.getAcceptedCountryCodesList()),
                paytmAccountPayloadPB.getEmailOrMobileNr(),
                proto.getMaxTradePeriod(),
                new HashMap<>(proto.getExcludeFromJsonDataMap()));
    }

    @Override
    public String getPaymentDetails() {
        return Res.get(paymentMethodId) + " - " + Res.getWithCol("payment.email.mobile") + " " + emailOrMobileNr;
    }

    @Override
    public String getPaymentDetailsForTradePopup() {
        return getPaymentDetails();
    }

    @Override
    public byte[] getAgeWitnessInputData() {
        return super.getAgeWitnessInputData(emailOrMobileNr.getBytes(StandardCharsets.UTF_8));
    }
}
