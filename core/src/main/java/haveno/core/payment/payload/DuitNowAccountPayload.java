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
public final class DuitNowAccountPayload extends PaymentAccountPayload implements PayloadWithHolderName {
    private String holderName = "";
    private String accountNr = "";
    private String bankName = "";

    public DuitNowAccountPayload(String paymentMethod, String id) {
        super(paymentMethod, id);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private DuitNowAccountPayload(String paymentMethod, String id,
                               String holderName,
                               String accountNr,
                               String bankName,
                               long maxTradePeriod,
                               Map<String, String> excludeFromJsonDataMap) {
        super(paymentMethod,
                id,
                maxTradePeriod,
                excludeFromJsonDataMap);
        this.holderName = holderName;
        this.accountNr = accountNr;
        this.bankName = bankName;
    }

    @Override
    public Message toProtoMessage() {
        return getPaymentAccountPayloadBuilder()
                .setDuitnowAccountPayload(protobuf.DuitNowAccountPayload.newBuilder()
                        .setHolderName(holderName)
                        .setAccountNr(accountNr)
                        .setBankName(bankName))
                .build();
    }

    public static DuitNowAccountPayload fromProto(protobuf.PaymentAccountPayload proto) {
        return new DuitNowAccountPayload(proto.getPaymentMethodId(),
                proto.getId(),
                proto.getDuitnowAccountPayload().getHolderName(),
                proto.getDuitnowAccountPayload().getAccountNr(),
                proto.getDuitnowAccountPayload().getBankName(),
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
                Res.getWithCol("payment.accountNr") + " " + accountNr + "\n" +
                Res.getWithCol("payment.bank.name") + " " + bankName;
    }

    @Override
    public byte[] getAgeWitnessInputData() {
        // We don't add holderName or bankName because we don't want to break age validation if the user recreates
        // an account with slight changes in those fields (e.g. bank name spelling)
        return super.getAgeWitnessInputData(accountNr.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String getOwnerId() {
        return holderName;
    }

    @Override
    public byte[] getPaymentEndpointData() {
        String digits = toDigits(accountNr);
        if (digits.matches("01[0-9]{8,9}")) digits = "6" + digits; // national mobile form to international; NRIC proxies pass through
        return getPaymentEndpointData(digits);
    }
}
