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
public final class RtgsAccountPayload extends IfscBasedAccountPayload {

    public RtgsAccountPayload(String paymentMethod, String id) {
        super(paymentMethod, id);
    }

    private RtgsAccountPayload(String paymentMethod,
                               String id,
                               String countryCode,
                               List<String> acceptedCountryCodes,
                               String holderName,
                               String accountNr,
                               String ifsc,
                               long maxTradePeriod,
                               Map<String, String> excludeFromJsonDataMap) {
        super(paymentMethod,
                id,
                countryCode,
                acceptedCountryCodes,
                holderName,
                accountNr,
                ifsc,
                maxTradePeriod,
                excludeFromJsonDataMap);
    }

    @Override
    public Message toProtoMessage() {
        protobuf.IfscBasedAccountPayload.Builder ifscBasedAccountPayloadBuilder = getPaymentAccountPayloadBuilder()
                .getCountryBasedPaymentAccountPayloadBuilder()
                .getIfscBasedAccountPayloadBuilder()
                .setRtgsAccountPayload(protobuf.RtgsAccountPayload.newBuilder());

        protobuf.CountryBasedPaymentAccountPayload.Builder countryBasedPaymentAccountPayloadBuilder = getPaymentAccountPayloadBuilder()
                .getCountryBasedPaymentAccountPayloadBuilder()
                .setIfscBasedAccountPayload(ifscBasedAccountPayloadBuilder);

        return getPaymentAccountPayloadBuilder()
                .setCountryBasedPaymentAccountPayload(countryBasedPaymentAccountPayloadBuilder)
                .build();
    }

    public static RtgsAccountPayload fromProto(protobuf.PaymentAccountPayload proto) {
        protobuf.CountryBasedPaymentAccountPayload countryBasedPaymentAccountPayload = proto.getCountryBasedPaymentAccountPayload();
        protobuf.IfscBasedAccountPayload ifscBasedAccountPayloadPB = countryBasedPaymentAccountPayload.getIfscBasedAccountPayload();
        return new RtgsAccountPayload(proto.getPaymentMethodId(),
                proto.getId(),
                countryBasedPaymentAccountPayload.getCountryCode(),
                new ArrayList<>(countryBasedPaymentAccountPayload.getAcceptedCountryCodesList()),
                ifscBasedAccountPayloadPB.getHolderName(),
                ifscBasedAccountPayloadPB.getAccountNr(),
                ifscBasedAccountPayloadPB.getIfsc(),
                proto.getMaxTradePeriod(),
                new HashMap<>(proto.getExcludeFromJsonDataMap()));
    }

    @Override
    public String getPaymentDetails() {
        return Res.get(paymentMethodId) + " - " + Res.getWithCol("payment.account.owner") + " " + holderName + ", " +
                Res.getWithCol("payment.account.no") + " " + accountNr +
                Res.getWithCol("payment.ifsc") + " " + ifsc;
    }

    @Override
    public String getPaymentDetailsForTradePopup() {
        return getPaymentDetails();
    }

    @Override
    public byte[] getAgeWitnessInputData() {
        String accountNr = this.accountNr == null ? "" : this.accountNr;
        return super.getAgeWitnessInputData(accountNr.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String getHolderName() {
        return getOwnerId();
    }
}
