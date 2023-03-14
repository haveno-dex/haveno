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
import java.util.HashMap;
import java.util.Map;

@EqualsAndHashCode(callSuper = true)
@ToString
@Setter
@Getter
@Slf4j
public final class MoneseAccountPayload extends PaymentAccountPayload {
    private String holderName = "";
    private String mobileNr = "";

    public MoneseAccountPayload(String paymentMethod, String id) {
        super(paymentMethod, id);
    }

    private MoneseAccountPayload(String paymentMethod,
                                 String id,
                                 String holderName,
                                 String mobileNr,
                                 long maxTradePeriod,
                                 Map<String, String> excludeFromJsonDataMap) {
        super(paymentMethod,
                id,
                maxTradePeriod,
                excludeFromJsonDataMap);

        this.holderName = holderName;
        this.mobileNr = mobileNr;
    }

    @Override
    public Message toProtoMessage() {
        return getPaymentAccountPayloadBuilder()
                .setMoneseAccountPayload(protobuf.MoneseAccountPayload.newBuilder()
                        .setHolderName(holderName)
                        .setMobileNr(mobileNr))
                .build();
    }

    public static MoneseAccountPayload fromProto(protobuf.PaymentAccountPayload proto) {
        return new MoneseAccountPayload(proto.getPaymentMethodId(),
                proto.getId(),
                proto.getMoneseAccountPayload().getHolderName(),
                proto.getMoneseAccountPayload().getMobileNr(),
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
