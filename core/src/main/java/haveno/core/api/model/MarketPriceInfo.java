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

package haveno.core.api.model;

import haveno.common.Payload;
import lombok.AllArgsConstructor;
import lombok.ToString;

@ToString
@AllArgsConstructor
public class MarketPriceInfo implements Payload {

    private final String currencyCode;
    private final double price;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public haveno.proto.grpc.MarketPriceInfo toProtoMessage() {
        return haveno.proto.grpc.MarketPriceInfo.newBuilder()
                .setPrice(price)
                .setCurrencyCode(currencyCode)
                .build();
    }

    public static MarketPriceInfo fromProto(haveno.proto.grpc.MarketPriceInfo proto) {
        return new MarketPriceInfo(proto.getCurrencyCode(),
                proto.getPrice());
    }
}
