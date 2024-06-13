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

@EqualsAndHashCode(callSuper = true)
@ToString
@Setter
@Getter
@Slf4j
public final class CashAppAccountPayload extends PaymentAccountPayload {
    private String emailOrMobileNrOrCashtag = "";
    private String details = "";

    public CashAppAccountPayload(String paymentMethod, String id) {
        super(paymentMethod, id);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private CashAppAccountPayload(String paymentMethod,
                                  String id,
                                  String emailOrMobileNrOrCashtag,
                                  String details,
                                  long maxTradePeriod,
                                  Map<String, String> excludeFromJsonDataMap) {
        super(paymentMethod,
                id,
                maxTradePeriod,
                excludeFromJsonDataMap);

        this.emailOrMobileNrOrCashtag = emailOrMobileNrOrCashtag;
        this.details = details;
    }

    @Override
    public Message toProtoMessage() {
        return getPaymentAccountPayloadBuilder()
                .setCashAppAccountPayload(protobuf.CashAppAccountPayload.newBuilder()
                        .setDetails(details)
                        .setEmailOrMobileNrOrCashtag(emailOrMobileNrOrCashtag))
                .build();
    }

    public static CashAppAccountPayload fromProto(protobuf.PaymentAccountPayload proto) {
        return new CashAppAccountPayload(proto.getPaymentMethodId(),
                proto.getId(),
                proto.getCashAppAccountPayload().getEmailOrMobileNrOrCashtag(),
                proto.getCashAppAccountPayload().getDetails(),
                proto.getMaxTradePeriod(),
                new HashMap<>(proto.getExcludeFromJsonDataMap()));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public String getPaymentDetails() {
        return Res.get(paymentMethodId) + " - " + Res.getWithCol("payment.email.mobile.cashtag") + " "
                + emailOrMobileNrOrCashtag + Res.getWithCol("shared.details") + " "
                + details;
    }

    @Override
    public String getPaymentDetailsForTradePopup() {
        return getPaymentDetails();
    }

    @Override
    public byte[] getAgeWitnessInputData() {
        return super.getAgeWitnessInputData(emailOrMobileNrOrCashtag.getBytes(StandardCharsets.UTF_8));
    }
}
