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
public final class VippsMobilePayAccountPayload extends CountryBasedPaymentAccountPayload {
    private String mobileNr = "";
    private String holderName = "";

    public VippsMobilePayAccountPayload(String paymentMethod, String id) {
        super(paymentMethod, id);
    }

    private VippsMobilePayAccountPayload(String paymentMethod,
                                         String id,
                                         String countryCode,
                                         List<String> acceptedCountryCodes,
                                         String mobileNr,
                                         String holderName,
                                         long maxTradePeriod,
                                         Map<String, String> excludeFromJsonDataMap) {
        super(paymentMethod,
                id,
                countryCode,
                acceptedCountryCodes,
                maxTradePeriod,
                excludeFromJsonDataMap);

        this.mobileNr = mobileNr;
        this.holderName = holderName;
    }

    @Override
    public Message toProtoMessage() {
        protobuf.VippsMobilePayAccountPayload.Builder builder = protobuf.VippsMobilePayAccountPayload.newBuilder()
                .setMobileNr(mobileNr)
                .setHolderName(holderName);
        final protobuf.CountryBasedPaymentAccountPayload.Builder countryBasedPaymentAccountPayload = getPaymentAccountPayloadBuilder()
                .getCountryBasedPaymentAccountPayloadBuilder()
                .setVippsMobilePayAccountPayload(builder);
        return getPaymentAccountPayloadBuilder()
                .setCountryBasedPaymentAccountPayload(countryBasedPaymentAccountPayload)
                .build();
    }

    public static VippsMobilePayAccountPayload fromProto(protobuf.PaymentAccountPayload proto) {
        protobuf.CountryBasedPaymentAccountPayload countryBasedPaymentAccountPayload = proto.getCountryBasedPaymentAccountPayload();
        protobuf.VippsMobilePayAccountPayload accountPayloadPB = countryBasedPaymentAccountPayload.getVippsMobilePayAccountPayload();
        return new VippsMobilePayAccountPayload(proto.getPaymentMethodId(),
                proto.getId(),
                countryBasedPaymentAccountPayload.getCountryCode(),
                new ArrayList<>(countryBasedPaymentAccountPayload.getAcceptedCountryCodesList()),
                accountPayloadPB.getMobileNr(),
                accountPayloadPB.getHolderName(),
                proto.getMaxTradePeriod(),
                new HashMap<>(proto.getExcludeFromJsonDataMap()));
    }

    @Override
    public String getPaymentDetails() {
        return Res.get(paymentMethodId) + " - " + Res.getWithCol("payment.account.owner.fullname") + " " + holderName +
                ", " + Res.getWithCol("payment.mobile") + " " + mobileNr;
    }

    @Override
    public String getPaymentDetailsForTradePopup() {
        return Res.getWithCol("payment.account.owner.fullname") + " " + holderName + "\n" +
                Res.getWithCol("payment.mobile") + " " + mobileNr;
    }

    @Override
    public byte[] getAgeWitnessInputData() {
        // We don't add holderName because we don't want to break age validation if the user recreates an account with
        // slight changes in holder name (e.g. add or remove middle name)
        return super.getAgeWitnessInputData(mobileNr.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String getOwnerId() {
        return holderName;
    }

    @Override
    public byte[] getPaymentEndpointData() {
        return null; // multi-region method, so national phone forms cannot be canonicalized to one region
    }
}
