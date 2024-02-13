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
public final class ZelleAccountPayload extends PaymentAccountPayload implements PayloadWithHolderName {
    private String emailOrMobileNr = "";
    private String holderName = "";

    public ZelleAccountPayload(String paymentMethod, String id) {
        super(paymentMethod, id);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private ZelleAccountPayload(String paymentMethod,
                                       String id,
                                       String emailOrMobileNr,
                                       String holderName,
                                       long maxTradePeriod,
                                       Map<String, String> excludeFromJsonDataMap) {
        super(paymentMethod,
                id,
                maxTradePeriod,
                excludeFromJsonDataMap);

        this.emailOrMobileNr = emailOrMobileNr;
        this.holderName = holderName;
    }

    @Override
    public Message toProtoMessage() {
        return getPaymentAccountPayloadBuilder()
                .setZelleAccountPayload(protobuf.ZelleAccountPayload.newBuilder()
                        .setEmailOrMobileNr(emailOrMobileNr)
                        .setHolderName(holderName))
                .build();
    }

    public static ZelleAccountPayload fromProto(protobuf.PaymentAccountPayload proto) {
        return new ZelleAccountPayload(proto.getPaymentMethodId(),
                proto.getId(),
                proto.getZelleAccountPayload().getEmailOrMobileNr(),
                proto.getZelleAccountPayload().getHolderName(),
                proto.getMaxTradePeriod(),
                new HashMap<>(proto.getExcludeFromJsonDataMap()));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public String getPaymentDetails() {
        return Res.get(paymentMethodId) + " - " + Res.getWithCol("payment.account.owner") + " " + holderName + ", " +
                Res.getWithCol("payment.emailOrMobile") + " " + emailOrMobileNr;
    }

    @Override
    public String getPaymentDetailsForTradePopup() {
        return Res.getWithCol("payment.account.owner") + " " + holderName + "\n" +
                Res.getWithCol("payment.emailOrMobile") + " " + emailOrMobileNr;
    }

    @Override
    public byte[] getAgeWitnessInputData() {
        // We don't add holderName because we don't want to break age validation if the user recreates an account with
        // slight changes in holder name (e.g. add or remove middle name)
        return super.getAgeWitnessInputData(emailOrMobileNr.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String getOwnerId() {
        return holderName;
    }
}
