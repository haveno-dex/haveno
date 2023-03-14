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
import haveno.core.locale.BankUtil;
import haveno.core.locale.CountryUtil;
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
public class MoneyGramAccountPayload extends PaymentAccountPayload implements PayloadWithHolderName {
    private String holderName;
    private String countryCode = "";
    private String state = ""; // is optional. we don't use @Nullable because it would makes UI code more complex.
    private String email;

    public MoneyGramAccountPayload(String paymentMethod, String id) {
        super(paymentMethod, id);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private MoneyGramAccountPayload(String paymentMethodName,
                                    String id,
                                    String countryCode,
                                    String holderName,
                                    String state,
                                    String email,
                                    long maxTradePeriod,
                                    Map<String, String> excludeFromJsonDataMap) {
        super(paymentMethodName,
                id,
                maxTradePeriod,
                excludeFromJsonDataMap);
        this.holderName = holderName;
        this.countryCode = countryCode;
        this.state = state;
        this.email = email;
    }

    @Override
    public Message toProtoMessage() {
        protobuf.MoneyGramAccountPayload.Builder builder =
                protobuf.MoneyGramAccountPayload.newBuilder()
                        .setHolderName(holderName)
                        .setCountryCode(countryCode)
                        .setState(state)
                        .setEmail(email);

        return getPaymentAccountPayloadBuilder()
                .setMoneyGramAccountPayload(builder)
                .build();
    }

    public static PaymentAccountPayload fromProto(protobuf.PaymentAccountPayload proto) {
        protobuf.MoneyGramAccountPayload moneyGramAccountPayload = proto.getMoneyGramAccountPayload();
        return new MoneyGramAccountPayload(proto.getPaymentMethodId(),
                proto.getId(),
                moneyGramAccountPayload.getCountryCode(),
                moneyGramAccountPayload.getHolderName(),
                moneyGramAccountPayload.getState(),
                moneyGramAccountPayload.getEmail(),
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
        String state = BankUtil.isStateRequired(countryCode) ? (Res.getWithCol("payment.account.state") +
                " " + this.state + "\n") : "";
        return Res.getWithCol("payment.account.fullName") + " " + holderName + "\n" +
                state +
                Res.getWithCol("payment.bank.country") + " " + CountryUtil.getNameByCode(countryCode) + "\n" +
                Res.getWithCol("payment.email") + " " + email;
    }

    @Override
    public byte[] getAgeWitnessInputData() {
        String all = this.countryCode +
                this.state +
                this.holderName +
                this.email;
        return super.getAgeWitnessInputData(all.getBytes(StandardCharsets.UTF_8));
    }
}
