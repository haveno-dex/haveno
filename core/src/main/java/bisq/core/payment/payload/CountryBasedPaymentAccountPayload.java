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

package bisq.core.payment.payload;

import org.apache.commons.lang3.ArrayUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@EqualsAndHashCode(callSuper = true)
@ToString
@Setter
@Getter
@Slf4j
public abstract class CountryBasedPaymentAccountPayload extends PaymentAccountPayload {
    protected String countryCode = "";
    protected List<String> acceptedCountryCodes = new ArrayList<String>();

    CountryBasedPaymentAccountPayload(String paymentMethodName, String id) {
        super(paymentMethodName, id);
    }

    protected CountryBasedPaymentAccountPayload(String paymentMethodName,
                                                String id,
                                                String countryCode,
                                                List<String> acceptedCountryCodes,
                                                long maxTradePeriod,
                                                Map<String, String> excludeFromJsonDataMap) {
        super(paymentMethodName,
                id,
                maxTradePeriod,
                excludeFromJsonDataMap);

        this.countryCode = countryCode;
        this.acceptedCountryCodes = acceptedCountryCodes;
    }

    @Override
    protected protobuf.PaymentAccountPayload.Builder getPaymentAccountPayloadBuilder() {
        protobuf.CountryBasedPaymentAccountPayload.Builder builder = protobuf.CountryBasedPaymentAccountPayload.newBuilder()
                .setCountryCode(countryCode)
                .addAllAcceptedCountryCodes(acceptedCountryCodes);
        return super.getPaymentAccountPayloadBuilder()
                .setCountryBasedPaymentAccountPayload(builder);
    }

    @Override
    public abstract String getPaymentDetails();

    @Override
    public abstract String getPaymentDetailsForTradePopup();

    @Override
    protected byte[] getAgeWitnessInputData(byte[] data) {
        return super.getAgeWitnessInputData(ArrayUtils.addAll(countryCode.getBytes(StandardCharsets.UTF_8), data));
    }
}
