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
public final class SatispayAccountPayload extends CountryBasedPaymentAccountPayload {
    private String holderName = "";
    private String mobileNr = "";

    public SatispayAccountPayload(String paymentMethod, String id) {
        super(paymentMethod, id);
    }

    private SatispayAccountPayload(String paymentMethod,
                                   String id,
                                   String countryCode,
                                   List<String> acceptedCountryCodes,
                                   String holderName,
                                   String mobileNr,
                                   long maxTradePeriod,
                                   Map<String, String> excludeFromJsonDataMap) {
        super(paymentMethod,
                id,
                countryCode,
                acceptedCountryCodes,
                maxTradePeriod,
                excludeFromJsonDataMap);

        this.holderName = holderName;
        this.mobileNr = mobileNr;
    }

    @Override
    public Message toProtoMessage() {
        protobuf.SatispayAccountPayload.Builder builder = protobuf.SatispayAccountPayload.newBuilder()
                .setHolderName(holderName)
                .setMobileNr(mobileNr);
        final protobuf.CountryBasedPaymentAccountPayload.Builder countryBasedPaymentAccountPayload = getPaymentAccountPayloadBuilder()
                .getCountryBasedPaymentAccountPayloadBuilder()
                .setSatispayAccountPayload(builder);
        return getPaymentAccountPayloadBuilder()
                .setCountryBasedPaymentAccountPayload(countryBasedPaymentAccountPayload)
                .build();
    }

    public static SatispayAccountPayload fromProto(protobuf.PaymentAccountPayload proto) {
        protobuf.CountryBasedPaymentAccountPayload countryBasedPaymentAccountPayload = proto.getCountryBasedPaymentAccountPayload();
        protobuf.SatispayAccountPayload accountPayloadPB = countryBasedPaymentAccountPayload.getSatispayAccountPayload();
        return new SatispayAccountPayload(proto.getPaymentMethodId(),
                proto.getId(),
                countryBasedPaymentAccountPayload.getCountryCode(),
                new ArrayList<>(countryBasedPaymentAccountPayload.getAcceptedCountryCodesList()),
                accountPayloadPB.getHolderName(),
                accountPayloadPB.getMobileNr(),
                proto.getMaxTradePeriod(),
                new HashMap<>(proto.getExcludeFromJsonDataMap()));
    }

    @Override
    public String getPaymentDetails() {
        return Res.get(paymentMethodId) + " - " + Res.getWithCol("payment.account.userName") + " " + holderName;
    }

    @Override
    public String getPaymentDetailsForTradePopup() {
        return getPaymentDetails();
    }

    @Override
    public byte[] getAgeWitnessInputData() {
        return super.getAgeWitnessInputData(holderName.getBytes(StandardCharsets.UTF_8));
    }
}
