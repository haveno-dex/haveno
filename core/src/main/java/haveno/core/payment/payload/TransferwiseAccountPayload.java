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
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.ArrayUtils;

@EqualsAndHashCode(callSuper = true)
@ToString
@Setter
@Getter
@Slf4j
public final class TransferwiseAccountPayload extends PaymentAccountPayload {
    private String email = "";
    // This field is excluded for backward compatibility and to allow changes.
    @JsonExclude
    private String holderName = "";

    public TransferwiseAccountPayload(String paymentMethod, String id) {
        super(paymentMethod, id);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private TransferwiseAccountPayload(String paymentMethod,
                                       String id,
                                       String email,
                                       String holderName,
                                       long maxTradePeriod,
                                       Map<String, String> excludeFromJsonDataMap) {
        super(paymentMethod,
                id,
                maxTradePeriod,
                excludeFromJsonDataMap);

        this.email = email;
        this.holderName = holderName;
    }

    @Override
    public Message toProtoMessage() {
        protobuf.TransferwiseAccountPayload.Builder builder = protobuf.TransferwiseAccountPayload.newBuilder()
                .setEmail(email)
                .setHolderName(holderName);
        return getPaymentAccountPayloadBuilder()
                .setTransferwiseAccountPayload(builder)
                .build();
    }

    public static TransferwiseAccountPayload fromProto(protobuf.PaymentAccountPayload proto) {
        return new TransferwiseAccountPayload(proto.getPaymentMethodId(),
                proto.getId(),
                proto.getTransferwiseAccountPayload().getEmail(),
                proto.getTransferwiseAccountPayload().getHolderName(),
                proto.getMaxTradePeriod(),
                new HashMap<>(proto.getExcludeFromJsonDataMap()));
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
        return Res.getWithCol("payment.email") + " " + email + "\n" +
                Res.getWithCol("payment.account.owner.fullname") + " " + PaymentAccountPayload.getHolderNameOrPromptIfEmpty(getHolderName());
    }

    @Override
    public byte[] getAgeWitnessInputData() {
        // holderName will be included as part of the witness data.
        // older accounts that don't have holderName still retain their existing witness.
        return super.getAgeWitnessInputData(ArrayUtils.addAll(
                email.getBytes(StandardCharsets.UTF_8),
                getHolderName().getBytes(StandardCharsets.UTF_8)));
    }
}
