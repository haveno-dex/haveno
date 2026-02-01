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

import haveno.common.util.JsonExclude;
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

import org.apache.commons.lang3.ArrayUtils;

@EqualsAndHashCode(callSuper = true)
@ToString
@Setter
@Getter
@Slf4j
public final class PixAccountPayload extends CountryBasedPaymentAccountPayload {
    private String pixKey = "";
    // This field is excluded for backward compatibility and to allow changes.
    @JsonExclude
    private String holderName = "";

    public PixAccountPayload(String paymentMethod, String id) {
        super(paymentMethod, id);
    }

    private PixAccountPayload(String paymentMethod,
                                String id,
                                String countryCode,
                                List<String> acceptedCountryCodes,
                                String pixKey,
                                String holderName,
                                long maxTradePeriod,
                                Map<String, String> excludeFromJsonDataMap) {
        super(paymentMethod,
                id,
                countryCode,
                acceptedCountryCodes,
                maxTradePeriod,
                excludeFromJsonDataMap);

        this.pixKey = pixKey;
        this.holderName = holderName;
    }

    @Override
    public Message toProtoMessage() {
        protobuf.PixAccountPayload.Builder builder = protobuf.PixAccountPayload.newBuilder()
                .setPixKey(pixKey)
                .setHolderName(holderName);
        final protobuf.CountryBasedPaymentAccountPayload.Builder countryBasedPaymentAccountPayload = getPaymentAccountPayloadBuilder()
                .getCountryBasedPaymentAccountPayloadBuilder()
                .setPixAccountPayload(builder);
        return getPaymentAccountPayloadBuilder()
                .setCountryBasedPaymentAccountPayload(countryBasedPaymentAccountPayload)
                .build();
    }

    public static PixAccountPayload fromProto(protobuf.PaymentAccountPayload proto) {
        protobuf.CountryBasedPaymentAccountPayload countryBasedPaymentAccountPayload = proto.getCountryBasedPaymentAccountPayload();
        protobuf.PixAccountPayload paytmAccountPayloadPB = countryBasedPaymentAccountPayload.getPixAccountPayload();
        return new PixAccountPayload(proto.getPaymentMethodId(),
                proto.getId(),
                countryBasedPaymentAccountPayload.getCountryCode(),
                new ArrayList<>(countryBasedPaymentAccountPayload.getAcceptedCountryCodesList()),
                paytmAccountPayloadPB.getPixKey(),
                paytmAccountPayloadPB.getHolderName(),
                proto.getMaxTradePeriod(),
                new HashMap<>(proto.getExcludeFromJsonDataMap()));
    }

    @Override
    public String getPaymentDetails() {
        return Res.get(paymentMethodId) + " - " + getPaymentDetailsForTradePopup().replace("\n", ", ");
    }

    @Override
    public String getPaymentDetailsForTradePopup() {
        return Res.getWithCol("payment.pix.key") + " " + pixKey + "\n" +
                Res.getWithCol("payment.account.owner.fullname") + " " + PaymentAccountPayload.getHolderNameOrPromptIfEmpty(getHolderName());
    }

    @Override
    public byte[] getAgeWitnessInputData() {
        // holderName will be included as part of the witness data.
        // older accounts that don't have holderName still retain their existing witness.
        return super.getAgeWitnessInputData(ArrayUtils.addAll(
                pixKey.getBytes(StandardCharsets.UTF_8),
                getHolderName().getBytes(StandardCharsets.UTF_8)));
    }
}
