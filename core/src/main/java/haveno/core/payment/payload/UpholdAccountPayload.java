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
public final class UpholdAccountPayload extends PaymentAccountPayload {
    private String accountId = "";
    private String accountOwner = "";

    public UpholdAccountPayload(String paymentMethod, String id) {
        super(paymentMethod, id);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private UpholdAccountPayload(String paymentMethod,
                                 String id,
                                 String accountId,
                                 String accountOwner,
                                 long maxTradePeriod,
                                 Map<String, String> excludeFromJsonDataMap) {
        super(paymentMethod,
                id,
                maxTradePeriod,
                excludeFromJsonDataMap);

        this.accountId = accountId;
        this.accountOwner = accountOwner;
    }

    @Override
    public Message toProtoMessage() {
        return getPaymentAccountPayloadBuilder()
                .setUpholdAccountPayload(protobuf.UpholdAccountPayload.newBuilder()
                        .setAccountOwner(accountOwner)
                        .setAccountId(accountId))
                .build();
    }

    public static UpholdAccountPayload fromProto(protobuf.PaymentAccountPayload proto) {
        return new UpholdAccountPayload(proto.getPaymentMethodId(),
                proto.getId(),
                proto.getUpholdAccountPayload().getAccountId(),
                proto.getUpholdAccountPayload().getAccountOwner(),
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
        if (accountOwner.isEmpty()) {
            return
                    Res.get("payment.account") + ": " + accountId + "\n" +
                            Res.get("payment.account.owner") + ": N/A";
        } else {
            return
                    Res.get("payment.account") + ": " + accountId + "\n" +
                            Res.get("payment.account.owner") + ": " + accountOwner;
        }
    }

    @Override
    public byte[] getAgeWitnessInputData() {
        return super.getAgeWitnessInputData(accountId.getBytes(StandardCharsets.UTF_8));
    }
}
