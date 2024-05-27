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
import java.util.HashMap;
import java.util.Map;

// Cannot be deleted as it would break old trade history entries
// Removed due too high chargeback risk
// @Deprecated
@EqualsAndHashCode(callSuper = true)
@ToString
@Setter
@Getter
@Slf4j
public final class PayPalAccountPayload extends PaymentAccountPayload {
    private String nameOrUsernameOrEmailOrMobileNr = "";

    public PayPalAccountPayload(String paymentMethod, String id) {
        super(paymentMethod, id);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private PayPalAccountPayload(String paymentMethod,
            String id,
            String nameOrUsernameOrEmailOrMobileNr,
            long maxTradePeriod,
            Map<String, String> excludeFromJsonDataMap) {
        super(paymentMethod,
                id,
                maxTradePeriod,
                excludeFromJsonDataMap);

        this.nameOrUsernameOrEmailOrMobileNr = nameOrUsernameOrEmailOrMobileNr;
    }

    @Override
    public Message toProtoMessage() {
        return getPaymentAccountPayloadBuilder()
                .setPaypalAccountPayload(protobuf.PayPalAccountPayload.newBuilder()
                        .setNameOrUsernameOrEmailOrMobileNr(nameOrUsernameOrEmailOrMobileNr))
                .build();
    }

    public static PayPalAccountPayload fromProto(protobuf.PaymentAccountPayload proto) {
        return new PayPalAccountPayload(proto.getPaymentMethodId(),
                proto.getId(),
                proto.getPaypalAccountPayload().getNameOrUsernameOrEmailOrMobileNr(),
                proto.getMaxTradePeriod(),
                new HashMap<>(proto.getExcludeFromJsonDataMap()));
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public String getPaymentDetails() {
        return Res.getWithCol("payment.paypal.paypalNameOrUsernameOrEmailOrMobileNr") + " "
                + nameOrUsernameOrEmailOrMobileNr;
    }

    @Override
    public String getPaymentDetailsForTradePopup() {
        return getPaymentDetails();
    }

    @Override
    public byte[] getAgeWitnessInputData() {
        return super.getAgeWitnessInputData(nameOrUsernameOrEmailOrMobileNr.getBytes(StandardCharsets.UTF_8));
    }
}
