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
public final class PayPayAccountPayload extends PaymentAccountPayload implements PayloadWithHolderName {
    private String holderName = "";
    private String accountNr = "";

    public PayPayAccountPayload(String paymentMethod, String id) {
        super(paymentMethod, id);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private PayPayAccountPayload(String paymentMethod, String id,
                               String holderName,
                               String accountNr,
                               long maxTradePeriod,
                               Map<String, String> excludeFromJsonDataMap) {
        super(paymentMethod,
                id,
                maxTradePeriod,
                excludeFromJsonDataMap);
        this.holderName = holderName;
        this.accountNr = accountNr;
    }

    @Override
    public Message toProtoMessage() {
        return getPaymentAccountPayloadBuilder()
                .setPaypayAccountPayload(protobuf.PayPayAccountPayload.newBuilder()
                        .setHolderName(holderName)
                        .setAccountNr(accountNr))
                .build();
    }

    public static PayPayAccountPayload fromProto(protobuf.PaymentAccountPayload proto) {
        return new PayPayAccountPayload(proto.getPaymentMethodId(),
                proto.getId(),
                proto.getPaypayAccountPayload().getHolderName(),
                proto.getPaypayAccountPayload().getAccountNr(),
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
        return Res.getWithCol("payment.account.owner.fullname") + " " + holderName + "\n" +
                Res.getWithCol("payment.paypay.accountId") + " " + accountNr;
    }

    @Override
    public byte[] getAgeWitnessInputData() {
        // We don't add holderName because we don't want to break age validation if the user recreates an account with
        // slight changes in holder name (e.g. add or remove middle name)
        return super.getAgeWitnessInputData(accountNr.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String getOwnerId() {
        return holderName;
    }

    @Override
    public byte[] getPaymentEndpointData() {
        String digits = toDigits(accountNr);
        if (digits.matches("0[789]0[0-9]{8}")) digits = "81" + digits.substring(1); // national mobile form to international
        return getPaymentEndpointData(digits.matches("81[789]0[0-9]{8}") ? digits : accountNr); // PayPay IDs, including all-digit ones, pass through
    }
}
