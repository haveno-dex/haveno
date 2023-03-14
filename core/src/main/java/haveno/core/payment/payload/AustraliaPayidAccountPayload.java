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
import haveno.common.util.CollectionUtils;
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
public final class AustraliaPayidAccountPayload extends PaymentAccountPayload {
    private String payid = "";
    private String bankAccountName = "";

    public AustraliaPayidAccountPayload(String paymentMethod, String id) {
        super(paymentMethod, id);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private AustraliaPayidAccountPayload(String paymentMethod,
                                         String id,
                                         String payid,
                                         String bankAccountName,
                                         long maxTradePeriod,
                                         Map<String, String> excludeFromJsonDataMap) {
        super(paymentMethod,
                id,
                maxTradePeriod,
                excludeFromJsonDataMap);

        this.payid = payid;
        this.bankAccountName = bankAccountName;
    }

    @Override
    public Message toProtoMessage() {
        return getPaymentAccountPayloadBuilder()
                .setAustraliaPayidPayload(
                        protobuf.AustraliaPayidPayload.newBuilder()
                                .setPayid(payid)
                                .setBankAccountName(bankAccountName)
                ).build();
    }

    public static AustraliaPayidAccountPayload fromProto(protobuf.PaymentAccountPayload proto) {
        protobuf.AustraliaPayidPayload AustraliaPayidPayload = proto.getAustraliaPayidPayload();
        return new AustraliaPayidAccountPayload(proto.getPaymentMethodId(),
                proto.getId(),
                AustraliaPayidPayload.getPayid(),
                AustraliaPayidPayload.getBankAccountName(),
                proto.getMaxTradePeriod(),
                CollectionUtils.isEmpty(proto.getExcludeFromJsonDataMap()) ? null : new HashMap<>(proto.getExcludeFromJsonDataMap()));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public String getPaymentDetails() {
        return Res.get(paymentMethodId) + " - " + getPaymentDetailsForTradePopup().replace("\n", ", ");
    }

    @Override
    public String getPaymentDetailsForTradePopup() {
        return
                Res.get("payment.australia.payid") + ": " + payid + "\n" +
                        Res.get("payment.account.owner") + ": " + bankAccountName;
    }


    @Override
    public byte[] getAgeWitnessInputData() {
        String all = this.payid + this.bankAccountName;
        return super.getAgeWitnessInputData(all.getBytes(StandardCharsets.UTF_8));
    }
}
