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
import haveno.common.util.Tuple2;
import haveno.core.locale.Res;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;

@EqualsAndHashCode(callSuper = true)
@ToString
@Slf4j
public final class RevolutAccountPayload extends PaymentAccountPayload {

    // Was added in 1.3.8
    // To not break signed accounts we keep accountId as internal id used for signing.
    // Old accounts get a popup to add the new required field username but accountId is
    // left unchanged. Newly created accounts fill accountId with the value of username.
    // In the UI we only use username.

    // For backward compatibility we need to exclude the new field for the contract json.
    // We can remove that after a while when risk that users with pre 1.3.8 version trade with updated
    // users is very low.
    @JsonExclude
    @Getter
    private String username = "";

    public RevolutAccountPayload(String paymentMethod, String id) {
        super(paymentMethod, id);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private RevolutAccountPayload(String paymentMethod,
                                  String id,
                                  @Nullable String username,
                                  long maxTradePeriod,
                                  Map<String, String> excludeFromJsonDataMap) {
        super(paymentMethod,
                id,
                maxTradePeriod,
                excludeFromJsonDataMap);

        this.username = username;
    }

    @Override
    public Message toProtoMessage() {
        protobuf.RevolutAccountPayload.Builder revolutBuilder = protobuf.RevolutAccountPayload.newBuilder()
                .setUsername(username);
        return getPaymentAccountPayloadBuilder().setRevolutAccountPayload(revolutBuilder).build();
    }


    public static RevolutAccountPayload fromProto(protobuf.PaymentAccountPayload proto) {
        protobuf.RevolutAccountPayload revolutAccountPayload = proto.getRevolutAccountPayload();
        return new RevolutAccountPayload(proto.getPaymentMethodId(),
                proto.getId(),
                revolutAccountPayload.getUsername(),
                proto.getMaxTradePeriod(),
                new HashMap<>(proto.getExcludeFromJsonDataMap()));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public String getPaymentDetails() {
        Tuple2<String, String> tuple = getLabelValueTuple();
        return Res.get(paymentMethodId) + " - " + tuple.first + ": " + tuple.second;
    }

    private Tuple2<String, String> getLabelValueTuple() {
        String label;
        String value;
        checkArgument(!username.isEmpty(), "Username must be set");
        label = Res.get("payment.account.username");
        value = username;
        return new Tuple2<>(label, value);
    }

    public Tuple2<String, String> getRecipientsAccountData() {
        Tuple2<String, String> tuple = getLabelValueTuple();
        String label = Res.get("portfolio.pending.step2_buyer.recipientsAccountData", tuple.first);
        return new Tuple2<>(label, tuple.second);
    }

    @Override
    public String getPaymentDetailsForTradePopup() {
        return getPaymentDetails();
    }

    @Override
    public byte[] getAgeWitnessInputData() {
        return super.getAgeWitnessInputData(username.getBytes(StandardCharsets.UTF_8));
    }

    public boolean usernameNotSet() {
        return username.isEmpty();
    }

    public void setUserName(String username) {
        this.username = username;
    }
}
